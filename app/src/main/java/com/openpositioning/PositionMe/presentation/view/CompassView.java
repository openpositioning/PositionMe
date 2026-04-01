package com.openpositioning.PositionMe.presentation.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * A lightweight compass dial that rotates to reflect the device's current
 * magnetic heading. Drawn entirely with Canvas for crisp rendering at any size.
 *
 * <p>Call {@link #setHeading(float)} with the azimuth in degrees (0 = north,
 * clockwise). The dial rotates so that the "N" label always points towards
 * magnetic north on screen.</p>
 */
public class CompassView extends View {

    private float heading = 0f;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint northPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint southPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path needlePath = new Path();

    public CompassView(Context context) {
        super(context);
        init();
    }

    public CompassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CompassView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        bgPaint.setColor(Color.argb(200, 255, 255, 255));
        bgPaint.setStyle(Paint.Style.FILL);

        rimPaint.setColor(Color.argb(100, 0, 0, 0));
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(2f);

        tickPaint.setColor(Color.DKGRAY);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        labelPaint.setColor(Color.DKGRAY);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Typeface.DEFAULT_BOLD);

        nLabelPaint.setColor(Color.RED);
        nLabelPaint.setTextAlign(Paint.Align.CENTER);
        nLabelPaint.setTypeface(Typeface.DEFAULT_BOLD);

        northPaint.setColor(Color.RED);
        northPaint.setStyle(Paint.Style.FILL);

        southPaint.setColor(Color.WHITE);
        southPaint.setStyle(Paint.Style.FILL);

        centerPaint.setColor(Color.DKGRAY);
        centerPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * Set the current heading in degrees (0 = north, clockwise).
     * The dial rotates so "N" always points toward magnetic north.
     */
    public void setHeading(float degrees) {
        this.heading = degrees;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - 2f;
        if (radius <= 0) return;

        // Background circle
        canvas.drawCircle(cx, cy, radius, bgPaint);
        canvas.drawCircle(cx, cy, radius, rimPaint);

        // Rotate entire dial so North points to magnetic north
        canvas.save();
        canvas.rotate(-heading, cx, cy);

        // Tick marks
        float majorLen = radius * 0.15f;
        float minorLen = radius * 0.08f;
        tickPaint.setStrokeWidth(radius * 0.02f);
        for (int deg = 0; deg < 360; deg += 10) {
            boolean isMajor = (deg % 30 == 0);
            float len = isMajor ? majorLen : minorLen;
            float startR = radius - len;
            float endR = radius - 1f;
            float rad = (float) Math.toRadians(deg);
            float sin = (float) Math.sin(rad);
            float cos = (float) Math.cos(rad);
            canvas.drawLine(
                    cx + startR * sin, cy - startR * cos,
                    cx + endR * sin, cy - endR * cos,
                    tickPaint);
        }

        // Cardinal labels
        float labelSize = radius * 0.28f;
        labelPaint.setTextSize(labelSize);
        nLabelPaint.setTextSize(labelSize);
        float labelR = radius * 0.62f;

        // N (red)
        canvas.drawText("N", cx, cy - labelR + labelSize * 0.35f, nLabelPaint);
        // S
        canvas.drawText("S", cx, cy + labelR + labelSize * 0.35f, labelPaint);
        // E
        canvas.drawText("E", cx + labelR, cy + labelSize * 0.35f, labelPaint);
        // W
        canvas.drawText("W", cx - labelR, cy + labelSize * 0.35f, labelPaint);

        // Needle — north half (red triangle pointing up)
        float needleHalfW = radius * 0.08f;
        float needleLen = radius * 0.40f;
        needlePath.reset();
        needlePath.moveTo(cx, cy - needleLen);
        needlePath.lineTo(cx - needleHalfW, cy);
        needlePath.lineTo(cx + needleHalfW, cy);
        needlePath.close();
        canvas.drawPath(needlePath, northPaint);

        // Needle — south half (white triangle pointing down)
        needlePath.reset();
        needlePath.moveTo(cx, cy + needleLen);
        needlePath.lineTo(cx - needleHalfW, cy);
        needlePath.lineTo(cx + needleHalfW, cy);
        needlePath.close();
        canvas.drawPath(needlePath, southPaint);

        canvas.restore();

        // Center dot (drawn after restore so it doesn't rotate)
        canvas.drawCircle(cx, cy, radius * 0.06f, centerPaint);
    }
}
