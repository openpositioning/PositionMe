package com.openpositioning.PositionMe.fragments;


import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
import java.util.List;

public class ReplayFragment extends Fragment implements OnMapReadyCallback {

    private static final String TAG = "ReplayFragment";
    private GoogleMap mMap;
    private Button playPauseButton, restartButton;
    private SeekBar progressBar;
    private boolean isPlaying = false;
    
    // 添加轨迹相关变量
    private List<TrajectoryPoint> trajectoryPoints = new ArrayList<>();
    private int currentPointIndex = 0;
    private Handler playbackHandler = new Handler();
    private static final int PLAYBACK_INTERVAL = 1000; // 1秒更新一次
    
    private Marker currentPositionMarker;
    
    private List<Location> pendingLocations = null;  // 添加这个变量
    
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
            JSONArray locationData = jsonObject.getJSONArray("locationData");
            
            if (locationData.length() == 0) {
                Toast.makeText(getContext(), "No location data found", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 解析位置数据
            List<Location> locations = new ArrayList<>();
            for (int i = 0; i < locationData.length(); i++) {
                JSONObject point = locationData.getJSONObject(i);
                Location location = new Location("");
                location.setLatitude(point.getDouble("latitude"));
                location.setLongitude(point.getDouble("longitude"));
                location.setTime(point.getLong("timestamp"));
                locations.add(location);
            }
            
            Log.d(TAG, "Successfully loaded trajectory points: " + locations.size());
            
            // 如果地图已经准备好，直接更新
            if (mMap != null) {
                updateMap(locations);
            } else {
                // 否则保存轨迹点，等待地图准备好
                pendingLocations = locations;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load trajectory file: " + e.getMessage());
            Toast.makeText(getContext(), "Failed to load trajectory file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        
        // 如果有待处理的轨迹点，现在更新它们
        if (pendingLocations != null) {
            updateMap(pendingLocations);
            pendingLocations = null;
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
        
        // 移除当前位置标记
        if (currentPositionMarker != null) {
            currentPositionMarker.remove();
            currentPositionMarker = null;
        }
        
        if (!trajectoryPoints.isEmpty()) {
            // 移动相机回到起点
            TrajectoryPoint startPoint = trajectoryPoints.get(0);
            mMap.animateCamera(CameraUpdateFactory.newLatLng(
                new LatLng(startPoint.latitude, startPoint.longitude)));
        }
    }

    private void updateMap(List<Location> locations) {
        if (mMap == null) {
            Log.e(TAG, "Map is not ready");
            return;
        }
        
        trajectoryPoints.clear();
        for (Location location : locations) {
            trajectoryPoints.add(new TrajectoryPoint(
                location.getTime(),
                location.getLatitude(),
                location.getLongitude()
            ));
        }
        
        Log.d(TAG, "Successfully loaded trajectory points: " + trajectoryPoints.size());
        
        // 设置进度条最大值
        if (progressBar != null) {
            progressBar.setMax(trajectoryPoints.size());
        }
        
        // 更新地图
        mMap.clear();
        if (!trajectoryPoints.isEmpty()) {
            // 绘制完整轨迹
            PolylineOptions polylineOptions = new PolylineOptions()
                .color(getResources().getColor(R.color.primaryBlue))
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
            
            // 移动相机到起点
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(startPoint.latitude, startPoint.longitude), 15));
        }
    }
}
