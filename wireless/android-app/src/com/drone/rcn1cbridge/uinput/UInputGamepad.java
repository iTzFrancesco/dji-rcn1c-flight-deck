package com.drone.rcn1cbridge.uinput;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import rikka.shizuku.Shizuku;

/** Small app-side wrapper around the Shizuku uinput user-service. */
public final class UInputGamepad {
    private static final String TAG = "RCN1C-UInputGamepad";

    public interface Listener {
        void onState(boolean ready, String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Shizuku.UserServiceArgs args;

    private volatile IUInputService service;
    private volatile boolean bound;
    private volatile boolean ready;

    public UInputGamepad(Context context, Listener listener) {
        this.listener = listener;
        args = new Shizuku.UserServiceArgs(
                new ComponentName(context.getPackageName(), UInputService.class.getName()))
                .daemon(false)
                .processNameSuffix("rcn1c_uinput")
                .debuggable(false)
                .version(1);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IUInputService.Stub.asInterface(binder);
            post(false, "Servizio gamepad collegato, creo il controller virtuale...");
            new Thread(() -> {
                try {
                    IUInputService s = service;
                    if (s == null) return;
                    if (!s.canCreateDevice()) {
                        ready = false;
                        post(false, "/dev/uinput bloccato su questo dispositivo");
                        return;
                    }
                    boolean ok = s.createGamepad();
                    ready = ok;
                    post(ok, ok
                            ? "Controller Android virtuale pronto"
                            : "Creazione controller virtuale fallita");
                } catch (Throwable t) {
                    ready = false;
                    Log.e(TAG, "uinput init failed", t);
                    post(false, "Errore uinput: " + safeMessage(t));
                }
            }, "rcn1c-uinput-init").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            ready = false;
            post(false, "Servizio gamepad disconnesso");
        }
    };

    public synchronized void bind() {
        if (bound) return;
        try {
            Shizuku.bindUserService(args, connection);
            bound = true;
            post(false, "Avvio servizio gamepad...");
        } catch (Throwable t) {
            Log.e(TAG, "bind failed", t);
            post(false, "Impossibile avviare Shizuku: " + safeMessage(t));
        }
    }

    public synchronized void unbind() {
        if (!bound) return;
        ready = false;
        try {
            IUInputService s = service;
            if (s != null) s.destroy();
        } catch (Throwable ignored) {
        }
        try {
            Shizuku.unbindUserService(args, connection, true);
        } catch (Throwable ignored) {
        }
        service = null;
        bound = false;
    }

    public boolean isReady() {
        return ready && service != null;
    }

    public void sendFrame(int buttons,
                          int leftStickX, int leftStickY,
                          int rightStickX, int rightStickY,
                          int leftTrigger, int rightTrigger,
                          int dpadX, int dpadY) {
        IUInputService s = service;
        if (!ready || s == null) return;
        try {
            s.sendFrame(buttons,
                    leftStickX, leftStickY,
                    rightStickX, rightStickY,
                    leftTrigger, rightTrigger,
                    dpadX, dpadY);
        } catch (Throwable t) {
            ready = false;
            Log.e(TAG, "sendFrame failed", t);
            post(false, "Gamepad virtuale disconnesso");
        }
    }

    /** Immediately neutralise every axis/button before stopping the RC reader. */
    public void neutral() {
        sendFrame(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private void post(boolean ok, String message) {
        if (listener == null) return;
        main.post(() -> listener.onState(ok, message));
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
