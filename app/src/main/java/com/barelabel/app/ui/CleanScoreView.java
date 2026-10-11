package com.barelabel.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Circular Clean Score gauge: gray track, colored arc (green >= 80,
 * amber >= 50, red below), animated sweep to the score, number in the
 * middle. Shows an em dash while no score is available.
 */
public class CleanScoreView extends View {

    private static final int COLOR_TRACK = Color.parseColor("#E5E7EB");
    private static final int COLOR_GREEN = Color.parseColor("#15803D");
    private static final int COLOR_AMBER = Color.parseColor("#D97706");
    private static final int COLOR_RED = Color.parseColor("#DC2626");
    private static final int COLOR_MUTED = Color.parseColor("#9CA3AF");

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();

    private int score = -1;
    private float displayed = 0f;

    public CleanScoreView(Context context) {
        super(context);
        init();
    }

    public CleanScoreView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dpF(7));
        trackPaint.setColor(COLOR_TRACK);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(dpF(7));
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** Animates the arc to {@code newScore}; negative shows the dash state. */
    public void setScore(int newScore) {
        score = newScore;
        if (newScore < 0) {
            displayed = 0f;
            invalidate();
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(0f, newScore);
        anim.setDuration(600);
        anim.addUpdateListener(a -> {
            displayed = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - dpF(5);
        if (radius <= 0) return;
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);

        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint);
        if (score >= 0) {
            int color = score >= 80 ? COLOR_GREEN
                    : score >= 50 ? COLOR_AMBER : COLOR_RED;
            arcPaint.setColor(color);
            canvas.drawArc(arcBounds, -90f, displayed * 3.6f, false, arcPaint);
            textPaint.setColor(color);
            textPaint.setTextSize(dpF(20));
            String label = String.valueOf(Math.round(displayed));
            float baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f;
            canvas.drawText(label, cx, baseline, textPaint);
        } else {
            textPaint.setColor(COLOR_MUTED);
            textPaint.setTextSize(dpF(18));
            float baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f;
            canvas.drawText("—", cx, baseline, textPaint);
        }
    }

    private float dpF(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
