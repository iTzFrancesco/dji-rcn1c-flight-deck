package com.drone.rcn1cbridge;

import android.accessibilityservice.AccessibilityServiceInfo;
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
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Main offline mode: RC-N1C -> Accessibility gestures -> touch-controlled FPV simulator. */
public final class PortableTouchActivity extends Activity {
    private static final String ACTION_USB_PERMISSION =
            "com.drone.rcn1cbridge.PORTABLE_TOUCH_USB_PERMISSION";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView accessStatus, bridgeStatus, stats;
    private Button startButton;
    private StickPadView padL, padR;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                UsbDevice device = getUsbDevice(intent);
                if (granted && device != null) startService(device);
                else toast("Permesso USB negato");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                stopServiceBridge();
            }
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            syncUi();
            ui.postDelayed(this, 80);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, f);
    }

    @Override protected void onResume() {
        super.onResume();
        PortableTouchAccessibilityService.disarm();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(usbReceiver); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(8));
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
        title.setText("PORTABLE TOUCH BRIDGE");
        title.setTextColor(0xFFEAF2FF);
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        titles.addView(title);
        bridgeStatus = new TextView(this);
        bridgeStatus.setTextColor(0xFF92A4B8);
        bridgeStatus.setTextSize(10);
        bridgeStatus.setText("Offline · nessun root/Wi-Fi/Shizuku");
        titles.addView(bridgeStatus);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        startButton = button("AVVIA", 0xFF123B32, 0xFF2ED573);
        startButton.setOnClickListener(v -> {
            if (PortableTouchBridgeService.active) stopServiceBridge();
            else beginStart();
        });
        header.addView(startButton, new LinearLayout.LayoutParams(dp(104), dp(44)));
        root.addView(header);

        accessStatus = new TextView(this);
        accessStatus.setTextSize(10);
        accessStatus.setPadding(dp(2), dp(5), dp(2), dp(3));
        root.addView(accessStatus);

        LinearLayout accessRow = new LinearLayout(this);
        Button enableAccess = button("ATTIVA ACCESSIBILITÀ", 0xFF172332, 0xFF39C5FF);
        enableAccess.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        accessRow.addView(enableAccess, new LinearLayout.LayoutParams(0, dp(40), 1f));
        Button calibrate = button("APRI + CALIBRA", 0xFF302710, 0xFFFFD166);
        calibrate.setOnClickListener(v -> openFreerider(true));
        LinearLayout.LayoutParams calLp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        calLp.leftMargin = dp(7);
        accessRow.addView(calibrate, calLp);
        root.addView(accessRow);

        LinearLayout pads = new LinearLayout(this);
        pads.setGravity(Gravity.CENTER);
        padL = new StickPadView(this, 0xFF39C5FF);
        padL.setLabel("THROTTLE / YAW");
        padR = new StickPadView(this, 0xFFFF9F43);
        padR.setLabel("PITCH / ROLL");
        pads.addView(padL, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        pads.addView(padR, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(pads, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        stats = new TextView(this);
        stats.setTextColor(0xFF8294A8);
        stats.setTextSize(10);
        stats.setTypeface(android.graphics.Typeface.MONOSPACE);
        stats.setSingleLine(true);
        stats.setText("RC --   touch --");
        root.addView(stats);

        LinearLayout games = new LinearLayout(this);
        Button freerider = button("FPV FREERIDER", 0xFF102E44, 0xFF39C5FF);
        freerider.setOnClickListener(v -> openFreerider(false));
        games.addView(freerider, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button backup = button("GAMEPAD BACKUP", 0xFF241D38, 0xFF9C7CFF);
        backup.setOnClickListener(v -> startActivity(new Intent(this, GamepadActivity.class)));
        LinearLayout.LayoutParams backupLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        backupLp.leftMargin = dp(7);
        games.addView(backup, backupLp);
        root.addView(games);

        TextView note = new TextView(this);
        note.setText("La prima volta abilita RC-N1C Flight Bridge in Accessibilità. Poi puoi usare questa modalità completamente offline. Shizuku resta solo nel backup Gamepad.");
        note.setTextColor(0xFF607184);
        note.setTextSize(9);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(8), dp(5), dp(8), 0);
        root.addView(note);

        setContentView(root);
        syncUi();
    }

    private void beginStart() {
        if (!isAccessibilityEnabled()) {
            toast("Abilita prima RC-N1C Flight Bridge in Accessibilità");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        UsbDevice device = Rcn1cUsbReader.findDji(manager);
        if (device == null) {
            toast("Nessun RC DJI rilevato · collega il cavo USB/OTG");
            return;
        }
        if (manager.hasPermission(device)) {
            startService(device);
            return;
        }
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
        manager.requestPermission(device, pi);
        bridgeStatus.setText("Richiedo permesso USB...");
    }

    private void startService(UsbDevice device) {
        Intent i = new Intent(this, PortableTouchBridgeService.class)
                .putExtra(PortableTouchBridgeService.EXTRA_DEVICE, device);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        bridgeStatus.setText("Avvio Portable Touch Bridge...");
    }

    private void stopServiceBridge() {
        Intent i = new Intent(this, PortableTouchBridgeService.class)
                .setAction(PortableTouchBridgeService.ACTION_STOP);
        startService(i);
        PortableTouchAccessibilityService.releaseTouches();
        bridgeStatus.setText("Arresto bridge...");
    }

    private void openFreerider(boolean calibrate) {
        if (!isAccessibilityEnabled()) {
            toast("Abilita prima il servizio Accessibilità di Flight Bridge");
            return;
        }
        Intent game = getPackageManager().getLaunchIntentForPackage("com.Freeride.Freerider_FREE");
        if (game == null) game = getPackageManager().getLaunchIntentForPackage("com.Freeride.Freerider");
        if (game == null) {
            toast("FPV Freerider non installato");
            return;
        }
        startActivity(game);
        if (calibrate) {
            ui.postDelayed(() -> {
                if (!PortableTouchAccessibilityService.requestCalibration()) {
                    toast("Servizio Accessibilità non ancora collegato");
                }
            }, 900);
        } else {
            ui.postDelayed(() -> {
                if (!PortableTouchAccessibilityService.armForGame("FPV Freerider")) {
                    toast("Servizio Accessibilità non collegato");
                }
            }, 350);
        }
    }

    private boolean isAccessibilityEnabled() {
        if (PortableTouchAccessibilityService.isConnected()) return true;
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> enabled = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String pkg = getPackageName();
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null
                    && pkg.equals(info.getResolveInfo().serviceInfo.packageName)
                    && PortableTouchAccessibilityService.class.getName()
                    .equals(info.getResolveInfo().serviceInfo.name)) return true;
        }
        return false;
    }

    private void syncUi() {
        boolean access = isAccessibilityEnabled();
        accessStatus.setText(access
                ? "● Accessibilità pronta · " + PortableTouchAccessibilityService.getLastState()
                : "○ Accessibilità non attiva · abilitala una sola volta");
        accessStatus.setTextColor(access ? 0xFF2ED573 : 0xFFFFD166);
        startButton.setText(PortableTouchBridgeService.active ? "FERMA" : "AVVIA");
        if (PortableTouchBridgeService.active) bridgeStatus.setText(PortableTouchBridgeService.status);

        Rcn1cUsbReader.Frame f = PortableTouchBridgeService.latestFrame;
        if (f == null) {
            padL.setPoint(Rcn1cUsbReader.RAW_CENTER, Rcn1cUsbReader.RAW_CENTER);
            padR.setPoint(Rcn1cUsbReader.RAW_CENTER, Rcn1cUsbReader.RAW_CENTER);
            stats.setText("RC --   touch " + (access ? "READY" : "--"));
        } else {
            padL.setPoint(f.rawLx, f.rawLy);
            padR.setPoint(f.rawRx, f.rawRy);
            stats.setText(String.format("%.0f pkt/s   %d pkt   touch %s",
                    f.packetsPerSecond, f.packetCount,
                    PortableTouchAccessibilityService.getLastState()));
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

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @SuppressWarnings("deprecation")
    private static UsbDevice getUsbDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }
}
