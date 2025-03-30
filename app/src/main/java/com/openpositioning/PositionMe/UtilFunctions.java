package com.openpositioning.PositionMe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;
import com.google.android.gms.maps.model.LatLng;
import android.util.Log;

/**
 * UtilFunctions: 包含常用工具方法，如计算 PDR 新位置
 * UtilFunctions: Contains common utility methods, such as calculating the new position of PDR
 */
public class UtilFunctions {
    // 1° 纬度/经度 对应的米数（WGS84 椭球模型下）
    // 1° latitude/longitude corresponds to meters (WGS84 ellipsoid model)
    private static final double DEGREE_IN_M = 111111.0;
    private static final int MAX_SIZE = 512;  // 限制最大 Bitmap 尺寸，防止 OOM Limit the maximum Bitmap size to prevent OOM

    /**
     * 根据 PDR（行人航位推算）计算新的地理位置
     *
     * @param initialLocation 用户当前的 GPS 位置
     * @param pdrMoved PDR 计算的 (X, Y) 移动量（单位: 米）
     * @return 计算后的新坐标 `LatLng`
     * @throws IllegalArgumentException 如果 `initialLocation` 为空，或者 `pdrMoved` 长度不足 2
     * Calculate the new geographic location based on PDR (pedestrian dead reckoning)
     *
     * @param initialLocation The user's current GPS location
     * @param pdrMoved The (X, Y) movement calculated by PDR (unit: meters)
     * @return The calculated new coordinates `LatLng`
     * @throws IllegalArgumentException if `initialLocation` is empty or `pdrMoved` is less than 2
     */
    public static LatLng calculateNewPos(final LatLng initialLocation, final float[] pdrMoved) {
        // 检查输入参数合法性
        // Check the validity of input parameters
        if (initialLocation == null)
            throw new IllegalArgumentException("Initial location cannot be null");
        if (pdrMoved == null || pdrMoved.length < 2)
            throw new IllegalArgumentException("pdrMoved must be a float array of length 2");

        // 计算新的纬度
        // Calculate the new latitude
        double newLatitude = initialLocation.latitude + (pdrMoved[1] / DEGREE_IN_M);

        // 计算新的经度（纬度越高，经度变化对实际距离影响越大，需要调整）
        // Calculate the new longitude (the higher the latitude, the greater the impact of longitude changes on the actual distance, and needs to be adjusted)
        double newLongitude = initialLocation.longitude + (pdrMoved[0] / DEGREE_IN_M)
                * Math.cos(Math.toRadians(initialLocation.latitude));

        // 记录日志（可选）
//        Log.d("UtilFunctions", "Moved from (" + initialLocation.latitude + ", " + initialLocation.longitude + ")"
//                + " to (" + newLatitude + ", " + newLongitude + ")");

        return new LatLng(newLatitude, newLongitude);
    }

    /**
     * 将纬度的度数转换为米
     * @param degreeVal 纬度值（单位：度）
     * @return 对应的米数
     * Convert latitude degrees to meters
     * @param degreeVal latitude value (unit: degrees)
     * @return corresponding meters
     */
    public static double degreesToMetersLat(double degreeVal) {
        return degreeVal * DEGREE_IN_M;
    }

    /**
     * 将经度的度数转换为米（需考虑纬度影响）
     * @param degreeVal 经度值（单位：度）
     * @param latitude 当前纬度
     * @return 对应的米数
     * Convert longitude to meters (consider latitude)
     * @param degreeVal longitude value (unit: degrees)
     * @param latitude current latitude
     * @return corresponding meters
     */
    public static double degreesToMetersLng(double degreeVal, double latitude) {
        return degreeVal * DEGREE_IN_M / Math.cos(Math.toRadians(latitude));
    }

    /**
     * 计算两点之间的球面距离（单位：米）
     * 使用 Haversine 公式以获得更高精度
     * @param pointA 起始点
     * @param pointB 终点
     * @return 两点之间的球面距离（单位：米）
     * @throws IllegalArgumentException 如果 `pointA` 或 `pointB` 为空
     * Calculate the spherical distance between two points (in meters)
     * Use Haversine formula for higher accuracy
     * @param pointA starting point
     * @param pointB end point
     * @return spherical distance between two points (in meters)
     * @throws IllegalArgumentException if `pointA` or `pointB` is null
     */
    public static double distanceBetweenPoints(LatLng pointA, LatLng pointB) {
        if (pointA == null || pointB == null)
            throw new IllegalArgumentException("LatLng points cannot be null");

        final double R = 6371000; // 地球半径（单位：米）Earth's radius (meters)
        double lat1 = Math.toRadians(pointA.latitude);
        double lat2 = Math.toRadians(pointB.latitude);
        double deltaLat = Math.toRadians(pointB.latitude - pointA.latitude);
        double deltaLon = Math.toRadians(pointB.longitude - pointA.longitude);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                        Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c; // 返回距离（单位：米）Return distance (unit: meter)
    }

    public static double[] convertLatLangToNorthingEasting(final LatLng start, final LatLng target) {
        if (start == null || target == null) {
            throw new IllegalArgumentException("Start and target points cannot be null");
        }

        // 计算经纬度差值
        // Compute the differences in latitude and longitude (in degrees)
        double deltaLat = target.latitude - start.latitude;
        double deltaLon = target.longitude - start.longitude;

        // 将纬度差转换为北向距离（单位：米）
        // Convert the latitude difference to northing (meters)
        double northing = deltaLat * DEGREE_IN_M;

        // 将经度差转换为东向距离（单位：米），考虑纬度对经度实际距离的影响
        // Convert the longitude difference to easting (meters), taking into account the latitude
        double easting = deltaLon * DEGREE_IN_M / Math.cos(Math.toRadians(start.latitude));

        return new double[]{easting, northing};
    }

    /**
     * 将 Vector Drawable 转换为 Bitmap
     * @param context 应用的 `Context`
     * @param vectorResourceID 矢量资源 ID
     * @return 转换后的 `Bitmap`
     * @throws IllegalArgumentException 如果 `context` 为空，或 `vectorResourceID` 无效
     * Convert Vector Drawable to Bitmap
     * @param context application `Context`
     * @param vectorResourceID vector resource ID
     * @return converted `Bitmap`
     * @throws IllegalArgumentException if `context` is null, or `vectorResourceID` is invalid
     */
    public static Bitmap getBitmapFromVector(Context context, int vectorResourceID) {
        // 检查参数合法性
        // Check the validity of the parameters
        if (context == null)
            throw new IllegalArgumentException("Context cannot be null");

        Drawable vectorDrawable = ContextCompat.getDrawable(context, vectorResourceID);
        if (vectorDrawable == null)
            throw new IllegalArgumentException("Invalid vector resource ID: " + vectorResourceID);

        // 确保 `width` 和 `height` 为正值
        // Make sure `width` and `height` are positive values
        int width = vectorDrawable.getIntrinsicWidth() > 0 ? vectorDrawable.getIntrinsicWidth() : 100;
        int height = vectorDrawable.getIntrinsicHeight() > 0 ? vectorDrawable.getIntrinsicHeight() : 100;

        // 限制最大尺寸，防止 OOM
        // Limit the maximum size to prevent OOM
        width = Math.min(width, MAX_SIZE);
        height = Math.min(height, MAX_SIZE);

        // 创建 Bitmap 并绘制矢量图
        // Create a Bitmap and draw a vector
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        vectorDrawable.setBounds(0, 0, width, height);
        vectorDrawable.draw(canvas);

        Log.d("UtilFunctions", "Bitmap created from vector ID: " + vectorResourceID +
                " (Size: " + width + "x" + height + ")");

        return bitmap;
    }

}
