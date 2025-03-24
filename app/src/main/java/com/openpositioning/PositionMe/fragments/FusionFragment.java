package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.BuildingPolygon;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.KFLinear2D;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * 融合了定位与室内楼层图切换功能的 FusionFragment：
 * 1. 使用卡尔曼滤波器融合 WiFi 与 GNSS 数据，并结合 PDR 增量更新位置；
 * 2. 对融合后的最佳位置进行指数平滑滤波，确保显示平稳；
 * 3. 分别记录最近 MAX_OBSERVATIONS 次 GNSS、WiFi 和 PDR 的绝对定位数据，并用不同颜色 Marker 显示；
 * 4. 显示融合位置全轨迹（红色折线），并调用 IndoorMapManager 更新楼层信息，
 *    保证楼层图保持不变（不使用 mMap.clear() 清除楼层图）。
 * 5. 新增 “Add tag” 按钮，允许用户将当前定位打上标签写入 Trajectory；
 * 6. 计算并显示定位精度（基于 KF 的协方差矩阵）；
 * 7. 根据当前融合位置判断室内/室外状态并显示。
 */
public class FusionFragment extends Fragment implements OnMapReadyCallback {

    private static final String TAG = "FusionFragment";

    private GoogleMap mMap;
    private Marker fusionMarker;
    private Polyline fusionPolyline;
    private List<LatLng> fusionPath = new ArrayList<>();

    // 保存最近 N 次观测
    private List<LatLng> gnssObservations = new ArrayList<>();
    private List<LatLng> wifiObservations = new ArrayList<>();
    private List<LatLng> pdrObservations = new ArrayList<>();
    private static final int MAX_OBSERVATIONS = 10;

    // 用于保存各传感器 Marker 的引用，方便清除
    private List<Marker> gnssMarkers = new ArrayList<>();
    private List<Marker> wifiMarkers = new ArrayList<>();
    private List<Marker> pdrMarkers = new ArrayList<>();

    private Handler updateHandler = new Handler(Looper.getMainLooper());
    private Runnable fusionUpdateRunnable;
    private static final long UPDATE_INTERVAL_MS = 1000; // 每秒更新一次

    private SensorFusion sensorFusion;
    private IndoorMapManager indoorMapManager;
    private Spinner mapSpinner;

    // 新增用于显示定位精度和室内/室外状态的 TextView
    private TextView accuracyTextView;
    private TextView indoorOutdoorTextView;

    // 卡尔曼滤波器 KF
    private KFLinear2D kf;
    private long lastUpdateTime = 0;

    // 局部坐标转换原点（经纬度），用于将经纬度转换为局部 (x,y)（单位：米）
    private double lat0Deg = 0.0;
    private double lon0Deg = 0.0;
    private boolean originSet = false;

    // 保存上一次 PDR 数据（用于计算增量）
    private float[] lastPdr = null;

    // 噪声参数
    private final double[][] R_wifi = { {20.0, 0.0}, {0.0, 20.0} };
    private final double[][] R_gnss = { {100.0, 0.0}, {0.0, 100.0} };
    private final double[][] Q = {
            {0.1, 0,   0,   0},
            {0,   0.1, 0,   0},
            {0,   0,   0.1, 0},
            {0,   0,   0,   0.1}
    };

    // 指数平滑滤波参数（取值范围 [0,1]，值越小平滑越强）
    private static final double SMOOTHING_ALPHA = 0.2;
    private LatLng smoothFusedPosition = null;

    // 用于记录录制开始的时间
    private long startTimestamp;

    public FusionFragment() {
        // required empty constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 初始化录制开始的时间
        startTimestamp = System.currentTimeMillis();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // 请确保 fragment_fusion.xml 中包含 id 为 addTagButton_fusion, accuracyTextView_fusion,
        // indoorOutdoorTextView_fusion 的控件
        return inflater.inflate(R.layout.fragment_fusion, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        sensorFusion = SensorFusion.getInstance();

        view.findViewById(R.id.exitButton_fusion).setOnClickListener(v ->
                Navigation.findNavController(v).popBackStack(R.id.homeFragment, false));

        // 楼层按钮点击事件
        view.findViewById(R.id.floorUpButton_fusion).setOnClickListener(v -> {
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
                if (smoothFusedPosition != null) {
                    updateFloor(smoothFusedPosition);
                }
            }
        });
        view.findViewById(R.id.floorDownButton_fusion).setOnClickListener(v -> {
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
                if (smoothFusedPosition != null) {
                    updateFloor(smoothFusedPosition);
                }
            }
        });

        mapSpinner = view.findViewById(R.id.mapSwitchSpinner_fusion);
        setupMapSpinner();

        // 初始化新加的控件：Add Tag 按钮和两个 TextView
        Button addTagButton = view.findViewById(R.id.addTagButton_fusion);
        accuracyTextView = view.findViewById(R.id.accuracyTextView_fusion);
        indoorOutdoorTextView = view.findViewById(R.id.indoorOutdoorTextView_fusion);

        addTagButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long relativeTimestamp = System.currentTimeMillis() - startTimestamp;
                if (smoothFusedPosition != null) {
                    double lat = smoothFusedPosition.latitude;
                    double lon = smoothFusedPosition.longitude;
                    float altitude = sensorFusion.getElevation();
                    // 调用 SensorFusion 中新增的 addFusionTag() 方法写入 Trajectory
                    sensorFusion.addFusionTag(relativeTimestamp, lat, lon, altitude, "fusion");
                    Log.d(TAG, "Add tag: timestamp=" + relativeTimestamp + ", lat=" + lat + ", lon=" + lon + ", alt=" + altitude);
                }
            }
        });

        fusionUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateFusionUI();
                updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
            }
        };

        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.fusionMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        } else {
            Log.e(TAG, "Map fragment is null");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        lastUpdateTime = System.currentTimeMillis();
        updateHandler.postDelayed(fusionUpdateRunnable, UPDATE_INTERVAL_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        updateHandler.removeCallbacks(fusionUpdateRunnable);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        this.mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        mMap.getUiSettings().setCompassEnabled(true);
        indoorMapManager = new IndoorMapManager(mMap);
        setupMapSpinner();
        // 当地图准备好后，IndoorMapManager 会根据当前位置自动添加楼层图覆盖物
    }

    private void updateFusionUI() {
        if (!originSet) {
            if (initLocalOriginIfPossible()) {
                initKalmanFilter();
            } else {
                return;
            }
        }
        if (kf == null) return;

        long now = System.currentTimeMillis();
        double dt = (now - lastUpdateTime) / 1000.0;
        lastUpdateTime = now;
        if (dt <= 0) dt = 0.001;

        // 1) 应用 PDR 增量
        applyPdrIncrement();

        // 2) KF 预测
        kf.predict(dt);

        // 3) 融合 WiFi 数据（噪声较大）
        LatLng wifiLatLon = sensorFusion.getLatLngWifiPositioning();
        if (wifiLatLon != null) {
            double[] localWifi = latLonToLocal(wifiLatLon.latitude, wifiLatLon.longitude);
            kf.setMeasurementNoise(R_wifi);
            kf.update(localWifi);
            Log.d(TAG, "KF updated with WiFi: " + wifiLatLon);
            addObservation(wifiObservations, wifiLatLon);
        }

        // 4) 融合 GNSS 数据（噪声较小）
        float[] gnssArr = sensorFusion.getGNSSLatitude(false);
        if (gnssArr != null && gnssArr.length >= 2) {
            float latGNSS = gnssArr[0];
            float lonGNSS = gnssArr[1];
            if (!(latGNSS == 0 && lonGNSS == 0)) {
                double[] localGnss = latLonToLocal(latGNSS, lonGNSS);
                kf.setMeasurementNoise(R_gnss);
                kf.update(localGnss);
                LatLng gnssLatLng = new LatLng(latGNSS, lonGNSS);
                Log.d(TAG, "KF updated with GNSS: " + gnssLatLng);
                addObservation(gnssObservations, gnssLatLng);
            }
        }

        // 5) 获取 KF 融合输出并转换为经纬度
        double[] xy = kf.getXY();
        double[] latlon = localToLatLon(xy[0], xy[1]);
        LatLng fusedLatLng = new LatLng(latlon[0], latlon[1]);

        // 6) 对融合结果进行指数平滑
        if (smoothFusedPosition == null) {
            smoothFusedPosition = fusedLatLng;
        } else {
            double smoothLat = SMOOTHING_ALPHA * fusedLatLng.latitude + (1 - SMOOTHING_ALPHA) * smoothFusedPosition.latitude;
            double smoothLon = SMOOTHING_ALPHA * fusedLatLng.longitude + (1 - SMOOTHING_ALPHA) * smoothFusedPosition.longitude;
            smoothFusedPosition = new LatLng(smoothLat, smoothLon);
        }
        fusionPath.add(smoothFusedPosition);

        // 7) 处理 PDR 数据（转换为绝对坐标）
        float[] pdrData = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrData != null && pdrData.length >= 2) {
            LatLng pdrLatLng = convertLocalToLatLon(pdrData[0], pdrData[1]);
            addObservation(pdrObservations, pdrLatLng);
        }

        // 8) 清除现有 Marker 与 Polyline（不调用 mMap.clear() 以保留楼层图覆盖物）
        if (fusionMarker != null) {
            fusionMarker.remove();
            fusionMarker = null;
        }
        if (fusionPolyline != null) {
            fusionPolyline.remove();
            fusionPolyline = null;
        }
        // 同时清除其他传感器 Marker（确保不会累积）
        for (Marker m : gnssMarkers) { m.remove(); }
        gnssMarkers.clear();
        for (Marker m : wifiMarkers) { m.remove(); }
        wifiMarkers.clear();
        for (Marker m : pdrMarkers) { m.remove(); }
        pdrMarkers.clear();

        // 9) 绘制融合位置 Marker 与全轨迹（红色）
        if (smoothFusedPosition != null) {
            fusionMarker = mMap.addMarker(new MarkerOptions()
                    .position(smoothFusedPosition)
                    .title("Fused Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .zIndex(1000));
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(smoothFusedPosition, 19f));
            if (fusionPath.size() > 1) {
                fusionPolyline = mMap.addPolyline(new PolylineOptions()
                        .addAll(fusionPath)
                        .color(android.graphics.Color.RED)
                        .width(10)
                        .zIndex(1000));
            }
        }

        // 10) 绘制 GNSS Marker（蓝色）
        for (LatLng pos : gnssObservations) {
            Marker marker = mMap.addMarker(new MarkerOptions()
                    .position(pos)
                    .title("GNSS")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    .zIndex(1000));
            gnssMarkers.add(marker);
        }
        // 11) 绘制 WiFi Marker（绿色）
        for (LatLng pos : wifiObservations) {
            Marker marker = mMap.addMarker(new MarkerOptions()
                    .position(pos)
                    .title("WiFi")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    .zIndex(1000));
            wifiMarkers.add(marker);
        }
        // 12) 绘制 PDR Marker（橙色）
        for (LatLng pos : pdrObservations) {
            Marker marker = mMap.addMarker(new MarkerOptions()
                    .position(pos)
                    .title("PDR")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                    .zIndex(1000));
            pdrMarkers.add(marker);
        }

        // 13) 计算定位精度并更新显示（利用 KF 的协方差矩阵）
        double[][] P = kf.getErrorCovariance(); // 新增方法，返回协方差矩阵 P
        double stdX = Math.sqrt(P[0][0]);
        double stdY = Math.sqrt(P[1][1]);
        double accuracy = (stdX + stdY) / 2.0;
        if (accuracyTextView != null) {
            accuracyTextView.setText(String.format("Accuracy: %.1f m", accuracy));
        }

        // 14) 检测室内/室外状态（根据融合位置是否落在建筑内）
        if (indoorOutdoorTextView != null) {
            if (BuildingPolygon.inNucleus(smoothFusedPosition) || BuildingPolygon.inLibrary(smoothFusedPosition)) {
                indoorOutdoorTextView.setText("Indoor");
            } else {
                indoorOutdoorTextView.setText("Outdoor");
            }
        }

        // 15) 更新楼层显示（更新 IndoorMapManager 中的楼层信息，楼层图由 IndoorMapManager 控制，不调用 mMap.clear()）
        updateFloor(smoothFusedPosition);
    }

    private boolean initLocalOriginIfPossible() {
        if (originSet) return true;
        LatLng wifi = sensorFusion.getLatLngWifiPositioning();
        if (wifi != null) {
            lat0Deg = wifi.latitude;
            lon0Deg = wifi.longitude;
            originSet = true;
            Log.d(TAG, "Local origin set from WiFi: " + wifi);
            return true;
        }
        float[] gnssArr = sensorFusion.getGNSSLatitude(false);
        if (gnssArr != null && gnssArr.length >= 2 && !(gnssArr[0] == 0 && gnssArr[1] == 0)) {
            lat0Deg = gnssArr[0];
            lon0Deg = gnssArr[1];
            originSet = true;
            Log.d(TAG, "Local origin set from GNSS: " + lat0Deg + ", " + lon0Deg);
            return true;
        }
        return false;
    }

    private void initKalmanFilter() {
        if (kf != null) return;
        double[] initialState = {0, 0, 0, 0};
        double[][] initialCov = {
                {10, 0, 0, 0},
                {0, 10, 0, 0},
                {0, 0, 10, 0},
                {0, 0, 0, 10}
        };
        kf = new KFLinear2D(initialState, initialCov, Q, R_wifi);
        Log.d(TAG, "KF built with local origin lat0=" + lat0Deg + ", lon0=" + lon0Deg);
    }

    private double[] latLonToLocal(double latDeg, double lonDeg) {
        double metersPerLat = 111320.0;
        double y = (latDeg - lat0Deg) * metersPerLat;
        double cosLat = Math.cos(Math.toRadians(lat0Deg));
        double metersPerLon = 111320.0 * cosLat;
        double x = (lonDeg - lon0Deg) * metersPerLon;
        return new double[]{x, y};
    }

    private double[] localToLatLon(double x, double y) {
        double metersPerLat = 111320.0;
        double latDiff = y / metersPerLat;
        double latDeg = lat0Deg + latDiff;
        double cosLat = Math.cos(Math.toRadians(lat0Deg));
        double metersPerLon = 111320.0 * cosLat;
        double lonDiff = x / metersPerLon;
        double lonDeg = lon0Deg + lonDiff;
        return new double[]{latDeg, lonDeg};
    }

    private void applyPdrIncrement() {
        float[] pdrNow = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrNow == null) return;
        if (!originSet) return;
        if (lastPdr == null) {
            lastPdr = pdrNow.clone();
            return;
        }
        float rawDx = pdrNow[0] - lastPdr[0];  // 前进/后退
        float rawDy = pdrNow[1] - lastPdr[1];  // 左右
        lastPdr[0] = pdrNow[0];
        lastPdr[1] = pdrNow[1];
        float dx = rawDy;
        float dy = rawDx;
        kf.applyPdrDelta(dx, dy);
    }

    private LatLng convertLocalToLatLon(float x, float y) {
        double[] latlon = localToLatLon(x, y);
        return new LatLng(latlon[0], latlon[1]);
    }

    private void addObservation(List<LatLng> list, LatLng pos) {
        list.add(pos);
        if (list.size() > MAX_OBSERVATIONS) {
            list.remove(0);
        }
    }

    private void updateFloor(LatLng fusedLatLng) {
        if (indoorMapManager == null) return;
        indoorMapManager.setCurrentLocation(fusedLatLng);
        float elevationVal = sensorFusion.getElevation();
        int wifiFloor = sensorFusion.getWifiFloor();
        int elevFloor = (int) Math.round(elevationVal / indoorMapManager.getFloorHeight());
        int avgFloor = Math.round((wifiFloor + elevFloor) / 2.0f);
        indoorMapManager.setCurrentFloor(avgFloor, true);
        indoorMapManager.setIndicationOfIndoorMap();
    }

    private void setupMapSpinner() {
        String[] maps = new String[]{"Hybrid", "Normal", "Satellite"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, maps);
        mapSpinner.setAdapter(adapter);
        mapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mMap == null) return;
                switch (position) {
                    case 0:
                        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                    case 1:
                        mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                        break;
                    case 2:
                        mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                        break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }
}
