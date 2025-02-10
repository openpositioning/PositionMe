//package com.openpositioning.PositionMe.fragments;
//
//import android.os.Bundle;
//import android.os.Handler;
//import android.os.Looper;
//import android.view.LayoutInflater;
//import android.view.View;
//import android.view.ViewGroup;
//import android.widget.Button;
//import android.widget.ProgressBar;
//import android.widget.Toast;
//
//import androidx.annotation.NonNull;
//import androidx.annotation.Nullable;
//import androidx.fragment.app.Fragment;
//
//import com.google.android.gms.maps.CameraUpdateFactory;
//import com.google.android.gms.maps.GoogleMap;
//import com.google.android.gms.maps.MapView;
//import com.google.android.gms.maps.OnMapReadyCallback;
//import com.google.android.gms.maps.model.LatLng;
//import com.google.android.gms.maps.model.Marker;
//import com.google.android.gms.maps.model.MarkerOptions;
//import com.google.android.gms.maps.model.Polyline;
//import com.google.android.gms.maps.model.PolylineOptions;
//import com.openpositioning.PositionMe.R;
//import com.openpositioning.PositionMe.Traj;
//import com.openpositioning.PositionMe.Traj.Trajectory;
//
//import java.io.File;
//import java.io.FileInputStream;
//import java.io.IOException;
//import java.util.List;
//
//public class ReplayFragment extends Fragment implements OnMapReadyCallback {
//
//    private MapView mapView;
//    private GoogleMap mMap;
//    private Button btnPlayPause, btnRestart, btnGoToEnd, btnExit;
//    private ProgressBar progressBar;
//
//    // 播放控制
//    private boolean isPlaying = false;
//    private int currentIndex = 0;
//    private Handler playbackHandler = new Handler(Looper.getMainLooper());
//    private Runnable playbackRunnable;
//
//    // 轨迹数据（采用 GNSS 数据）
//    private Traj.Trajectory trajectory;
//    // 修改这里，使用 GNSS 数据列表
//    private List<com.openpositioning.PositionMe.Traj.GNSS_Sample> positions;
//    private Polyline trajectoryPolyline;
//    private Marker currentMarker;
//
//    // 文件路径从 Bundle 中获取
//    private String filePath;
//
//    @Override
//    public View onCreateView(LayoutInflater inflater, ViewGroup container,
//                             Bundle savedInstanceState) {
//        View view = inflater.inflate(R.layout.fragment_replay, container, false);
//        mapView = view.findViewById(R.id.mapView);
//        btnPlayPause = view.findViewById(R.id.btnPlayPause);
//        btnRestart = view.findViewById(R.id.btnRestart);
//        btnGoToEnd = view.findViewById(R.id.btnGoToEnd);
//        btnExit = view.findViewById(R.id.btnExit);
//        progressBar = view.findViewById(R.id.progressBar);
//
//        mapView.onCreate(savedInstanceState);
//        mapView.getMapAsync(this);
//        return view;
//    }
//
//    @Override
//    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
//        super.onViewCreated(view, savedInstanceState);
//
//        // 从 Bundle 获取临时文件路径
//        if (getArguments() != null) {
//            filePath = getArguments().getString("trajectory_file_path");
//        }
//        if (filePath == null) {
//            Toast.makeText(getContext(), "No trajectory file provided", Toast.LENGTH_SHORT).show();
//            return;
//        }
//        // 读取并解析文件数据到 Traj.Trajectory 对象
//        try {
//            File file = new File(filePath);
//            FileInputStream fis = new FileInputStream(file);
//            byte[] data = new byte[(int) file.length()];
//            fis.read(data);
//            fis.close();
//            trajectory = Traj.Trajectory.parseFrom(data);
//            // 使用 GNSS 数据列表（用于经纬度信息）
//            positions = trajectory.getGnssDataList();
//        } catch (IOException e) {
//            e.printStackTrace();
//            Toast.makeText(getContext(), "Failed to load trajectory data", Toast.LENGTH_SHORT).show();
//        }
//
//        // 初始化按钮点击事件
//        btnPlayPause.setOnClickListener(v -> {
//            if (isPlaying) {
//                pauseReplay();
//            } else {
//                startReplay();
//            }
//        });
//
//        btnRestart.setOnClickListener(v -> restartReplay());
//        btnGoToEnd.setOnClickListener(v -> goToEndReplay());
//        btnExit.setOnClickListener(v -> exitReplay());
//
//        // 设置进度条最大值为轨迹点数量
//        if (positions != null && !positions.isEmpty()) {
//            progressBar.setMax(positions.size());
//        }
//    }
//
//    @Override
//    public void onMapReady(GoogleMap googleMap) {
//        mMap = googleMap;
//        if (positions != null && !positions.isEmpty()) {
//            PolylineOptions polylineOptions = new PolylineOptions();
//            // 遍历 GNSS 数据点
//            for (Traj.GNSS_Sample pos : positions) {
//                LatLng latLng = new LatLng(pos.getLatitude(), pos.getLongitude());
//                polylineOptions.add(latLng);
//            }
//            trajectoryPolyline = mMap.addPolyline(polylineOptions);
//            // 将摄像头移动到起点
//            LatLng startPos = new LatLng(positions.get(0).getLatitude(), positions.get(0).getLongitude());
//            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startPos, 18f));
//            // 添加初始标记
//            currentMarker = mMap.addMarker(new MarkerOptions().position(startPos).title("Current Position"));
//        }
//    }
//
//    // 开始回放：每隔一定时间更新当前播放位置
//    private void startReplay() {
//        if (positions == null || positions.isEmpty()) return;
//        isPlaying = true;
//        btnPlayPause.setText("Pause");
//
//        playbackRunnable = new Runnable() {
//            @Override
//            public void run() {
//                if (currentIndex < positions.size()) {
//                    Traj.GNSS_Sample pos = positions.get(currentIndex);
//                    LatLng latLng = new LatLng(pos.getLatitude(), pos.getLongitude());
//                    // 更新标记位置
//                    if (currentMarker != null) {
//                        currentMarker.setPosition(latLng);
//                    } else {
//                        currentMarker = mMap.addMarker(new MarkerOptions().position(latLng).title("Current Position"));
//                    }
//                    // 更新摄像头
//                    mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng));
//                    // 更新进度条
//                    progressBar.setProgress(currentIndex);
//                    currentIndex++;
//                    playbackHandler.postDelayed(this, 500);  // 每500ms更新一次
//                } else {
//                    // 播放结束后暂停
//                    pauseReplay();
//                }
//            }
//        };
//        playbackHandler.post(playbackRunnable);
//    }
//
//    // 暂停回放
//    private void pauseReplay() {
//        isPlaying = false;
//        btnPlayPause.setText("Play");
//        if (playbackRunnable != null) {
//            playbackHandler.removeCallbacks(playbackRunnable);
//        }
//    }
//
//    // 重启回放：从头开始播放
//    private void restartReplay() {
//        pauseReplay();
//        currentIndex = 0;
//        progressBar.setProgress(0);
//        startReplay();
//    }
//
//    // 快进到末尾：直接显示最后一个位置
//    private void goToEndReplay() {
//        pauseReplay();
//        if (positions != null && !positions.isEmpty()) {
//            currentIndex = positions.size() - 1;
//            Traj.GNSS_Sample pos = positions.get(currentIndex);
//            LatLng latLng = new LatLng(pos.getLatitude(), pos.getLongitude());
//            if (currentMarker != null) {
//                currentMarker.setPosition(latLng);
//            } else {
//                currentMarker = mMap.addMarker(new MarkerOptions().position(latLng).title("Current Position"));
//            }
//            mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng));
//            progressBar.setProgress(currentIndex);
//        }
//    }
//
//    // 退出回放：返回上一级界面
//    private void exitReplay() {
//        pauseReplay();
//        getActivity().onBackPressed();
//    }
//
//    // MapView 生命周期方法
//    @Override
//    public void onResume() {
//        super.onResume();
//        mapView.onResume();
//    }
//
//    @Override
//    public void onPause() {
//        super.onPause();
//        mapView.onPause();
//    }
//
//    @Override
//    public void onDestroy() {
//        super.onDestroy();
//        mapView.onDestroy();
//        // 播放结束后删除临时文件以节约存储空间
//        if (getArguments() != null) {
//            String path = getArguments().getString("trajectory_file_path");
//            if (path != null) {
//                File file = new File(path);
//                if (file.exists()) {
//                    file.delete();
//                }
//            }
//        }
//    }
//
//    @Override
//    public void onLowMemory() {
//        super.onLowMemory();
//        mapView.onLowMemory();
//    }
//}
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
import com.openpositioning.PositionMe.UtilFunctions;

import android.graphics.Color;
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
    private int currentGnssIndex = 0;
    private int currentPdrIndex = 0;
    private Handler playbackHandler = new Handler(Looper.getMainLooper());
    private Runnable playbackRunnable;

    // 轨迹数据：这里分别使用 Traj.Trajectory 解析得到的 GNSS 和 PDR 数据
    private Traj.Trajectory trajectory;
    private List<Traj.GNSS_Sample> gnssPositions;
    private List<Traj.Pdr_Sample> pdrPositions;
    private Polyline gnssPolyline;
    private Polyline pdrPolyline;
    private Marker gnssMarker;
    private Marker pdrMarker;

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
            // 分别获取 GNSS 数据列表和 PDR 数据列表
            gnssPositions = trajectory.getGnssDataList();
            pdrPositions = trajectory.getPdrDataList();
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

        // 设置进度条的最大值为两种数据中较大者
        if ((gnssPositions != null && !gnssPositions.isEmpty()) ||
                (pdrPositions != null && !pdrPositions.isEmpty())) {
            int maxCount = Math.max(gnssPositions != null ? gnssPositions.size() : 0,
                    pdrPositions != null ? pdrPositions.size() : 0);
            progressBar.setMax(maxCount);
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        // 绘制 GNSS 轨迹（蓝色）
        if (gnssPositions != null && !gnssPositions.isEmpty()) {
            PolylineOptions gnssOptions = new PolylineOptions().color(Color.BLUE);
            for (Traj.GNSS_Sample sample : gnssPositions) {
                LatLng latLng = new LatLng(sample.getLatitude(), sample.getLongitude());
                gnssOptions.add(latLng);
            }
            gnssPolyline = mMap.addPolyline(gnssOptions);
            // 将摄像头移动到 GNSS 轨迹起点
            LatLng gnssStart = new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude());
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(gnssStart, 18f));
            // 创建 GNSS 动态标记
            gnssMarker = mMap.addMarker(new MarkerOptions().position(gnssStart).title("GNSS Position")
                    .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_BLUE)));
        }
        // 绘制 PDR 轨迹（红色）
        if (pdrPositions != null && !pdrPositions.isEmpty()) {
            // 假设 PDR 数据的起点采用 GNSS 轨迹的起点（如果有的话）
            LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty())
                    ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude())
                    : new LatLng(0, 0);
            PolylineOptions pdrOptions = new PolylineOptions().color(Color.RED);
            for (Traj.Pdr_Sample sample : pdrPositions) {
                // 利用工具函数将 PDR 数据的相对偏移量转换为地图坐标
                float[] pdrOffset = new float[]{ sample.getX(), sample.getY() };
                LatLng latLng = UtilFunctions.calculateNewPos(pdrStart, pdrOffset);

                pdrOptions.add(latLng);
            }
            pdrPolyline = mMap.addPolyline(pdrOptions);
            // 创建 PDR 动态标记（初始位置取第一个 PDR 转换后的点）
            if (!pdrOptions.getPoints().isEmpty()) {
                pdrMarker = mMap.addMarker(new MarkerOptions().position(pdrOptions.getPoints().get(0)).title("PDR Position")
                        .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_RED)));
            }
        }
    }

    // 开始回放：每隔一定时间更新两个轨迹的动态标记位置
    private void startReplay() {
        if ((gnssPositions == null || gnssPositions.isEmpty()) &&
                (pdrPositions == null || pdrPositions.isEmpty()))
            return;
        isPlaying = true;
        btnPlayPause.setText("Pause");

        playbackRunnable = new Runnable() {
            @Override
            public void run() {
                // 更新 GNSS 标记
                if (gnssPositions != null && currentGnssIndex < gnssPositions.size()) {
                    Traj.GNSS_Sample sample = gnssPositions.get(currentGnssIndex);
                    LatLng latLng = new LatLng(sample.getLatitude(), sample.getLongitude());
                    if (gnssMarker != null) {
                        gnssMarker.setPosition(latLng);
                    } else {
                        gnssMarker = mMap.addMarker(new MarkerOptions().position(latLng).title("GNSS Position")
                                .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_BLUE)));
                    }
                    currentGnssIndex++;
                }
                // 更新 PDR 标记
                if (pdrPositions != null && currentPdrIndex < pdrPositions.size()) {
                    Traj.Pdr_Sample sample = pdrPositions.get(currentPdrIndex);
                    // 假设 PDR 数据起点与 GNSS 数据起点一致
                    LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty())
                            ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude())
                            : new LatLng(0, 0);

                    //LatLng latLng = UtilFunctions.offsetLatLng(pdrStart, sample.getX(), sample.getY());
                    float[] pdrOffset = new float[]{ sample.getX(), sample.getY() };
                    LatLng latLng = UtilFunctions.calculateNewPos(pdrStart, pdrOffset);

                    if (pdrMarker != null) {
                        pdrMarker.setPosition(latLng);
                    } else {
                        pdrMarker = mMap.addMarker(new MarkerOptions().position(latLng).title("PDR Position")
                                .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_RED)));
                    }
                    currentPdrIndex++;
                }
                // 更新进度条，取两者的平均进度（或根据实际需求修改）
                int progress = (currentGnssIndex + currentPdrIndex) / 2;
                progressBar.setProgress(progress);

                if ((gnssPositions != null && currentGnssIndex < gnssPositions.size()) ||
                        (pdrPositions != null && currentPdrIndex < pdrPositions.size())) {
                    playbackHandler.postDelayed(this, 500); // 每500毫秒更新一次
                } else {
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
        currentGnssIndex = 0;
        currentPdrIndex = 0;
        progressBar.setProgress(0);
        startReplay();
    }

    // 快进到末尾：直接显示最后一个位置
    private void goToEndReplay() {
        pauseReplay();
        if (gnssPositions != null && !gnssPositions.isEmpty()) {
            currentGnssIndex = gnssPositions.size() - 1;
            Traj.GNSS_Sample sample = gnssPositions.get(currentGnssIndex);
            LatLng latLng = new LatLng(sample.getLatitude(), sample.getLongitude());
            if (gnssMarker != null) {
                gnssMarker.setPosition(latLng);
            } else {
                gnssMarker = mMap.addMarker(new MarkerOptions().position(latLng).title("GNSS Position"));
            }
            mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng));
            progressBar.setProgress(currentGnssIndex);
        }
        if (pdrPositions != null && !pdrPositions.isEmpty()) {
            currentPdrIndex = pdrPositions.size() - 1;
            LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty())
                    ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude())
                    : new LatLng(0, 0);
            Traj.Pdr_Sample sample = pdrPositions.get(currentPdrIndex);
            //LatLng latLng = UtilFunctions.offsetLatLng(pdrStart, sample.getX(), sample.getY());
            float[] pdrOffset = new float[]{ sample.getX(), sample.getY() };
            LatLng latLng = UtilFunctions.calculateNewPos(pdrStart, pdrOffset);

            if (pdrMarker != null) {
                pdrMarker.setPosition(latLng);
            } else {
                pdrMarker = mMap.addMarker(new MarkerOptions().position(latLng).title("PDR Position"));
            }
            mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng));
            progressBar.setProgress(Math.max(currentGnssIndex, currentPdrIndex));
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
