package com.drone.rcn1cbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

public class StickPadView extends View {
    public static final int RAW_CENTER = 1024;
    public static final int RAW_SPAN_HALF = 660;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float nx = 0f, ny = 0f;
    private String label = "";

    public StickPadView(Context c, int dotColor) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(2));
        ring.setColor(0xFF3A4453);
        cross.setStrokeWidth(dp(1.25f));
        cross.setColor(0xFF242C38);
        dot.setColor(dotColor);
        dot.setShadowLayer(dp(8), 0, 0, dotColor);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        text.setColor(0xFF8B98A8);
        text.setTextSize(dp(12));
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
        canvas.drawCircle(cx, cy, r, ring);
        canvas.drawLine(cx - r, cy, cx + r, cy, cross);
        canvas.drawLine(cx, cy - r, cx, cy + r, cross);
        canvas.drawCircle(cx + nx * r, cy - ny * r, dp(14), dot);
        if (!label.isEmpty()) {
            canvas.drawText(label, dp(8), dp(16), text);
        }
    }
}
