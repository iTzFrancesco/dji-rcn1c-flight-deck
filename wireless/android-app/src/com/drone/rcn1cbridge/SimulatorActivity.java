package com.drone.rcn1cbridge;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;

public final class SimulatorActivity extends Activity implements Rcn1cUsbReader.Listener {
    private static final String ACTION_USB_PERMISSION = "com.drone.rcn1cbridge.SIMULATOR_USB_PERMISSION";
    private static final long RETRY_MS = 1200L;
    private static final long FRAME_PUSH_MS = 16L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile Rcn1cUsbReader.Frame latestFrame;
    private volatile boolean pageReady;
    private volatile boolean closing;

    private WebView webView;
    private TextView status;
    private Rcn1cUsbReader reader;

    private final Runnable retryReader = new Runnable() {
        @Override
        public void run() {
            if (!closing) startReaderIfPossible();
        }
    };

    private final Runnable framePump = new Runnable() {
        @Override
        public void run() {
            if (closing) return;
            pushLatestFrame();
            ui.postDelayed(this, FRAME_PUSH_MS);
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                postStatus(granted ? "Permesso USB ricevuto" : "Permesso USB negato");
                if (granted) startReaderIfPossible();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (reader != null) reader.stop();
                postStatus("RC scollegato · collega di nuovo il radiocomando");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        reader = new Rcn1cUsbReader(
                (UsbManager) getSystemService(USB_SERVICE), this);
        ui.post(framePump);
        startReaderIfPossible();
    }

    @Override
    protected void onDestroy() {
        closing = true;
        ui.removeCallbacks(retryReader);
        ui.removeCallbacks(framePump);
        if (reader != null) reader.stop();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        webView.setBackgroundColor(0xFF05080B);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                postStatus("Simulatore pronto · attendo RC-N1C");
            }
        });
        webView.loadUrl("file:///android_asset/fpv-sim/index.html");
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setText("Avvio simulatore...");
        status.setTextColor(0xFFFFB86B);
        status.setTextSize(11);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(8), dp(4), dp(8), dp(4));
        status.setBackgroundColor(0xCC07101A);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(28), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        statusParams.topMargin = dp(6);
        root.addView(status, statusParams);
        setContentView(root);
    }

    private void startReaderIfPossible() {
        if (closing || reader == null || reader.isRunning()) return;
        UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        UsbDevice device = Rcn1cUsbReader.findDji(manager);
        if (device == null) {
            postStatus("Collega l'RC-N1C via OTG");
            ui.removeCallbacks(retryReader);
            ui.postDelayed(retryReader, RETRY_MS);
            return;
        }
        if (!manager.hasPermission(device)) {
            requestUsbPermission(manager, device);
            postStatus("Conferma il permesso USB per l'RC");
            return;
        }
        if (!reader.start(device)) {
            postStatus("Impossibile avviare il lettore RC");
            ui.postDelayed(retryReader, RETRY_MS);
        }
    }

    private void requestUsbPermission(UsbManager manager, UsbDevice device) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                1,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        manager.requestPermission(device, pendingIntent);
    }

    private void pushLatestFrame() {
        Rcn1cUsbReader.Frame frame = latestFrame;
        if (!pageReady || frame == null || webView == null) return;
        String script = String.format(Locale.US,
                "window.setRcn1cFrame(%d,%d,%d,%d,%d,%d,%.1f);",
                frame.lx, frame.ly, frame.rx, frame.ry,
                frame.buttonMask, frame.mode, frame.packetsPerSecond);
        webView.evaluateJavascript(script, null);
    }

    @Override
    public void onStatus(String message) {
        postStatus(message);
    }

    @Override
    public void onFrame(Rcn1cUsbReader.Frame frame) {
        latestFrame = frame;
    }

    @Override
    public void onStopped(String reason) {
        if (!closing) {
            postStatus("Lettore fermo · " + reason);
            ui.postDelayed(retryReader, RETRY_MS);
        }
    }

    private void postStatus(String message) {
        ui.post(() -> {
            if (status != null) status.setText(message);
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.setRcn1cStatus(" + JSONObject.quote(message) + "," +
                                (reader != null && reader.isRunning()) + ");", null);
            }
        });
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
