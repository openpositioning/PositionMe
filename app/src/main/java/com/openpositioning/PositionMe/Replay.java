package com.openpositioning.PositionMe;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.gson.*;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.ServerCommunications;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Replay implements OnMapReadyCallback {

    private Context context;
    private GoogleMap googleMap;
    private List<double[]> trajectoryCoordinates;
    private Handler playbackHandler;
    private Runnable playbackRunnable;


    private int playbackIndex = 0;
    private boolean isPlaying = false;
    private Polyline trajectoryPolyline; // 轨迹线

    private TrajectoryPlayer trajectoryPlayer;
    private static final int POINT_INTERVAL = 1;

    public Replay(Context context, String trajectoryJson) {
        this.context = context;
        this.trajectoryCoordinates = new ArrayList<>();
        this.playbackHandler = new Handler(Looper.getMainLooper());
        processTrajectoryJson(trajectoryJson);
    }

    private void processTrajectoryJson(String trajectoryJson) {
        JsonParser parser = new JsonParser();
        JsonObject jsonObject = parser.parse(trajectoryJson).getAsJsonObject();

        // 解析 PDR 数据
        JsonArray pdrArray = jsonObject.getAsJsonArray("pdr_samples");
        if (pdrArray != null) {
            for (JsonElement element : pdrArray) {
                JsonObject pdrSample = element.getAsJsonObject();
                long timestamp = pdrSample.get("relative_timestamp").getAsLong();
                float x = pdrSample.get("x").getAsFloat();
                float y = pdrSample.get("y").getAsFloat();
                System.out.println("PDR Sample - Timestamp: " + timestamp + ", X: " + x + ", Y: " + y);
            }
        }

        // 解析 GNSS 数据
        JsonArray gnssArray = jsonObject.getAsJsonArray("gnss_samples");
        if (gnssArray != null && gnssArray.size() > 0) {
            JsonObject firstGnssSample = gnssArray.get(0).getAsJsonObject();
            double baseLat = firstGnssSample.get("latitude").getAsDouble();
            double baseLng = firstGnssSample.get("longitude").getAsDouble();

            for (JsonElement element : gnssArray) {
                JsonObject gnssSample = element.getAsJsonObject();
                long timestamp = gnssSample.get("relative_timestamp").getAsLong();
                double latitude = gnssSample.get("latitude").getAsDouble();
                double longitude = gnssSample.get("longitude").getAsDouble();
                double altitude = gnssSample.get("altitude").getAsDouble();
                float accuracy = gnssSample.get("accuracy").getAsFloat();
                float speed = gnssSample.get("speed").getAsFloat();
                System.out.println("GNSS Sample - Timestamp: " + timestamp
                        + ", Latitude: " + latitude + ", Longitude: " + longitude
                        + ", Altitude: " + altitude + ", Accuracy: " + accuracy + ", Speed: " + speed);
            }

            // Convert PDR coordinates to LatLng
            for (JsonElement element : pdrArray) {
                JsonObject pdrSample = element.getAsJsonObject();
                float x = pdrSample.get("x").getAsFloat();
                float y = pdrSample.get("y").getAsFloat();
                double[] latLng = convertToLatLng(baseLat, baseLng, x, y);
                trajectoryCoordinates.add(latLng);
            }
        }

        // 打印 trajectoryCoordinates 到日志
        for (double[] coordinate : trajectoryCoordinates) {
            Log.d("Replay", "Coordinate - Latitude: " + coordinate[0] + ", Longitude: " + coordinate[1]);
        }
    }

    private double[] convertToLatLng(double baseLat, double baseLng, double x, double y) {
        double latitudeOffset = y * 1e-8;
        double longitudeOffset = x * 1e-8/ Math.cos(Math.toRadians(baseLat));
        return new double[]{baseLat + latitudeOffset, baseLng + longitudeOffset};
    }

    @Override
    public void onMapReady(GoogleMap map) {
        this.googleMap = map;
        if (trajectoryCoordinates.isEmpty()) {
            Toast.makeText(context, "没有轨迹数据展示", Toast.LENGTH_SHORT).show();
            return;
        }
        trajectoryPlayer = new TrajectoryPlayer(googleMap);
    }

    public void play() {
        if (googleMap == null || trajectoryCoordinates.isEmpty()) return;

        isPlaying = true;

        // 如果是重新开始播放，清空地图
        if (playbackIndex == 0) {
            googleMap.clear();
            trajectoryPolyline = googleMap.addPolyline(new PolylineOptions().color(Color.BLUE).width(1));
        }

        playbackHandler.post(playbackRunnable = new Runnable() {
            @Override
            public void run() {
                if (playbackIndex < trajectoryCoordinates.size()) {
                    double[] point = trajectoryCoordinates.get(playbackIndex);
                    LatLng latLng = new LatLng(point[0], point[1]);

                    // 绘制圆形蓝色点
                    googleMap.addCircle(new CircleOptions()
                            .center(latLng)
                            .radius(0.02) // 半径（单位：米）
                            .fillColor(Color.BLUE) // 填充颜色
                            .strokeColor(Color.BLUE) // 边框颜色
                            .strokeWidth(5f)); // 边框宽度

                    // 更新轨迹线
                    if (trajectoryPolyline != null) {
                        List<LatLng> points = new ArrayList<>(trajectoryPolyline.getPoints());
                        points.add(latLng);
                        trajectoryPolyline.setPoints(points);
                    }

                    // 移动相机跟随轨迹
                    googleMap.moveCamera(CameraUpdateFactory.newLatLng(latLng));

                    playbackIndex++;
                    playbackHandler.postDelayed(this, 1);
                }
            }
        });
    }

    public void pause() {
        isPlaying = false;
        playbackHandler.removeCallbacks(playbackRunnable);
    }

    public void replay() {
        pause();
        playbackIndex = 0; // 重新开始
        googleMap.clear(); // 清空地图
        trajectoryPolyline = null; // 清除旧轨迹
        play();
    }

    public void displayFullTrajectory() {
        if (googleMap == null || trajectoryCoordinates.isEmpty()) return;

        googleMap.clear();
        PolylineOptions polylineOptions = new PolylineOptions().color(Color.BLUE).width(5);

        for (double[] coordinate : trajectoryCoordinates) {
            polylineOptions.add(new LatLng(coordinate[0], coordinate[1]));
        }

        trajectoryPolyline = googleMap.addPolyline(polylineOptions);
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(trajectoryCoordinates.get(0)[0], trajectoryCoordinates.get(0)[1]), 22));
    }
    // 获取总时长，单位为毫秒
    public int getTotalDuration() {
        return trajectoryCoordinates.size() * POINT_INTERVAL;
    }

    // 获取当前进度，单位为毫秒
    public int getCurrentProgress() {
        return playbackIndex * POINT_INTERVAL;
    }

}


