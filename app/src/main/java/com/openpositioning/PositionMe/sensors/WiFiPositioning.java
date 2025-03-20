package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * WiFiPositioning 类既处理 WiFi 指纹 POST 请求获取定位信息，
 * 又集成了地图轨迹绘制和楼层图片覆盖的功能。
 *
 * 【主要功能】
 * 1. 在轨迹开始时，将相机自动移动到经纬度 (55.92279, 3.174643) 与 (55.92335, 3.173829) 构成的矩形区域，
 *    并锁定该区域（示例中通过禁用手势实现）。
 * 2. 根据当前楼层加载对应的楼层图片（图片放在 drawable 文件夹中，共四层）。
 * 3. 实时更新用户位置：更新轨迹（Polyline）和当前位置标记（Marker）。
 *
 * 同时保留原有利用 Volley 进行网络请求的功能。
 */
public class WiFiPositioning {
    // 用于处理 WiFi 定位请求的 Volley 请求队列和 API URL
    private RequestQueue requestQueue;
    private static final String url = "https://openpositioning.org/api/position/fine";
    // 存储通过网络获取的定位信息
    private LatLng wifiLocation;
    private int floor = 0; // 默认楼层（例如：0表示底层）

    // ------------- 以下为轨迹绘制和楼层覆盖相关字段 -------------
    private GoogleMap mMap;
    private Polyline trajectoryPolyline;
    private Marker currentLocationMarker;
    private List<LatLng> trajectoryPoints;
    private GroundOverlay floorOverlay;
    private int currentFloor = 0; // 当前显示的楼层，初始为 0

    // 矩形区域：由两组坐标 (55.92279, 3.174643) 与 (55.92335, 3.173829) 构成
    // 注意：SW点（左下角）取 (55.92279, 3.173829)，NE点（右上角）取 (55.92335, 3.174643)
    private final LatLngBounds overlayBounds = new LatLngBounds(
            new LatLng(55.92279, 3.173829),
            new LatLng(55.92335, 3.174643)
    );

    /**
     * 构造函数，用于仅发起定位网络请求（原有功能）
     */
    public WiFiPositioning(Context context) {
        this.requestQueue = Volley.newRequestQueue(context.getApplicationContext());
    }

    /**
     * 重载构造函数，用于绘制轨迹和楼层覆盖。
     * 请在地图加载完成后调用此构造函数，将 GoogleMap 对象传入。
     *
     * @param map     GoogleMap 对象
     * @param context 上下文
     */
    public WiFiPositioning(GoogleMap map, Context context) {
        this.mMap = map;
        this.requestQueue = Volley.newRequestQueue(context.getApplicationContext()); // 添加这一行
        trajectoryPoints = new ArrayList<>();
        trajectoryPolyline = mMap.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(5)
                .zIndex(3));
        mMap.moveCamera(CameraUpdateFactory.newLatLngBounds(overlayBounds, 50));
        mMap.getUiSettings().setAllGesturesEnabled(false);
        updateFloorOverlay(currentFloor);
    }


    // ------------- 网络请求相关代码（原有 WiFi 定位） -------------
    public LatLng getWifiLocation() {
        return wifiLocation;
    }

    public int getFloor() {
        return floor;
    }

    /**
     * 发起 WiFi 定位请求，采用 POST 方式提交 WiFi 指纹数据。
     *
     * @param jsonWifiFeatures WiFi 指纹 JSON 数据
     */
    public void request(JSONObject jsonWifiFeatures) {
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST, url, jsonWifiFeatures,
                response -> {
                    try {
                        wifiLocation = new LatLng(response.getDouble("lat"), response.getDouble("lon"));
                        floor = response.getInt("floor");
                    } catch (JSONException e) {
                        Log.e("jsonErrors", "Error parsing response: " + e.getMessage() + " " + response);
                    }
                },
                error -> {
                    if (error.networkResponse != null && error.networkResponse.statusCode == 422) {
                        Log.e("WiFiPositioning", "Validation Error " + error.getMessage());
                    } else {
                        if (error.networkResponse != null) {
                            Log.e("WiFiPositioning", "Response Code: " + error.networkResponse.statusCode + ", " + error.getMessage());
                        } else {
                            Log.e("WiFiPositioning", "Error message: " + error.getMessage());
                        }
                    }
                }
        );
        requestQueue.add(jsonObjectRequest);
    }

    /**
     * 发起 WiFi 定位请求，并通过回调返回定位信息
     *
     * @param jsonWifiFeatures WiFi 指纹 JSON 数据
     * @param callback         回调接口
     */
    public void request(JSONObject jsonWifiFeatures, final VolleyCallback callback) {
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST, url, jsonWifiFeatures,
                response -> {
                    try {
                        Log.d("jsonObject", response.toString());
                        wifiLocation = new LatLng(response.getDouble("lat"), response.getDouble("lon"));
                        floor = response.getInt("floor");
                        callback.onSuccess(wifiLocation, floor);
                    } catch (JSONException e) {
                        Log.e("jsonErrors", "Error parsing response: " + e.getMessage() + " " + response);
                        callback.onError("Error parsing response: " + e.getMessage());
                    }
                },
                error -> {
                    if (error.networkResponse != null && error.networkResponse.statusCode == 422) {
                        Log.e("WiFiPositioning", "Validation Error " + error.getMessage());
                        callback.onError("Validation Error (422): " + error.getMessage());
                    } else {
                        if (error.networkResponse != null) {
                            Log.e("WiFiPositioning", "Response Code: " + error.networkResponse.statusCode + ", " + error.getMessage());
                            callback.onError("Response Code: " + error.networkResponse.statusCode + ", " + error.getMessage());
                        } else {
                            Log.e("WiFiPositioning", "Error message: " + error.getMessage());
                            callback.onError("Error message: " + error.getMessage());
                        }
                    }
                }
        );
        requestQueue.add(jsonObjectRequest);
    }

    public interface VolleyCallback {
        void onSuccess(LatLng location, int floor);
        void onError(String message);
    }

    // ------------- 以下为地图轨迹和楼层图片覆盖相关代码 -------------

    /**
     * 更新用户位置：当新的经纬度数据及楼层到来时调用。
     * 若当前定位楼层与正在显示的楼层一致，则将新位置加入轨迹，并更新当前位置标记。
     *
     * @param newPosition 新位置
     * @param floor       用户当前楼层
     */
    public void updatePosition(LatLng newPosition, int floor) {
        // 只有在当前楼层匹配时，才更新轨迹（否则可选择清空或另行处理）
        if (floor == currentFloor) {
            trajectoryPoints.add(newPosition);
            trajectoryPolyline.setPoints(trajectoryPoints);
        }
        // 更新或创建当前位置 Marker（设置 zIndex 高于轨迹）
        if (currentLocationMarker == null) {
            MarkerOptions markerOptions = new MarkerOptions()
                    .position(newPosition)
                    .zIndex(4);
            currentLocationMarker = mMap.addMarker(markerOptions);
        } else {
            currentLocationMarker.setPosition(newPosition);
        }
    }

    /**
     * 根据传入楼层号更新地图上的楼层图片覆盖层
     * 楼层图片文件保存在 drawable 中，命名示例：floor_0, floor_1, floor_2, floor_3
     *
     * @param floor 目标楼层
     */
    public void updateFloorOverlay(int floor) {
        // 如果当前显示楼层已为目标楼层，则不做处理
        if (floor == currentFloor && floorOverlay != null) return;
        currentFloor = floor;
        // 清理轨迹（可选：切换楼层时清除轨迹数据）
        clearTrajectory();

        if (floorOverlay != null) {
            floorOverlay.remove();
        }

        int drawableResId;
        switch (floor) {
            case 0:
                drawableResId = R.drawable.nucleusground;
                break;
            case 1:
                drawableResId = R.drawable.nucleus1;
                break;
            case 2:
                drawableResId = R.drawable.nucleus2;
                break;
            case 3:
                drawableResId = R.drawable.nucleus3;
                break;
            default:
                drawableResId = R.drawable.nucleusground;
        }

        // 创建 GroundOverlayOptions，将楼层图片覆盖到指定矩形区域内，zIndex 设为 2（低于轨迹和标记）
        GroundOverlayOptions overlayOptions = new GroundOverlayOptions()
                .image(BitmapDescriptorFactory.fromResource(drawableResId))
                .positionFromBounds(overlayBounds)
                .zIndex(2);
        floorOverlay = mMap.addGroundOverlay(overlayOptions);
    }

    /**
     * 清空当前轨迹
     */
    public void clearTrajectory() {
        trajectoryPoints.clear();
        trajectoryPolyline.setPoints(trajectoryPoints);
    }

    /**
     * 获取当前显示的楼层号
     *
     * @return 当前楼层
     */
    public int getCurrentFloor() {
        return currentFloor;
    }
}
