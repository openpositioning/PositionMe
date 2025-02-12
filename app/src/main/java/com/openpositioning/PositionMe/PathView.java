package com.openpositioning.PositionMe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 自定义 View：显示路径轨迹
 * 该类基于 PDR 计算的坐标绘制路径，并支持轨迹缩放适配屏幕。
 */
public class PathView extends View {
    // 轨迹绘制颜色
    private int paintColor = Color.BLUE;
    // 画笔
    private Paint drawPaint;
    // 存储轨迹的 Path
    private Path path = new Path();
    // 存储轨迹坐标
    private List<Float> xCoords = new ArrayList<>();
    private List<Float> yCoords = new ArrayList<>();
    // 轨迹缩放比例
    private float scalingRatio;
    // 控制是否绘制轨迹
    private boolean draw = true;
    private boolean reDraw = false;
    private float strokeWidth = 5f;
    private static final int MAX_POINTS = 500;  // 限制最多 500 个轨迹点
    private List<PointF> trajectoryPoints = new ArrayList<>();  // 存储轨迹点

    /**
     * 构造函数：初始化 PathView
     *
     * @param context 应用上下文
     * @param attrs   视图属性
     */
    public PathView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setupPaint();
    }

    /**
     * 设置画笔样式
     */
    private void setupPaint() {
        if (drawPaint == null) {
            drawPaint = new Paint();
        }
        drawPaint.setColor(paintColor);  // 设置颜色
        drawPaint.setAntiAlias(true);    // 抗锯齿（如果影响性能，可关闭）
        drawPaint.setStrokeWidth(strokeWidth);  // 设置线宽
        drawPaint.setStyle(Paint.Style.STROKE); // 仅绘制轮廓
        drawPaint.setStrokeJoin(Paint.Join.ROUND); // 线段连接方式
        drawPaint.setStrokeCap(Paint.Cap.ROUND);   // 线端点样式
    }

    /**
     * 设置绘制颜色
     */
    public void setPaintColor(int color) {
        this.paintColor = color;
        if (drawPaint != null) {
            drawPaint.setColor(color);
        }
    }

    /**
     * 设置绘制线宽
     */
    public void setStrokeWidth(float width) {
        this.strokeWidth = width;
        if (drawPaint != null) {
            drawPaint.setStrokeWidth(width);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 如果轨迹为空，直接返回
        if (xCoords.isEmpty()) return;

        path.reset();  // 清空旧路径

        if (reDraw) {
            scaleTrajectory();  // 只有在重新绘制时才进行缩放计算
        }

        // 轨迹起点应是 xCoords.get(0), yCoords.get(0)
        path.moveTo(xCoords.get(0), yCoords.get(0));

        if (reDraw) {
            // 计算缩放后的坐标，避免修改原始数据
            for (int i = 1; i < xCoords.size(); i++) {
                float newX = (xCoords.get(i) - getWidth() / 2) * scalingRatio + getWidth() / 2;
                float newY = (yCoords.get(i) - getHeight() / 2) * scalingRatio + getHeight() / 2;
                path.lineTo(newX, newY);
            }
        } else {
            // 普通绘制，使用原始坐标
            for (int i = 1; i < xCoords.size(); i++) {
                path.lineTo(xCoords.get(i), yCoords.get(i));
            }
        }

        // 绘制轨迹
        canvas.drawPath(path, drawPaint);

        // 防止重复绘制
        draw = false;
        reDraw = false;
    }

    /**
     * 添加 PDR 轨迹坐标
     * @param newCords 轨迹点坐标 (float[2])
     */
    public void drawTrajectory(float[] newCords) {
        if (newCords == null || newCords.length < 2) return;  // 避免崩溃

        // 保持列表大小不超过 MAX_POINTS
        if (trajectoryPoints.size() >= MAX_POINTS) {
            trajectoryPoints.remove(0);  // 删除最早的点
        }

        // 添加新轨迹点（Y 坐标取反适应屏幕坐标）
        trajectoryPoints.add(new PointF(newCords[0], -newCords[1]));
    }

    /**
     * 缩放 PDR 轨迹点，使其适应屏幕尺寸
     */
    private void scaleTrajectory() {
        if (xCoords.isEmpty() || yCoords.isEmpty()) return; // 避免空列表异常

        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;

        // 计算 X 和 Y 方向的最大值（确保不为 0）
        float xRightMax = Math.max(Math.abs(Collections.max(xCoords)), 0.1f);
        float xLeftMax = Math.max(Math.abs(Collections.min(xCoords)), 0.1f);
        float yTopMax = Math.max(Math.abs(Collections.max(yCoords)), 0.1f);
        float yBottomMax = Math.max(Math.abs(Collections.min(yCoords)), 0.1f);

        // 计算缩放比例
        float xRightRange = (getWidth() / 2) / xRightMax;
        float xLeftRange = (getWidth() / 2) / xLeftMax;
        float yTopRange = (getHeight() / 2) / yTopMax;
        float yBottomRange = (getHeight() / 2) / yBottomMax;

        // 取最小缩放比例，保证所有点都适应屏幕
        float minRatio = Math.min(Math.min(xRightRange, xLeftRange), Math.min(yTopRange, yBottomRange));

        // 预留 10% 边距，限制缩放比例
        scalingRatio = Math.max(0.5f, Math.min(0.9f * minRatio, 23.926f));

        System.out.println("Adjusted scaling ratio: " + scalingRatio);

        // 设置 `scalingRatio`，同步 Google Maps 缩放
//        correctionFragment.setScalingRatio(scalingRatio);

        // 计算缩放后的坐标
        List<Float> scaledXCoords = new ArrayList<>();
        List<Float> scaledYCoords = new ArrayList<>();

        for (int i = 0; i < xCoords.size(); i++) {
            scaledXCoords.add(xCoords.get(i) * scalingRatio + centerX);
            scaledYCoords.add(yCoords.get(i) * scalingRatio + centerY);
        }

        // 替换坐标列表
        xCoords = scaledXCoords;
        yCoords = scaledYCoords;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        // 确保不为空后清空轨迹数据
        if (xCoords != null) xCoords.clear();
        if (yCoords != null) yCoords.clear();

        // 只有在有数据时才标记 `draw = true`
        if (!xCoords.isEmpty() || !yCoords.isEmpty()) {
            draw = true;
        }

        // 释放 `Paint` 资源（可选）
        drawPaint = null;
    }

    /**
     * 重新缩放路径
     *
     * @param newScale 新的缩放比例
     */
    public void redraw(float newScale) {
        // 如果缩放比例几乎没有变化，则不触发重绘
        if (Math.abs(newScale - scalingRatio) < 0.01f) return;

        // 限制缩放范围，防止过大或过小
        if (newScale < 0.1f) {
            scalingRatio = 0.1f;
        } else if (newScale > 23.926f) {
            scalingRatio = 23.926f;
        } else {
            scalingRatio = newScale;
        }

        // 标记 `reDraw = true`，让 `onDraw()` 重新缩放轨迹
        reDraw = true;

        // 立即刷新 UI
        invalidate();
    }


}
