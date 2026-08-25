package com.drone.rcn1cbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Portable backend: converts RC axes to synthetic touchscreen joystick drags.
 * No root, ADB, Wi-Fi or Shizuku.
 */
public final class PortableTouchAccessibilityService extends AccessibilityService {
    private static final long STEP_MS = 14;
    private static final float DEADZONE = 0.045f;
    private static final float MOVE_EPSILON_PX = 0.8f;
    private static final String PREFS = "touch_profile";
    private static final String KEY_SCHEMA = "schema_v8";
    private static final int SCHEMA = 8;

    private static volatile PortableTouchAccessibilityService instance;
    private static volatile String lastState = "Accessibilità non attiva";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean driveScheduled = new AtomicBoolean(false);

    private String foregroundPackage = "";
    private String armedPackage = "";
    private String calibrationPackage = "";
    private volatile boolean gestureInFlight = false;
    private volatile boolean inputDirty = false;
    private boolean releaseRequested = false;
    private boolean calibrating = false;
    private boolean explicitlyArmed = false;

    private final PointerState left = new PointerState();
    private final PointerState right = new PointerState();

    private WindowManager windowManager;
    private CalibrationOverlay calibrationOverlay;

    private static final class PointerState {
        GestureDescription.StrokeDescription stroke;
        float x;
        float y;

        boolean down() { return stroke != null; }
        void clear() { stroke = null; x = 0f; y = 0f; }
    }

    private static final class Geometry {
        final Rect bounds;
        final float leftCx, leftCy, rightCx, rightCy, radius;

        Geometry(Rect bounds, float leftCx, float leftCy,
                 float rightCx, float rightCy, float radius) {
            this.bounds = bounds;
            this.leftCx = leftCx;
            this.leftCy = leftCy;
            this.rightCx = rightCx;
            this.rightCy = rightCy;
            this.radius = radius;
        }
    }

    public static boolean isConnected() { return instance != null; }
    public static String getLastState() { return lastState; }

    public static void kick() {
        PortableTouchAccessibilityService s = instance;
        if (s == null) return;
        s.inputDirty = true;
        if (!s.gestureInFlight) s.scheduleDrive(0);
    }

    public static void releaseTouches() {
        PortableTouchAccessibilityService s = instance;
        if (s == null) return;
        s.handler.post(() -> {
            s.releaseRequested = true;
            s.scheduleDrive(0);
        });
    }

    public static boolean requestCalibration(String packageName) {
        PortableTouchAccessibilityService s = instance;
        if (s == null) return false;
        s.handler.post(() -> {
            s.calibrationPackage = packageName == null ? "" : packageName;
            s.showCalibrationOverlay();
        });
        return true;
    }

    public static boolean armForGame(String packageName, String label) {
        PortableTouchAccessibilityService s = instance;
        if (s == null) return false;
        s.handler.post(() -> {
            if (!isSupportedGame(packageName)) {
                s.explicitlyArmed = false;
                s.armedPackage = "";
                s.releaseRequested = true;
                lastState = "Package gioco non supportato";
                s.scheduleDrive(0);
                return;
            }
            s.armedPackage = packageName;
            s.explicitlyArmed = true;
            s.releaseRequested = false;
            s.inputDirty = true;
            lastState = "ARMED · " + label;
            s.scheduleDrive(0);
        });
        return true;
    }

    public static void disarm() {
        PortableTouchAccessibilityService s = instance;
        if (s == null) return;
        s.handler.post(() -> {
            s.explicitlyArmed = false;
            s.armedPackage = "";
            s.releaseRequested = true;
            lastState = "Touch disarmato";
            s.scheduleDrive(0);
        });
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        lastState = "Portable Touch pronto";
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        foregroundPackage = event.getPackageName().toString();
        if (getPackageName().equals(foregroundPackage)) {
            explicitlyArmed = false;
            armedPackage = "";
            releaseRequested = true;
            lastState = "Touch sospeso · Flight Bridge in primo piano";
        }
        scheduleDrive(0);
    }

    @Override
    public void onInterrupt() {
        releaseRequested = true;
        scheduleDrive(0);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        hideCalibrationOverlay(false);
        resetPointers();
        if (instance == this) instance = null;
        lastState = "Accessibilità non attiva";
        return super.onUnbind(intent);
    }

    private void scheduleDrive(long delayMs) {
        if (!driveScheduled.compareAndSet(false, true)) return;
        Runnable r = () -> {
            driveScheduled.set(false);
            drive();
        };
        if (delayMs <= 0) handler.post(r);
        else handler.postDelayed(r, delayMs);
    }

    private String resolveActivePackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                return root.getPackageName().toString();
            }
        } catch (Throwable ignored) {}

        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null || (!window.isActive() && !window.isFocused())) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null && root.getPackageName() != null) {
                        return root.getPackageName().toString();
                    }
                }
            }
        } catch (Throwable ignored) {}

        return foregroundPackage == null ? "" : foregroundPackage;
    }

    private Rect resolvePackageBounds(String packageName) {
        Rect fallback = new Rect();
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                AccessibilityWindowInfo activeApplication = null;
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    if (window.isActive() || window.isFocused()) activeApplication = window;

                    AccessibilityNodeInfo root = null;
                    try { root = window.getRoot(); } catch (Throwable ignored) {}
                    if (root == null || root.getPackageName() == null) continue;
                    if (!packageName.equals(root.getPackageName().toString())) continue;

                    Rect b = new Rect();
                    window.getBoundsInScreen(b);
                    if (!b.isEmpty()) return b;
                }

                if (packageName.equals(resolveActivePackage()) && activeApplication != null) {
                    activeApplication.getBoundsInScreen(fallback);
                    if (!fallback.isEmpty()) return fallback;
                }
            }
        } catch (Throwable ignored) {}

        if (windowManager != null) {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(dm);
            fallback.set(0, 0, dm.widthPixels, dm.heightPixels);
        }
        return fallback;
    }

    private boolean correctGameWindow() {
        String active = resolveActivePackage();
        if (active != null && !active.isEmpty()) foregroundPackage = active;
        boolean correct = explicitlyArmed
                && isSupportedGame(active)
                && active.equals(armedPackage);
        if (explicitlyArmed && !correct && !releaseRequested) {
            lastState = "WAIT GAME · " + (active == null || active.isEmpty()
                    ? "finestra sconosciuta" : active);
        }
        return correct;
    }

    private void drive() {
        if (gestureInFlight) return;

        if (releaseRequested || !correctGameWindow()
                || !PortableTouchBridgeService.active
                || PortableTouchBridgeService.latestFrame == null) {
            if (left.down() || right.down()) {
                dispatchReleaseAll();
            } else if (releaseRequested) {
                releaseRequested = false;
            }
            return;
        }

        Rcn1cUsbReader.Frame f = PortableTouchBridgeService.latestFrame;
        inputDirty = false;

        Geometry g = geometryFor(armedPackage);
        if (g == null || g.bounds.isEmpty()) {
            lastState = "WAIT GAME · bounds non disponibili";
            return;
        }

        float lx = axis(f.lx);
        float ly = axis(f.ly);
        float rx = axis(f.rx);
        float ry = axis(f.ry);

        boolean leftWanted = lx != 0f || ly != 0f;
        boolean rightWanted = rx != 0f || ry != 0f;

        float leftTx = clamp(g.leftCx + lx * g.radius,
                g.bounds.left + 1f, g.bounds.right - 1f);
        float leftTy = clamp(g.leftCy - ly * g.radius,
                g.bounds.top + 1f, g.bounds.bottom - 1f);
        float rightTx = clamp(g.rightCx + rx * g.radius,
                g.bounds.left + 1f, g.bounds.right - 1f);
        float rightTy = clamp(g.rightCy - ry * g.radius,
                g.bounds.top + 1f, g.bounds.bottom - 1f);

        dispatchPointerTransition(
                leftWanted, leftTx, leftTy, g.leftCx, g.leftCy,
                rightWanted, rightTx, rightTy, g.rightCx, g.rightCy);
    }

    private void dispatchPointerTransition(
            boolean leftWanted, float leftTx, float leftTy, float leftCx, float leftCy,
            boolean rightWanted, float rightTx, float rightTy, float rightCx, float rightCy) {

        boolean leftChanged = pointerChanged(left, leftWanted, leftTx, leftTy);
        boolean rightChanged = pointerChanged(right, rightWanted, rightTx, rightTy);

        if (!leftChanged && !rightChanged) {
            if (!leftWanted && !rightWanted) lastState = "READY · stick neutri";
            else lastState = "HOLD · RC stabile";
            return;
        }

        GestureDescription.Builder builder = new GestureDescription.Builder();

        GestureDescription.StrokeDescription nextLeft = buildNextStroke(
                left, leftWanted, leftTx, leftTy, leftCx, leftCy, leftChanged);
        GestureDescription.StrokeDescription nextRight = buildNextStroke(
                right, rightWanted, rightTx, rightTy, rightCx, rightCy, rightChanged);

        if (nextLeft == null && nextRight == null) {
            resetPointers();
            return;
        }

        if (nextLeft != null) builder.addStroke(nextLeft);
        if (nextRight != null) builder.addStroke(nextRight);

        applyNextState(left, nextLeft, leftWanted, leftTx, leftTy);
        applyNextState(right, nextRight, rightWanted, rightTx, rightTy);

        dispatchBuiltGesture(builder.build());
    }

    private boolean pointerChanged(PointerState p, boolean wanted, float tx, float ty) {
        if (p.down() != wanted) return true;
        if (!wanted) return false;
        float dx = tx - p.x;
        float dy = ty - p.y;
        return dx * dx + dy * dy >= MOVE_EPSILON_PX * MOVE_EPSILON_PX;
    }

    private GestureDescription.StrokeDescription buildNextStroke(
            PointerState state, boolean wanted, float tx, float ty,
            float cx, float cy, boolean changed) {
        try {
            if (!state.down() && !wanted) return null;

            if (!state.down()) {
                Path p = new Path();
                p.moveTo(cx, cy);
                p.lineTo(tx, ty);
                return new GestureDescription.StrokeDescription(p, 0, STEP_MS, true);
            }

            Path p = new Path();
            p.moveTo(state.x, state.y);
            if (wanted && changed) p.lineTo(tx, ty);
            return state.stroke.continueStroke(p, 0, STEP_MS, wanted);
        } catch (Throwable t) {
            lastState = "Pointer transition fallita · " + safeMessage(t);
            resetPointers();
            return null;
        }
    }

    private void applyNextState(PointerState state,
                                GestureDescription.StrokeDescription next,
                                boolean wanted, float tx, float ty) {
        if (!wanted) {
            state.clear();
            return;
        }
        state.stroke = next;
        state.x = tx;
        state.y = ty;
    }

    private void dispatchReleaseAll() {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        boolean hasStroke = false;
        try {
            if (left.down()) {
                Path p = new Path();
                p.moveTo(left.x, left.y);
                builder.addStroke(left.stroke.continueStroke(p, 0, 1, false));
                hasStroke = true;
            }
            if (right.down()) {
                Path p = new Path();
                p.moveTo(right.x, right.y);
                builder.addStroke(right.stroke.continueStroke(p, 0, 1, false));
                hasStroke = true;
            }
        } catch (Throwable t) {
            resetPointers();
            releaseRequested = false;
            return;
        }

        left.clear();
        right.clear();
        if (!hasStroke) {
            releaseRequested = false;
            return;
        }
        dispatchBuiltGesture(builder.build());
    }

    private void dispatchBuiltGesture(GestureDescription gesture) {
        gestureInFlight = true;
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                gestureInFlight = false;
                if (!left.down() && !right.down()) {
                    releaseRequested = false;
                    lastState = explicitlyArmed ? "READY · nessun touch premuto" : "Touch rilasciato";
                }
                if (releaseRequested || inputDirty) scheduleDrive(0);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                gestureInFlight = false;
                resetPointers();
                if (explicitlyArmed) {
                    inputDirty = true;
                    lastState = "Gesture cancellata · reset";
                    scheduleDrive(40);
                }
            }
        }, handler);

        if (!accepted) {
            gestureInFlight = false;
            resetPointers();
            lastState = "Android ha rifiutato la gesture";
        } else if (left.down() || right.down()) {
            lastState = "DISPATCHING · " + foregroundPackage;
        }
    }

    private void resetPointers() {
        gestureInFlight = false;
        left.clear();
        right.clear();
    }

    private Geometry geometryFor(String packageName) {
        Rect b = resolvePackageBounds(packageName);
        if (b == null || b.width() <= 0 || b.height() <= 0) return null;

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean v8 = p.getInt(KEY_SCHEMA, 0) == SCHEMA;
        float lnx = v8 ? p.getFloat("v8_left_x", 0.22f) : 0.22f;
        float lny = v8 ? p.getFloat("v8_left_y", 0.74f) : 0.74f;
        float rnx = v8 ? p.getFloat("v8_right_x", 0.78f) : 0.78f;
        float rny = v8 ? p.getFloat("v8_right_y", 0.74f) : 0.74f;
        float nr = v8 ? p.getFloat("v8_radius", 0.17f) : 0.17f;

        float lcX = b.left + lnx * b.width();
        float lcY = b.top + lny * b.height();
        float rcX = b.left + rnx * b.width();
        float rcY = b.top + rny * b.height();
        float radius = nr * b.height();
        radius = Math.max(8f, Math.min(radius, b.height() * 0.45f));
        return new Geometry(b, lcX, lcY, rcX, rcY, radius);
    }

    private static float axis(int value) {
        float v = Math.max(-1f, Math.min(1f, value / 32767f));
        float a = Math.abs(v);
        if (a <= DEADZONE) return 0f;
        float scaled = (a - DEADZONE) / (1f - DEADZONE);
        return Math.copySign(Math.min(1f, scaled), v);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isSupportedGame(String pkg) {
        return "com.Freeride.Freerider_FREE".equals(pkg)
                || "com.Freeride.Freerider".equals(pkg)
                || "com.FullFocusStudio.FeelFPV".equals(pkg)
                || "com.Orqa.FPVSkyDive".equals(pkg);
    }

    private void showCalibrationOverlay() {
        if (calibrating || windowManager == null) return;
        releaseRequested = true;
        if (left.down() || right.down()) dispatchReleaseAll();

        calibrating = true;
        calibrationOverlay = new CalibrationOverlay(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(calibrationOverlay, lp);
        lastState = "Calibrazione touch aperta";
    }

    private void hideCalibrationOverlay(boolean save) {
        CalibrationOverlay view = calibrationOverlay;
        if (view == null) return;
        if (save) view.saveProfile();
        try { windowManager.removeView(view); } catch (Throwable ignored) {}
        calibrationOverlay = null;
        calibrating = false;
        calibrationPackage = "";
        explicitlyArmed = false;
        armedPackage = "";
        releaseRequested = true;
        lastState = save
                ? "Calibrazione v8 salvata · premi APRI FPV FREERIDER"
                : "Calibrazione annullata";
        scheduleDrive(0);
    }

    private final class CalibrationOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect gameBounds = new Rect();
        private float lx, ly, rx, ry, radius;
        private int dragging = 0;
        private float density = 1f;

        CalibrationOverlay(android.content.Context context) {
            super(context);
            setBackgroundColor(0x22000000);
            density = getResources().getDisplayMetrics().density;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            Rect resolved = resolvePackageBounds(calibrationPackage);
            if (resolved == null || resolved.isEmpty()) resolved = new Rect(0, 0, w, h);
            gameBounds.set(resolved);

            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean v8 = p.getInt(KEY_SCHEMA, 0) == SCHEMA;
            float lnx = v8 ? p.getFloat("v8_left_x", 0.22f) : 0.22f;
            float lny = v8 ? p.getFloat("v8_left_y", 0.74f) : 0.74f;
            float rnx = v8 ? p.getFloat("v8_right_x", 0.78f) : 0.78f;
            float rny = v8 ? p.getFloat("v8_right_y", 0.74f) : 0.74f;
            float nr = v8 ? p.getFloat("v8_radius", 0.17f) : 0.17f;

            lx = gameBounds.left + lnx * gameBounds.width();
            ly = gameBounds.top + lny * gameBounds.height();
            rx = gameBounds.left + rnx * gameBounds.width();
            ry = gameBounds.top + rny * gameBounds.height();
            radius = nr * gameBounds.height();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * density);
            paint.setColor(0xAA2ED573);
            c.drawRect(gameBounds, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xDD0B0F14);
            c.drawRoundRect(12 * density, 10 * density,
                    getWidth() - 12 * density, 58 * density,
                    12 * density, 12 * density, paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(14 * density);
            paint.setColor(Color.WHITE);
            c.drawText("Metti L/R sul CENTRO esatto dei knob di Freerider",
                    getWidth() / 2f, 36 * density, paint);
            paint.setTextSize(10 * density);
            paint.setColor(0xFF9EB3C8);
            c.drawText("bordo verde = finestra reale del gioco",
                    getWidth() / 2f, 52 * density, paint);

            drawStick(c, lx, ly, 0xCC39C5FF, "L");
            drawStick(c, rx, ry, 0xCCFF9F43, "R");

            float bh = 58 * density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xE6151C24);
            c.drawRect(0, getHeight() - bh, getWidth(), getHeight(), paint);
            paint.setTextSize(14 * density);
            paint.setColor(Color.WHITE);
            c.drawText("RAGGIO −", getWidth() / 6f, getHeight() - 21 * density, paint);
            c.drawText("SALVA", getWidth() / 2f, getHeight() - 21 * density, paint);
            c.drawText("RAGGIO +", getWidth() * 5f / 6f, getHeight() - 21 * density, paint);

            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setTextSize(13 * density);
            paint.setColor(0xFFFFD166);
            c.drawText("ANNULLA", getWidth() - 22 * density, 38 * density, paint);
        }

        private void drawStick(Canvas c, float x, float y, int color, String label) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3 * density);
            paint.setColor(color);
            c.drawCircle(x, y, radius, paint);
            c.drawCircle(x, y, 18 * density, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(16 * density);
            paint.setColor(Color.WHITE);
            c.drawText(label, x, y + 6 * density, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX();
            float y = e.getY();

            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (y < 62 * density && x > getWidth() * 0.76f) {
                    hideCalibrationOverlay(false);
                    return true;
                }
                if (y > getHeight() - 64 * density) {
                    if (x < getWidth() / 3f) {
                        radius = Math.max(24 * density, radius - 8 * density);
                        invalidate();
                    } else if (x > getWidth() * 2f / 3f) {
                        radius = Math.min(gameBounds.height() * 0.45f, radius + 8 * density);
                        invalidate();
                    } else {
                        hideCalibrationOverlay(true);
                    }
                    return true;
                }
                float dl = dist2(x, y, lx, ly);
                float dr = dist2(x, y, rx, ry);
                dragging = dl <= dr ? 1 : 2;
                moveDragged(x, y);
                return true;
            }

            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && dragging != 0) {
                moveDragged(x, y);
                return true;
            }

            if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                dragging = 0;
                return true;
            }
            return true;
        }

        private void moveDragged(float x, float y) {
            float edge = 4 * density;
            float minX = gameBounds.left + edge;
            float maxX = gameBounds.right - edge;
            float minY = gameBounds.top + edge;
            float maxY = gameBounds.bottom - edge;

            x = clamp(x, minX, maxX);
            y = clamp(y, minY, maxY);
            if (dragging == 1) {
                lx = x;
                ly = y;
            } else if (dragging == 2) {
                rx = x;
                ry = y;
            }
            invalidate();
        }

        private float dist2(float x1, float y1, float x2, float y2) {
            float dx = x1 - x2;
            float dy = y1 - y2;
            return dx * dx + dy * dy;
        }

        void saveProfile() {
            if (gameBounds.width() <= 0 || gameBounds.height() <= 0) return;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_SCHEMA, SCHEMA)
                    .putFloat("v8_left_x", (lx - gameBounds.left) / gameBounds.width())
                    .putFloat("v8_left_y", (ly - gameBounds.top) / gameBounds.height())
                    .putFloat("v8_right_x", (rx - gameBounds.left) / gameBounds.width())
                    .putFloat("v8_right_y", (ry - gameBounds.top) / gameBounds.height())
                    .putFloat("v8_radius", radius / gameBounds.height())
                    .apply();
        }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
