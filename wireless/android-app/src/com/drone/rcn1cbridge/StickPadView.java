package com.drone.rcn1cbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

public class StickPadView extends View {
    public static final int RAW_CENTER = 1024;
    public static final int RAW_SPAN_HALF = 660;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint surface = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private volatile float nx = 0f, ny = 0f;
    private String label = "";

    public StickPadView(Context c, int dotColor) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(2.5f));
        ring.setColor(0xFF42566D);
        surface.setStyle(Paint.Style.FILL);
        surface.setColor(0xFF111923);
        cross.setStrokeWidth(dp(1.25f));
        cross.setColor(0xFF242C38);
        dot.setColor(dotColor);
        dotRing.setStyle(Paint.Style.STROKE);
        dotRing.setStrokeWidth(dp(2));
        dotRing.setColor(dotColor);
        dotRing.setAlpha(170);
        text.setColor(0xFF8B98A8);
        text.setTextSize(dp(11));
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setLabel(String s) {
        label = s;
        invalidate();
    }

    public void setPoint(int rawX, int rawY) {
        nx = clamp((rawX - RAW_CENTER) / (float) RAW_SPAN_HALF);
        ny = clamp((rawY - RAW_CENTER) / (float) RAW_SPAN_HALF);
        postInvalidateOnAnimation();
    }

    public void reset() {
        nx = 0;
        ny = 0;
        postInvalidateOnAnimation();
    }

    private static float clamp(float v) {
        return v < -1f ? -1f : (v > 1f ? 1f : v);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f - dp(10);
        canvas.drawCircle(cx, cy, r, surface);
        canvas.drawCircle(cx, cy, r, ring);
        canvas.drawLine(cx - r, cy, cx + r, cy, cross);
        canvas.drawLine(cx, cy - r, cx, cy + r, cross);
        float px = cx + nx * r, py = cy - ny * r;
        canvas.drawCircle(px, py, dp(22), dotRing);
        canvas.drawCircle(px, py, dp(17), dot);
        if (!label.isEmpty()) {
            canvas.drawText(label, dp(10), dp(20), text);
        }
    }
}
