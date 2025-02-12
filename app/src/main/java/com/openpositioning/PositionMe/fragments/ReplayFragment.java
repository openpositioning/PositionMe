package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.hardware.SensorManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.Toast;
import android.graphics.Color;

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
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.PdrProcessing;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ReplayFragment extends Fragment implements OnMapReadyCallback {

    private MapView mapView;
    private GoogleMap mMap;
    private Button btnPlayPause, btnRestart, btnGoToEnd, btnExit;
    private SeekBar progressBar;

    // 播放控制
    private boolean isPlaying = false;
    private int currentGnssIndex = 0;
    private int currentPdrIndex = 0;
    private Handler playbackHandler = new Handler(Looper.getMainLooper());
    private Runnable playbackRunnable;

    // 轨迹数据
    private Traj.Trajectory trajectory;
    private List<Traj.GNSS_Sample> gnssPositions;
    private List<Traj.Pdr_Sample> pdrPositions;
    private List<Traj.Pressure_Sample> pressureinfos;
    private List<Float> altitudeList;
    private List<Float> relativeAltitudeList;

    private Polyline gnssPolyline;
    private Polyline pdrPolyline;
    private Marker gnssMarker;
    private Marker pdrMarker;
    private PdrProcessing pdrProcessing;

    private IndoorMapManager indoorMapManager;

    // 楼层切换相关变量
    private int currentMeasuredFloor = 0;  // 录制起始时相对高度为 0 层
    private final float FLOOR_HEIGHT = 4.2f; // 默认 Nucleus 楼层高度 4.2 米
    private final float TOLERANCE = 0.5f;    // 容差 ±0.5 米
    private final int CONSECUTIVE_THRESHOLD = 3;
    private int upCounter = 0;
    private int downCounter = 0;

    // 手动调整楼层控件（需要在布局文件中添加）
    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton;
    private com.google.android.material.floatingactionbutton.FloatingActionButton floorDownButton;
    private Switch autoFloor;

    // 文件路径
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
        // 获取楼层控制控件
        floorUpButton = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        autoFloor = view.findViewById(R.id.autoFloor);

        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 初始化 pdrProcessing
        pdrProcessing = new PdrProcessing(getContext());

        // 获取文件路径
        if (getArguments() != null) {
            filePath = getArguments().getString("trajectory_file_path");
        }
        if (filePath == null) {
            Toast.makeText(getContext(), "No trajectory file provided", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File file = new File(filePath);
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();
            trajectory = Traj.Trajectory.parseFrom(data);
            gnssPositions = trajectory.getGnssDataList();
            pdrPositions = trajectory.getPdrDataList();
            pressureinfos = trajectory.getPressureDataList();
            altitudeList = new ArrayList<>();
            relativeAltitudeList = new ArrayList<>();
            if (pressureinfos != null && !pressureinfos.isEmpty()) {
                for (Traj.Pressure_Sample ps : pressureinfos) {
                    float alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, ps.getPressure());
                    altitudeList.add(alt);
                }
            }
            // 将绝对海拔转换为相对海拔（注意：updateElevation() 是状态化的，只转换一次）
            for (Float absoluteAlt : altitudeList) {
                float relativeAlt = pdrProcessing.updateElevation(absoluteAlt);
                relativeAltitudeList.add(relativeAlt);
            }
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

        // 设置进度条最大值
        if ((gnssPositions != null && !gnssPositions.isEmpty()) ||
                (pdrPositions != null && !pdrPositions.isEmpty())) {
            int maxCount = Math.max(
                    (gnssPositions != null ? gnssPositions.size() : 0),
                    (pdrPositions != null ? pdrPositions.size() : 0));
            progressBar.setMax(maxCount);
        }

        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentGnssIndex = progress;
                    currentPdrIndex = progress;
                    updateMarkersForProgress();
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                pauseReplay();
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        // 设置手动楼层调整控件的监听器
        floorUpButton.setOnClickListener(v -> {
            // 手动调整时关闭自动楼层
            autoFloor.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
            }
        });
        floorDownButton.setOnClickListener(v -> {
            autoFloor.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
            }
        });
        // 初始时，根据室内地图是否显示来设置楼层按钮的可见性
        if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
            setFloorButtonVisibility(View.VISIBLE);
        } else {
            setFloorButtonVisibility(View.GONE);
        }
    }

    // 辅助方法：设置楼层按钮及自动楼层开关的可见性
    private void setFloorButtonVisibility(int visibility) {
        if (floorUpButton != null) {
            floorUpButton.setVisibility(visibility);
        }
        if (floorDownButton != null) {
            floorDownButton.setVisibility(visibility);
        }
        if (autoFloor != null) {
            autoFloor.setVisibility(visibility);
        }
    }

    private void updateMarkersForProgress() {
        if (mMap == null) {
            return;
        }
        if (indoorMapManager == null) {
            indoorMapManager = new IndoorMapManager(mMap);
        }
        // 更新 GNSS Marker
        if (gnssPositions != null && !gnssPositions.isEmpty()) {
            int index = Math.min(currentGnssIndex, gnssPositions.size() - 1);
            Traj.GNSS_Sample sample = gnssPositions.get(index);
            LatLng latLng = new LatLng(sample.getLatitude(), sample.getLongitude());
            if (gnssMarker != null) {
                gnssMarker.setPosition(latLng);
            } else {
                gnssMarker = mMap.addMarker(new MarkerOptions()
                        .position(latLng)
                        .title("GNSS Position")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
            }
        }
        // 更新 PDR Marker 和显示相对高度
        if (pdrPositions != null && !pdrPositions.isEmpty()) {
            int index = Math.min(currentPdrIndex, pdrPositions.size() - 1);
            Traj.Pdr_Sample sample = pdrPositions.get(index);
            LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty())
                    ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude())
                    : new LatLng(0, 0);
            float[] pdrOffset = new float[]{ sample.getX(), sample.getY() };
            LatLng latLng = UtilFunctions.calculateNewPos(pdrStart, pdrOffset);
            if (pdrMarker != null) {
                pdrMarker.setPosition(latLng);
            } else {
                pdrMarker = mMap.addMarker(new MarkerOptions()
                        .position(latLng)
                        .title("PDR Position")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
            }
            if (relativeAltitudeList != null && !relativeAltitudeList.isEmpty()) {
                int altIndex = Math.min(currentPdrIndex, relativeAltitudeList.size() - 1);
                float currentRelAlt = relativeAltitudeList.get(altIndex);
                pdrMarker.setSnippet("Altitude: " + String.format("%.1f", currentRelAlt) + " m");
            }
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        mMap.getUiSettings().setCompassEnabled(true);
        mMap.getUiSettings().setTiltGesturesEnabled(true);
        mMap.getUiSettings().setRotateGesturesEnabled(true);
        mMap.getUiSettings().setScrollGesturesEnabled(true);
        // 绘制 GNSS 轨迹（蓝色）
        if (gnssPositions != null && !gnssPositions.isEmpty()) {
            PolylineOptions gnssOptions = new PolylineOptions().color(Color.BLUE);
            for (Traj.GNSS_Sample sample : gnssPositions) {
                LatLng latLng = new LatLng(sample.getLatitude(), sample.getLongitude());
                gnssOptions.add(latLng);
            }
            gnssPolyline = mMap.addPolyline(gnssOptions);
            LatLng gnssStart = new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude());
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(gnssStart, 18f));
            gnssMarker = mMap.addMarker(new MarkerOptions().position(gnssStart).title("GNSS Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
        }
        // 绘制 PDR 轨迹（红色）
        if (pdrPositions != null && !pdrPositions.isEmpty()) {
            LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty())
                    ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude())
                    : new LatLng(0, 0);
            PolylineOptions pdrOptions = new PolylineOptions().color(Color.RED);
            for (Traj.Pdr_Sample sample : pdrPositions) {
                float[] pdrOffset = new float[]{ sample.getX(), sample.getY() };
                LatLng latLng = UtilFunctions.calculateNewPos(pdrStart, pdrOffset);
                pdrOptions.add(latLng);
            }
            pdrPolyline = mMap.addPolyline(pdrOptions);
            if (!pdrOptions.getPoints().isEmpty()) {
                pdrMarker = mMap.addMarker(new MarkerOptions().position(pdrOptions.getPoints().get(0)).title("PDR Position")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
            }
        }
        // 初始化 indoorMapManager
        indoorMapManager = new IndoorMapManager(mMap);
    }

    // 开始回放：更新 Marker 位置，并检测楼层切换
    private void startReplay() {
        if ((gnssPositions == null || gnssPositions.isEmpty()) &&
                (pdrPositions == null || pdrPositions.isEmpty()))
            return;
        isPlaying = true;
        btnPlayPause.setText("Pause");

        playbackRunnable = new Runnable() {
            @Override
            public void run() {
                // 更新 GNSS Marker
                if (gnssPositions != null && currentGnssIndex < gnssPositions.size()) {
                    Traj.GNSS_Sample sample = gnssPositions.get(currentGnssIndex);
                    LatLng latLng = new LatLng(sample.getLatitude(), sample.getLongitude());
                    if (gnssMarker != null) {
                        gnssMarker.setPosition(latLng);
                    } else {
                        gnssMarker = mMap.addMarker(new MarkerOptions()
                                .position(latLng)
                                .title("GNSS Position")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
                    }
                    // 更新室内地图位置
                    if (indoorMapManager != null) {
                        indoorMapManager.setCurrentLocation(latLng);
                    }
                    currentGnssIndex++;
                }
                // 更新 PDR Marker 并检测楼层切换
                if (pdrPositions != null && currentPdrIndex < pdrPositions.size()) {
                    Traj.Pdr_Sample sample = pdrPositions.get(currentPdrIndex);
                    LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty())
                            ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude())
                            : new LatLng(0, 0);
                    float[] pdrOffset = new float[]{ sample.getX(), sample.getY() };
                    LatLng latLng = UtilFunctions.calculateNewPos(pdrStart, pdrOffset);
                    if (pdrMarker != null) {
                        pdrMarker.setPosition(latLng);
                    } else {
                        pdrMarker = mMap.addMarker(new MarkerOptions()
                                .position(latLng)
                                .title("PDR Position")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
                    }
                    currentPdrIndex++;
                    // 更新相对高度显示，并检测楼层切换
                    if (relativeAltitudeList != null && !relativeAltitudeList.isEmpty()) {
                        int altIndex = Math.min(currentPdrIndex, relativeAltitudeList.size() - 1);
                        float currentRelAlt = relativeAltitudeList.get(altIndex);
                        pdrMarker.setSnippet("Altitude: " + String.format("%.1f", currentRelAlt) + " m");

                        // 判断当前建筑类型，并设置相应楼层高度
                        float buildingFloorHeight = FLOOR_HEIGHT; // 默认 Nucleus
                        // 这里使用第一个 GNSS 点来判断建筑类型（或使用最新位置）
                        if (gnssPositions != null && !gnssPositions.isEmpty()) {
                            LatLng currentPos = new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude());
                            // 注意：确保 BuildingPolygon 类已正确实现
                            if (com.openpositioning.PositionMe.BuildingPolygon.inLibrary(currentPos)) {
                                buildingFloorHeight = IndoorMapManager.LIBRARY_FLOOR_HEIGHT; // 如 3.6f
                            }
                        }

                        // 计算期望上层和下层的相对高度
                        float expectedUp = (currentMeasuredFloor + 1) * buildingFloorHeight;
                        float expectedDown = (currentMeasuredFloor - 1) * buildingFloorHeight;
                        if (currentRelAlt >= expectedUp - TOLERANCE && currentRelAlt <= expectedUp + TOLERANCE) {
                            upCounter++;
                            downCounter = 0;
                        } else if (currentRelAlt >= expectedDown - TOLERANCE && currentRelAlt <= expectedDown + TOLERANCE) {
                            downCounter++;
                            upCounter = 0;
                        } else {
                            upCounter = 0;
                            downCounter = 0;
                        }
                        if (upCounter >= CONSECUTIVE_THRESHOLD && indoorMapManager.getIsIndoorMapSet()) {
                            currentMeasuredFloor++; // 向上切换一层
                            indoorMapManager.setCurrentFloor(currentMeasuredFloor, true);
                            upCounter = 0;
                        }
                        if (downCounter >= CONSECUTIVE_THRESHOLD && indoorMapManager.getIsIndoorMapSet()) {
                            currentMeasuredFloor--; // 向下切换一层
                            indoorMapManager.setCurrentFloor(currentMeasuredFloor, true);
                            downCounter = 0;
                        }
                    }
                }
                // 更新进度条
                int progress = Math.max(currentGnssIndex, currentPdrIndex);
                progressBar.setProgress(progress);

                if ((gnssPositions != null && currentGnssIndex < gnssPositions.size()) ||
                        (pdrPositions != null && currentPdrIndex < pdrPositions.size())) {
                    playbackHandler.postDelayed(this, 500);
                } else {
                    pauseReplay();
                }
            }
        };
        playbackHandler.postDelayed(playbackRunnable, 500);
    }

    private void pauseReplay() {
        isPlaying = false;
        btnPlayPause.setText("Play");
        if (playbackRunnable != null) {
            playbackHandler.removeCallbacks(playbackRunnable);
        }
    }

    private void restartReplay() {
        pauseReplay();
        currentGnssIndex = 0;
        currentPdrIndex = 0;
        progressBar.setProgress(0);
        currentMeasuredFloor = 0;
        upCounter = 0;
        downCounter = 0;
        startReplay();
    }

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

    private void exitReplay() {
        pauseReplay();
        getActivity().onBackPressed();
    }

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
