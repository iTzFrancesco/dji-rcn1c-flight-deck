package com.drone.rcn1cbridge.uinput;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * App-side virtual gamepad wrapper.
 * Prefers a real Linux uinput InputDevice and automatically falls back to Shizuku
 * InputManager injection on ROMs whose SELinux policy denies /dev/uinput.
 */
public final class UInputGamepad {
    private static final String TAG = "RCN1C-UInputGamepad";

    public interface Listener {
        void onState(boolean ready, String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Shizuku.UserServiceArgs args;
    private final ShizukuInputInjector injector = new ShizukuInputInjector();

    private volatile IUInputService service;
    private volatile boolean bound;
    private volatile boolean ready;
    private volatile boolean fallbackInjection;

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
            post(false, "Servizio gamepad collegato, verifico uinput...");
            new Thread(() -> {
                try {
                    IUInputService s = service;
                    if (s == null) return;

                    boolean uinputOk = false;
                    try {
                        uinputOk = s.canCreateDevice() && s.createGamepad();
                    } catch (Throwable t) {
                        Log.w(TAG, "uinput unavailable", t);
                    }

                    if (uinputOk) {
                        fallbackInjection = false;
                        ready = true;
                        post(true, "Controller Android virtuale pronto · uinput");
                        return;
                    }

                    // Some ColorOS / hardened ROM policies deny shell UID access to /dev/uinput.
                    // Keep a best-effort fallback so the app can still be tested without root.
                    boolean injectOk = injector.init();
                    fallbackInjection = injectOk;
                    ready = injectOk;
                    post(injectOk, injectOk
                            ? "Gamepad pronto · fallback Shizuku inject (compatibilità ridotta)"
                            : "/dev/uinput bloccato e fallback InputManager non disponibile");
                } catch (Throwable t) {
                    ready = false;
                    Log.e(TAG, "gamepad init failed", t);
                    post(false, "Errore gamepad: " + safeMessage(t));
                }
            }, "rcn1c-gamepad-init").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            if (!fallbackInjection) {
                ready = false;
                post(false, "Servizio gamepad disconnesso");
            }
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
        fallbackInjection = false;
        bound = false;
    }

    public boolean isReady() {
        if (!ready) return false;
        return fallbackInjection ? injector.isReady() : service != null;
    }

    public String getBackendName() {
        if (!ready) return "--";
        return fallbackInjection ? "Shizuku inject" : "uinput";
    }

    public void sendFrame(int buttons,
                          int leftStickX, int leftStickY,
                          int rightStickX, int rightStickY,
                          int leftTrigger, int rightTrigger,
                          int dpadX, int dpadY) {
        if (!ready) return;

        if (fallbackInjection) {
            injector.sendFrame(buttons,
                    leftStickX, leftStickY,
                    rightStickX, rightStickY,
                    leftTrigger, rightTrigger,
                    dpadX, dpadY);
            return;
        }

        IUInputService s = service;
        if (s == null) return;
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
