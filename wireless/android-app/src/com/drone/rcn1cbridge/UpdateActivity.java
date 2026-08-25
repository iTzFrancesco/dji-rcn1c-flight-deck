package com.drone.rcn1cbridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateActivity extends Activity {
    private static final String API = "https://api.github.com/repos/iTzFrancesco/dji-rcn1c-flight-deck/releases/latest";
    private static final String TRUST_PREFIX = "https://github.com/iTzFrancesco/dji-rcn1c-flight-deck/releases/download/";

    private TextView status;
    private Button action;
    private String remoteVersion;
    private String remoteUrl;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        check();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(18), dp(24), dp(18));
        root.setBackgroundColor(0xFF0B0F14);

        TextView title = text("AGGIORNAMENTI", 20, 0xFFEAF2FF, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView current = text("Installata: v" + BuildConfig.VERSION_NAME, 12, 0xFF8294A8, false);
        current.setGravity(Gravity.CENTER);
        current.setPadding(0, dp(7), 0, dp(16));
        root.addView(current);

        status = text("Controllo GitHub…", 12, 0xFF92A4B8, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        action = button("CONTROLLO…", 0xFF123B32, 0xFF2ED573);
        action.setEnabled(false);
        action.setOnClickListener(v -> { if (remoteUrl != null) download(); else check(); });
        root.addView(action, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        Button back = button("INDIETRO", 0xFF171E28, 0xFF52657A);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        backLp.topMargin = dp(8);
        root.addView(back, backLp);
        setContentView(root);
    }

    private void check() {
        remoteVersion = null;
        remoteUrl = null;
        action.setEnabled(false);
        action.setText("CONTROLLO…");
        status.setText("Controllo ultima release…");

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = open(API, 12000, 12000);
                int code = connection.getResponseCode();
                if (code != 200) throw new IOException("GitHub HTTP " + code);

                JSONObject release = new JSONObject(read(connection.getInputStream()));
                String tag = release.optString("tag_name", "").trim();
                if (tag.isEmpty()) throw new IOException("Versione release mancante");

                JSONArray assets = release.optJSONArray("assets");
                String exact = null;
                String fallback = null;
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset == null) continue;
                        String name = asset.optString("name", "");
                        String url = asset.optString("browser_download_url", "");
                        if (url.isEmpty()) continue;
                        if ("RCN1C_Bridge.apk".equals(name)) exact = url;
                        else if (fallback == null && name.toLowerCase(Locale.ROOT).endsWith(".apk")) fallback = url;
                    }
                }

                String apkUrl = exact != null ? exact : fallback;
                if (apkUrl == null) throw new IOException("Nessun APK nella release");
                if (!apkUrl.startsWith(TRUST_PREFIX)) throw new SecurityException("URL release non attendibile");

                String version = tag.startsWith("v") ? tag.substring(1) : tag;
                int comparison = compare(version, BuildConfig.VERSION_NAME);
                String foundUrl = apkUrl;
                runOnUiThread(() -> {
                    if (comparison > 0) {
                        remoteVersion = version;
                        remoteUrl = foundUrl;
                        status.setText("Nuova versione disponibile: v" + version);
                        action.setText("SCARICA E AGGIORNA");
                    } else if (comparison < 0) {
                        status.setText("Questa build è più recente della release pubblica");
                        action.setText("RICONTROLLA");
                    } else {
                        status.setText("Sei già aggiornato · v" + BuildConfig.VERSION_NAME);
                        action.setText("RICONTROLLA");
                    }
                    action.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Controllo fallito: " + safeMessage(e));
                    action.setText("RIPROVA");
                    action.setEnabled(true);
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "update-check").start();
    }

    private void download() {
        if (remoteUrl == null || remoteVersion == null) return;
        action.setEnabled(false);
        status.setText("Scarico v" + remoteVersion + "…");
        String url = remoteUrl;
        String version = remoteVersion;

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) throw new IOException("Download non disponibile");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cartella download non disponibile");
                File apk = new File(dir, "RCN1C_Bridge-" + version + ".apk");

                connection = open(url, 15000, 30000);
                connection.setInstanceFollowRedirects(true);
                int code = connection.getResponseCode();
                if (code != 200) throw new IOException("Download HTTP " + code);
                try (InputStream in = new BufferedInputStream(connection.getInputStream()); FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
                }

                if (apk.length() < 10000) throw new IOException("APK scaricata non valida");
                if (!sameSigner(apk)) throw new SecurityException("Firma APK diversa: aggiornamento bloccato");

                ApkFileProvider.setSharedFile(apk);
                Uri uri = ApkFileProvider.uriFor(apk);
                Intent installer = new Intent(Intent.ACTION_VIEW);
                installer.setDataAndType(uri, "application/vnd.android.package-archive");
                installer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                installer.setClipData(ClipData.newRawUri("RCN1C_Bridge", uri));
                runOnUiThread(() -> {
                    status.setText("APK verificata · conferma l'installazione");
                    action.setEnabled(true);
                    try { startActivity(installer); }
                    catch (ActivityNotFoundException e) { toast("Installatore APK non disponibile"); }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Aggiornamento fermato: " + safeMessage(e));
                    action.setText("RIPROVA");
                    action.setEnabled(true);
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "update-download").start();
    }

    private HttpURLConnection open(String url, int connectTimeout, int readTimeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectTimeout);
        c.setReadTimeout(readTimeout);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "RCN1C-Flight-Bridge/" + BuildConfig.VERSION_NAME);
        return c;
    }

    private boolean sameSigner(File apk) throws Exception {
        PackageManager pm = getPackageManager();
        if (Build.VERSION.SDK_INT >= 28) {
            PackageInfo current = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (archive == null || archive.signingInfo == null || current.signingInfo == null) return false;
            Signature[] a = current.signingInfo.getApkContentsSigners();
            Signature[] b = archive.signingInfo.getApkContentsSigners();
            return a.length > 0 && b.length > 0 && digest(a[0]).equals(digest(b[0]));
        }
        PackageInfo current = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        return archive != null && current.signatures != null && archive.signatures != null
                && current.signatures.length > 0 && archive.signatures.length > 0
                && digest(current.signatures[0]).equals(digest(archive.signatures[0]));
    }

    private static String digest(Signature signature) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
        StringBuilder out = new StringBuilder();
        for (byte b : d) out.append(String.format(Locale.ROOT, "%02x", b));
        return out.toString();
    }

    private static int compare(String left, String right) {
        String[] la = left.split("-", 2), ra = right.split("-", 2);
        String[] a = la[0].split("\\."), b = ra[0].split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? parseInt(a[i]) : 0;
            int y = i < b.length ? parseInt(b[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        if (la.length == 1 && ra.length > 1) return 1;
        if (la.length > 1 && ra.length == 1) return -1;
        if (la.length == 1) return 0;
        int x = suffixNumber(la[1]), y = suffixNumber(ra[1]);
        if (x != y) return x < y ? -1 : 1;
        return la[1].compareToIgnoreCase(ra[1]);
    }

    private static int suffixNumber(String value) {
        Matcher m = Pattern.compile("(\\d+)").matcher(value);
        return m.find() ? parseInt(m.group(1)) : 0;
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; }
    }

    private static String read(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return t;
    }

    private Button button(String s, int fill, int stroke) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(0xFFEAF2FF); b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(fill); bg.setCornerRadius(dp(12)); bg.setStroke(dp(1), stroke); b.setBackground(bg);
        return b;
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
