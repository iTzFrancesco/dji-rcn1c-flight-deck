package com.drone.rcn1cbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

public class StripChartView extends View {
    public static final int RAW_MIN = 364;
    public static final int RAW_MAX = 1684;
    public static final int RAW_CENTER = 1024;
    private static final int CAP = 400;

    private final int[] xs = new int[CAP];
    private final int[] ys = new int[CAP];
    private int head = 0, count = 0;

    private final Paint px = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint py = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pathX = new Path();
    private final Path pathY = new Path();

    public StripChartView(Context c, int colorX, int colorY) {
        super(c);
        px.setColor(colorX);
        px.setStrokeWidth(dp(2));
        px.setStyle(Paint.Style.STROKE);
        py.setColor(colorY);
        py.setStrokeWidth(dp(2));
        py.setStyle(Paint.Style.STROKE);
        grid.setColor(0xFF242C38);
        grid.setStrokeWidth(dp(1));
        text.setColor(0xFF8B98A8);
        text.setTextSize(dp(12));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public synchronized void push(int x, int y) {
        xs[head] = x;
        ys[head] = y;
        head = (head + 1) % CAP;
        if (count < CAP) count++;
    }

    public synchronized void clear() {
        head = 0;
        count = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        canvas.drawLine(0, h / 2f, w, h / 2f, grid);
        canvas.drawLine(0, h * 0.05f, w, h * 0.05f, grid);
        canvas.drawLine(0, h * 0.95f, w, h * 0.95f, grid);

        pathX.rewind();
        pathY.rewind();
        boolean firstX = true, firstY = true;
        synchronized (this) {
            for (int k = 0; k < count; k++) {
                int idx = (head - count + k + CAP) % CAP;
                float x = w - (count - 1 - k) * (w / (CAP - 1));
                if (x < 0) continue;
                float yX = mapY(xs[idx], h);
                float yY = mapY(ys[idx], h);
                if (firstX) { pathX.moveTo(x, yX); firstX = false; } else pathX.lineTo(x, yX);
                if (firstY) { pathY.moveTo(x, yY); firstY = false; } else pathY.lineTo(x, yY);
            }
        }
        canvas.drawPath(pathX, px);
        canvas.drawPath(pathY, py);
        canvas.drawText("X", dp(6), dp(18), px);
        canvas.drawText("Y", dp(24), dp(18), py);
    }

    private static float mapY(int raw, float h) {
        float t = (raw - RAW_MIN) / (float) (RAW_MAX - RAW_MIN);
        return h * (1f - t) * 0.9f + h * 0.05f;
    }
}
