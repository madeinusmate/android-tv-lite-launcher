package dev.litehome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

public final class ProgressRing extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float dotRadius;
    private long origin;
    private long duration = 25_000L;
    private boolean running;

    public ProgressRing(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        float stroke = 0.85f * density;
        dotRadius = 1.05f * density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(0x66FFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(0xFFFFFFFF);
        dot.setColor(0xFFFFFFFF);
        setWillNotDraw(false);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void start(long originUptime, long durationMs) {
        origin = originUptime;
        duration = Math.max(1L, durationMs);
        running = true;
        invalidate();
    }

    public void holdEmpty() {
        running = false;
        origin = 0L;
        invalidate();
    }

    public void stop() {
        running = false;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float inset = arc.getStrokeWidth() / 2f + 1f;
        oval.set(inset, inset, width - inset, height - inset);
        canvas.drawOval(oval, track);
        float progress = 0f;
        if (running && origin > 0L) {
            progress = (SystemClock.uptimeMillis() - origin) / (float) duration;
            if (progress < 0f) {
                progress = 0f;
            } else if (progress > 1f) {
                progress = 1f;
            }
        }
        if (progress > 0.004f) {
            canvas.drawArc(oval, -90f, 360f * progress, false, arc);
        }
        canvas.drawCircle(width / 2f, height / 2f, dotRadius, dot);
        if (running && progress < 1f && isAttachedToWindow() && isShown()) {
            postInvalidateOnAnimation();
        }
    }
}
