package com.openpositioning.PositionMe.fragments;


import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ReplayFragment extends Fragment implements OnMapReadyCallback {

    private static final String TAG = "ReplayFragment";
    private GoogleMap mMap;
    private Button playPauseButton, restartButton;
    private SeekBar progressBar;
    private RadioGroup trajectoryTypeGroup;
    private RadioButton pdrRadioButton, gnssRadioButton, ekfRadioButton;
    private boolean isPlaying = false;
    
    // 添加轨迹相关变量
    private List<TrajectoryPoint> trajectoryPoints = new ArrayList<>();
    private List<TrajectoryPoint> pdrTrajectoryPoints = new ArrayList<>();
    private List<TrajectoryPoint> gnssTrajectoryPoints = new ArrayList<>();
    private List<TrajectoryPoint> ekfTrajectoryPoints = new ArrayList<>();
    
    private int currentPointIndex = 0;
    private Handler playbackHandler = new Handler();
    private static final int PLAYBACK_INTERVAL = 1000; // 1秒更新一次
    
    // 轨迹平滑处理相关参数
    private static final int DOWNSAMPLE_FACTOR = 3; // 每3个点取1个点
    private static final boolean ENABLE_DOWNSAMPLING = true; // 启用降采样
    private static final boolean ENABLE_SMOOTHING = true; // 启用平滑处理
    
    private Marker currentPositionMarker;
    
    private List<Location> pendingLocations = null;  // 添加这个变量
    
    // 当前显示的轨迹类型
    private enum TrajectoryType {
        PDR,
        GNSS,
        EKF
    }
    
    private TrajectoryType currentTrajectoryType = TrajectoryType.EKF; // 默认显示EKF轨迹
    
    // 轨迹点类
    private static class TrajectoryPoint {
        long timestamp;
        double latitude;
        double longitude;
        
        TrajectoryPoint(long timestamp, double latitude, double longitude) {
            this.timestamp = timestamp;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    public ReplayFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_replay, container, false);

        // Initialize UI elements
        playPauseButton = view.findViewById(R.id.play_pause_button);
        restartButton = view.findViewById(R.id.restart_button);
        progressBar = (SeekBar) view.findViewById(R.id.progress_bar);
        
        // 初始化轨迹类型选择按钮
        trajectoryTypeGroup = view.findViewById(R.id.trajectory_type_group);
        pdrRadioButton = view.findViewById(R.id.pdr_radio_button);
        gnssRadioButton = view.findViewById(R.id.gnss_radio_button);
        ekfRadioButton = view.findViewById(R.id.ekf_radio_button);
        
        Log.d(TAG, "轨迹选择控件初始化: " + 
              "RadioGroup=" + (trajectoryTypeGroup != null) + ", " +
              "PDR=" + (pdrRadioButton != null) + ", " +
              "GNSS=" + (gnssRadioButton != null) + ", " + 
              "EKF=" + (ekfRadioButton != null));
        
        if (trajectoryTypeGroup != null) {
            // 设置点击事件监听器
            trajectoryTypeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup group, int checkedId) {
                    Log.d(TAG, "轨迹类型切换: checkedId=" + checkedId);
                    
                    if (checkedId == R.id.pdr_radio_button) {
                        Log.d(TAG, "切换到PDR轨迹");
                        switchTrajectoryType(TrajectoryType.PDR);
                    } else if (checkedId == R.id.gnss_radio_button) {
                        Log.d(TAG, "切换到GNSS轨迹");
                        switchTrajectoryType(TrajectoryType.GNSS);
                    } else if (checkedId == R.id.ekf_radio_button) {
                        Log.d(TAG, "切换到EKF轨迹");
                        switchTrajectoryType(TrajectoryType.EKF);
                    }
                }
            });
            
            // 单独为每个RadioButton设置点击监听器，以防RadioGroup监听器失效
            pdrRadioButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.d(TAG, "PDR RadioButton 点击");
                    pdrRadioButton.setChecked(true);
                    switchTrajectoryType(TrajectoryType.PDR);
                }
            });
            
            gnssRadioButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.d(TAG, "GNSS RadioButton 点击");
                    gnssRadioButton.setChecked(true);
                    switchTrajectoryType(TrajectoryType.GNSS);
                }
            });
            
            ekfRadioButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.d(TAG, "EKF RadioButton 点击");
                    ekfRadioButton.setChecked(true);
                    switchTrajectoryType(TrajectoryType.EKF);
                }
            });
        } else {
            Log.e(TAG, "轨迹类型RadioGroup未找到!");
        }

        // Set button click listeners
        playPauseButton.setOnClickListener(v -> togglePlayback());
        restartButton.setOnClickListener(v -> restartPlayback());

        // Set up the map
        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        // 设置进度条监听器
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentPointIndex = progress;
                    if (!trajectoryPoints.isEmpty() && progress < trajectoryPoints.size()) {
                        TrajectoryPoint point = trajectoryPoints.get(progress);
                        LatLng position = new LatLng(point.latitude, point.longitude);
                        
                        // 更新标记位置
                        if (currentPositionMarker == null) {
                            currentPositionMarker = mMap.addMarker(new MarkerOptions()
                                .position(position)
                                .title("Current Position")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
                        } else {
                            currentPositionMarker.setPosition(position);
                        }
                        
                        // 移动相机
                        mMap.animateCamera(CameraUpdateFactory.newLatLng(position));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 开始拖动时暂停播放
                if (isPlaying) {
                    togglePlayback();
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 可以选择在停止拖动时自动开始播放
                // if (!isPlaying) {
                //     togglePlayback();
                // }
            }
        });

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 获取传入的文件路径
        String filePath = ReplayFragmentArgs.fromBundle(getArguments()).getFilePath();
        Log.d(TAG, "Received file path: " + filePath);
        
        // 如果没有指定文件路径，使用最新的本地轨迹文件
        if (filePath == null || filePath.isEmpty()) {
            File directory = new File(requireContext().getExternalFilesDir(null), "location_logs");
            File[] files = directory.listFiles((dir, name) -> name.startsWith("location_log_local_"));
            if (files != null && files.length > 0) {
                // 按修改时间排序，获取最新的文件
                File latestFile = files[0];
                for (File file : files) {
                    if (file.lastModified() > latestFile.lastModified()) {
                        latestFile = file;
                    }
                }
                filePath = latestFile.getAbsolutePath();
                Log.d(TAG, "Using latest local trajectory file: " + filePath);
            }
        }
        
        try {
            // 读取轨迹文件
            File trajectoryFile = new File(filePath);
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(trajectoryFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
            }
            
            // 解析 JSON
            JSONObject jsonObject = new JSONObject(content.toString());
            
            // 优先读取EKF轨迹数据
            boolean hasValidTrajectory = false;
            
            // 读取EKF轨迹数据
            if (jsonObject.has("ekfLocationData")) {
                JSONArray ekfLocationData = jsonObject.getJSONArray("ekfLocationData");
                if (ekfLocationData.length() > 0) {
                    hasValidTrajectory = true;
                    parseTrajectoryData(ekfLocationData, ekfTrajectoryPoints);
                    Log.d(TAG, "Successfully loaded EKF trajectory: " + ekfTrajectoryPoints.size() + " points");
                    ekfRadioButton.setEnabled(true);
                } else {
                    ekfRadioButton.setEnabled(false);
                }
            } else {
                ekfRadioButton.setEnabled(false);
            }
            
            // 读取GNSS轨迹数据
            if (jsonObject.has("gnssLocationData")) {
                JSONArray gnssLocationData = jsonObject.getJSONArray("gnssLocationData");
                if (gnssLocationData.length() > 0) {
                    hasValidTrajectory = true;
                    parseTrajectoryData(gnssLocationData, gnssTrajectoryPoints);
                    Log.d(TAG, "Successfully loaded GNSS trajectory: " + gnssTrajectoryPoints.size() + " points");
                    gnssRadioButton.setEnabled(true);
                } else {
                    gnssRadioButton.setEnabled(false);
                }
            } else {
                gnssRadioButton.setEnabled(false);
            }
            
            // 读取PDR轨迹数据
            if (jsonObject.has("locationData")) {
                JSONArray locationData = jsonObject.getJSONArray("locationData");
                if (locationData.length() > 0) {
                    hasValidTrajectory = true;
                    parseTrajectoryData(locationData, pdrTrajectoryPoints);
                    Log.d(TAG, "Successfully loaded PDR trajectory: " + pdrTrajectoryPoints.size() + " points");
                    pdrRadioButton.setEnabled(true);
                } else {
                    pdrRadioButton.setEnabled(false);
                }
            } else {
                pdrRadioButton.setEnabled(false);
            }
            
            if (!hasValidTrajectory) {
                Toast.makeText(getContext(), "No valid trajectory data found", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 设置默认轨迹类型
            Log.d(TAG, "设置默认轨迹类型 - EKF轨迹可用: " + ekfRadioButton.isEnabled() + 
                  ", GNSS轨迹可用: " + gnssRadioButton.isEnabled() + 
                  ", PDR轨迹可用: " + pdrRadioButton.isEnabled());
            
            if (ekfRadioButton.isEnabled()) {
                currentTrajectoryType = TrajectoryType.EKF;
                ekfRadioButton.setChecked(true);
                gnssRadioButton.setChecked(false);
                pdrRadioButton.setChecked(false);
                trajectoryPoints = ekfTrajectoryPoints;
                Log.d(TAG, "默认显示EKF轨迹，轨迹点数量: " + trajectoryPoints.size());
            } else if (gnssRadioButton.isEnabled()) {
                currentTrajectoryType = TrajectoryType.GNSS;
                gnssRadioButton.setChecked(true);
                ekfRadioButton.setChecked(false);
                pdrRadioButton.setChecked(false);
                trajectoryPoints = gnssTrajectoryPoints;
                Log.d(TAG, "默认显示GNSS轨迹，轨迹点数量: " + trajectoryPoints.size());
            } else if (pdrRadioButton.isEnabled()) {
                currentTrajectoryType = TrajectoryType.PDR;
                pdrRadioButton.setChecked(true);
                ekfRadioButton.setChecked(false);
                gnssRadioButton.setChecked(false);
                trajectoryPoints = pdrTrajectoryPoints;
                Log.d(TAG, "默认显示PDR轨迹，轨迹点数量: " + trajectoryPoints.size());
            }
            
            // 如果地图已经准备好，直接更新
            if (mMap != null) {
                updateMap();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load trajectory file: " + e.getMessage());
            Toast.makeText(getContext(), "Failed to load trajectory file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 解析轨迹数据并添加到指定列表
     */
    private void parseTrajectoryData(JSONArray jsonArray, List<TrajectoryPoint> targetList) throws Exception {
        targetList.clear();
        
        if (jsonArray.length() == 0) {
            return;
        }
        
        // 原始点集合
        List<TrajectoryPoint> originalPoints = new ArrayList<>();
        
        // 先解析所有原始点
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject point = jsonArray.getJSONObject(i);
            originalPoints.add(new TrajectoryPoint(
                point.getLong("timestamp"),
                point.getDouble("latitude"),
                point.getDouble("longitude")
            ));
        }
        
        // 按时间戳排序
        Collections.sort(originalPoints, (p1, p2) -> Long.compare(p1.timestamp, p2.timestamp));
        
        // 如果点数太多, 进行降采样
        if (ENABLE_DOWNSAMPLING && originalPoints.size() > 100) {
            Log.d(TAG, "原始点数: " + originalPoints.size() + "，进行降采样处理");
            
            // 每DOWNSAMPLE_FACTOR个点取一个点
            for (int i = 0; i < originalPoints.size(); i += DOWNSAMPLE_FACTOR) {
                targetList.add(originalPoints.get(i));
            }
            
            // 确保包含最后一个点
            if (targetList.isEmpty() || targetList.get(targetList.size()-1) != originalPoints.get(originalPoints.size()-1)) {
                targetList.add(originalPoints.get(originalPoints.size()-1));
            }
            
            Log.d(TAG, "降采样后点数: " + targetList.size());
        } else {
            // 点数较少，不降采样，直接使用原始点
            targetList.addAll(originalPoints);
        }
        
        // 对GNSS轨迹进行额外平滑处理
        if (ENABLE_SMOOTHING && targetList == gnssTrajectoryPoints && targetList.size() > 5) {
            Log.d(TAG, "对GNSS轨迹执行平滑处理");
            smoothGnssTrajectory(targetList);
        }
    }
    
    /**
     * 对GNSS轨迹进行平滑处理
     */
    private void smoothGnssTrajectory(List<TrajectoryPoint> points) {
        if (points.size() < 5) return;
        
        // 复制原始点集合
        List<TrajectoryPoint> originalPoints = new ArrayList<>(points);
        points.clear();
        
        // 移动平均窗口宽度
        int windowSize = 5;
        double[] weights = {0.1, 0.2, 0.4, 0.2, 0.1}; // 加权移动平均权重
        
        // 处理开头的点（直接保留）
        for (int i = 0; i < windowSize/2; i++) {
            points.add(originalPoints.get(i));
        }
        
        // 对中间的点应用加权移动平均
        for (int i = windowSize/2; i < originalPoints.size() - windowSize/2; i++) {
            double sumLat = 0, sumLng = 0;
            for (int j = 0; j < windowSize; j++) {
                int idx = i - windowSize/2 + j;
                TrajectoryPoint pt = originalPoints.get(idx);
                sumLat += pt.latitude * weights[j];
                sumLng += pt.longitude * weights[j];
            }
            
            TrajectoryPoint smoothedPoint = new TrajectoryPoint(
                originalPoints.get(i).timestamp,
                sumLat,
                sumLng
            );
            points.add(smoothedPoint);
        }
        
        // 处理结尾的点（直接保留）
        for (int i = originalPoints.size() - windowSize/2; i < originalPoints.size(); i++) {
            points.add(originalPoints.get(i));
        }
        
        Log.d(TAG, "GNSS轨迹平滑处理完成");
    }
    
    /**
     * 切换轨迹类型
     */
    private void switchTrajectoryType(TrajectoryType type) {
        if (mMap == null) {
            Log.e(TAG, "地图尚未准备好，无法切换轨迹类型");
            return;
        }
        
        Log.d(TAG, "切换轨迹类型: " + type.name());
        
        // 停止播放并重置位置
        stopPlayback();
        currentPointIndex = 0;
        
        currentTrajectoryType = type;
        
        // 更新轨迹点和RadioButton状态
        switch (type) {
            case PDR:
                trajectoryPoints = pdrTrajectoryPoints;
                Log.d(TAG, "切换到PDR轨迹，轨迹点数量: " + trajectoryPoints.size());
                if (!pdrRadioButton.isChecked()) {
                    pdrRadioButton.setChecked(true);
                    gnssRadioButton.setChecked(false);
                    ekfRadioButton.setChecked(false);
                }
                break;
            case GNSS:
                trajectoryPoints = gnssTrajectoryPoints;
                Log.d(TAG, "切换到GNSS轨迹，轨迹点数量: " + trajectoryPoints.size());
                if (!gnssRadioButton.isChecked()) {
                    gnssRadioButton.setChecked(true);
                    pdrRadioButton.setChecked(false);
                    ekfRadioButton.setChecked(false);
                }
                break;
            case EKF:
                trajectoryPoints = ekfTrajectoryPoints;
                Log.d(TAG, "切换到EKF轨迹，轨迹点数量: " + trajectoryPoints.size());
                if (!ekfRadioButton.isChecked()) {
                    ekfRadioButton.setChecked(true);
                    pdrRadioButton.setChecked(false);
                    gnssRadioButton.setChecked(false);
                }
                break;
        }
        
        // 更新地图和进度条
        updateMap();
        
        // 确保进度条正确反映轨迹长度
        if (progressBar != null) {
            progressBar.setMax(trajectoryPoints.size() > 0 ? trajectoryPoints.size() - 1 : 0);
            progressBar.setProgress(0);
        }
        
        Log.d(TAG, "轨迹类型切换完成: " + type.name());
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        
        // 更新地图
        if (!trajectoryPoints.isEmpty()) {
            updateMap();
        }
    }

    private void togglePlayback() {
        isPlaying = !isPlaying;
        if (isPlaying) {
            playPauseButton.setText(R.string.pause);
            startPlayback();
        } else {
            playPauseButton.setText(R.string.play);
            stopPlayback();
        }
    }

    private void startPlayback() {
        playbackHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isPlaying && currentPointIndex < trajectoryPoints.size()) {
                    // 更新地图上的位置
                    TrajectoryPoint point = trajectoryPoints.get(currentPointIndex);
                    LatLng position = new LatLng(point.latitude, point.longitude);
                    
                    // 更新或创建当前位置标记
                    if (currentPositionMarker == null) {
                        currentPositionMarker = mMap.addMarker(new MarkerOptions()
                            .position(position)
                            .title("Current Position")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
                    } else {
                        currentPositionMarker.setPosition(position);
                    }
                    
                    // 移动相机
                    mMap.animateCamera(CameraUpdateFactory.newLatLng(position));
                    
                    // 更新进度条
                    progressBar.setProgress(currentPointIndex);
                    
                    currentPointIndex++;
                    playbackHandler.postDelayed(this, PLAYBACK_INTERVAL);
                } else {
                    isPlaying = false;
                    playPauseButton.setText(R.string.play);
                }
            }
        }, PLAYBACK_INTERVAL);
    }

    private void stopPlayback() {
        playbackHandler.removeCallbacksAndMessages(null);
    }

    private void restartPlayback() {
        stopPlayback();
        currentPointIndex = 0;
        progressBar.setProgress(0);
        playPauseButton.setText(R.string.play);
        isPlaying = false;
        
        // 移动相机回到起点
        if (!trajectoryPoints.isEmpty()) {
            TrajectoryPoint startPoint = trajectoryPoints.get(0);
            LatLng startPosition = new LatLng(startPoint.latitude, startPoint.longitude);
            
            // 更新位置标记
            if (currentPositionMarker != null) {
                currentPositionMarker.setPosition(startPosition);
            } else {
                currentPositionMarker = mMap.addMarker(new MarkerOptions()
                    .position(startPosition)
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
            }
            
            // 移动相机
            mMap.animateCamera(CameraUpdateFactory.newLatLng(startPosition));
        }
    }

    private void updateMap() {
        if (mMap == null || trajectoryPoints.isEmpty()) {
            return;
        }
        
        // 清除地图
        mMap.clear();
        currentPositionMarker = null;
        
        // 确定轨迹颜色
        int trajectoryColor;
        switch (currentTrajectoryType) {
            case PDR:
                trajectoryColor = getResources().getColor(R.color.colorRed);
                break;
            case GNSS:
                trajectoryColor = getResources().getColor(R.color.colorBlue);
                break;
            case EKF:
                trajectoryColor = getResources().getColor(R.color.colorGreen);
                break;
            default:
                trajectoryColor = getResources().getColor(R.color.primaryBlue);
        }
        
        // 绘制完整轨迹
        PolylineOptions polylineOptions = new PolylineOptions()
            .color(trajectoryColor)
            .width(5);
            
        for (TrajectoryPoint point : trajectoryPoints) {
            polylineOptions.add(new LatLng(point.latitude, point.longitude));
        }
        mMap.addPolyline(polylineOptions);
        
        // 添加起点标记
        TrajectoryPoint startPoint = trajectoryPoints.get(0);
        mMap.addMarker(new MarkerOptions()
            .position(new LatLng(startPoint.latitude, startPoint.longitude))
            .title("Start point"));
            
        // 添加终点标记
        TrajectoryPoint endPoint = trajectoryPoints.get(trajectoryPoints.size() - 1);
        mMap.addMarker(new MarkerOptions()
            .position(new LatLng(endPoint.latitude, endPoint.longitude))
            .title("End point")
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
        
        // 添加当前位置标记（初始位置设为起点）
        currentPositionMarker = mMap.addMarker(new MarkerOptions()
            .position(new LatLng(startPoint.latitude, startPoint.longitude))
            .title("Current Position")
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
        
        // 移动相机到起点
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
            new LatLng(startPoint.latitude, startPoint.longitude), 17));
            
        // 设置进度条
        progressBar.setMax(trajectoryPoints.size());
        progressBar.setProgress(0);
    }
}
