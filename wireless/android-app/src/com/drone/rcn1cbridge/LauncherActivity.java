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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (shizukuStatus != null) refreshShizukuStatus();
    }

    private TextView shizukuStatus;

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(22), dp(22), dp(18));
        root.setBackgroundColor(0xFF0B0F14);

        TextView title = new TextView(this);
        title.setText("RC-N1C FLIGHT BRIDGE");
        title.setTextColor(0xFFEAF2FF);
        title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("v" + BuildConfig.VERSION_NAME + "  ·  scegli dove inviare il radiocomando");
        subtitle.setTextColor(0xFF8294A8);
        subtitle.setTextSize(12);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dp(5), 0, dp(22));
        root.addView(subtitle, subLp);

        Button pc = modeButton(
                "PC BRIDGE",
                "RC → USB telefono → Wi-Fi → PC",
                0xFF123B32,
                0xFF2ED573);
        pc.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        root.addView(pc, cardLp());

        Button android = modeButton(
                "ANDROID GAMEPAD  ·  BETA",
                "RC → USB telefono → controller virtuale → gioco",
                0xFF102E44,
                0xFF39C5FF);
        android.setOnClickListener(v -> startActivity(new Intent(this, GamepadActivity.class)));
        LinearLayout.LayoutParams androidLp = cardLp();
        androidLp.topMargin = dp(12);
        root.addView(android, androidLp);

        shizukuStatus = new TextView(this);
        shizukuStatus.setTextSize(11);
        shizukuStatus.setGravity(Gravity.CENTER);
        shizukuStatus.setPadding(dp(8), dp(14), dp(8), dp(4));
        root.addView(shizukuStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshShizukuStatus();

        TextView note = new TextView(this);
        note.setText("La modalità PC resta invariata. Shizuku serve solo per esporre il RC come gamepad Android senza root.");
        note.setTextColor(0xFF607184);
        note.setTextSize(10);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(18), dp(5), dp(18), 0);
        root.addView(note, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void refreshShizukuStatus() {
        boolean running = false;
        boolean granted = false;
        try {
            running = Shizuku.pingBinder();
            granted = running && Shizuku.checkSelfPermission()
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
        }
        if (granted) {
            shizukuStatus.setText("● Shizuku pronto per Android Gamepad");
            shizukuStatus.setTextColor(0xFF2ED573);
        } else if (running) {
            shizukuStatus.setText("● Shizuku attivo · autorizzazione richiesta al primo avvio");
            shizukuStatus.setTextColor(0xFFFFD166);
        } else {
            shizukuStatus.setText("○ Shizuku non attivo · necessario solo per Android Gamepad");
            shizukuStatus.setTextColor(0xFF8294A8);
        }
    }

    private Button modeButton(String title, String subtitle, int fill, int stroke) {
        Button b = new Button(this);
        b.setText(title + "\n" + subtitle);
        b.setAllCaps(false);
        b.setTextColor(0xFFEAF2FF);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setMinHeight(0);
        b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(15));
        bg.setStroke(dp(1), stroke);
        b.setBackground(bg);
        return b;
    }

    private LinearLayout.LayoutParams cardLp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(86));
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
