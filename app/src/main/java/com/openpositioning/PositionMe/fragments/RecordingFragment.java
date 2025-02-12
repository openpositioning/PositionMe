package com.openpositioning.PositionMe.fragments;


import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class RecordingFragment extends Fragment {

    //Button to end PDR recording
    private Button stopButton;
    private Button startButton;
    //Text views to display distance travelled and elevation since beginning of recording
    private TextView elevation;
    private TextView distanceTravelled;
    // Text view to show the error between current PDR and current GNSS
    private TextView gnssError;
    private static final DecimalFormat df = new DecimalFormat("#.####");

    //Singleton class to collect all sensor data
    private SensorFusion sensorFusion;
    // Responsible for updating UI in Loop
    private Handler refreshDataHandler;

    //variables to store data of the trajectory
    private float distance;
    private float previousPosX;
    private float previousPosY;

    // Starting point coordinates
    private static LatLng start;
    // Storing the google map object
    private GoogleMap gMap;
    //Switch Map Dropdown
    private Spinner switchMapSpinner;
    //Map Marker
    private Marker orientationMarker;
    // Current Location coordinates
    private LatLng currentLocation;
    // Next Location coordinates
    private LatLng nextLocation;
    // Stores the polyline object for plotting path
    private Polyline polyline;
    // Manages overlaying of the indoor maps
    public com.openpositioning.PositionMe.IndoorMapManager indoorMapManager;
    // Floor Up button
    public FloatingActionButton floorUpButton;
    // Floor Down button
    public FloatingActionButton floorDownButton;
    // GNSS Switch
    private Switch gnss;
    // GNSS marker
    private Marker gnssMarker;
    // Switch used to set auto floor
    private Switch autoFloor;
    private String zoneName;
    private double markerLatitude;
    private double markerLongitude;
    private boolean isRecording = false;
    private static final int REQUEST_ACTIVITY_RECOGNITION_PERMISSION_CODE = 1001;
    private NucleusBuildingManager nucleusBuildingManager;
    private boolean ifstart = false;


    /**
     * Public Constructor for the class.
     * Left empty as not required
     */
    public RecordingFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 检查 Android 版本，只有 Android 10（API 29）及以上需要运行时权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACTIVITY_RECOGNITION)
                    != PackageManager.PERMISSION_GRANTED) {
                // 请求权限
                requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION},
                        REQUEST_ACTIVITY_RECOGNITION_PERMISSION_CODE);
            } else {
                // 已经拥有权限，可以继续后续操作
                Log.d("RecordingFragment", "✅ 已授予活动识别权限");
            }
        } else {
            // Android 9 以下不需要额外申请权限
            Log.d("RecordingFragment", "✅ Android 9 以下无需活动识别权限");
        }

        // ✅ 确保 SensorFusion 正确初始化
        this.sensorFusion = SensorFusion.getInstance();
        // 设置应用程序上下文
        sensorFusion.setContext(getActivity().getApplicationContext());
        if (this.sensorFusion == null) {
            Log.e("SensorFusion", "❌ SensorFusion is NULL! Retrying initialization...");
            this.sensorFusion = SensorFusion.getInstance(); // 重新获取实例
        }

        // ✅ 初始化 `Handler`（用于定期更新 UI）
        this.refreshDataHandler = new Handler();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_recording, container, false);

        // ✅ **从 Bundle 里获取传递的数据**
        if (getArguments() != null) {
            zoneName = getArguments().getString("zone_name");
            markerLatitude = getArguments().getDouble("marker_latitude", 0.0);
            markerLongitude = getArguments().getDouble("marker_longitude", 0.0);

            Log.d("RecordingFragment", "📍 Zone: " + zoneName + " | Lat: " + markerLatitude + " | Lon: " + markerLongitude);
        }

        // ✅ 获取 GNSS 初始位置（确保包含纬度 & 经度）
        if (markerLatitude != 0.0 && markerLongitude != 0.0) {
            start = new LatLng(markerLatitude, markerLongitude);
        } else {
            start = new LatLng(55.953251, -3.188267); // 💡 默认位置（爱丁堡）
        }

        float[] sendStartLocation = new float[2];
        sendStartLocation[0] = (float) start.latitude;
        sendStartLocation[1] = (float) start.longitude;
        sensorFusion.setStartGNSSLatitude(sendStartLocation);

        currentLocation = start; // 🔥 确保 currentLocation 也初始化

        // ✅ 初始化地图
        SupportMapFragment supportMapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.map_fragment);
        if (supportMapFragment != null) {
            supportMapFragment.getMapAsync(map -> {
                gMap = map;

                // ✅ 初始化室内地图（先检查是否需要）
                if (indoorMapManager == null) {
                    indoorMapManager = new com.openpositioning.PositionMe.IndoorMapManager(gMap);
                }

                // ✅ 配置 Google Map UI
                map.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                map.getUiSettings().setCompassEnabled(true);
                map.getUiSettings().setTiltGesturesEnabled(true);
                map.getUiSettings().setRotateGesturesEnabled(true);
                map.getUiSettings().setScrollGesturesEnabled(true);

                // ✅ 添加起始点 Marker（带有方向指示）
                orientationMarker = map.addMarker(new MarkerOptions()
                        .position(start)
                        .title("Current Position")
                        .flat(true)
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(requireContext(), R.drawable.ic_baseline_navigation_24)
                        )));
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 19f));

                // ✅ 初始化 PDR 轨迹（Polyline）
                polyline = gMap.addPolyline(new PolylineOptions()
                        .color(Color.RED)
                        .add(currentLocation)
                        .zIndex(6));

                // ✅ 设置室内地图（如适用）
                indoorMapManager.setCurrentLocation(currentLocation);
                indoorMapManager.setIndicationOfIndoorMap();
            });
        } else {
            Log.e("RecordingFragment", "❌ SupportMapFragment is NULL!");
        }

        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 🛑 **删除旧 Marker，避免重复**
        if (orientationMarker != null) {
            orientationMarker.remove();
            orientationMarker = null;
            Log.d("MarkerReset", "🔥 旧 Marker 被移除");
        }

        if (polyline != null) {
            polyline.remove();
            polyline = null;
            Log.d("PolylineReset", "🔥 旧 Polyline 被移除");
        }

        // ✅ 初始化 UI 组件（避免 `getView()` 多次调用）
        this.elevation = view.findViewById(R.id.tv_elevation);
        this.distanceTravelled = view.findViewById(R.id.tv_distance);
        this.gnssError = view.findViewById(R.id.tv_gnss_error);
        this.startButton = view.findViewById(R.id.button_start);
        this.stopButton = view.findViewById(R.id.button_stop);

        // ✅ **设置默认 UI 值**
        this.gnssError.setVisibility(View.GONE);
        this.elevation.setText("Elevation: 0.0 m");
        this.distanceTravelled.setText("Distance: 0.0 m");

        // ✅ **重置轨迹计算变量**
        this.distance = 0f;
        this.previousPosX = 0f;
        this.previousPosY = 0f;

        // ✅ **Start 按钮（开始录制）**
        this.startButton.setOnClickListener(view1 -> {
            ifstart = true;

            // 停止之前的录制、传感器监听和定时任务
            sensorFusion.stopRecording();
            sensorFusion.stopListening();
            refreshDataHandler.removeCallbacks(refreshDataTask);
            // 第一次调用 resetMap()，立即重置地图
            resetMap();
            // 延迟一定时间后，再自动调用一次 resetMap() 模拟第二次点击
            new Handler().postDelayed(() -> {
                resetMap();
            }, 100); // 延迟100毫秒，你可以根据实际情况调整延迟时间

            if (sensorFusion != null) {
                sensorFusion.setContext(getActivity().getApplicationContext());
                sensorFusion.resumeListening();  // 注册所有传感器监听器
                sensorFusion.startRecording();
                Toast.makeText(getContext(), "Recording Started", Toast.LENGTH_SHORT).show();
                Log.d("RecordingFragment", "🚀 SensorFusion 录制已启动");
                isRecording = true; // 标记正在录制
                // 开始更新 UI
                refreshDataHandler.post(refreshDataTask);

            } else {
                Log.e("RecordingFragment", "❌ SensorFusion 未初始化！");
            }
        });

        // ✅ **Stop 按钮（结束录制 & 跳转）**
        this.stopButton.setOnClickListener(view1 -> {
            if (ifstart){
                if (sensorFusion != null) {
                    sensorFusion.stopRecording();
                    Toast.makeText(getContext(), "Recording Stopped", Toast.LENGTH_SHORT).show();
                    isRecording = false; // 标记录制已停止
                    Log.d("RecordingFragment", "🛑 SensorFusion 录制已停止");
                } else {
                    Log.e("RecordingFragment", "❌ SensorFusion 未初始化！");
                }

                // 停止 UI 更新任务
                if (refreshDataHandler != null) {
                    refreshDataHandler.removeCallbacks(refreshDataTask);
                }

                // ✅ **跳转至 FilesFragment**
                if (isAdded()) {

                    //Send trajectory data to the cloud
                    sensorFusion.sendTrajectoryToCloud();

                    FragmentTransaction transaction = requireActivity().getSupportFragmentManager().beginTransaction();
                    transaction.replace(R.id.fragment_container, new PositionFragment());
                    transaction.addToBackStack(null);
                    transaction.commit();
                } else {
                    Log.w("RecordingFragment", "⚠️ Fragment 已销毁，无法跳转");
                }
            }else{
                Toast.makeText(getContext(), "未开始录制", Toast.LENGTH_SHORT).show();
            }
        });

        // ✅ **初始化 UI 组件**
        this.floorUpButton = view.findViewById(R.id.floorUpButton);
        this.floorDownButton = view.findViewById(R.id.floorDownButton);
        this.autoFloor = view.findViewById(R.id.switch_auto_floor);

        // ✅ **设置默认状态**
        autoFloor.setChecked(true); // 🚀 默认开启自动楼层
        setFloorButtonVisibility(View.GONE); // 🚀 初始隐藏楼层切换按钮

        // ✅ **地图类型切换**
        mapDropdown();
        switchMap();

        // ✅ **楼层上升按钮**
        this.floorUpButton.setOnClickListener(view1 -> {
            autoFloor.setChecked(false); // 🚀 关闭 Auto Floor
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
                Log.d("FloorControl", "📈 楼层上升");
            } else {
                Log.e("FloorControl", "❌ indoorMapManager 为空，无法切换楼层！");
            }
        });

        // ✅ **楼层下降按钮**
        this.floorDownButton.setOnClickListener(view1 -> {
            autoFloor.setChecked(false); // 🚀 关闭 Auto Floor
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
                Log.d("FloorControl", "📉 楼层下降");
            } else {
                Log.e("FloorControl", "❌ indoorMapManager 为空，无法切换楼层！");
            }
        });

        // ✅ **自动楼层切换**
        this.autoFloor.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                Log.d("FloorControl", "✅ Auto Floor 已启用");
            } else {
                Log.d("FloorControl", "⚠️ Auto Floor 已禁用，用户需要手动切换楼层");
            }
        });

        // ✅ **绑定 GNSS 开关**
        this.gnss = view.findViewById(R.id.switch_gnss);

        // GNSS 开关监听器
        this.gnss.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked) {
                // 获取所有传感器数据（其中 GNSS 数据不依赖 pdrProcessing）
                Map<SensorTypes, float[]> sensorData = sensorFusion.getSensorValueMap();
                if (sensorData == null) {
                    Toast.makeText(getContext(), "传感器数据不可用", Toast.LENGTH_SHORT).show();
                    gnss.setChecked(false);
                    return;
                }

                // 获取 GNSS 数据
                float[] gnssData = sensorData.get(SensorTypes.GNSSLATLONG);
                if (gnssData == null || gnssData.length < 2) {
                    Toast.makeText(getContext(), "GNSS 数据不可用", Toast.LENGTH_SHORT).show();
                    gnss.setChecked(false);
                    return;
                }

                // 将 GNSS 数据转换为 LatLng 对象
                LatLng gnssLocation = new LatLng(gnssData[0], gnssData[1]);

                // 判断 orientationMarker 是否存在
                if (orientationMarker != null) {
                    LatLng orientationPos = orientationMarker.getPosition();
                    // 计算 orientationMarker 与 GNSS 数据之间的距离（单位：米）
                    double distance = UtilFunctions.distanceBetweenPoints(orientationPos, gnssLocation);
                    // 设置一个距离阈值，判断两者是否“特别接近”
                    final double THRESHOLD_DISTANCE = 1.0; // 阈值为1米，可根据需要调整

                    if (distance < THRESHOLD_DISTANCE) {
                        // 如果两者非常接近，则只保留 orientationMarker，
                        // 同时确保删除之前可能存在的 GNSS Marker
                        if (gnssMarker != null) {
                            gnssMarker.remove();
                            gnssMarker = null;
                        }
                        // 可在界面上显示一个提示，告诉用户两者非常接近
                        gnssError.setVisibility(View.VISIBLE);
                        String GnssErrorRound = df.format(distance);
                        gnss.setText("GNSS error: " + GnssErrorRound + " m");
//                        gnssError.setText("GNSS error: " + String.format("%.2f", distance) + " m (位置接近)");
                    } else {
                        // 如果距离大于阈值，则在地图上显示一个 GNSS Marker，
                        // 以便用户可以比较 orientationMarker 与 GNSS Marker 之间的距离
                        if (gnssMarker == null) {
                            gnssMarker = gMap.addMarker(new MarkerOptions()
                                    .title("GNSS Position")
                                    .position(gnssLocation)
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                        } else {
                            gnssMarker.setPosition(gnssLocation);
                        }
                        gnssError.setVisibility(View.VISIBLE);
                        gnssError.setText("GNSS error: " + String.format("%.2f", distance) + " m");
                    }
                } else {
                    // 如果 orientationMarker 尚未创建（这种情况比较少见），直接显示 GNSS Marker
                    if (gnssMarker == null) {
                        gnssMarker = gMap.addMarker(new MarkerOptions()
                                .title("GNSS Position")
                                .position(gnssLocation)
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                    } else {
                        gnssMarker.setPosition(gnssLocation);
                    }
                    gnssError.setVisibility(View.VISIBLE);
                    gnssError.setText("GNSS error: 无法比较（orientationMarker 未就绪）");
                }
            } else {
                // 当 GNSS 关闭时，只保留 orientationMarker，将 GNSS Marker 移除
                if (gnssMarker != null) {
                    gnssMarker.remove();
                    gnssMarker = null;
                }
                gnssError.setVisibility(View.GONE);
                Log.d("GNSS", "GNSS Marker 已移除，仅保留 orientationMarker");
            }
        });

    }

    private void resetMap() {
        // 如果地图对象 gMap 不为 null，则清除所有覆盖物
        if (gMap != null) {
            orientationMarker.remove();
            polyline.remove();
        }
        // 重置当前位置信息为初始位置（假设 start 是你的初始位置）
        currentLocation = new LatLng(start.latitude, start.longitude);

        // 重置摄像机视角（例如 zoom 为 19f）
        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 19f));

        // 重新添加 orientationMarker 到初始位置
        orientationMarker = gMap.addMarker(new MarkerOptions()
                .position(currentLocation)
                .title("Current Position")
                .flat(true)
                .icon(BitmapDescriptorFactory.fromBitmap(
                        UtilFunctions.getBitmapFromVector(requireContext(), R.drawable.ic_baseline_navigation_24)
                )));

        // 重新创建轨迹 Polyline，以初始位置为起点
        polyline = gMap.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .add(currentLocation)
                .zIndex(6));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_ACTIVITY_RECOGNITION_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限授予，可以继续录制
                Log.d("RecordingFragment", "✅ 已授予活动识别权限");
            } else {
                // 权限被拒绝，提示用户
                Log.w("RecordingFragment", "⚠️ 未授予活动识别权限");
            }
        }
    }

    /**
     * Creates and initializes the dropdown for switching map types.
     */
    private void mapDropdown() {
        // ✅ 获取 Spinner 控件
        switchMapSpinner = getView().findViewById(R.id.spinner_map_type);

        if (switchMapSpinner == null) {
            Log.e("MapDropdown", "❌ Spinner is NULL! Cannot initialize dropdown.");
            return;
        }

        // ✅ 定义地图类型选项
        String[] maps = new String[]{
                "Hybrid",
                "Normal",
                "Satellite"
        };

        // ✅ 创建适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                maps
        );

        // ✅ 设置适配器
        switchMapSpinner.setAdapter(adapter);

        // ✅ 设置默认选项（如 Hybrid）
        switchMapSpinner.setSelection(0); // 默认选项为第一个（Hybrid）

        Log.d("MapDropdown", "✅ Map dropdown initialized with default selection: Hybrid");
    }

    /**
     * Spinner listener to change map type based on user selection.
     */
    private void switchMap() {
        if (switchMapSpinner == null) {
            Log.e("MapSwitch", "❌ Spinner is NULL! Cannot set listener.");
            return;
        }

        switchMapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (gMap == null) {
                    Log.e("MapSwitch", "❌ GoogleMap is NULL! Cannot switch map type.");
                    return;
                }

                // ✅ **使用 HashMap 代替硬编码索引**
                Map<Integer, Integer> mapTypeMap = new HashMap<>();
                mapTypeMap.put(0, GoogleMap.MAP_TYPE_HYBRID);
                mapTypeMap.put(1, GoogleMap.MAP_TYPE_NORMAL);
                mapTypeMap.put(2, GoogleMap.MAP_TYPE_SATELLITE);

                if (mapTypeMap.containsKey(position)) {
                    gMap.setMapType(mapTypeMap.get(position));
                    Log.d("MapSwitch", "✅ Switched to: " + position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                if (gMap != null) {
                    gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                    Log.d("MapSwitch", "⚠️ No selection, defaulting to Hybrid.");
                }
            }
        });
    }

    /**
     * 🔄 Runnable task for refreshing UI with live data (every 200ms)
     */
    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            if (refreshDataHandler != null) {
                updateUIandPosition();
                refreshDataHandler.postDelayed(this, 200);
            } else {
                Log.e("refreshDataTask", "❌ Handler is NULL! Stopping refresh.");
            }
        }
    };

    /**
     * 🔄 更新 UI 并计算 PDR 轨迹
     * - 计算用户步行轨迹 & 距离
     * - 处理 GNSS 误差
     * - 更新室内地图楼层
     * - 旋转方向箭头
     */
    private void updateUIandPosition() {
//        Log.d("updateUI", "更新UI和位置...");
        // ✅ **获取 PDR 数据**（检查是否为 null）
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null || pdrValues.length < 2) {
//            Log.e("updateUI", "❌ PDR Data is NULL or Incomplete!");
            return;
        }

        // ✅ **计算移动距离**
        float deltaX = pdrValues[0] - previousPosX;
        float deltaY = pdrValues[1] - previousPosY;
        float stepDistance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
//        Log.d("deltaX", "🚶‍♂️ Delta X: " + deltaX);
//        Log.d("deltaY", "🚶‍♂️ Delta Y: " + deltaY);
//        Log.d("updateUI", "🚶‍♂️ Step Distance: " + stepDistance);

        // ✅ **避免误差累积（例如 < 0.001m 变化忽略）**
        if (stepDistance > 0.001f) {
            distance += stepDistance;
            distanceTravelled.setText("Distance: " + String.format("%.2f", distance) + " m");

            // ✅ **绘制轨迹（只在用户真正移动时）**
            plotLines(new float[]{deltaX, deltaY});
        }

        // ✅ **检查室内地图管理器**
        if (indoorMapManager == null) {
            indoorMapManager = new IndoorMapManager(gMap);
        }

        // ✅ **GNSS 误差计算 & GNSS Marker 位置更新**
        if (gnss != null && gnss.isChecked()) {
            float[] gnssData = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
            if (gnssData != null && gnssData.length >= 2) {
                LatLng gnssLocation = new LatLng(gnssData[0], gnssData[1]);

                // 计算 GNSS 和 PDR 位置的误差
                double error = UtilFunctions.distanceBetweenPoints(currentLocation, gnssLocation);
                gnssError.setVisibility(View.VISIBLE);
                gnssError.setText("GNSS error: " + error + " m");

                // 更新 GNSS Marker 位置
                if (gnssMarker != null) {
                    gnssMarker.setPosition(gnssLocation);
                } else {
                    gnssMarker = gMap.addMarker(new MarkerOptions()
                            .position(gnssLocation)
                            .title("GNSS Position")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                }
            }
        } else {
            if (gnssMarker != null) {
                gnssMarker.remove();
                gnssMarker = null;
            }
            gnssError.setVisibility(View.GONE);
        }

        // ✅ **室内地图管理**
        indoorMapManager.setCurrentLocation(currentLocation);
        float elevationVal = sensorFusion.getElevation();

        // ✅ **检查是否在室内地图**
        if (indoorMapManager.getIsIndoorMapSet()) {
            setFloorButtonVisibility(View.VISIBLE);

            // **Auto Floor 功能**
            if (autoFloor != null && autoFloor.isChecked()) {
                int estimatedFloor = (int) (elevationVal / indoorMapManager.getFloorHeight());
                indoorMapManager.setCurrentFloor(estimatedFloor, true);
            }
        } else {
            setFloorButtonVisibility(View.GONE);
        }

        // ✅ **存储上一次的 PDR 位置**
        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];

        // ✅ **更新 UI Elevation**
        elevation.setText("Elevation: " + String.format("%.2f", elevationVal) + " m");

        // ✅ **旋转方向箭头**
        if (orientationMarker != null) {
            float heading = (float) Math.toDegrees(sensorFusion.passOrientation());
            orientationMarker.setRotation(heading);
        }
    }

    /**
     * 🔄 计算并绘制 PDR 轨迹
     * - 计算用户位置
     * - 更新轨迹折线（Polyline）
     * - 调整地图视角
     * @param pdrMoved 包含 X/Y 方向上的 PDR 变化量
     */
    private void plotLines(float[] pdrMoved) {
        if (pdrMoved == null || pdrMoved.length < 2) {
            Log.e("PlottingPDR", "❌ Invalid pdrMoved data!");
            return;
        }

        if (currentLocation != null) {
            // ✅ **计算新位置**
            LatLng nextLocation = UtilFunctions.calculateNewPos(currentLocation, pdrMoved);
            if (nextLocation == null) {
                Log.e("PlottingPDR", "❌ nextLocation is NULL!");
                return;
            }

            try {
                // ✅ **更新 PDR 轨迹**
                List<LatLng> points = new ArrayList<>(polyline.getPoints()); // 🔥 避免 GC 频繁回收
                points.add(nextLocation);
                polyline.setPoints(points);

                // ✅ **移动方向指示 Marker**
                if (orientationMarker != null) {
                    orientationMarker.setPosition(nextLocation);
                }

                // ✅ **平滑移动摄像机**
                gMap.animateCamera(CameraUpdateFactory.newLatLngZoom(nextLocation, 19f));

            } catch (Exception ex) {
                Log.e("PlottingPDR", "❌ Exception: " + ex.getMessage());
            }

            // ✅ **更新当前位置**
            currentLocation = nextLocation;
        } else {
            // **初始化起始位置**
            float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
            if (location != null && location.length >= 2) {
                currentLocation = new LatLng(location[0], location[1]);
                nextLocation = currentLocation;
            } else {
                Log.e("PlottingPDR", "❌ GNSS location unavailable!");
            }
        }
    }

    /**
     * 🔄 设置楼层按钮（Floor Up/Down & Auto-Floor）的可见性
     * @param visibility 可见性（View.VISIBLE / View.INVISIBLE / View.GONE）
     */
    private void setFloorButtonVisibility(int visibility) {
        if (floorUpButton != null) {
            floorUpButton.setVisibility(visibility);
        } else {
            Log.e("UI Visibility", "❌ floorUpButton is NULL!");
        }

        if (floorDownButton != null) {
            floorDownButton.setVisibility(visibility);
        } else {
            Log.e("UI Visibility", "❌ floorDownButton is NULL!");
        }

        if (autoFloor != null) {
            autoFloor.setVisibility(visibility);
        } else {
            Log.e("UI Visibility", "❌ autoFloor Switch is NULL!");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 如果当前处于录制状态，则恢复 UI 更新任务
        if (isRecording && refreshDataHandler != null) {
            refreshDataHandler.post(refreshDataTask);
            Log.d("RecordingFragment", "✅ onResume: 恢复 UI 刷新任务");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // 离开页面时停止 UI 更新任务，避免后台执行
        if (refreshDataHandler != null) {
            refreshDataHandler.removeCallbacks(refreshDataTask);
            Log.d("RecordingFragment", "⏹ onPause: 停止 UI 刷新任务");
        }
        sensorFusion.stopListening(); // 停止所有传感器监听器
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 清除所有 Handler 回调，防止内存泄漏
        if (refreshDataHandler != null) {
            refreshDataHandler.removeCallbacksAndMessages(null);
            Log.d("RecordingFragment", "🔥 onDestroy: 清理所有 Handler 回调");
        }
    }

}
