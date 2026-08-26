package com.drone.rcn1cbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class MainActivity extends Activity {

    private static final byte[] ENABLE_SIMULATOR = {
            0x55, 0x0e, 0x04, 0x66, 0x0a, 0x06, (byte) 0xeb, 0x34,
            0x40, 0x06, 0x24, 0x01, (byte) 0xd9, (byte) 0xec};
    private static final byte[] REQUEST = {
            0x55, 0x0d, 0x04, 0x33, 0x0a, 0x06, (byte) 0xeb, 0x34,
            0x40, 0x06, 0x01, 0x74, 0x24};
    private static final byte[] REQUEST_BUTTONS = {
            0x55, 0x0d, 0x04, 0x33, 0x0a, 0x06, (byte) 0xeb, 0x34,
            0x40, 0x06, 0x27, 0x40, 0x60};
    private static final int PACKET_LEN = 38;
    private static final int BUTTON_PACKET_LEN = 58;
    private static final int DEFAULT_PORT = 26789;
    private static final int DISCOVERY_PORT = 26790;
    private static final int VENDOR_DJI = 0x2CA3;
    private static final String ACTION_USB_PERMISSION = "com.drone.rcn1cbridge.USB_PERMISSION";
    private static final String APP_VERSION = BuildConfig.VERSION_NAME;
    private static final String UPDATE_API =
            "https://api.github.com/repos/iTzFrancesco/dji-rcn1c-flight-deck/releases/latest";
    private static final String RELEASE_ASSET_PREFIX =
            "https://github.com/iTzFrancesco/dji-rcn1c-flight-deck/";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean checkingUpdate = new AtomicBoolean(false);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile int vRx = StickPadView.RAW_CENTER, vRy = StickPadView.RAW_CENTER;
    private volatile int vLy = StickPadView.RAW_CENTER, vLx = StickPadView.RAW_CENTER;
    private volatile int vButtonMask = 0x1000, vMode = 1;
    private volatile boolean vShutter = false, vPhotoVideo = false, vRth = false, vFn = false;
    private volatile long sentPkts = 0, badPkts = 0;
    private volatile float ratePktS = 0f, rttMs = 0f;
    private volatile String deviceName = "";
    private volatile String destText = "-";

    private TextView status, versionHint, stats;
    private EditText ipEdit, portEdit;
    private Button goBtn, updateBtn, settingsBtn;
    private StickPadView padL, padR;
    private StripChartView chartL, chartR;

    private Thread worker;
    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;
    private DatagramSocket socket;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    notifyAll();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                stopBridge("Radiocomando scollegato");
            }
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            chartL.invalidate();
            chartR.invalidate();
            stats.setText(String.format("Inviati %d   scarti %d   %.0f pkt/s   RTT %.1f ms   %s",
                    sentPkts, badPkts, ratePktS, rttMs,
                    netRttMs > 0 ? String.format("rete %.0f ms", netRttMs) : "rete --"));
            int on = 0xFF2ED573, off = 0xFF4A5568;
            chipRotL.setTextColor(vCam <= StickPadView.RAW_CENTER - 99 ? on : off);
            chipRotR.setTextColor(vCam >= StickPadView.RAW_CENTER + 99 ? on : off);
            chipShutter.setTextColor(vShutter ? on : off);
            chipPhotoVideo.setTextColor(vPhotoVideo ? on : off);
            chipRth.setTextColor(vRth ? on : off);
            chipFn.setTextColor(vFn ? on : off);
            chipMode.setText(modeLabel(vMode));
            chipMode.setTextColor(0xFFE5B567);
            ui.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, f);
        }

        SharedPreferences p = getSharedPreferences("cfg", MODE_PRIVATE);
        ipEdit.setText(p.getString("ip", ""));
        portEdit.setText(p.getString("port", String.valueOf(DEFAULT_PORT)));

        handleAttach(getIntent());
        startTicker();
        runStartupChecks();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleAttach(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running.set(false);
        ui.removeCallbacks(ticker);
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        releaseLocks();
    }

    private void handleAttach(Intent intent) {
        UsbDevice dev = intent != null ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) : null;
        if (dev != null) {
            deviceName = dev.getProductName() != null ? dev.getProductName() : dev.getDeviceName();
            status.setText("RC rilevato · premi AVVIA");
            Toast.makeText(this, "RC rilevato: " + deviceName, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String modeLabel(int code) {
        if (code == 0) return "SPORT";
        if (code == 2) return "CINE";
        return "NORMAL";
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(8));
        root.setBackgroundColor(0xFF0B0F14);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(42));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("RC-N1C FLIGHT DECK  ·  v" + APP_VERSION);
        title.setTextColor(0xFFEAF2FF);
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        title.setLetterSpacing(0.06f);
        titleBlock.addView(title);

        status = new TextView(this);
        status.setTextColor(0xFF92A4B8);
        status.setTextSize(11);
        status.setSingleLine(true);
        status.setText("Pronto · collega il radiocomando e premi AVVIA");
        titleBlock.addView(status);
        header.addView(titleBlock, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout headerActions = new LinearLayout(this);
        headerActions.setGravity(Gravity.CENTER_VERTICAL);

        goBtn = styledButton("AVVIA", 0xFF123B32, 0xFF2ED573);
        goBtn.setTextSize(11);
        goBtn.setOnClickListener(v -> toggle());
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(dp(112), dp(44));
        goLp.setMargins(0, 0, dp(6), 0);
        headerActions.addView(goBtn, goLp);

        Button simulatorBtn = styledButton("SIMULATORE FPV", 0xFF2B2145, 0xFFB58CFF);
        simulatorBtn.setTextSize(10);
        simulatorBtn.setOnClickListener(v -> openSimulator());
        LinearLayout.LayoutParams simulatorLp = new LinearLayout.LayoutParams(dp(132), dp(44));
        simulatorLp.setMargins(0, 0, dp(6), 0);
        headerActions.addView(simulatorBtn, simulatorLp);

        settingsBtn = styledButton("\u2699", 0xFF172332, 0xFF2E9DCE);
        settingsBtn.setTextSize(21);
        settingsBtn.setContentDescription("Impostazioni");
        settingsBtn.setPadding(0, 0, 0, dp(2));
        settingsBtn.setOnClickListener(v -> showSettings());
        headerActions.addView(settingsBtn, new LinearLayout.LayoutParams(dp(52), dp(44)));
        header.addView(headerActions);
        root.addView(header);

        versionHint = new TextView(this);
        versionHint.setTextColor(0xFF5F7288);
        versionHint.setTextSize(10);
        versionHint.setSingleLine(true);
        versionHint.setPadding(dp(2), 0, dp(2), dp(2));
        versionHint.setText("Controllo iniziale in corso...");
        root.addView(versionHint);

        ipEdit = settingsField("IP PC (vuoto = AUTO)", InputType.TYPE_CLASS_PHONE);
        portEdit = settingsField("Porta", InputType.TYPE_CLASS_NUMBER);

        LinearLayout pads = new LinearLayout(this);
        pads.setGravity(Gravity.CENTER);
        pads.setPadding(0, 0, 0, 0);
        padL = new StickPadView(this, 0xFF39C5FF);
        padL.setLabel("THROTTLE / YAW");
        padR = new StickPadView(this, 0xFFFF9F43);
        padR.setLabel("PITCH / ROLL");
        pads.addView(padL, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        pads.addView(padR, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(pads, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout charts = new LinearLayout(this);
        charts.setPadding(0, dp(2), 0, 0);
        chartL = new StripChartView(this, 0xFF39C5FF, 0xFF2ED573);
        chartR = new StripChartView(this, 0xFFFF9F43, 0xFFFFD166);
        charts.addView(chartL, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        charts.addView(chartR, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(charts, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));

        stats = new TextView(this);
        stats.setTextColor(0xFF8294A8);
        stats.setTextSize(10);
        stats.setSingleLine(true);
        stats.setTypeface(android.graphics.Typeface.MONOSPACE);
        stats.setPadding(dp(2), 0, dp(2), 0);
        stats.setText("Inviati 0   scarti 0   0 pkt/s   RTT 0.0 ms");
        root.addView(stats);

        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setPadding(0, dp(1), 0, dp(1));
        chipRotL = mkChip(chips, "ROT ◀");
        chipRotR = mkChip(chips, "ROT ▶");
        chipShutter = mkChip(chips, "SCATTO");
        chipPhotoVideo = mkChip(chips, "FOTO/VIDEO");
        chipRth = mkChip(chips, "RTH");
        chipFn = mkChip(chips, "FN");
        chipMode = mkChip(chips, "NORMAL");
        root.addView(chips, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(23)));

        setContentView(root);
    }

    private Button styledButton(String label, int fill, int stroke) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(0xFFEAF2FF);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(42));
        button.setMinWidth(0);
        button.setStateListAnimator(null);
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), stroke);
        button.setBackground(background);
        return button;
    }

    private EditText settingsField(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setSingleLine(true);
        field.setTextColor(0xFFE6EDF3);
        field.setHintTextColor(0xFF6D7D90);
        field.setTextSize(14);
        field.setPadding(dp(12), 0, dp(12), 0);
        return field;
    }

    private void updateGoButton() {
        if (goBtn == null) return;
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(12));
        if (running.get()) {
            goBtn.setText("FERMA");
            goBtn.setTextColor(0xFFFFD9D9);
            background.setColor(0xFF4A1D24);
            background.setStroke(dp(1), 0xFFFF5A67);
        } else {
            goBtn.setText("AVVIA");
            goBtn.setTextColor(0xFFEAF2FF);
            background.setColor(0xFF123B32);
            background.setStroke(dp(1), 0xFF2ED573);
        }
        goBtn.setBackground(background);
    }

    private void showSettings() {
        String currentIp = ipEdit != null ? ipEdit.getText().toString() : "";
        String currentPort = portEdit != null ? portEdit.getText().toString() : String.valueOf(DEFAULT_PORT);
        ipEdit = settingsField("IP PC (vuoto = AUTO)", InputType.TYPE_CLASS_PHONE);
        ipEdit.setText(currentIp);
        portEdit = settingsField("Porta", InputType.TYPE_CLASS_NUMBER);
        portEdit.setText(currentPort);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(4), 0, dp(4), 0);

        TextView hint = new TextView(this);
        hint.setText("Connessione PC\nIP vuoto = rilevamento automatico nella rete locale");
        hint.setTextColor(0xFF8FA1B4);
        hint.setTextSize(12);
        hint.setPadding(0, 0, 0, dp(12));
        panel.addView(hint);

        panel.addView(ipEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout.LayoutParams portLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        portLp.topMargin = dp(8);
        panel.addView(portEdit, portLp);

        updateBtn = styledButton("CONTROLLA AGGIORNAMENTI", 0xFF172332, 0xFF2E9DCE);
        updateBtn.setOnClickListener(v -> checkForUpdate(true, updateBtn));
        LinearLayout.LayoutParams updateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        updateLp.topMargin = dp(16);
        panel.addView(updateBtn, updateLp);

        TextView buildInfo = new TextView(this);
        buildInfo.setText("Versione installata: v" + APP_VERSION
                + "\nIl download di una nuova APK richiede la conferma di Android.");
        buildInfo.setTextColor(0xFF697B90);
        buildInfo.setTextSize(11);
        buildInfo.setPadding(0, dp(12), 0, 0);
        panel.addView(buildInfo);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(panel);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Impostazioni")
                .setView(scroll)
                .setNegativeButton("ANNULLA", null)
                .setPositiveButton("SALVA", (d, which) -> saveSettings())
                .create();
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                GradientDrawable background = new GradientDrawable();
                background.setColor(0xFF151C24);
                background.setCornerRadius(dp(14));
                dialog.getWindow().setBackgroundDrawable(background);
                dialog.getWindow().setLayout(
                        (int) (getResources().getDisplayMetrics().widthPixels * 0.82f),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) positive.setTextColor(0xFF39C5FF);
            Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (negative != null) negative.setTextColor(0xFF8FA1B4);
        });
        dialog.show();
    }

    private void saveSettings() {
        String ip = ipEdit.getText().toString().trim();
        String port = portEdit.getText().toString().trim();
        if (port.isEmpty()) port = String.valueOf(DEFAULT_PORT);
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putString("ip", ip)
                .putString("port", port)
                .apply();
        toast("Impostazioni salvate");
    }

    private TextView mkChip(LinearLayout row, String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(9);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setPadding(dp(7), 0, dp(7), 0);
        t.setTextColor(0xFF4A5568);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF141C26);
        background.setCornerRadius(dp(7));
        t.setBackground(background);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(23));
        lp.setMargins(0, 0, dp(4), 0);
        row.addView(t, lp);
        return t;
    }

    private void startTicker() {
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    private void runStartupChecks() {
        versionHint.setText("Controllo versione GitHub...");
        checkForUpdate(false, null);
        probeUsb(0);
    }

    private void probeUsb(int attempt) {
        if (isFinishing() || isDestroyed() || running.get()) return;
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        UsbDevice dev = findDji(um);
        if (dev != null) {
            deviceName = dev.getProductName() != null ? dev.getProductName() : dev.getDeviceName();
            status.setText("RC pronto · premi AVVIA");
            return;
        }
        status.setText(attempt == 0
                ? "Cerco il radiocomando..."
                : "Nessun RC rilevato · collega il cavo USB");
        if (attempt < 5) ui.postDelayed(() -> probeUsb(attempt + 1), 700);
    }

    private void toggle() {
        if (running.get()) {
            stopBridge("Fermato");
        } else {
            startBridge();
        }
    }

    private void openSimulator() {
        if (running.get()) stopBridge("Passaggio al simulatore FPV");
        startActivity(new Intent(this, SimulatorActivity.class));
    }

    private void checkForUpdate(boolean showDialog, Button trigger) {
        if (!checkingUpdate.compareAndSet(false, true)) return;
        final Button finalTrigger = trigger;
        if (finalTrigger != null) finalTrigger.setEnabled(false);
        versionHint.setText("Controllo versione GitHub...");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL endpoint = new URL(UPDATE_API);
                connection = (HttpURLConnection) endpoint.openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "RCN1C-Flight-Deck/" + APP_VERSION);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("GitHub HTTP " + connection.getResponseCode());
                }
                String json = readText(connection.getInputStream());
                String tag = jsonValue(json, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
                String assetUrl = jsonValue(json,
                        "\"name\"\\s*:\\s*\"RCN1C_Bridge\\.apk\".*?"
                                + "\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"");
                if (tag == null || assetUrl == null || !assetUrl.startsWith(RELEASE_ASSET_PREFIX)) {
                    throw new IOException("release APK non trovata");
                }
                String remoteVersion = tag.startsWith("v") ? tag.substring(1) : tag;
                int comparison = compareVersions(remoteVersion, APP_VERSION);
                final String finalRemoteVersion = remoteVersion;
                final String finalAssetUrl = assetUrl;
                ui.post(() -> {
                    checkingUpdate.set(false);
                    if (finalTrigger != null) finalTrigger.setEnabled(true);
                    if (comparison <= 0) {
                        versionHint.setText("v" + APP_VERSION + " · app aggiornata");
                        if (showDialog) toast("Hai già l'ultima versione (v" + APP_VERSION + ")");
                        return;
                    }
                    versionHint.setText("v" + finalRemoteVersion + " disponibile · apri Impostazioni");
                    if (!showDialog) return;
                    new AlertDialog.Builder(this)
                            .setTitle("Aggiornamento disponibile")
                            .setMessage("È disponibile RC-N1C Bridge v" + finalRemoteVersion
                                    + ". Vuoi scaricare l'APK?")
                            .setNegativeButton("ANNULLA", null)
                            .setPositiveButton("SCARICA", (dialog, which) ->
                                    downloadApk(finalRemoteVersion, finalAssetUrl))
                            .show();
                });
            } catch (Exception error) {
                ui.post(() -> {
                    checkingUpdate.set(false);
                    if (finalTrigger != null) finalTrigger.setEnabled(true);
                    versionHint.setText("Versione non verificata · apri Impostazioni per riprovare");
                    if (showDialog) toast("Controllo aggiornamenti fallito: " + error.getMessage());
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "rc-update-check").start();
    }

    private void downloadApk(String version, String downloadUrl) {
        if (!downloadUrl.startsWith(RELEASE_ASSET_PREFIX)) {
            toast("URL aggiornamento non attendibile");
            return;
        }
        if (updateBtn != null) updateBtn.setEnabled(false);
        versionHint.setText("Scarico RC-N1C Bridge v" + version + "...");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                File directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (directory == null) throw new IOException("cartella download non disponibile");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IOException("impossibile creare la cartella download");
                }
                File apk = new File(directory, "RCN1C_Bridge-" + version + ".apk");
                URL endpoint = new URL(downloadUrl);
                connection = (HttpURLConnection) endpoint.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "RCN1C-Flight-Deck/" + APP_VERSION);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("download HTTP " + connection.getResponseCode());
                }
                try (InputStream input = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                }
                if (apk.length() < 10000) throw new IOException("APK scaricata non valida");
                ApkFileProvider.setSharedFile(apk);
                Uri uri = ApkFileProvider.uriFor(apk);
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(uri, "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                install.setClipData(ClipData.newRawUri("RCN1C_Bridge", uri));
                ui.post(() -> {
                    if (updateBtn != null) updateBtn.setEnabled(true);
                    versionHint.setText("APK pronta · conferma l'installazione di v" + version);
                    try {
                        startActivity(install);
                    } catch (ActivityNotFoundException error) {
                        toast("Nessun installatore APK disponibile");
                    }
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (updateBtn != null) updateBtn.setEnabled(true);
                    versionHint.setText("Download aggiornamento fallito");
                    toast("Download fallito: " + error.getMessage());
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "rc-update-download").start();
    }

    private static String readText(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = source.read(buffer)) != -1) output.write(buffer, 0, n);
            return new String(output.toByteArray(), "UTF-8");
        }
    }

    private static String jsonValue(String json, String expression) {
        Matcher matcher = Pattern.compile(expression, Pattern.DOTALL).matcher(json);
        return matcher.find() ? matcher.group(1).replace("\\/", "/") : null;
    }

    private static int compareVersions(String left, String right) {
        String[] la = left.split("-", 2);
        String[] ra = right.split("-", 2);
        String[] a = la[0].split("[.]");
        String[] b = ra[0].split("[.]");
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            int av = i < a.length ? Integer.parseInt(a[i].replaceAll("[^0-9]", "")) : 0;
            int bv = i < b.length ? Integer.parseInt(b[i].replaceAll("[^0-9]", "")) : 0;
            if (av != bv) return av < bv ? -1 : 1;
        }
        if (la.length == 1 && ra.length > 1) return 1;
        if (la.length > 1 && ra.length == 1) return -1;
        if (la.length == 1) return 0;
        java.util.regex.Matcher lm = Pattern.compile("([0-9]+)").matcher(la[1]);
        java.util.regex.Matcher rm = Pattern.compile("([0-9]+)").matcher(ra[1]);
        int ln = lm.find() ? Integer.parseInt(lm.group(1)) : 0;
        int rn = rm.find() ? Integer.parseInt(rm.group(1)) : 0;
        if (ln != rn) return ln < rn ? -1 : 1;
        return la[1].compareToIgnoreCase(ra[1]);
    }

    private void startBridge() {
        if (running.get()) return;
        String ip = ipEdit.getText().toString().trim();
        String portStr = portEdit.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(portStr.isEmpty() ? String.valueOf(DEFAULT_PORT) : portStr);
        } catch (NumberFormatException e) {
            toast("Porta non valida");
            return;
        }
        SharedPreferences.Editor e = getSharedPreferences("cfg", MODE_PRIVATE).edit();
        e.putString("ip", ip);
        e.putString("port", String.valueOf(port)).apply();
        acquireLocks();

        final String fIp = ip;
        final int fPort = port;
        running.set(true);
        updateGoButton();
        worker = new Thread(() -> runBridge(fIp, fPort), "rc-bridge");
        worker.start();
    }

    private void stopBridge(String why) {
        running.set(false);
        Thread t = worker;
        if (t != null) {
            try {
                t.join(1200);
            } catch (InterruptedException ignored) {
            }
        }
        releaseLocks();
        zeroInputs();
        status.setText("Non collegato - " + why);
        updateGoButton();
    }

    private void zeroInputs() {
        vLx = vLy = vRx = vRy = StickPadView.RAW_CENTER;
        vButtonMask = 0x1000;
        vMode = 1;
        vShutter = vPhotoVideo = vRth = vFn = false;
        padL.reset();
        padR.reset();
    }

    private void acquireLocks() {
        releaseLocks();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rcn1c:cpu");
        cpuLock.setReferenceCounted(false);
        cpuLock.acquire();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int mode = Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "rcn1c:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        } catch (Exception ignored) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
        }
    }

    private boolean waitPermission(UsbDevice dev) {
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        if (um.hasPermission(dev)) return true;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
        synchronized (usbReceiver) {
            um.requestPermission(dev, pi);
            try {
                usbReceiver.wait(30000);
            } catch (InterruptedException ignored) {
            }
        }
        return um.hasPermission(dev);
    }

    private UsbDevice findDji(UsbManager um) {
        UsbDevice fallback = null;
        for (UsbDevice d : um.getDeviceList().values()) {
            String pn = d.getProductName() != null ? d.getProductName().toUpperCase() : "";
            if (d.getVendorId() == VENDOR_DJI || pn.contains("DJI")) return d;
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                int cl = d.getInterface(i).getInterfaceClass();
                if (cl == UsbConstants.USB_CLASS_CDC_DATA || cl == UsbConstants.USB_CLASS_COMM) {
                    fallback = d;
                    break;
                }
            }
        }
        return fallback;
    }

    private String usbSummary(UsbManager um) {
        StringBuilder sb = new StringBuilder();
        for (UsbDevice d : um.getDeviceList().values()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(String.format("%s %04X:%04X",
                    d.getProductName() != null ? d.getProductName() : "?",
                    d.getVendorId(), d.getProductId()));
        }
        return sb.length() > 0 ? sb.toString() : "nessuno";
    }

    private List<Object[]> findEndpointCandidates(UsbDevice dev) {
        List<Object[]> out = new ArrayList<>();
        UsbEndpoint firstIn = null, firstOut = null;
        UsbInterface ifaceIn = null, ifaceOut = null;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface it = dev.getInterface(i);
            UsbEndpoint ein = null, eout = null;
            for (int j = 0; j < it.getEndpointCount(); j++) {
                UsbEndpoint ep = it.getEndpoint(j);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) ein = ep;
                    else eout = ep;
                }
            }
            if (ein != null && eout != null) out.add(new Object[]{it, ein, eout});
            if (ein != null && firstIn == null) { firstIn = ein; ifaceIn = it; }
            if (eout != null && firstOut == null) { firstOut = eout; ifaceOut = it; }
        }
        if (firstIn != null && firstOut != null && !sameIface(out, firstIn, firstOut)) {
            out.add(new Object[]{null, firstIn, firstOut});
        }
        return out;
    }

    private static boolean sameIface(List<Object[]> cands, UsbEndpoint in, UsbEndpoint out) {
        for (Object[] c : cands) {
            if (c[1] == in && c[2] == out) return true;
        }
        return false;
    }

    private void runBridge(String ip, int port) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        try {
            UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
            int seq = 0;
            while (running.get()) {
            UsbDeviceConnection conn = null;
            UsbEndpoint inEp = null;
            UsbEndpoint outEp = null;
            int attempts = 0;
            while (running.get() && conn == null) {
                attempts++;
                UsbDevice dev = findDji(um);
                if (dev != null && recentlyDenied(dev)) dev = null;
                if (dev != null) {
                    deviceName = dev.getProductName() != null ? dev.getProductName() : "DJI RC";
                    if (!waitPermission(dev)) {
                        markDenied(dev);
                        postStatus("Permesso USB negato per " + deviceName);
                        dev = null;
                    }
                }
                if (dev != null) {
                    UsbDeviceConnection c = um.openDevice(dev);
                    if (c != null) {
                        Object[] ok = probeCandidates(c, findEndpointCandidates(dev), new byte[512]);
                        if (ok != null) {
                            conn = c;
                            inEp = (UsbEndpoint) ok[0];
                            outEp = (UsbEndpoint) ok[1];
                        } else {
                            c.close();
                        }
                    }
                }
                if (conn == null) {
                    if (attempts == 1 || attempts % 10 == 0) {
                        postStatus("Attendo l'RC: attiva OTG e collega il cavo... USB: " + usbSummary(um));
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            if (!running.get() || conn == null) break;

            InetAddress dest = null;
            if (ip.isEmpty()) {
                postStatus("RC collegato · cerco il PC sulla rete...");
                String found = discoverPc(2000);
                if (found != null) {
                    ip = found;
                    final String fip = ip;
                    ui.post(() -> ipEdit.setText(fip));
                }
            }
            if (!ip.isEmpty()) {
                try {
                    dest = InetAddress.getByName(ip);
                } catch (Exception ignored) {
                    postStatus("Monitor locale · indirizzo PC non valido");
                }
            }

            conn.bulkTransfer(outEp, ENABLE_SIMULATOR, ENABLE_SIMULATOR.length, 100);
            ByteBuffer frame = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
            DatagramPacket pkt = null;
            if (dest != null) {
                socket = new DatagramSocket();
                try {
                    socket.setTrafficClass(0xB8);
                } catch (Exception ignored) {
                }
                pkt = new DatagramPacket(frame.array(), 18, dest, port);
                destText = ip + ":" + port;
                postStatus("Collegato: " + deviceName + " -> " + destText);
            } else {
                socket = null;
                destText = "solo monitor";
                netRttMs = 0f;
                postStatus("Monitor locale: RC e dashboard · PC non collegato");
            }
            netRttMs = 0f;
            if (socket != null) {
                pingBuf.clear();
                pingBuf.put((byte) 'P').put((byte) 'N').put((byte) 'G').put((byte) '1');
                pingPkt = new DatagramPacket(pingBuf.array(), 16, dest, port);
                final DatagramSocket psock = socket;
                new Thread(() -> listenPongs(psock), "ping").start();
            }

            byte[] inbuf = new byte[512];
            byte[] acc = new byte[2048];
            int accLen = 0;
            long statWindowT0 = System.nanoTime();
            int statCount = 0;
                double emaRtt = 0;
                int ioErrors = 0;
                long lastPingNs = 0;

                while (running.get()) {
                    long t0 = System.nanoTime();
                    conn.bulkTransfer(outEp, REQUEST, REQUEST.length, 50);
                    conn.bulkTransfer(outEp, REQUEST_BUTTONS, REQUEST_BUTTONS.length, 50);
                    int n = conn.bulkTransfer(inEp, inbuf, inbuf.length, 20);
                    long t1 = System.nanoTime();
                    if (n <= 0) {
                        if (++ioErrors > 2000) break;
                        continue;
                    }
                    ioErrors = 0;
                    double rtt = (t1 - t0) / 1e6;
                    emaRtt = emaRtt == 0 ? rtt : emaRtt * 0.9 + rtt * 0.1;
                    rttMs = (float) emaRtt;

                    if (socket != null && t1 - lastPingNs >= 250_000_000L) {
                        lastPingNs = t1;
                        long id = inflightId.incrementAndGet();
                        inflightSentNs = t1;
                        pingBuf.putInt(4, (int) id);
                        pingBuf.putLong(8, t1);
                        try {
                            socket.send(pingPkt);
                        } catch (Exception ignored) {
                        }
                    }

                if (accLen + n > acc.length) accLen = 0;
                System.arraycopy(inbuf, 0, acc, accLen, n);
                accLen += n;

                int consumed = 0;
                while (accLen - consumed >= 3) {
                    if (acc[consumed] != 0x55) {
                        consumed++;
                        continue;
                    }
                    int plen = ((acc[consumed + 1] & 0xFF) | (acc[consumed + 2] & 0xFF) << 8) & 0x3FF;
                    if (plen < 3 || plen > 512) {
                        consumed++;
                        continue;
                    }
                    if (accLen - consumed < plen) break;
                    if (plen == PACKET_LEN) {
                        int p = consumed;
                        int rxv = (acc[p + 13] & 0xFF) | (acc[p + 14] & 0xFF) << 8;
                        int ryv = (acc[p + 16] & 0xFF) | (acc[p + 17] & 0xFF) << 8;
                        int lyv = (acc[p + 19] & 0xFF) | (acc[p + 20] & 0xFF) << 8;
                        int lxv = (acc[p + 22] & 0xFF) | (acc[p + 23] & 0xFF) << 8;
                        int camv = (acc[p + 25] & 0xFF) | (acc[p + 26] & 0xFF) << 8;
                        vCam = camv;
                        vRx = rxv;
                        vRy = ryv;
                        vLy = lyv;
                        vLx = lxv;
                        padL.setPoint(lxv, lyv);
                        padR.setPoint(rxv, ryv);
                        chartL.push(lxv, lyv);
                        chartR.push(rxv, ryv);
                        if (socket != null && pkt != null) {
                            frame.clear();
                            frame.putInt(seq++).putShort((short) rxv).putShort((short) ryv)
                                    .putShort((short) lyv).putShort((short) lxv)
                                    .putShort((short) camv).putShort((short) vButtonMask)
                                    .put((byte) vMode).put((byte) 0);
                            try {
                                socket.send(pkt);
                                sentPkts++;
                            } catch (Exception ex) {
                                badPkts++;
                            }
                        }
                        statCount++;
                    } else if (plen == BUTTON_PACKET_LEN) {
                        int p = consumed;
                        int mask = ((acc[p + 28] & 0xFF) << 8) | (acc[p + 29] & 0xFF);
                        vButtonMask = mask;
                        vMode = (mask & 0x3000) >> 12;
                        vShutter = (mask & 0x0060) == 0x0060;
                        vPhotoVideo = (mask & 0x0004) != 0;
                        vRth = (mask & 0x0080) != 0;
                        vFn = (mask & 0x0002) != 0;
                    }
                    consumed += plen;
                }
                if (consumed > 0) {
                    System.arraycopy(acc, consumed, acc, 0, accLen - consumed);
                    accLen -= consumed;
                }

                long now = System.nanoTime();
                if (now - statWindowT0 >= 1_000_000_000L) {
                    ratePktS = statCount * 1e9f / (now - statWindowT0);
                    statCount = 0;
                    statWindowT0 = now;
                }
            }

            try {
                socket.close();
            } catch (Exception ignored) {
            }
            socket = null;
            try {
                conn.close();
            } catch (Exception ignored) {
            }
            zeroInputsOnUi();
            if (running.get()) {
                postStatus("Connessione persa, ricerco il radiocomando...");
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    break;
                }
            }
            }
        } catch (Throwable t) {
            fail("Errore: " + t.getMessage());
        } finally {
            running.set(false);
            releaseLocks();
            ui.post(() -> goBtn.setText("AVVIA"));
        }
    }

    private void closeSocket(UsbDeviceConnection conn) {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
        try {
            conn.close();
        } catch (Exception ignored) {
        }
    }

    private String discoverPc(int timeoutMs) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setBroadcast(true);
            s.setSoTimeout(400);
            byte[] q = "RCN1C_DISC".getBytes("UTF-8");
            List<String> targets = new ArrayList<>();
            targets.add("255.255.255.255");
            try {
                for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!nif.isUp() || nif.isLoopback()) continue;
                    for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                        InetAddress bc = ia.getBroadcast();
                        if (bc != null) targets.add(bc.getHostAddress());
                    }
                }
            } catch (Exception ignored) {
            }
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            byte[] buf = new byte[32];
            while (running.get() && SystemClock.elapsedRealtime() < deadline) {
                for (String t : targets) {
                    try {
                        s.send(new DatagramPacket(q, q.length, InetAddress.getByName(t), DISCOVERY_PORT));
                    } catch (Exception ignored) {
                    }
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                    if (p.getLength() >= 10 && new String(p.getData(), 0, p.getLength(), "UTF-8")
                            .startsWith("RCN1C_HERE")) {
                        return p.getAddress().getHostAddress();
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (s != null) s.close();
        }
        return null;
    }

    private void listenPongs(DatagramSocket sock) {
        byte[] b = new byte[32];
        try {
            sock.setSoTimeout(300);
            while (running.get()) {
                DatagramPacket rp = new DatagramPacket(b, b.length);
                try {
                    sock.receive(rp);
                } catch (SocketTimeoutException ignored) {
                    continue;
                }
                if (rp.getLength() == 16 && b[0] == 'P' && b[1] == 'N' && b[2] == 'G' && b[3] == '1') {
                    ByteBuffer rb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
                    rb.position(4);
                    int id = rb.getInt();
                    long sent = rb.getLong();
                    if (id == inflightId.get() && sent == inflightSentNs && inflightSentNs > 0) {
                        double rtt = (System.nanoTime() - sent) / 1e6;
                        netRttMs = netRttMs == 0 ? (float) rtt : (float) (netRttMs * 0.8 + rtt * 0.2);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Object[] probeCandidates(UsbDeviceConnection conn, List<Object[]> candidates, byte[] probeBuf) {
        for (Object[] c : candidates) {
            UsbInterface it = (UsbInterface) c[0];
            UsbEndpoint inEp = (UsbEndpoint) c[1];
            UsbEndpoint outEp = (UsbEndpoint) c[2];
            if (it != null && !conn.claimInterface(it, true)) continue;
            try {
                conn.controlTransfer(0x21, 0x22, 0x03, it != null ? it.getId() : 0, null, 0, 1000);
            } catch (Exception ignored) {
            }
            boolean alive = false;
            for (int attempt = 0; attempt < 3 && !alive; attempt++) {
                conn.bulkTransfer(outEp, REQUEST, REQUEST.length, 100);
                int n = conn.bulkTransfer(inEp, probeBuf, probeBuf.length, 250);
                alive = n >= PACKET_LEN && probeBuf[0] == 0x55;
            }
            if (alive) return new Object[]{inEp, outEp};
            if (it != null) conn.releaseInterface(it);
        }
        return null;
    }

    private final HashMap<Integer, Long> deniedAt = new HashMap<>();
    private final AtomicLong inflightId = new AtomicLong(-1);
    private volatile long inflightSentNs = 0L;
    private volatile float netRttMs = 0f;
    private final ByteBuffer pingBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
    private volatile int vCam = StickPadView.RAW_CENTER;
    private TextView chipRotL, chipRotR, chipShutter, chipPhotoVideo, chipRth, chipFn, chipMode;
    private DatagramPacket pingPkt;

    private boolean recentlyDenied(UsbDevice d) {
        Long t = deniedAt.get(d.getDeviceId());
        return t != null && SystemClock.elapsedRealtime() - t < 20000;
    }

    private void markDenied(UsbDevice d) {
        deniedAt.put(d.getDeviceId(), SystemClock.elapsedRealtime());
    }

    private void zeroInputsOnUi() {
        ui.post(this::zeroInputs);
    }

    private void postStatus(String s) {
        ui.post(() -> status.setText(s));
    }

    private void fail(String msg) {
        ui.post(() -> {
            status.setText("[ERRORE] " + msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
