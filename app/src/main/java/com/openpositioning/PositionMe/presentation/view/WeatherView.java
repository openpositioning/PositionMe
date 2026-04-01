package com.openpositioning.PositionMe.presentation.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * A lightweight weather indicator drawn with Canvas.
 * Shows a weather icon based on WMO weather code and temperature text below.
 *
 * <p>Call {@link #setWeather(int, double)} with the WMO weather code and
 * temperature in Celsius to update the display.</p>
 */
public class WeatherView extends View {

    private int weatherCode = -1;
    private double temperature = Double.NaN;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sunPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sunRayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cloudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cloudOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint snowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fogPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tempPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path path = new Path();

    public WeatherView(Context context) {
        super(context);
        init();
    }

    public WeatherView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WeatherView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        bgPaint.setColor(Color.argb(200, 255, 255, 255));
        bgPaint.setStyle(Paint.Style.FILL);

        rimPaint.setColor(Color.argb(100, 0, 0, 0));
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(2f);

        sunPaint.setColor(Color.rgb(255, 193, 7)); // Amber
        sunPaint.setStyle(Paint.Style.FILL);

        sunRayPaint.setColor(Color.rgb(255, 193, 7));
        sunRayPaint.setStyle(Paint.Style.STROKE);
        sunRayPaint.setStrokeCap(Paint.Cap.ROUND);

        cloudPaint.setColor(Color.rgb(224, 224, 224));
        cloudPaint.setStyle(Paint.Style.FILL);

        cloudOutlinePaint.setColor(Color.rgb(158, 158, 158));
        cloudOutlinePaint.setStyle(Paint.Style.STROKE);
        cloudOutlinePaint.setStrokeWidth(1.5f);

        rainPaint.setColor(Color.rgb(33, 150, 243)); // Blue
        rainPaint.setStyle(Paint.Style.STROKE);
        rainPaint.setStrokeCap(Paint.Cap.ROUND);

        snowPaint.setColor(Color.rgb(144, 202, 249)); // Light blue
        snowPaint.setStyle(Paint.Style.FILL);

        fogPaint.setColor(Color.rgb(189, 189, 189));
        fogPaint.setStyle(Paint.Style.STROKE);
        fogPaint.setStrokeCap(Paint.Cap.ROUND);

        boltPaint.setColor(Color.rgb(255, 235, 59)); // Yellow
        boltPaint.setStyle(Paint.Style.FILL);

        tempPaint.setColor(Color.DKGRAY);
        tempPaint.setTextAlign(Paint.Align.CENTER);
        tempPaint.setTypeface(Typeface.DEFAULT_BOLD);

        labelPaint.setColor(Color.GRAY);
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * Update weather display.
     * @param wmoCode WMO weather code (0=clear, 1-3=cloudy, 51-67=rain, 71-77=snow, 95+=thunder)
     * @param tempC temperature in Celsius
     */
    public void setWeather(int wmoCode, double tempC) {
        this.weatherCode = wmoCode;
        this.temperature = tempC;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float iconAreaH = h * 0.78f;
        float cx = w / 2f;
        float iconCy = iconAreaH * 0.45f;
        float radius = Math.min(cx, iconAreaH * 0.5f) - 2f;
        if (radius <= 0) return;

        // Background rounded rect
        RectF bg = new RectF(1, 1, w - 1, h - 1);
        canvas.drawRoundRect(bg, 12f, 12f, bgPaint);
        canvas.drawRoundRect(bg, 12f, 12f, rimPaint);

        if (weatherCode < 0) {
            // No data yet — draw "?"
            tempPaint.setTextSize(radius * 0.6f);
            canvas.drawText("?", cx, iconCy + radius * 0.2f, tempPaint);
        } else {
            drawWeatherIcon(canvas, cx, iconCy, radius);
        }

        // Temperature text below icon — tight gap
        if (!Double.isNaN(temperature)) {
            tempPaint.setTextSize(h * 0.20f);
            String temp = String.format("%.0f°C", temperature);
            canvas.drawText(temp, cx, h - h * 0.03f, tempPaint);
        }
    }

    private void drawWeatherIcon(Canvas canvas, float cx, float cy, float r) {
        if (weatherCode == 0) {
            // Clear sky — sun
            drawSun(canvas, cx, cy, r * 0.80f);
        } else if (weatherCode <= 3) {
            // Partly cloudy — sun + cloud
            drawSun(canvas, cx - r * 0.2f, cy - r * 0.15f, r * 0.50f);
            drawCloud(canvas, cx + r * 0.1f, cy + r * 0.1f, r * 0.65f);
        } else if (weatherCode <= 48) {
            // Fog
            drawFog(canvas, cx, cy, r * 0.70f);
        } else if (weatherCode <= 67) {
            // Rain / drizzle
            drawCloud(canvas, cx, cy - r * 0.15f, r * 0.65f);
            drawRain(canvas, cx, cy + r * 0.30f, r * 0.55f);
        } else if (weatherCode <= 77) {
            // Snow
            drawCloud(canvas, cx, cy - r * 0.15f, r * 0.65f);
            drawSnow(canvas, cx, cy + r * 0.30f, r * 0.55f);
        } else if (weatherCode <= 82) {
            // Rain showers
            drawCloud(canvas, cx, cy - r * 0.15f, r * 0.70f);
            drawRain(canvas, cx, cy + r * 0.30f, r * 0.60f);
        } else if (weatherCode <= 86) {
            // Snow showers
            drawCloud(canvas, cx, cy - r * 0.15f, r * 0.70f);
            drawSnow(canvas, cx, cy + r * 0.30f, r * 0.60f);
        } else {
            // Thunderstorm
            drawCloud(canvas, cx, cy - r * 0.2f, r * 0.70f);
            drawBolt(canvas, cx, cy + r * 0.15f, r * 0.50f);
        }
    }

    private void drawSun(Canvas canvas, float cx, float cy, float r) {
        canvas.drawCircle(cx, cy, r * 0.5f, sunPaint);
        sunRayPaint.setStrokeWidth(r * 0.1f);
        for (int i = 0; i < 8; i++) {
            float angle = (float) Math.toRadians(i * 45);
            float sin = (float) Math.sin(angle);
            float cos = (float) Math.cos(angle);
            canvas.drawLine(
                    cx + r * 0.65f * sin, cy - r * 0.65f * cos,
                    cx + r * 0.9f * sin, cy - r * 0.9f * cos,
                    sunRayPaint);
        }
    }

    private void drawCloud(Canvas canvas, float cx, float cy, float r) {
        canvas.drawCircle(cx - r * 0.3f, cy, r * 0.35f, cloudPaint);
        canvas.drawCircle(cx + r * 0.3f, cy, r * 0.3f, cloudPaint);
        canvas.drawCircle(cx, cy - r * 0.2f, r * 0.38f, cloudPaint);
        canvas.drawRect(cx - r * 0.55f, cy, cx + r * 0.55f, cy + r * 0.25f, cloudPaint);

        canvas.drawCircle(cx - r * 0.3f, cy, r * 0.35f, cloudOutlinePaint);
        canvas.drawCircle(cx + r * 0.3f, cy, r * 0.3f, cloudOutlinePaint);
        canvas.drawCircle(cx, cy - r * 0.2f, r * 0.38f, cloudOutlinePaint);
    }

    private void drawRain(Canvas canvas, float cx, float cy, float r) {
        rainPaint.setStrokeWidth(r * 0.12f);
        for (int i = -1; i <= 1; i++) {
            float x = cx + i * r * 0.35f;
            canvas.drawLine(x, cy, x - r * 0.1f, cy + r * 0.35f, rainPaint);
        }
    }

    private void drawSnow(Canvas canvas, float cx, float cy, float r) {
        float dotR = r * 0.1f;
        for (int i = -1; i <= 1; i++) {
            float x = cx + i * r * 0.35f;
            canvas.drawCircle(x, cy + r * 0.1f, dotR, snowPaint);
            canvas.drawCircle(x + r * 0.15f, cy + r * 0.3f, dotR, snowPaint);
        }
    }

    private void drawFog(Canvas canvas, float cx, float cy, float r) {
        fogPaint.setStrokeWidth(r * 0.12f);
        for (int i = -1; i <= 1; i++) {
            float y = cy + i * r * 0.35f;
            canvas.drawLine(cx - r * 0.6f, y, cx + r * 0.6f, y, fogPaint);
        }
    }

    private void drawBolt(Canvas canvas, float cx, float cy, float r) {
        path.reset();
        path.moveTo(cx + r * 0.1f, cy - r * 0.5f);
        path.lineTo(cx - r * 0.2f, cy + r * 0.05f);
        path.lineTo(cx + r * 0.05f, cy + r * 0.05f);
        path.lineTo(cx - r * 0.1f, cy + r * 0.5f);
        path.lineTo(cx + r * 0.25f, cy - r * 0.1f);
        path.lineTo(cx, cy - r * 0.1f);
        path.close();
        canvas.drawPath(path, boltPaint);
    }
}
