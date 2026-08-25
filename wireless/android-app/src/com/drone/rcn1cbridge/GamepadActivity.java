package com.drone.rcn1cbridge;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

/** UI for the local RC-N1C -> Android virtual gamepad mode. */
public final class GamepadActivity extends Activity {
    private static final String ACTION_USB_PERMISSION =
            "com.drone.rcn1cbridge.GAMEPAD_USB_PERMISSION";
    private static final int SHIZUKU_REQUEST = 2101;
    private static final int NOTIFICATION_REQUEST = 2102;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusView, statsView, shizukuView;
    private Button toggleButton;
    private StickPadView padL, padR;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != SHIZUKU_REQUEST) return;
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    setStatus("Shizuku autorizzato · cerco il radiocomando...");
                    ensureUsbAndStart();
                } else {
                    setStatus("Permesso Shizuku negato");
                }
            };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                UsbDevice device = getUsbDevice(intent);
                if (granted && device != null) startBridgeService(device);
                else setStatus("Permesso USB negato");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                stopBridgeService();
                setStatus("Radiocomando scollegato");
            }
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            syncUi();
            ui.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        try { unregisterReceiver(usbReceiver); } catch (Throwable ignored) {}
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener); }
        catch (Throwable ignored) {}
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(9));
        root.setBackgroundColor(0xFF0B0F14);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        Button back = button("‹", 0xFF172332, 0xFF31465C);
        back.setTextSize(24);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText("ANDROID GAMEPAD  ·  BETA");
        title.setTextColor(0xFFEAF2FF);
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        titles.addView(title);
        statusView = new TextView(this);
        statusView.setTextColor(0xFF92A4B8);
        statusView.setTextSize(10);
        statusView.setSingleLine(true);
        statusView.setText("Pronto");
        titles.addView(statusView);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        toggleButton = button("AVVIA", 0xFF123B32, 0xFF2ED573);
        toggleButton.setOnClickListener(v -> {
            if (GamepadBridgeService.active) stopBridgeService();
            else beginStartFlow();
        });
        header.addView(toggleButton, new LinearLayout.LayoutParams(dp(104), dp(44)));
        root.addView(header);

        shizukuView = new TextView(this);
        shizukuView.setTextSize(10);
        shizukuView.setPadding(dp(2), dp(5), dp(2), dp(3));
        root.addView(shizukuView);

        LinearLayout pads = new LinearLayout(this);
        pads.setGravity(Gravity.CENTER);
        padL = new StickPadView(this, 0xFF39C5FF);
        padL.setLabel("THROTTLE / YAW");
        padR = new StickPadView(this, 0xFFFF9F43);
        padR.setLabel("PITCH / ROLL");
        pads.addView(padL, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        pads.addView(padR, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(pads, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statsView = new TextView(this);
        statsView.setTextColor(0xFF8294A8);
        statsView.setTextSize(10);
        statsView.setTypeface(android.graphics.Typeface.MONOSPACE);
        statsView.setSingleLine(true);
        statsView.setPadding(dp(2), dp(3), dp(2), dp(5));
        statsView.setText("RC --   gamepad --");
        root.addView(statsView);

        LinearLayout gameRow = new LinearLayout(this);
        gameRow.setGravity(Gravity.CENTER_VERTICAL);

        Button skyDive = button("FPV.SKYDIVE", 0xFF102E44, 0xFF39C5FF);
        skyDive.setOnClickListener(v -> launchPackage("com.Orqa.FPVSkyDive", "FPV.Skydive"));
        gameRow.addView(skyDive, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button feelFpv = button("FEELFPV", 0xFF241D38, 0xFF9C7CFF);
        feelFpv.setOnClickListener(v -> launchPackage("com.FullFocusStudio.FeelFPV", "FeelFPV"));
        LinearLayout.LayoutParams feelLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        feelLp.leftMargin = dp(7);
        gameRow.addView(feelFpv, feelLp);
        root.addView(gameRow);

        Button shizuku = button("APRI SHIZUKU", 0xFF171E28, 0xFF52657A);
        shizuku.setOnClickListener(v -> launchPackage(
                "moe.shizuku.privileged.api", "Shizuku"));
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        shLp.topMargin = dp(7);
        root.addView(shizuku, shLp);

        setContentView(root);
        syncUi();
    }

    private void beginStartFlow() {
        try {
            if (!Shizuku.pingBinder()) {
                setStatus("Shizuku non è attivo · avvialo prima");
                toast("Avvia Shizuku, poi torna qui e premi AVVIA");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                setStatus("Richiedo autorizzazione Shizuku...");
                Shizuku.requestPermission(SHIZUKU_REQUEST);
                return;
            }
        } catch (Throwable t) {
            setStatus("Shizuku non disponibile: " + safeMessage(t));
            return;
        }
        ensureUsbAndStart();
    }

    private void ensureUsbAndStart() {
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        UsbDevice device = Rcn1cUsbReader.findDji(manager);
        if (device == null) {
            setStatus("Nessun RC DJI rilevato · collega il cavo USB/OTG");
            return;
        }
        if (manager.hasPermission(device)) {
            startBridgeService(device);
            return;
        }
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent permission = PendingIntent.getBroadcast(
                this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                flags);
        manager.requestPermission(device, permission);
        setStatus("Richiedo permesso USB per " + deviceLabel(device) + "...");
    }

    private void startBridgeService(UsbDevice device) {
        Intent service = new Intent(this, GamepadBridgeService.class)
                .putExtra(GamepadBridgeService.EXTRA_DEVICE, device);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
        setStatus("Avvio bridge locale...");
    }

    private void stopBridgeService() {
        Intent stop = new Intent(this, GamepadBridgeService.class)
                .setAction(GamepadBridgeService.ACTION_STOP);
        startService(stop);
        setStatus("Arresto bridge...");
    }

    private void syncUi() {
        boolean shizukuRunning = false;
        boolean shizukuGranted = false;
        try {
            shizukuRunning = Shizuku.pingBinder();
            shizukuGranted = shizukuRunning
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
        }

        if (shizukuGranted) {
            shizukuView.setText("● Shizuku pronto · uinput verrà creato con UID shell");
            shizukuView.setTextColor(0xFF2ED573);
        } else if (shizukuRunning) {
            shizukuView.setText("● Shizuku attivo · autorizzazione non ancora concessa");
            shizukuView.setTextColor(0xFFFFD166);
        } else {
            shizukuView.setText("○ Shizuku non attivo");
            shizukuView.setTextColor(0xFF8294A8);
        }

        toggleButton.setText(GamepadBridgeService.active ? "FERMA" : "AVVIA");
        if (GamepadBridgeService.active) {
            statusView.setText(GamepadBridgeService.status);
        }

        Rcn1cUsbReader.Frame f = GamepadBridgeService.latestFrame;
        if (f == null) {
            padL.setPoint(Rcn1cUsbReader.RAW_CENTER, Rcn1cUsbReader.RAW_CENTER);
            padR.setPoint(Rcn1cUsbReader.RAW_CENTER, Rcn1cUsbReader.RAW_CENTER);
            statsView.setText("RC --   gamepad " + (GamepadBridgeService.gamepadReady ? "READY" : "--"));
            return;
        }

        padL.setPoint(f.rawLx, f.rawLy);
        padR.setPoint(f.rawRx, f.rawRy);
        statsView.setText(String.format(
                "%.0f pkt/s   %s   mode %s   gamepad %s",
                f.packetsPerSecond,
                f.packetCount + " pkt",
                modeLabel(f.mode),
                GamepadBridgeService.gamepadReady ? "READY" : "--"));
    }

    private void launchPackage(String packageName, String label) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            toast(label + " non installato");
            return;
        }
        try {
            startActivity(intent);
        } catch (Throwable t) {
            toast("Impossibile aprire " + label);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }
    }

    private Button button(String text, int fill, int stroke) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFEAF2FF);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(11));
        bg.setStroke(dp(1), stroke);
        b.setBackground(bg);
        return b;
    }

    private void setStatus(String text) {
        statusView.setText(text);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String modeLabel(int mode) {
        if (mode == 0) return "SPORT";
        if (mode == 2) return "CINE";
        return "NORMAL";
    }

    private static String deviceLabel(UsbDevice d) {
        String name = d.getProductName();
        return name == null || name.trim().isEmpty() ? "DJI RC" : name;
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice getUsbDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
