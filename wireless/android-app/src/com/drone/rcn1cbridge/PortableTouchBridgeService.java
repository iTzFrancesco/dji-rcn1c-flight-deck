package com.drone.rcn1cbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;

/** Keeps RC-N1C USB input alive while a touch-controlled simulator is foreground. */
public final class PortableTouchBridgeService extends Service {
    public static final String ACTION_STOP = "com.drone.rcn1cbridge.action.STOP_PORTABLE_TOUCH";
    public static final String EXTRA_DEVICE = "usb_device";

    private static final String CHANNEL_ID = "rcn1c_portable_touch";
    private static final int NOTIFICATION_ID = 4110;

    public static volatile boolean active = false;
    public static volatile String status = "Fermato";
    public static volatile Rcn1cUsbReader.Frame latestFrame = null;

    private Rcn1cUsbReader reader;
    private UsbDevice device;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        active = true;
        status = "Avvio Portable Touch Bridge...";
        startForeground(NOTIFICATION_ID, buildNotification(status));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        UsbDevice incoming = getUsbDevice(intent);
        if (incoming != null) device = incoming;
        if (device == null) {
            setStatus("Nessun RC DJI passato al servizio");
            stopSelf();
            return START_NOT_STICKY;
        }

        startReaderIfPossible();
        return START_STICKY;
    }

    private void startReaderIfPossible() {
        if (reader != null && reader.isRunning()) return;
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        if (device == null || !manager.hasPermission(device)) {
            setStatus("Permesso USB mancante · torna in Flight Bridge");
            return;
        }

        reader = new Rcn1cUsbReader(manager, new Rcn1cUsbReader.Listener() {
            @Override public void onStatus(String message) { setStatus(message); }

            @Override public void onFrame(Rcn1cUsbReader.Frame frame) {
                latestFrame = frame;
                if (PortableTouchAccessibilityService.isConnected()) {
                    PortableTouchAccessibilityService.kick();
                }
            }

            @Override public void onStopped(String reason) {
                latestFrame = null;
                if (active) setStatus("RC fermato · " + reason);
                PortableTouchAccessibilityService.releaseTouches();
            }
        });

        if (!reader.start(device)) setStatus("Impossibile avviare il reader RC");
    }

    @Override
    public void onDestroy() {
        active = false;
        latestFrame = null;
        status = "Fermato";
        try { if (reader != null) reader.stop(); } catch (Throwable ignored) {}
        reader = null;
        PortableTouchAccessibilityService.releaseTouches();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void setStatus(String message) {
        status = message == null ? "-" : message;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID, buildNotification(status));
        } catch (Throwable ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "RC-N1C Portable Touch",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Mantiene il RC attivo mentre il simulatore usa i controlli touch");
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, PortableTouchActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, PortableTouchBridgeService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("RC-N1C Flight Bridge · Portable Touch")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_launcher, "Ferma", stopPi)
                .build();
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice getUsbDevice(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(EXTRA_DEVICE);
    }
}
