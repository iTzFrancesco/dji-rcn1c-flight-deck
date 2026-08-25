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

public final class LauncherActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.setBackgroundColor(0xFF0B0F14);

        TextView title = text("RC-N1C FLIGHT BRIDGE", 19, 0xFFEAF2FF, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = text("v" + BuildConfig.VERSION_NAME + "  ·  scegli dove vuoi usare il radiocomando", 10, 0xFF8294A8, false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(2), 0, dp(10));
        root.addView(sub);

        Button phone = modeButton(
                "1 · GIOCA SUL TELEFONO  ·  CONSIGLIATO",
                "RC → simulatore Android · completamente offline · niente Wi-Fi o root",
                0xFF10372F, 0xFF2ED573);
        phone.setOnClickListener(v -> startActivity(new Intent(this, PortableTouchActivity.class)));
        root.addView(phone, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button pc = modeButton(
                "2 · USA IL PC",
                "RC → telefono → Wi-Fi → simulatore PC · autodiscovery disponibile",
                0xFF102E44, 0xFF39C5FF);
        pc.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        pcLp.topMargin = dp(9);
        root.addView(pc, pcLp);

        Button updates = smallButton("AGGIORNAMENTI");
        updates.setOnClickListener(v -> startActivity(new Intent(this, UpdateActivity.class)));
        LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        upLp.topMargin = dp(9);
        root.addView(updates, upLp);

        setContentView(root);
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return t;
    }

    private Button modeButton(String title, String description, int fill, int stroke) {
        Button b = new Button(this);
        b.setText(title + "\n" + description);
        b.setAllCaps(false);
        b.setTextColor(0xFFEAF2FF);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(18), dp(8), dp(18), dp(8));
        b.setMinHeight(0);
        b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), stroke);
        b.setBackground(bg);
        return b;
    }

    private Button smallButton(String s) {
        Button b = modeButton(s, "", 0xFF171E28, 0xFF52657A);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(11);
        return b;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
