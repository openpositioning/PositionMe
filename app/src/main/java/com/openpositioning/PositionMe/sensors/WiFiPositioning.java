package com.openpositioning.PositionMe.sensors;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.android.volley.RequestQueue;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.maps.model.LatLng;

import org.json.JSONObject;
/**
 * Class for handling WiFi-based positioning using Android's LocationManager
 * 
 * 由于OpenPositioning API没有提供WiFi定位端点，我们改用Android系统的LocationManager
 * 获取基于WiFi的位置信息。
 * 
 * 该类将使用Android的网络位置提供商（主要是WiFi和蜂窝网络）来获取位置信息。
 *
 * @author Arun Gopalakrishnan
 */
public class WiFiPositioning {
    // 上下文和位置服务
    private final Context context;
    private final LocationManager locationManager;
    
    // WiFi位置和楼层
    private LatLng wifiLocation;
    private int floor = 0; // 默认楼层为0
    
    // 位置更新最小间隔（毫秒）和最小距离（米）
    private static final long MIN_TIME = 1000; // 1秒
    private static final float MIN_DISTANCE = 0; // 不限制最小距离
    
    // 用于保存回调的队列
    private final RequestQueue requestQueue;
    
    /**
     * 位置监听器，处理位置更新
     */
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            wifiLocation = new LatLng(location.getLatitude(), location.getLongitude());
            Log.d("WiFiPositioning", "位置已更新: " + wifiLocation.latitude + ", " + wifiLocation.longitude);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            Log.d("WiFiPositioning", "提供商状态变化: " + provider + ", 状态: " + status);
        }

        @Override
        public void onProviderEnabled(String provider) {
            Log.d("WiFiPositioning", "提供商已启用: " + provider);
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.d("WiFiPositioning", "提供商已禁用: " + provider);
        }
    };

    /**
     * Constructor to create the WiFi positioning object
     *
     * Initialising the location manager and request queue
     *
     * @param context Context of object calling
     */
    public WiFiPositioning(Context context) {
        this.context = context;
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.requestQueue = Volley.newRequestQueue(context.getApplicationContext());
        
        // 启动位置更新
        startLocationUpdates();
    }
    
    /**
     * 开始接收位置更新
     */
    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e("WiFiPositioning", "没有位置权限，无法获取位置更新");
            return;
        }
        
        // 优先使用网络位置（WiFi和蜂窝网络）
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                MIN_TIME,
                MIN_DISTANCE,
                locationListener,
                Looper.getMainLooper()
            );
            Log.d("WiFiPositioning", "已请求网络位置更新");
            
            // 尝试立即获取一个位置
            try {
                Location lastLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (lastLocation != null) {
                    wifiLocation = new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude());
                    Log.d("WiFiPositioning", "获取到初始位置: " + wifiLocation.latitude + ", " + wifiLocation.longitude);
                }
            } catch (Exception e) {
                Log.e("WiFiPositioning", "获取初始位置失败: " + e.getMessage());
            }
        } else {
            Log.e("WiFiPositioning", "网络位置提供商不可用");
        }
    }

    /**
     * Getter for the WiFi positioning coordinates
     * @return the user's coordinates based on WiFi positioning
     */
    public LatLng getWifiLocation() {
        // 如果没有位置，尝试获取最后已知位置
        if (wifiLocation == null) {
            try {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (lastKnownLocation != null) {
                        wifiLocation = new LatLng(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude());
                        Log.d("WiFiPositioning", "使用最后已知位置: " + wifiLocation.latitude + ", " + wifiLocation.longitude);
                    }
                }
            } catch (Exception e) {
                Log.e("WiFiPositioning", "获取最后已知位置失败: " + e.getMessage());
            }
        }
        return wifiLocation;
    }

    /**
     * Getter for the WiFi positioning floor
     * @return the user's floor based on WiFi positioning (默认为0)
     */
    public int getFloor() {
        return floor;
    }

    /**
     * 创建一个获取WiFi位置的请求。
     * 由于我们现在使用Android的位置系统，此方法仅用于保持与旧代码的兼容性。
     *
     * @param jsonWifiFeatures WiFi指纹数据（现在被忽略）
     */
    public void request(JSONObject jsonWifiFeatures) {
        Log.d("WiFiPositioning", "收到WiFi位置请求，使用Android系统位置服务");
    }

    /**
     * 创建一个带回调的获取WiFi位置请求。
     * 将立即返回当前的位置信息。
     *
     * @param jsonWifiFeatures WiFi指纹数据（现在被忽略）
     * @param callback 用于返回位置的回调
     */
    public void request(JSONObject jsonWifiFeatures, final VolleyCallback callback) {
        Log.d("WiFiPositioning", "收到带回调的WiFi位置请求");
        
        // 获取当前位置
        LatLng currentLocation = getWifiLocation();
        
        // 如果有位置信息，通过回调返回
        if (currentLocation != null) {
            Log.d("WiFiPositioning", "返回当前位置: " + currentLocation.latitude + ", " + currentLocation.longitude);
            callback.onSuccess(currentLocation, floor);
        } else {
            // 如果没有位置信息，返回错误
            Log.e("WiFiPositioning", "没有可用的位置信息");
            callback.onError("没有可用的位置信息");
        }
    }

    /**
     * 释放资源，停止位置更新
     */
    public void release() {
        locationManager.removeUpdates(locationListener);
    }

    /**
     * Interface defined for the callback to access position data
     */
    public interface VolleyCallback {
        void onSuccess(LatLng location, int floor);
        void onError(String message);
    }
}