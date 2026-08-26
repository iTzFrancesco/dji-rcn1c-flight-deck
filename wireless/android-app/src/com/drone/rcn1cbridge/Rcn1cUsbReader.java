package com.drone.rcn1cbridge;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Process;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared-ish RC-N1C USB/VCOM reader for the Android side.
 *
 * It intentionally contains no UI, network or gamepad code. The existing Wi-Fi dashboard can
 * migrate to this class later; the Android virtual-gamepad mode already uses it directly.
 */
public final class Rcn1cUsbReader {
    public static final int DJI_VENDOR_ID = 0x2CA3;
    public static final int RAW_MIN = 364;
    public static final int RAW_MAX = 1684;
    public static final int RAW_CENTER = 1024;

    private static final int PACKET_LEN = 38;
    private static final int BUTTON_PACKET_LEN = 58;

    private static final byte[] ENABLE_SIMULATOR = {
            0x55, 0x0e, 0x04, 0x66, 0x0a, 0x06, (byte) 0xeb, 0x34,
            0x40, 0x06, 0x24, 0x01, (byte) 0xd9, (byte) 0xec};
    private static final byte[] REQUEST_STICKS = {
            0x55, 0x0d, 0x04, 0x33, 0x0a, 0x06, (byte) 0xeb, 0x34,
            0x40, 0x06, 0x01, 0x74, 0x24};
    private static final byte[] REQUEST_BUTTONS = {
            0x55, 0x0d, 0x04, 0x33, 0x0a, 0x06, (byte) 0xeb, 0x34,
            0x40, 0x06, 0x27, 0x40, 0x60};

    public interface Listener {
        void onStatus(String message);
        void onFrame(Frame frame);
        void onStopped(String reason);
    }

    public static final class Frame {
        public final int rawLx, rawLy, rawRx, rawRy, rawCamera;
        public final int lx, ly, rx, ry;
        public final int buttonMask;
        public final int mode;
        public final boolean shutter, photoVideo, rth, fn;
        public final long packetCount;
        public final float packetsPerSecond;

        Frame(int rawLx, int rawLy, int rawRx, int rawRy, int rawCamera,
              int buttonMask, long packetCount, float packetsPerSecond) {
            this.rawLx = rawLx;
            this.rawLy = rawLy;
            this.rawRx = rawRx;
            this.rawRy = rawRy;
            this.rawCamera = rawCamera;
            this.lx = rawToAxis(rawLx);
            this.ly = rawToAxis(rawLy);
            this.rx = rawToAxis(rawRx);
            this.ry = rawToAxis(rawRy);
            this.buttonMask = buttonMask;
            this.mode = (buttonMask & 0x3000) >> 12;
            this.shutter = (buttonMask & 0x0060) == 0x0060;
            this.photoVideo = (buttonMask & 0x0004) != 0;
            this.rth = (buttonMask & 0x0080) != 0;
            this.fn = (buttonMask & 0x0002) != 0;
            this.packetCount = packetCount;
            this.packetsPerSecond = packetsPerSecond;
        }
    }

    private static final class Candidate {
        final UsbInterface iface;
        final UsbEndpoint in;
        final UsbEndpoint out;

        Candidate(UsbInterface iface, UsbEndpoint in, UsbEndpoint out) {
            this.iface = iface;
            this.in = in;
            this.out = out;
        }
    }

    private final UsbManager usbManager;
    private final Listener listener;
    private volatile boolean running;
    private Thread worker;

    public Rcn1cUsbReader(UsbManager usbManager, Listener listener) {
        this.usbManager = usbManager;
        this.listener = listener;
    }

    public static UsbDevice findDji(UsbManager manager) {
        UsbDevice fallback = null;
        for (UsbDevice d : manager.getDeviceList().values()) {
            String product = d.getProductName() == null ? "" : d.getProductName().toUpperCase();
            if (d.getVendorId() == DJI_VENDOR_ID || product.contains("DJI")) return d;
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                int cls = d.getInterface(i).getInterfaceClass();
                if (cls == UsbConstants.USB_CLASS_CDC_DATA || cls == UsbConstants.USB_CLASS_COMM) {
                    fallback = d;
                }
            }
        }
        return fallback;
    }

    public synchronized boolean start(UsbDevice device) {
        if (running || worker != null || device == null || !usbManager.hasPermission(device)) return false;
        running = true;
        worker = new Thread(() -> run(device), "rcn1c-android-reader");
        worker.start();
        return true;
    }

    public void stop() {
        Thread t;
        synchronized (this) {
            running = false;
            t = worker;
        }
        if (t != null) t.interrupt();
    }

    public void stopAndWait(long timeoutMs) {
        Thread t;
        synchronized (this) {
            running = false;
            t = worker;
        }
        if (t == null || t == Thread.currentThread()) return;
        t.interrupt();
        try {
            t.join(timeoutMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void run(UsbDevice device) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        UsbDeviceConnection connection = null;
        UsbInterface claimed = null;
        String stopReason = "Fermato";
        try {
            status("Apro " + deviceLabel(device) + "...");
            connection = usbManager.openDevice(device);
            if (connection == null) throw new IllegalStateException("openDevice ha restituito null");

            Candidate candidate = probe(connection, device);
            if (candidate == null) throw new IllegalStateException("endpoint VCOM DJI non trovato");
            claimed = candidate.iface;
            status("RC-N1C collegato · input attivo");

            connection.bulkTransfer(candidate.out, ENABLE_SIMULATOR, ENABLE_SIMULATOR.length, 100);

            byte[] in = new byte[512];
            byte[] acc = new byte[2048];
            int accLen = 0;
            int buttonMask = 0x1000;
            long packetCount = 0;
            int windowPackets = 0;
            long windowStart = System.nanoTime();
            float packetRate = 0f;
            int emptyReads = 0;

            while (running) {
                connection.bulkTransfer(candidate.out, REQUEST_STICKS, REQUEST_STICKS.length, 50);
                connection.bulkTransfer(candidate.out, REQUEST_BUTTONS, REQUEST_BUTTONS.length, 50);
                int n = connection.bulkTransfer(candidate.in, in, in.length, 20);
                if (n <= 0) {
                    if (++emptyReads > 2500) {
                        stopReason = "RC non risponde";
                        break;
                    }
                    continue;
                }
                emptyReads = 0;

                if (accLen + n > acc.length) accLen = 0;
                System.arraycopy(in, 0, acc, accLen, n);
                accLen += n;

                int consumed = 0;
                while (accLen - consumed >= 3) {
                    if ((acc[consumed] & 0xFF) != 0x55) {
                        consumed++;
                        continue;
                    }
                    int plen = ((acc[consumed + 1] & 0xFF)
                            | ((acc[consumed + 2] & 0xFF) << 8)) & 0x3FF;
                    if (plen < 3 || plen > 512) {
                        consumed++;
                        continue;
                    }
                    if (accLen - consumed < plen) break;

                    int p = consumed;
                    if (plen == BUTTON_PACKET_LEN) {
                        buttonMask = ((acc[p + 28] & 0xFF) << 8) | (acc[p + 29] & 0xFF);
                    } else if (plen == PACKET_LEN) {
                        int rawRx = u16(acc, p + 13);
                        int rawRy = u16(acc, p + 16);
                        int rawLy = u16(acc, p + 19);
                        int rawLx = u16(acc, p + 22);
                        int camera = u16(acc, p + 25);
                        packetCount++;
                        windowPackets++;

                        long now = System.nanoTime();
                        if (now - windowStart >= 1_000_000_000L) {
                            packetRate = windowPackets * 1_000_000_000f / (now - windowStart);
                            windowPackets = 0;
                            windowStart = now;
                        }

                        if (listener != null) {
                            listener.onFrame(new Frame(
                                    rawLx, rawLy, rawRx, rawRy, camera,
                                    buttonMask, packetCount, packetRate));
                        }
                    }
                    consumed += plen;
                }

                if (consumed > 0) {
                    System.arraycopy(acc, consumed, acc, 0, accLen - consumed);
                    accLen -= consumed;
                }
            }
        } catch (Throwable t) {
            stopReason = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        } finally {
            running = false;
            try {
                if (connection != null && claimed != null) connection.releaseInterface(claimed);
            } catch (Throwable ignored) {
            }
            try {
                if (connection != null) connection.close();
            } catch (Throwable ignored) {
            }
            synchronized (this) {
                if (worker == Thread.currentThread()) worker = null;
            }
            if (listener != null) listener.onStopped(stopReason);
        }
    }

    private Candidate probe(UsbDeviceConnection connection, UsbDevice device) {
        List<Candidate> candidates = candidates(device);
        byte[] probe = new byte[256];
        for (Candidate c : candidates) {
            try {
                if (c.iface != null && !connection.claimInterface(c.iface, true)) continue;
                if (c.iface != null) {
                    connection.controlTransfer(
                            0x21, 0x22, 0x03, c.iface.getId(), null, 0, 1000);
                }
                connection.bulkTransfer(c.out, ENABLE_SIMULATOR, ENABLE_SIMULATOR.length, 100);
                connection.bulkTransfer(c.out, REQUEST_STICKS, REQUEST_STICKS.length, 100);
                int n = connection.bulkTransfer(c.in, probe, probe.length, 150);
                if (n > 0 && containsFrameStart(probe, n)) return c;
                if (c.iface != null) connection.releaseInterface(c.iface);
            } catch (Throwable ignored) {
                if (c.iface != null) {
                    try { connection.releaseInterface(c.iface); } catch (Throwable ignored2) {}
                }
            }
        }
        return null;
    }

    private static List<Candidate> candidates(UsbDevice device) {
        List<Candidate> out = new ArrayList<>();
        UsbEndpoint firstIn = null;
        UsbEndpoint firstOut = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            UsbEndpoint in = null, bulkOut = null;
            for (int j = 0; j < iface.getEndpointCount(); j++) {
                UsbEndpoint ep = iface.getEndpoint(j);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
                else bulkOut = ep;
            }
            if (in != null && bulkOut != null) out.add(new Candidate(iface, in, bulkOut));
            if (in != null && firstIn == null) firstIn = in;
            if (bulkOut != null && firstOut == null) firstOut = bulkOut;
        }
        if (firstIn != null && firstOut != null && !hasEndpoints(out, firstIn, firstOut)) {
            out.add(new Candidate(null, firstIn, firstOut));
        }
        return out;
    }

    private static boolean hasEndpoints(List<Candidate> candidates,
                                        UsbEndpoint in, UsbEndpoint out) {
        for (Candidate candidate : candidates) {
            if (candidate.in == in && candidate.out == out) return true;
        }
        return false;
    }

    private static boolean containsFrameStart(byte[] data, int n) {
        for (int i = 0; i < n; i++) if ((data[i] & 0xFF) == 0x55) return true;
        return false;
    }

    private static int u16(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8);
    }

    public static int rawToAxis(int raw) {
        long span = RAW_MAX - RAW_MIN;
        long value = (long) (raw - RAW_CENTER) * 65535L / span;
        if (value < -32767) return -32767;
        if (value > 32767) return 32767;
        return (int) value;
    }

    private void status(String message) {
        if (listener != null) listener.onStatus(message);
    }

    private static String deviceLabel(UsbDevice device) {
        String name = device.getProductName();
        return name == null || name.trim().isEmpty() ? "DJI RC" : name;
    }
}
