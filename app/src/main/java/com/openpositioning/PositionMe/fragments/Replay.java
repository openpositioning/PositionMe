package com.openpositioning.PositionMe.fragments;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.ar.core.Point;
import com.google.protobuf.InvalidProtocolBufferException;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.IndoorMapManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Replay extends AppCompatActivity implements OnMapReadyCallback {

    private GoogleMap mMap;
    private List<LatLng> trackPoints = new ArrayList<>();
    private Polyline polyline;
    private Marker currentMarker;
    private int currentIndex = 0;
    private boolean isPlaying = false;
    private Handler handler = new Handler();

    private SeekBar seekBar;
    private ImageButton playButton, fastRewind, fastForward, gotoStartButton, gotoEndButon;
    private TextView progressText,totaltimetext;
    private String filePath;

    private int totalDuration = 0; // 轨迹总时长（ms）
    private int playbackSpeed = 300; // 每个点的播放间隔（ms）
    private int currentTime = 0; // 当前回放时间（ms）

    // 用于室内地图显示的管理器
    private IndoorMapManager indoorMapManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_replay);


        // 获取 Intent 传递的文件路径
        filePath = getIntent().getStringExtra("filePath");

        // 初始化 UI 组件
        seekBar = findViewById(R.id.seekBar);
        playButton = findViewById(R.id.playPauseButton);
        fastRewind = findViewById(R.id.fastRewindButton);
        fastForward = findViewById(R.id.fastForwardButton);
        gotoStartButton = findViewById(R.id.goToStartButton);
        gotoEndButon = findViewById(R.id.goToEndButton);
        progressText = findViewById(R.id.currentTime);
        totaltimetext = findViewById(R.id.totalTime);



        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        // 读取轨迹数据
        Traj.Trajectory trajectory = readTrajectoryFromFile(this, filePath);
        if (trajectory != null) {
            trackPoints = convertTrajectoryToLatLng(trajectory);
            totalDuration = trackPoints.size() * playbackSpeed;
            seekBar.setMax(totalDuration);
        } else {
            Log.e(TAG, "轨迹文件解析失败！");
        }

        // 按钮事件
        playButton.setOnClickListener(v -> {
            if (isPlaying) {
                pausePlayback();
            } else {
                startPlayback();
            }
        });
        fastRewind.setOnClickListener(v -> fastRewind());
        fastForward.setOnClickListener(v -> fastForward());
        gotoStartButton.setOnClickListener(v -> gotoStart());
        gotoEndButon.setOnClickListener(v -> gotoEnd());

        // 进度条拖动监听
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentTime = progress;
                    currentIndex = currentTime / playbackSpeed;
                    if (currentIndex >= trackPoints.size()) {
                        currentIndex = trackPoints.size() - 1;
                    }
                    updateMapPosition();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });



        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }


    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        // 初始化室内地图管理器（IndoorMapManager），将在播放过程中根据当前位置显示室内地图覆盖层
        indoorMapManager = new IndoorMapManager(mMap);
        drawTrack();
    }

    // 解析 Protobuf 轨迹文件
    public static Traj.Trajectory readTrajectoryFromFile(Context context, String filePath) {
//        File file = new File(context.getFilesDir(), filePath);

        File file = new File(filePath);
        if (!file.exists()) {
            Log.e(TAG, "文件不存在");
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return Traj.Trajectory.parseFrom(data);
        } catch (InvalidProtocolBufferException e) {
            Log.e(TAG, "Protobuf 解析失败", e);
        } catch (IOException e) {
            Log.e(TAG, "文件读取失败", e);
        }
        return null;
    }

    // Method to convert trajectory to latitude and longitude
    private List<LatLng> convertTrajectoryToLatLng(Traj.Trajectory trajectory) {
        List<LatLng> points = new ArrayList<>();

        // Earth's radius in meters
        double R = 6378137;

        // Initial latitude and longitude
        double lat0 = 0;
        double lon0 = 0;

        if (!trajectory.getGnssDataList().isEmpty()) {
            Traj.GNSS_Sample firstGnss = trajectory.getGnssDataList().get(0);
            lat0 = firstGnss.getLatitude();
            lon0 = firstGnss.getLongitude();
        }

        for (Traj.Pdr_Sample PdrSample : trajectory.getPdrDataList()) {
            double trackX = PdrSample.getX();  // Forward displacement (meters)
            double trackY = PdrSample.getY();  // Side displacement (meters)

            // Fix coordinate transformation
            double dLat = trackY / R;  // Latitude should be affected by Y displacement
            double dLon = trackX / (R * Math.cos(Math.toRadians(lat0)));  // Longitude should be affected by X displacement

            // Calculate new latitude and longitude
            double lat = lat0 + Math.toDegrees(dLat);
            double lon = lon0 + Math.toDegrees(dLon);

            points.add(new LatLng(lat, lon));
        }

        return points;
    }

    private void drawTrack() {
        if (mMap != null && !trackPoints.isEmpty()) {
            PolylineOptions polylineOptions = new PolylineOptions()
                    .addAll(trackPoints)
                    .width(10)
                    .color(0xFFFF00FF) // 蓝色轨迹
                    .geodesic(true);
            polyline = mMap.addPolyline(polylineOptions);

            // 添加起始点标记
            currentMarker = mMap.addMarker(new MarkerOptions().position(trackPoints.get(0)).title("起点"));
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(trackPoints.get(0), 20));
        }
    }


    // 开始播放——基于时间累加控制进度
    private void startPlayback() {
        if (trackPoints.isEmpty()) return;
        isPlaying = true;
        playButton.setImageResource(R.drawable.baseline_pause_24);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isPlaying && currentTime < totalDuration) {
                    currentIndex = currentTime / playbackSpeed;
                    if (currentIndex >= trackPoints.size()) {
                        currentIndex = trackPoints.size() - 1;
                    }
                    updateMapPosition();
                    seekBar.setProgress(currentTime);
                    currentTime += playbackSpeed;
                    handler.postDelayed(this, playbackSpeed);
                } else {
                    isPlaying = false;
                    playButton.setImageResource(R.drawable.baseline_play_arrow_24);
                }
            }
        }, playbackSpeed);
    }

    private void pausePlayback() {
        isPlaying = false;
        handler.removeCallbacksAndMessages(null);
        playButton.setImageResource(R.drawable.baseline_play_arrow_24);
    }

    // 快进5秒
    private void fastForward() {
        int jumpTime = 5000;
        currentTime = Math.min(currentTime + jumpTime, totalDuration);
        currentIndex = currentTime / playbackSpeed;
        if (currentIndex >= trackPoints.size()) {
            currentIndex = trackPoints.size() - 1;
        }
        updateMapPosition();
        seekBar.setProgress(currentTime);
    }

    // 快退5秒
    private void fastRewind() {
        int jumpTime = 5000;
        currentTime = Math.max(currentTime - jumpTime, 0);
        currentIndex = currentTime / playbackSpeed;
        updateMapPosition();
        seekBar.setProgress(currentTime);
    }

    // 跳转到开始位置
    private void gotoStart() {
        currentTime = 0;
        currentIndex = 0;
        updateMapPosition();
        seekBar.setProgress(currentTime);
    }

    // 跳转到结束位置
    private void gotoEnd() {
        currentTime = totalDuration;
        currentIndex = trackPoints.size() - 1;
        updateMapPosition();
        seekBar.setProgress(currentTime);
    }


    private void updateMapPosition() {
        if (mMap != null && currentIndex < trackPoints.size()) {
            LatLng point = trackPoints.get(currentIndex);
            mMap.moveCamera(CameraUpdateFactory.newLatLng(point));
            if (currentMarker != null) {
                currentMarker.setPosition(point);
            }
            // Modified: 更新室内地图显示，将当前回放位置传给 IndoorMapManager
            if (indoorMapManager != null) {
                indoorMapManager.setCurrentLocation(point);
            }
            // 格式化时间显示（mm:ss / mm:ss）
            int seconds = currentTime / 1000;
            int minutes = seconds / 60;
            seconds = seconds % 60;
            int totalSeconds = totalDuration / 1000;
            int totalMinutes = totalSeconds / 60;
            totalSeconds = totalSeconds % 60;
            if (progressText != null) {
                progressText.setText(String.format("%02d:%02d", minutes, seconds));
                totaltimetext.setText(String.format("%02d:%02d",  totalMinutes, totalSeconds));
            }
        }
    }



}


