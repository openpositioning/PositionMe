package com.openpositioning.PositionMe.fragments;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;
import com.openpositioning.PositionMe.sensors.WiFiPositioning.VolleyCallback;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class WiFiPositioningFragment extends Fragment implements OnMapReadyCallback {

    private GoogleMap googleMap;
    private Marker currentMarker;
    // 使用 IndoorMapManager 管理室内地图叠加
    private IndoorMapManager indoorMapManager;
    private Switch autoFloorSwitch;
    // 楼层上移与下移按钮，以及返回按钮
    private View floorUpButton, floorDownButton;
    private Button returnButton;
    // 自动楼层开关状态
    private boolean isAutoFloor = true;
    // WiFi 定位对象（调用 API 获取定位数据）
    private WiFiPositioning wifiPositioning;
    // 用于绘制轨迹的变量
    private Polyline trajectoryPolyline;
    private List<LatLng> trajectoryPoints = new ArrayList<>();
    // 定时器，用于 SensorFusion 定时更新定位数据
    private Timer sensorFusionTimer;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_wifi_positioning, container, false);

        // 获取布局中的控件
        autoFloorSwitch = view.findViewById(R.id.autoFloorSwitch);
        floorUpButton = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        returnButton = view.findViewById(R.id.returnButton);

        // 初始化自动楼层状态
        autoFloorSwitch.setChecked(true);
        autoFloorSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> isAutoFloor = isChecked);

        // 手动切换楼层：点击上/下按钮时调用 IndoorMapManager 的相应方法，并关闭自动模式
        floorUpButton.setOnClickListener(v -> {
            if (isAutoFloor) {
                autoFloorSwitch.setChecked(false);
            }
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
            }
        });

        floorDownButton.setOnClickListener(v -> {
            if (isAutoFloor) {
                autoFloorSwitch.setChecked(false);
            }
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
            }
        });

        // 返回按钮，点击后返回上一页面
        returnButton.setOnClickListener(v -> Navigation.findNavController(v).popBackStack());

        // 初始化 WiFi 定位对象，并发起定位请求
        wifiPositioning = new WiFiPositioning(getContext());
        JSONObject jsonWifiFeatures = new JSONObject();
        wifiPositioning.request(jsonWifiFeatures, new VolleyCallback() {
            @Override
            public void onSuccess(LatLng location, int floor) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> updateLocation(location, floor));
            }

            @Override
            public void onError(String message) {
                // 处理错误，例如显示 Toast 或日志记录
            }
        });

        // 获取地图对象（通过 SupportMapFragment）
        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.wifiMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 通过 SensorFusion 定时获取最新定位数据，每秒更新一次
        sensorFusionTimer = new Timer();
        sensorFusionTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                LatLng fusionLocation = SensorFusion.getInstance().getLatLngWifiPositioning();
                int fusionFloor = SensorFusion.getInstance().getWifiFloor();
                if (fusionLocation != null) {
                    getActivity().runOnUiThread(() -> updateLocation(fusionLocation, fusionFloor));
                }
            }
        }, 0, 1000);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (sensorFusionTimer != null) {
            sensorFusionTimer.cancel();
            sensorFusionTimer = null;
        }
    }

    /**
     * 地图准备就绪后初始化地图设置、轨迹 Polyline 及 IndoorMapManager
     */
    @Override
    public void onMapReady(GoogleMap map) {
        googleMap = map;
        // 初始化轨迹 Polyline（宽度为 5，蓝色）
        PolylineOptions polylineOptions = new PolylineOptions().width(5).color(Color.BLUE);
        trajectoryPolyline = googleMap.addPolyline(polylineOptions);
        // 初始化 IndoorMapManager，并可选设置建筑边界指示
        indoorMapManager = new IndoorMapManager(googleMap);
        indoorMapManager.setIndicationOfIndoorMap();
    }

    /**
     * 公共方法：更新定位点、绘制轨迹并更新室内地图（楼层图）
     *
     * @param location 新的定位点
     * @param floor    WiFi 定位回传的楼层
     */
    private void updateLocation(LatLng location, int floor) {
        if (googleMap == null || location == null) return;
        // 如果是第一个定位点，则移动相机到该位置
        if (trajectoryPoints.isEmpty()) {
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(location, 15));
        }
        // 更新 marker：如果已存在则更新位置，否则添加新 marker
        if (currentMarker != null) {
            currentMarker.setPosition(location);
        } else {
            currentMarker = googleMap.addMarker(new MarkerOptions().position(location).title("Current Position"));
        }
        // 添加定位点到轨迹列表，并更新 Polyline
        trajectoryPoints.add(location);
        if (trajectoryPolyline != null) {
            trajectoryPolyline.setPoints(trajectoryPoints);
        }
        // 更新室内地图 overlay：根据新的定位点判断是否在建筑内，并设置或移除叠加层
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(location);
            // 如果自动楼层模式开启，则更新楼层图（此处 autoFloor 参数为 true）
            if (isAutoFloor) {
                indoorMapManager.setCurrentFloor(floor, true);
            }
        }
    }
}
