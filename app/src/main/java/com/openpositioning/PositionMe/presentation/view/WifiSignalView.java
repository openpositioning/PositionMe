package com.openpositioning.PositionMe.presentation.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * A compact WiFi signal quality indicator that draws 3 vertical bars.
 * Bars fill from left to right; unfilled bars are drawn in light grey.
 *
 * <p>Call {@link #setLevel(int)} with 0–3 to update the display:
 * <ul>
 *   <li>0 = no data (all grey)</li>
 *   <li>1 = weak / REJECTED (1 bar, red-orange)</li>
 *   <li>2 = medium / AMBIGUOUS (2 bars, amber)</li>
 *   <li>3 = strong / GOOD (3 bars, green)</li>
 * </ul>
 */
public class WifiSignalView extends View {

    private int level = 0; // 0..3

    private final Paint filledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final int COLOR_STRONG  = Color.rgb( 76, 175,  80); // green
    private static final int COLOR_MEDIUM  = Color.rgb(255, 193,   7); // amber
    private static final int COLOR_WEAK    = Color.rgb(255,  87,  34); // deep orange
    private static final int COLOR_EMPTY   = Color.rgb(200, 200, 200); // light grey

    public WifiSignalView(Context context) {
        super(context);
        init();
    }

    public WifiSignalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WifiSignalView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        emptyPaint.setColor(COLOR_EMPTY);
        emptyPaint.setStyle(Paint.Style.FILL);
        filledPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * Sets the signal level (0–3) and redraws.
     */
    public void setLevel(int level) {
        this.level = Math.max(0, Math.min(3, level));
        invalidate();
    }

    /** Returns the current signal level (0-3). */
    public int getLevel() {
        return level;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Choose colour based on level
        int activeColor;
        switch (level) {
            case 3:  activeColor = COLOR_STRONG; break;
            case 2:  activeColor = COLOR_MEDIUM; break;
            case 1:  activeColor = COLOR_WEAK;   break;
            default: activeColor = COLOR_EMPTY;  break;
        }
        filledPaint.setColor(activeColor);

        // Layout: 3 bars with small gaps.
        // Bar heights: 40%, 70%, 100% of view height.
        float gap = w * 0.10f;
        float barWidth = (w - gap * 2f) / 3f;
        float[] heightFractions = {0.40f, 0.70f, 1.00f};
        float cornerRadius = barWidth * 0.25f;

        for (int i = 0; i < 3; i++) {
            float barH = h * heightFractions[i];
            float left = i * (barWidth + gap);
            float top  = h - barH;
            float right = left + barWidth;

            Paint paint = (i < level) ? filledPaint : emptyPaint;
            canvas.drawRoundRect(new RectF(left, top, right, h), cornerRadius, cornerRadius, paint);
        }
    }
}
