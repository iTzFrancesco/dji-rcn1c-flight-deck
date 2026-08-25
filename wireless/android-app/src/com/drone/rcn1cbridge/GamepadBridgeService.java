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

import com.drone.rcn1cbridge.uinput.UInputGamepad;

/**
 * Foreground service that keeps RC-N1C -> virtual Android gamepad alive while a simulator
 * such as FPV.Skydive is in the foreground.
 */
public final class GamepadBridgeService extends Service {
    public static final String ACTION_STOP = "com.drone.rcn1cbridge.action.STOP_ANDROID_GAMEPAD";
    public static final String EXTRA_DEVICE = "usb_device";

    private static final String CHANNEL_ID = "rcn1c_android_gamepad";
    private static final int NOTIFICATION_ID = 4107;
    private static final int CAMERA_THRESHOLD = 99;

    public static volatile boolean active = false;
    public static volatile boolean gamepadReady = false;
    public static volatile String status = "Fermato";
    public static volatile Rcn1cUsbReader.Frame latestFrame = null;

    private UInputGamepad gamepad;
    private Rcn1cUsbReader reader;
    private UsbDevice device;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        active = true;
        status = "Avvio Android Gamepad...";
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

        if (gamepad == null) {
            gamepad = new UInputGamepad(this, (ready, message) -> {
                gamepadReady = ready;
                setStatus(message);
                if (ready) startReaderIfPossible();
            });
            gamepad.bind();
        } else if (gamepad.isReady()) {
            startReaderIfPossible();
        }
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
            @Override
            public void onStatus(String message) {
                setStatus(message);
            }

            @Override
            public void onFrame(Rcn1cUsbReader.Frame frame) {
                latestFrame = frame;
                pushToGamepad(frame);
            }

            @Override
            public void onStopped(String reason) {
                latestFrame = null;
                if (gamepad != null) gamepad.neutral();
                if (active) setStatus("RC fermato · " + reason);
            }
        });
        if (!reader.start(device)) setStatus("Impossibile avviare il reader RC");
    }

    private void pushToGamepad(Rcn1cUsbReader.Frame f) {
        UInputGamepad g = gamepad;
        if (g == null || !g.isReady()) return;

        int buttons = 0;
        if (f.rawCamera >= Rcn1cUsbReader.RAW_CENTER + CAMERA_THRESHOLD) buttons |= 1 << 0; // A
        else if (f.rawCamera <= Rcn1cUsbReader.RAW_CENTER - CAMERA_THRESHOLD) buttons |= 1 << 1; // B
        if (f.fn) buttons |= 1 << 2;          // X
        if (f.shutter) buttons |= 1 << 3;     // Y
        if (f.photoVideo) buttons |= 1 << 6;  // Back/Select
        if (f.rth) buttons |= 1 << 7;         // Start

        // Preserve the same raw channel mapping used by the PC bridge. FPV simulators can
        // calibrate/invert individual channels if their Android convention differs.
        g.sendFrame(buttons,
                f.lx, f.ly,
                f.rx, f.ry,
                0, 0,
                0, 0);
    }

    @Override
    public void onDestroy() {
        active = false;
        gamepadReady = false;
        status = "Fermato";
        latestFrame = null;
        try {
            if (reader != null) reader.stop();
        } catch (Throwable ignored) {
        }
        try {
            if (gamepad != null) {
                gamepad.neutral();
                gamepad.unbind();
            }
        } catch (Throwable ignored) {
        }
        reader = null;
        gamepad = null;
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void setStatus(String message) {
        status = message == null ? "-" : message;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID, buildNotification(status));
        } catch (Throwable ignored) {
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "RC-N1C Android Gamepad",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Mantiene attivo il bridge RC-N1C mentre giochi");
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, GamepadActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, GamepadBridgeService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("RC-N1C Flight Bridge")
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
