package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.Traj.Trajectory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

public class ReplayFragment extends Fragment implements OnMapReadyCallback {

    private MapView mapView;
    private GoogleMap mMap;
    private Button btnPlayPause, btnRestart, btnGoToEnd, btnExit;
    private ProgressBar progressBar;

    // 播放控制
    private boolean isPlaying = false;
    private int currentIndex = 0;
    private Handler playbackHandler = new Handler(Looper.getMainLooper());
    private Runnable playbackRunnable;

    // 轨迹数据（采用 GNSS 数据）
    private Traj.Trajectory trajectory;
    // 修改这里，使用 GNSS 数据列表
    private List<com.openpositioning.PositionMe.Traj.GNSS_Sample> positions;
    private Polyline trajectoryPolyline;
    private Marker currentMarker;

    // 文件路径从 Bundle 中获取
    private String filePath;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_replay, container, false);
        mapView = view.findViewById(R.id.mapView);
        btnPlayPause = view.findViewById(R.id.btnPlayPause);
        btnRestart = view.findViewById(R.id.btnRestart);
        btnGoToEnd = view.findViewById(R.id.btnGoToEnd);
        btnExit = view.findViewById(R.id.btnExit);
        progressBar = view.findViewById(R.id.progressBar);

        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 从 Bundle 获取临时文件路径
        if (getArguments() != null) {
            filePath = getArguments().getString("trajectory_file_path");
        }
        if (filePath == null) {
            Toast.makeText(getContext(), "No trajectory file provided", Toast.LENGTH_SHORT).show();
            return;
        }
        // 读取并解析文件数据到 Traj.Trajectory 对象
        try {
            File file = new File(filePath);
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();
            trajectory = Traj.Trajectory.parseFrom(data);
            // 使用 GNSS 数据列表（用于经纬度信息）
            positions = trajectory.getGnssDataList();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Failed to load trajectory data", Toast.LENGTH_SHORT).show();
        }

        // 初始化按钮点击事件
        btnPlayPause.setOnClickListener(v -> {
            if (isPlaying) {
                pauseReplay();
            } else {
                startReplay();
            }
        });

        btnRestart.setOnClickListener(v -> restartReplay());
        btnGoToEnd.setOnClickListener(v -> goToEndReplay());
        btnExit.setOnClickListener(v -> exitReplay());

        // 设置进度条最大值为轨迹点数量
        if (positions != null && !positions.isEmpty()) {
            progressBar.setMax(positions.size());
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        if (positions != null && !positions.isEmpty()) {
            PolylineOptions polylineOptions = new PolylineOptions();
            // 遍历 GNSS 数据点
            for (Traj.GNSS_Sample pos : positions) {
                LatLng latLng = new LatLng(pos.getLatitude(), pos.getLongitude());
                polylineOptions.add(latLng);
            }
            trajectoryPolyline = mMap.addPolyline(polylineOptions);
            // 将摄像头移动到起点
            LatLng startPos = new LatLng(positions.get(0).getLatitude(), positions.get(0).getLongitude());
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startPos, 18f));
            // 添加初始标记
            currentMarker = mMap.addMarker(new MarkerOptions().position(startPos).title("Current Position"));
        }
    }

    // 开始回放：每隔一定时间更新当前播放位置
    private void startReplay() {
        if (positions == null || positions.isEmpty()) return;
        isPlaying = true;
        btnPlayPause.setText("Pause");

        playbackRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentIndex < positions.size()) {
                    Traj.GNSS_Sample pos = positions.get(currentIndex);
                    LatLng latLng = new LatLng(pos.getLatitude(), pos.getLongitude());
                    // 更新标记位置
                    if (currentMarker != null) {
                        currentMarker.setPosition(latLng);
                    } else {
                        currentMarker = mMap.addMarker(new MarkerOptions().position(latLng).title("Current Position"));
                    }
                    // 更新摄像头
                    mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng));
                    // 更新进度条
                    progressBar.setProgress(currentIndex);
                    currentIndex++;
                    playbackHandler.postDelayed(this, 500);  // 每500ms更新一次
                } else {
                    // 播放结束后暂停
                    pauseReplay();
                }
            }
        };
        playbackHandler.post(playbackRunnable);
    }

    // 暂停回放
    private void pauseReplay() {
        isPlaying = false;
        btnPlayPause.setText("Play");
        if (playbackRunnable != null) {
            playbackHandler.removeCallbacks(playbackRunnable);
        }
    }

    // 重启回放：从头开始播放
    private void restartReplay() {
        pauseReplay();
        currentIndex = 0;
        progressBar.setProgress(0);
        startReplay();
    }

    // 快进到末尾：直接显示最后一个位置
    private void goToEndReplay() {
        pauseReplay();
        if (positions != null && !positions.isEmpty()) {
            currentIndex = positions.size() - 1;
            Traj.GNSS_Sample pos = positions.get(currentIndex);
            LatLng latLng = new LatLng(pos.getLatitude(), pos.getLongitude());
            if (currentMarker != null) {
                currentMarker.setPosition(latLng);
            } else {
                currentMarker = mMap.addMarker(new MarkerOptions().position(latLng).title("Current Position"));
            }
            mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng));
            progressBar.setProgress(currentIndex);
        }
    }

    // 退出回放：返回上一级界面
    private void exitReplay() {
        pauseReplay();
        getActivity().onBackPressed();
    }

    // MapView 生命周期方法
    @Override
    public void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mapView.onDestroy();
        // 播放结束后删除临时文件以节约存储空间
        if (getArguments() != null) {
            String path = getArguments().getString("trajectory_file_path");
            if (path != null) {
                File file = new File(path);
                if (file.exists()) {
                    file.delete();
                }
            }
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }
}
