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

public final class LauncherActivity extends Activity {
    private TextView shizukuStatus;

    @Override protected void onCreate(Bundle savedInstanceState) { super.onCreate(savedInstanceState); buildUi(); }
    @Override protected void onResume() { super.onResume(); if (shizukuStatus != null) refreshShizukuStatus(); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(8));
        root.setBackgroundColor(0xFF0B0F14);

        TextView title = text("RC-N1C FLIGHT BRIDGE", 18, 0xFFEAF2FF, true);
        title.setGravity(Gravity.CENTER); root.addView(title);
        TextView sub = text("v" + BuildConfig.VERSION_NAME + "  ·  scegli cosa vuoi fare", 10, 0xFF8294A8, false);
        sub.setGravity(Gravity.CENTER); sub.setPadding(0, dp(2), 0, dp(8)); root.addView(sub);

        Button phone = modeButton("1 · GIOCA SUL TELEFONO  ·  CONSIGLIATO",
                "RC → gioco Android · offline · niente Wi-Fi, root o Shizuku",
                0xFF10372F, 0xFF2ED573);
        phone.setOnClickListener(v -> startActivity(new Intent(this, PortableTouchActivity.class)));
        root.addView(phone, cardLp(72));

        Button pc = modeButton("2 · USA IL PC",
                "Il telefono manda il radiocomando via Wi-Fi al simulatore sul PC",
                0xFF102E44, 0xFF39C5FF);
        pc.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        LinearLayout.LayoutParams p2 = cardLp(72); p2.topMargin = dp(7); root.addView(pc, p2);

        Button backup = modeButton("3 · GAMEPAD ANDROID  ·  BACKUP",
                "Provalo se Touch non va · controller di sistema · richiede Shizuku",
                0xFF241D38, 0xFF9C7CFF);
        backup.setOnClickListener(v -> startActivity(new Intent(this, GamepadActivity.class)));
        LinearLayout.LayoutParams p3 = cardLp(72); p3.topMargin = dp(7); root.addView(backup, p3);

        LinearLayout bottom = new LinearLayout(this); bottom.setGravity(Gravity.CENTER_VERTICAL);
        Button updates = smallButton("AGGIORNAMENTI");
        updates.setOnClickListener(v -> startActivity(new Intent(this, UpdateActivity.class)));
        bottom.addView(updates, new LinearLayout.LayoutParams(0, dp(38), 1f));
        shizukuStatus = text("", 9, 0xFF607184, false); shizukuStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams st = new LinearLayout.LayoutParams(0, dp(38), 1.5f); st.leftMargin=dp(7); bottom.addView(shizukuStatus, st);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)); bp.topMargin=dp(7); root.addView(bottom,bp);
        refreshShizukuStatus(); setContentView(root);
    }

    private void refreshShizukuStatus() {
        boolean running=false, granted=false;
        try { running=Shizuku.pingBinder(); granted=running && Shizuku.checkSelfPermission()==android.content.pm.PackageManager.PERMISSION_GRANTED; } catch(Throwable ignored){}
        if (granted) { shizukuStatus.setText("Backup: Shizuku pronto"); shizukuStatus.setTextColor(0xFF2ED573); }
        else if (running) { shizukuStatus.setText("Backup: Shizuku da autorizzare"); shizukuStatus.setTextColor(0xFFFFD166); }
        else { shizukuStatus.setText("Shizuku serve solo alla modalità 3"); shizukuStatus.setTextColor(0xFF607184); }
    }

    private TextView text(String s,int size,int color,boolean bold){ TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD); return t; }
    private Button modeButton(String a,String b,int fill,int stroke){ Button v=new Button(this); v.setText(a+"\n"+b); v.setAllCaps(false); v.setTextColor(0xFFEAF2FF); v.setTextSize(11); v.setGravity(Gravity.CENTER_VERTICAL|Gravity.START); v.setPadding(dp(14),0,dp(14),0); v.setMinHeight(0); v.setStateListAnimator(null); GradientDrawable g=new GradientDrawable(); g.setColor(fill); g.setCornerRadius(dp(12)); g.setStroke(dp(1),stroke); v.setBackground(g); return v; }
    private Button smallButton(String s){ Button b=modeButton(s,"",0xFF171E28,0xFF52657A); b.setGravity(Gravity.CENTER); b.setTextSize(10); return b; }
    private LinearLayout.LayoutParams cardLp(int h){ return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(h)); }
    private int dp(float v){ return Math.round(v*getResources().getDisplayMetrics().density); }
}
