package com.openpositioning.PositionMe.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Tile;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.android.gms.maps.model.TileProvider;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.Traj.Trajectory;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.IndoorMapManager;

import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

public class ReplayFragment extends Fragment implements OnMapReadyCallback {//它实现了 OnMapReadyCallback 接口，用于在Google Map准备好时执行相关操作。

    private MapView mapView;//用于在界面上显示 Google 地图的视图组件。
    private GoogleMap mMap;//对应 MapView 中的地图对象，用于添加标记、多边线、移动摄像头等操作。
    private Button btnPlayPause, btnRestart, btnGoToEnd, btnExit;
    private SeekBar progressBar;

    // 播放控制
    private boolean isPlaying = false;//表示是否正在回放
    private int currentGnssIndex = 0;
    private int currentPdrIndex = 0;
    private Handler playbackHandler = new Handler(Looper.getMainLooper());//用于在主线程上调度回放更新任务。
    private Runnable playbackRunnable;//用于实现周期性更新轨迹标记位置的任务。

    // 轨迹数据：这里分别使用 Traj.Trajectory 解析得到的 GNSS 和 PDR 数据
    private Traj.Trajectory trajectory;//从文件中解析得到的轨迹数据对象。
    private List<Traj.Position_Sample> _positionData;
    private int _positionData_pointer = 0;
    private List<Traj.GNSS_Sample> gnssPositions;//存储解析后的 GNSS 数据列表（每个数据包含纬度、经度）。
    private List<Traj.Pdr_Sample> pdrPositions;//存储解析后的 PDR 数据列表（每个数据通常包含相对位移信息，如 x、y 偏移量）。

    private Polyline gnssPolyline;
    private Polyline pdrPolyline;
    private Marker gnssMarker;
    private Marker pdrMarker;

    private IndoorMapManager _indoorMapManager;
    // 文件路径从 Bundle 中获取
    private String filePath;//用于存储传入的轨迹数据文件路径，从bundle中获取。

    public FloatingActionButton _floorUpButton; // Floor Up button
    public FloatingActionButton _floorDownButton; // Floor Down button
    private Switch _autoFloor;

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

        mapView.onCreate(savedInstanceState);//以保证 MapView 能正确管理自己的生命周期。
        mapView.getMapAsync(this);//this是当前类的实例对象，注册当前 Fragment 作为地图加载完成的回调（即调用 onMapReady 方法）。
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
            FileInputStream fis = new FileInputStream(file);//stream类型读取信息的方式
            byte[] data = new byte[(int) file.length()];//file.length() 返回文件的大小（以字节为单位）。(int) file.length() 将文件大小转换为 int 类型。
            fis.read(data);
            fis.close();
            trajectory = Traj.Trajectory.parseFrom(data);
            // 分别获取 GNSS 数据列表和 PDR 数据列表
            _positionData = trajectory.getPositionDataList();
            gnssPositions = trajectory.getGnssDataList();
            pdrPositions = trajectory.getPdrDataList();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Failed to load trajectory data", Toast.LENGTH_SHORT).show();
        }

        // 初始化按钮点击事件
        // Floor changer Buttons
        this._floorUpButton=getView().findViewById(R.id.floorUpButton1);
        this._floorDownButton=getView().findViewById(R.id.floorDownButton1);
        // Auto-floor switch
        this._autoFloor=getView().findViewById(R.id.autoFloor1);
        _autoFloor.setChecked(true);
        // Hiding floor changing buttons and auto-floor switch
        setFloorButtonVisibility(View.GONE);
        this._floorUpButton.setOnClickListener(new View.OnClickListener() {
            /**
             *{@inheritDoc}
             * Listener for increasing the floor for the indoor map
             */
            @Override
            public void onClick(View view) {
                // Setting off auto-floor as manually changed
                _autoFloor.setChecked(false);
                _indoorMapManager.increaseFloor();
            }
        });
        this._floorDownButton.setOnClickListener(new View.OnClickListener() {
            /**
             *{@inheritDoc}
             * Listener for decreasing the floor for the indoor map
             */
            @Override
            public void onClick(View view) {
                // Setting off auto-floor as manually changed
                _autoFloor.setChecked(false);
                _indoorMapManager.decreaseFloor();
            }
        });

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

        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    // 将拖动进度映射为回放的索引
                    currentGnssIndex = progress;
                    currentPdrIndex = progress;
                    updateMarkersForProgress();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 可选：拖动开始时暂停自动播放，防止与自动更新冲突
                pauseReplay();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 可选：拖动结束后是否恢复自动播放由需求决定
            }
        });
    }
    private void updateMarkersForProgress() {
        if (mMap == null) {
            // 地图还没有准备好，直接返回或做其他处理
            return;
        }
        // 更新 GNSS 标记
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
        // 更新 PDR 标记
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

        //inner buildings
        _indoorMapManager = new IndoorMapManager(mMap);
        //Showing an indication of available indoor maps using PolyLines
        _indoorMapManager .setIndicationOfIndoorMap();

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

    private void setFloorButtonVisibility(int visibility){
        _floorUpButton.setVisibility(visibility);
        _floorDownButton.setVisibility(visibility);
        _autoFloor.setVisibility(visibility);
    }

    // 开始回放：每隔一定时间更新两个轨迹的动态标记位置
    private void startReplay() {
        if ((gnssPositions == null || gnssPositions.isEmpty()) && (pdrPositions == null || pdrPositions.isEmpty()))
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
                    LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty()) ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude()) : new LatLng(0, 0);

                    //LatLng latLng = UtilFunctions.offsetLatLng(pdrStart, sample.getX(), sample.getY());
                    float[] pdrOffset = new float[]{ sample.getX(), sample.getY() };
                    LatLng latLng = UtilFunctions.calculateNewPos(pdrStart, pdrOffset);


                    if(_positionData != null && _positionData_pointer < _positionData.size()) {
                        // If not initialized, initialize
                        if (_indoorMapManager == null) {
                            _indoorMapManager = new IndoorMapManager(mMap);
                        }
                        //  Updates current location of user to show the indoor floor map (if applicable)
                        //latLng = new LatLng(55.923089201509164, -3.17426605622692); //test
                        _indoorMapManager.setCurrentLocation(latLng); //latLng
                        float elevationVal = _positionData.get(_positionData_pointer).getMagZ();
                        Log.d("MagZ", String.format("Elevation Value: %.2f", elevationVal));
                        // Display buttons to allow user to change floors if indoor map is visible
                        if (_indoorMapManager.getIsIndoorMapSet()) {
                            setFloorButtonVisibility(View.VISIBLE);
                            // Auto-floor logic
                            if (_autoFloor.isChecked()) {
                                _indoorMapManager.setCurrentFloor((int) (elevationVal / _indoorMapManager.getFloorHeight())
                                        , true);
                            }
                        } else {
                            // Hide the buttons and switch used to change floor if indoor map is not visible
                            setFloorButtonVisibility(View.GONE);
                        }
                        _positionData_pointer++;
                    }


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
