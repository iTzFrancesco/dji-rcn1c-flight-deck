package com.drone.rcn1cbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

/** Unified entry point for RC-N1C Flight Bridge. */
public final class LauncherActivity extends Activity {
    private TextView shizukuStatus;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        if (shizukuStatus != null) refreshShizukuStatus();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(8));
        root.setBackgroundColor(0xFF0B0F14);

        TextView title = new TextView(this);
        title.setText("RC-N1C FLIGHT BRIDGE");
        title.setTextColor(0xFFEAF2FF);
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("v" + BuildConfig.VERSION_NAME + "  ·  RC ovunque");
        subtitle.setTextColor(0xFF8294A8);
        subtitle.setTextSize(11);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dp(2), 0, dp(8));
        root.addView(subtitle, subLp);

        Button portable = modeButton(
                "PORTABLE TOUCH  ·  CONSIGLIATO",
                "RC → USB telefono → controlli touch del gioco · offline",
                0xFF10372F, 0xFF2ED573);
        portable.setOnClickListener(v -> startActivity(new Intent(this, PortableTouchActivity.class)));
        root.addView(portable, cardLp());

        Button pc = modeButton(
                "PC BRIDGE",
                "RC → USB telefono → Wi-Fi → PC",
                0xFF102E44, 0xFF39C5FF);
        pc.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        LinearLayout.LayoutParams pcLp = cardLp();
        pcLp.topMargin = dp(6);
        root.addView(pc, pcLp);

        Button backup = modeButton(
                "ANDROID GAMEPAD  ·  BACKUP",
                "controller virtuale di sistema · richiede Shizuku",
                0xFF241D38, 0xFF9C7CFF);
        backup.setOnClickListener(v -> startActivity(new Intent(this, GamepadActivity.class)));
        LinearLayout.LayoutParams backupLp = cardLp();
        backupLp.topMargin = dp(6);
        root.addView(backup, backupLp);

        shizukuStatus = new TextView(this);
        shizukuStatus.setTextSize(9);
        shizukuStatus.setGravity(Gravity.CENTER);
        shizukuStatus.setPadding(dp(4), dp(5), dp(4), 0);
        root.addView(shizukuStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshShizukuStatus();

        setContentView(root);
    }

    private void refreshShizukuStatus() {
        boolean running = false, granted = false;
        try {
            running = Shizuku.pingBinder();
            granted = running && Shizuku.checkSelfPermission()
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {}

        if (granted) {
            shizukuStatus.setText("Backup Gamepad: Shizuku pronto");
            shizukuStatus.setTextColor(0xFF2ED573);
        } else if (running) {
            shizukuStatus.setText("Backup Gamepad: Shizuku attivo, autorizzazione non concessa");
            shizukuStatus.setTextColor(0xFFFFD166);
        } else {
            shizukuStatus.setText("Portable Touch non usa Shizuku · il backup sì");
            shizukuStatus.setTextColor(0xFF607184);
        }
    }

    private Button modeButton(String title, String subtitle, int fill, int stroke) {
        Button b = new Button(this);
        b.setText(title + "\n" + subtitle);
        b.setAllCaps(false);
        b.setTextColor(0xFFEAF2FF);
        b.setTextSize(11);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinHeight(0);
        b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), stroke);
        b.setBackground(bg);
        return b;
    }

    private LinearLayout.LayoutParams cardLp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
