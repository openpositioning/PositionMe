package com.openpositioning.PositionMe;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

public class ReplayFragment extends Fragment implements OnMapReadyCallback {
    private GoogleMap gMap;
    private Polyline trajectoryLine;
    private Marker positionMarker;
    private IndoorMapManager indoorMapManager;
    private LatLng currentLocation;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_replay, container, false);
        
        // 初始化地图
        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager()
                .findFragmentById(R.id.replayMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
        
        return view;
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        gMap = googleMap;
        
        // 初始化室内地图管理器
        indoorMapManager = new IndoorMapManager(gMap);
        
        // 设置地图类型
        gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        
        // 启用地图控件
        gMap.getUiSettings().setCompassEnabled(true);
        gMap.getUiSettings().setZoomControlsEnabled(true);
        
        // 显示可用的室内地图区域
        indoorMapManager.setIndicationOfIndoorMap();
        
        // 初始化轨迹线
        trajectoryLine = gMap.addPolyline(new PolylineOptions()
                .zIndex(1000f));  // 确保轨迹显示在最上层
    }

    /**
     * 更新当前位置和轨迹
     * @param location 新的位置
     */
    public void updatePosition(LatLng location) {
        if (location == null || indoorMapManager == null) return;
        
        currentLocation = location;
        
        // 更新室内地图
        indoorMapManager.setCurrentLocation(location);
        
        // 更新位置标记
        if (positionMarker != null) {
            positionMarker.setPosition(location);
            positionMarker.setZIndex(1000f);  // 确保标记显示在最上层
        }
    }

    /**
     * 更新当前楼层
     * @param floor 楼层数
     */
    public void updateFloor(int floor) {
        if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
            indoorMapManager.setCurrentFloor(floor, true);
        }
    }
}
