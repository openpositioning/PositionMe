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
    private ImageButton playButton, fastRewind, fastForward;
    private TextView progressText;
    private String filePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_replay);

//        // 获取 Intent 传递的数据
//        Intent intent = getIntent();
//        String fileName = intent.getStringExtra("fileName");  // 获取文件名
//        String filePath = intent.getStringExtra("filePath");  // 获取文件路径
//
//        // 例如，在 TextView 上显示文件名和路径
//        TextView fileNameTextView = findViewById(R.id.textView);
//        TextView filePathTextView = findViewById(R.id.textView2);
//
//        fileNameTextView.setText("文件名: " + fileName);
//        filePathTextView.setText("文件路径: " + filePath);

        // 获取 Intent 传递的文件路径
        filePath = getIntent().getStringExtra("filePath");

        // 初始化 UI 组件
        seekBar = findViewById(R.id.seekBar);
        playButton = findViewById(R.id.playPauseButton);
        fastRewind = findViewById(R.id.fastRewindButton);
        fastForward = findViewById(R.id.fastForwardButton);
        progressText = findViewById(R.id.currentTime);



        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        // 读取轨迹数据
        Traj.Trajectory trajectory = readTrajectoryFromFile(this, filePath);
        if (trajectory != null) {
            trackPoints = convertTrajectoryToLatLng(trajectory);
            seekBar.setMax(trackPoints.size());
        } else {
            Log.e(TAG, "轨迹文件解析失败！");
        }

        // 按钮事件
        playButton.setOnClickListener(v -> startPlayback());
        fastRewind.setOnClickListener(v -> pausePlayback());
        fastForward.setOnClickListener(v -> fastForward());

        // 进度条拖动监听
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentIndex = progress;
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

    // 轨迹转换方法
    private List<LatLng> convertTrajectoryToLatLng(Traj.Trajectory trajectory) {
        List<LatLng> points = new ArrayList<>();

        for (Traj.GNSS_Sample gnssSample : trajectory.getGnssDataList()) {
            double latitude = gnssSample.getLatitude();
            double longitude = gnssSample.getLongitude();
            points.add(new LatLng(latitude, longitude));
        }

        return points;
    }



    private void drawTrack() {
        if (mMap != null && !trackPoints.isEmpty()) {
            PolylineOptions polylineOptions = new PolylineOptions()
                    .addAll(trackPoints)
                    .width(10)
                    .color(0xFF0000FF) // 蓝色轨迹
                    .geodesic(true);
            polyline = mMap.addPolyline(polylineOptions);

            // 添加起始点标记
            currentMarker = mMap.addMarker(new MarkerOptions().position(trackPoints.get(0)).title("起点"));
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(trackPoints.get(0), 15));
        }
    }


    private void startPlayback() {
        if (trackPoints.isEmpty()) return;
        isPlaying = true;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currentIndex < trackPoints.size() && isPlaying) {
                    updateMapPosition();
                    seekBar.setProgress(currentIndex);
                    currentIndex++;
                    handler.postDelayed(this, 1000); // 1秒播放一个点
                }
            }
        }, 1000);
    }

    private void pausePlayback() {
        isPlaying = false;
    }

    private void fastForward() {
        if (currentIndex + 5 < trackPoints.size()) {
            currentIndex += 5;
            updateMapPosition();
            seekBar.setProgress(currentIndex);
        }
    }

    private void updateMapPosition() {
        if (mMap != null && currentIndex < trackPoints.size()) {
            LatLng point = trackPoints.get(currentIndex);
            mMap.moveCamera(CameraUpdateFactory.newLatLng(point));
            if (currentMarker != null) currentMarker.setPosition(point);

            // Avoid crash if progressText is null
            if (progressText != null) {
                progressText.setText("进度：" + (currentIndex + 1) + "/" + trackPoints.size());
            } else {
                Log.e(TAG, "progressText is null! Make sure it is properly initialized in onCreate().");
            }
        }
    }



}


