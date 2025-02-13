package com.openpositioning.PositionMe;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.List;

public class TrajectoryPlayer {
    private GoogleMap map;
    private Polyline polyline;

    public TrajectoryPlayer(GoogleMap map) {
        this.map = map;
    }

    public void playTrajectory(List<Coordinate> coordinateList) {
        // 将坐标信息转换为 LatLng 列表
        List<LatLng> latLngList = new ArrayList<>();
        for (Coordinate coordinate : coordinateList) {
            LatLng latLng = new LatLng(coordinate.latitude, coordinate.longitude);
            latLngList.add(latLng);
        }

        // 创建 PolylineOptions 对象并设置相关属性
        PolylineOptions polylineOptions = new PolylineOptions()
                .color(android.graphics.Color.BLUE)
                .width(5f);

        // 将 LatLng 列表添加到 PolylineOptions 中
        polylineOptions.addAll(latLngList);

        // 在地图上添加 Polyline
        polyline = map.addPolyline(polylineOptions);
    }

    public static class Coordinate {
        public double latitude;
        public double longitude;

        public Coordinate(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}