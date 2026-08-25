package com.drone.rcn1cbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Portable backend: converts RC axes to two persistent Android touch pointers.
 * No root, ADB, Wi-Fi or Shizuku is used by this mode.
 */
public final class PortableTouchAccessibilityService extends AccessibilityService {
    private static final long STEP_MS = 32;
    private static final String PREFS = "touch_profile";

    private static volatile PortableTouchAccessibilityService instance;
    private static volatile String lastState = "Accessibilità non attiva";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String foregroundPackage = "";
    private String armedPackage = "";
    private volatile boolean gestureInFlight = false;
    private final AtomicBoolean driveScheduled = new AtomicBoolean(false);
    private boolean releaseRequested = false;
    private boolean calibrating = false;
    private boolean explicitlyArmed = false;

    private GestureDescription.StrokeDescription leftStroke;
    private GestureDescription.StrokeDescription rightStroke;
    private float leftX, leftY, rightX, rightY;

    private WindowManager windowManager;
    private CalibrationOverlay calibrationOverlay;

    public static boolean isConnected() { return instance != null; }
    public static String getLastState() { return lastState; }

    public static void kick() {
        PortableTouchAccessibilityService s = instance;
        if (s != null && !s.gestureInFlight) s.scheduleDrive(0);
    }

    public static void releaseTouches() {
        PortableTouchAccessibilityService s = instance;
        if (s != null) s.handler.post(() -> {
            s.releaseRequested = true;
            s.drive();
        });
    }

    public static boolean requestCalibration() {
        PortableTouchAccessibilityService s = instance;
        if (s == null) return false;
        s.handler.post(s::showCalibrationOverlay);
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
        // dispatchGesture is global Android input. Never let an armed gesture survive
        // a foreground-package change: otherwise Recents/Home/SystemUI can receive the
        // same synthetic fingers that were meant for the simulator.
        if (explicitlyArmed && (!isSupportedGame(foregroundPackage)
                || !foregroundPackage.equals(armedPackage))) {
            explicitlyArmed = false;
            armedPackage = "";
            releaseRequested = true;
            lastState = "Touch sospeso · fuori dal gioco";
            scheduleDrive(0);
            return;
        }
        if (getPackageName().equals(foregroundPackage)) {
            explicitlyArmed = false;
            armedPackage = "";
            releaseRequested = true;
            lastState = "Touch sospeso · Flight Bridge in primo piano";
            scheduleDrive(0);
        } else if (isSupportedGame(foregroundPackage) && !explicitlyArmed) {
            lastState = "Gioco rilevato · in attesa di avvio";
        }
    }

    @Override
    public void onInterrupt() {
        releaseRequested = true;
        drive();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        hideCalibrationOverlay(false);
        resetGestureState();
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

    private boolean shouldDrive() {
        return !calibrating
                && explicitlyArmed
                && !releaseRequested
                && isSupportedGame(foregroundPackage)
                && foregroundPackage.equals(armedPackage)
                && PortableTouchBridgeService.active
                && PortableTouchBridgeService.latestFrame != null;
    }

    private void drive() {
        if (gestureInFlight) return;
        if (!shouldDrive()) {
            if (leftStroke != null || rightStroke != null) dispatchRelease();
            return;
        }
        Rcn1cUsbReader.Frame f = PortableTouchBridgeService.latestFrame;
        if (f == null) return;
        float[] target = targetPoints(f);
        releaseRequested = false;
        if (leftStroke == null || rightStroke == null) startPointers(target);
        else continuePointers(target, true);
    }

    private void startPointers(float[] p) {
        Path leftPath = new Path(); leftPath.moveTo(p[0], p[1]); leftPath.lineTo(p[0] + 0.01f, p[1]);
        Path rightPath = new Path(); rightPath.moveTo(p[2], p[3]); rightPath.lineTo(p[2] + 0.01f, p[3]);
        leftStroke = new GestureDescription.StrokeDescription(leftPath, 0, STEP_MS, true);
        rightStroke = new GestureDescription.StrokeDescription(rightPath, 0, STEP_MS, true);
        leftX = p[0]; leftY = p[1]; rightX = p[2]; rightY = p[3];
        dispatch(leftStroke, rightStroke, false);
    }

    private void continuePointers(float[] p, boolean willContinue) {
        try {
            Path leftPath = new Path();
            leftPath.moveTo(leftX, leftY); leftPath.lineTo(p[0], p[1]);
            Path rightPath = new Path();
            rightPath.moveTo(rightX, rightY); rightPath.lineTo(p[2], p[3]);
            leftStroke = leftStroke.continueStroke(leftPath, 0, STEP_MS, willContinue);
            rightStroke = rightStroke.continueStroke(rightPath, 0, STEP_MS, willContinue);
            leftX = p[0]; leftY = p[1]; rightX = p[2]; rightY = p[3];
            dispatch(leftStroke, rightStroke, !willContinue);
        } catch (Throwable t) {
            lastState = "CANCELLED/RESET · " + safeMessage(t);
            resetGestureState();
        }
    }

    private void dispatchRelease() {
        if (gestureInFlight) return;
        if (leftStroke == null || rightStroke == null) {
            resetGestureState();
            return;
        }
        continuePointers(new float[]{leftX, leftY, rightX, rightY}, false);
    }

    private void dispatch(GestureDescription.StrokeDescription left,
                          GestureDescription.StrokeDescription right,
                          boolean finalRelease) {
        GestureDescription gesture;
        try {
            gesture = new GestureDescription.Builder().addStroke(left).addStroke(right).build();
        } catch (Throwable t) {
            lastState = "Gesture build fallita: " + safeMessage(t);
            resetGestureState();
            return;
        }
        gestureInFlight = true;
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                gestureInFlight = false;
                if (finalRelease) resetGestureState();
                else scheduleDrive(1);
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                gestureInFlight = false;
                resetGestureState();
                scheduleDrive(80);
            }
        }, handler);
        if (!accepted) {
            gestureInFlight = false;
            lastState = "Android ha rifiutato la gesture";
            resetGestureState();
        } else {
            lastState = finalRelease ? "Touch rilasciato" : "DISPATCHING · " + foregroundPackage;
        }
    }

    private void resetGestureState() {
        gestureInFlight = false;
        leftStroke = null;
        rightStroke = null;
        releaseRequested = false;
    }

    private float[] targetPoints(Rcn1cUsbReader.Frame f) {
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        float lcX = p.getFloat("left_x", 0.22f) * dm.widthPixels;
        float lcY = p.getFloat("left_y", 0.74f) * dm.heightPixels;
        float rcX = p.getFloat("right_x", 0.78f) * dm.widthPixels;
        float rcY = p.getFloat("right_y", 0.74f) * dm.heightPixels;
        float radius = p.getFloat("radius", 0.17f) * dm.heightPixels;
        float density = getResources().getDisplayMetrics().density;
        float safeX = Math.max(24f * density, dm.widthPixels * 0.03f);
        float safeTop = Math.max(20f * density, dm.heightPixels * 0.04f);
        float safeBottom = Math.max(48f * density, dm.heightPixels * 0.08f);
        float lx = axis(f.lx), ly = axis(f.ly), rx = axis(f.rx), ry = axis(f.ry);
        return new float[]{
                clamp(lcX + lx * radius, safeX, dm.widthPixels - safeX),
                clamp(lcY - ly * radius, safeTop, dm.heightPixels - safeBottom),
                clamp(rcX + rx * radius, safeX, dm.widthPixels - safeX),
                clamp(rcY - ry * radius, safeTop, dm.heightPixels - safeBottom)
        };
    }

    private static float axis(int value) { return Math.max(-1f, Math.min(1f, value / 32767f)); }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }

    private static boolean isSupportedGame(String pkg) {
        return "com.Freeride.Freerider_FREE".equals(pkg)
                || "com.Freeride.Freerider".equals(pkg)
                || "com.FullFocusStudio.FeelFPV".equals(pkg)
                || "com.Orqa.FPVSkyDive".equals(pkg);
    }

    private void showCalibrationOverlay() {
        if (calibrating || windowManager == null) return;
        releaseRequested = true;
        drive();
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
        releaseRequested = false;
        if (save && isSupportedGame(foregroundPackage)) {
            armedPackage = foregroundPackage;
            explicitlyArmed = true;
            releaseRequested = false;
            lastState = "ARMED · profilo calibrato";
        } else {
            armedPackage = "";
            explicitlyArmed = false;
            releaseRequested = true;
            lastState = save ? "Calibrazione salvata · riapri il gioco" : "Calibrazione annullata";
        }
        scheduleDrive(180);
    }

    private final class CalibrationOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lx, ly, rx, ry, radius;
        private int dragging = 0;
        private float density = 1f;

        CalibrationOverlay(android.content.Context context) {
            super(context);
            setBackgroundColor(0x22000000);
            density = getResources().getDisplayMetrics().density;
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            lx = p.getFloat("left_x", 0.22f) * w;
            ly = p.getFloat("left_y", 0.74f) * h;
            rx = p.getFloat("right_x", 0.78f) * w;
            ry = p.getFloat("right_y", 0.74f) * h;
            radius = p.getFloat("radius", 0.17f) * h;
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xCC0B0F14);
            c.drawRoundRect(12*density, 10*density, getWidth()-12*density, 54*density, 12*density, 12*density, paint);
            paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(15*density); paint.setColor(Color.WHITE);
            c.drawText("Trascina L/R sopra i joystick del gioco · regola il raggio", getWidth()/2f, 38*density, paint);
            drawStick(c, lx, ly, 0xCC39C5FF, "L");
            drawStick(c, rx, ry, 0xCCFF9F43, "R");
            float bh = 58*density;
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xE6151C24);
            c.drawRect(0, getHeight()-bh, getWidth(), getHeight(), paint);
            paint.setTextSize(14*density); paint.setColor(Color.WHITE);
            c.drawText("RAGGIO −", getWidth()/6f, getHeight()-21*density, paint);
            c.drawText("SALVA", getWidth()/2f, getHeight()-21*density, paint);
            c.drawText("RAGGIO +", getWidth()*5f/6f, getHeight()-21*density, paint);
            paint.setTextAlign(Paint.Align.RIGHT); paint.setTextSize(13*density); paint.setColor(0xFFFFD166);
            c.drawText("ANNULLA", getWidth()-22*density, 38*density, paint);
        }

        private void drawStick(Canvas c, float x, float y, int color, String label) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3*density); paint.setColor(color);
            c.drawCircle(x, y, radius, paint); c.drawCircle(x, y, 18*density, paint);
            paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(16*density); paint.setColor(Color.WHITE);
            c.drawText(label, x, y+6*density, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x=e.getX(), y=e.getY();
            if (e.getActionMasked()==MotionEvent.ACTION_DOWN) {
                if (y<58*density && x>getWidth()*0.76f) { hideCalibrationOverlay(false); return true; }
                if (y>getHeight()-64*density) {
                    if (x<getWidth()/3f) { radius=Math.max(30*density, radius-10*density); invalidate(); }
                    else if (x>getWidth()*2f/3f) { radius=Math.min(getHeight()*0.48f, radius+10*density); invalidate(); }
                    else hideCalibrationOverlay(true);
                    return true;
                }
                float dl=dist2(x,y,lx,ly), dr=dist2(x,y,rx,ry);
                dragging=dl<=dr?1:2; moveDragged(x,y); return true;
            }
            if (e.getActionMasked()==MotionEvent.ACTION_MOVE && dragging!=0) { moveDragged(x,y); return true; }
            if (e.getActionMasked()==MotionEvent.ACTION_UP || e.getActionMasked()==MotionEvent.ACTION_CANCEL) { dragging=0; return true; }
            return true;
        }

        private void moveDragged(float x, float y) {
            float edge=8*density;
            float top=58*density;
            float bottom=getHeight()-64*density;
            x=clamp(x,edge,getWidth()-edge); y=clamp(y,top,bottom);
            if (dragging==1) { lx=x; ly=y; } else if (dragging==2) { rx=x; ry=y; }
            invalidate();
        }

        private float dist2(float x1,float y1,float x2,float y2) { float dx=x1-x2,dy=y1-y2; return dx*dx+dy*dy; }

        void saveProfile() {
            if (getWidth()<=0 || getHeight()<=0) return;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putFloat("left_x", lx/getWidth()).putFloat("left_y", ly/getHeight())
                    .putFloat("right_x", rx/getWidth()).putFloat("right_y", ry/getHeight())
                    .putFloat("radius", radius/getHeight()).apply();
        }
    }

    private static String safeMessage(Throwable t) {
        String m=t.getMessage();
        return m==null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
