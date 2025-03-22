package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.hardware.SensorManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.Toast;
import android.graphics.Color;
import android.util.Log;

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
import com.openpositioning.PositionMe.BuildingPolygon;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * ReplayFragment: 在地图上回放 GNSS + PDR + Pressure 等轨迹数据。
 * 修复了"并行递增索引导致的时间错位"问题，改为基于相对时间戳 (relative_timestamp) 顺序回放。
 */
public class ReplayFragment extends Fragment implements OnMapReadyCallback {

    private MapView mapView;
    private GoogleMap mMap;
    private Button btnPlayPause, btnRestart, btnGoToEnd, btnExit;
    private SeekBar progressBar;

    // 播放控制
    private boolean isPlaying = false;
    private Handler playbackHandler = new Handler(Looper.getMainLooper());

    // 轨迹数据
    private Traj.Trajectory trajectory;
    private List<Traj.GNSS_Sample> gnssPositions;
    private List<Traj.Pdr_Sample> pdrPositions;
    private List<Traj.Pressure_Sample> pressureinfos;
    private List<Traj.WiFi_Sample> wifiSamples;
    private List<Float> altitudeList;
    private List<Float> relativeAltitudeList;

    private Polyline gnssPolyline;
    private Polyline pdrPolyline;
    private Polyline wifiPolyline;
    private Marker gnssMarker;
    private Marker pdrMarker;
    private Marker wifiMarker;
    private PdrProcessing pdrProcessing;

    private IndoorMapManager indoorMapManager;

    // 当前位置信息（用于更新 indoorMapManager）
    private LatLng currentLocation;

    // 楼层切换相关变量（与你原先代码一致）
    private int currentMeasuredFloor = 0;
    private final float FLOOR_HEIGHT = 4.2f;
    private final float TOLERANCE = 0.5f;
    // 注意：为让楼层检测更"敏感"一点，这里把 CONSECUTIVE_THRESHOLD 设为 1
    private final int CONSECUTIVE_THRESHOLD = 1;
    private int upCounter = 0;
    private int downCounter = 0;

    // 手动调整楼层控件
    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton;
    private com.google.android.material.floatingactionbutton.FloatingActionButton floorDownButton;
    private Switch autoFloor;

    // 文件路径
    private String filePath;

    // --------------------------
    //  新增：事件队列 (mergedEvents)
    // --------------------------
    // 用于合并 GNSS/PDR/Pressure 并按时间戳回放
    private List<Event> mergedEvents = new ArrayList<>();
    private int currentEventIndex = 0;  // 当前事件播放到哪一个
    private long startReplayTimestamp;  // 用于"模拟真实间隔"时，记录回放开始的系统时间

    // WiFi定位服务
    private WiFiPositioning wiFiPositioning;
    // 存储WiFi样本对应的位置信息
    private Map<Long, LatLng> wifiPositionCache = new HashMap<>();
    // 标记位置请求是否完成
    private boolean wifiPositionRequestsComplete = false;

    private List<LatLng> wifiTrajectoryPoints = new ArrayList<>();

    // 添加常量以匹配SensorFusion中的键名
    private static final String WIFI_FINGERPRINT = "wf";

    /** 用于统一管理不同类型的轨迹事件 */
    private static class Event {
        long relativeTime;  // the relative_timestamp from the GNSS/PDR/Pressures
        int eventType;      // 0=GNSS, 1=PDR, 2=Pressure, 3=WiFi
        Traj.GNSS_Sample gnss;
        Traj.Pdr_Sample pdr;
        Traj.Pressure_Sample pressure;
        Traj.WiFi_Sample wifi;
    }

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

        // 楼层控制控件
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
        
        // 初始化 WiFiPositioning
        wiFiPositioning = new WiFiPositioning(getContext());

        // 获取传入的文件路径
        if (getArguments() != null) {
            filePath = getArguments().getString("trajectory_file_path");
        }
        if (filePath == null) {
            Toast.makeText(getContext(), "No trajectory file provided", Toast.LENGTH_SHORT).show();
            return;
        }

        // 读取轨迹文件
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
            wifiSamples = trajectory.getWifiDataList();

            altitudeList = new ArrayList<>();
            relativeAltitudeList = new ArrayList<>();
            if (pressureinfos != null && !pressureinfos.isEmpty()) {
                for (Traj.Pressure_Sample ps : pressureinfos) {
                    float alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, ps.getPressure());
                    altitudeList.add(alt);
                }
            }
            // 生成相对海拔列表
            for (Float absoluteAlt : altitudeList) {
                float relativeAlt = pdrProcessing.updateElevation(absoluteAlt);
                relativeAltitudeList.add(relativeAlt);
            }

            // 预处理WiFi数据，获取位置信息
            if (wifiSamples != null && !wifiSamples.isEmpty()) {
                processWifiSamplesForPositioning();
            }

            // -----------------------------
            // **关键：构建 mergedEvents 列表**
            // -----------------------------
            buildMergedEvents();

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

        // 进度条最大值改为 mergedEvents.size()
        if (!mergedEvents.isEmpty()) {
            progressBar.setMax(mergedEvents.size());
        }

        // 进度条监听：拖动时跳到对应的 event
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentEventIndex = progress;
                    // 同步到当前 eventIndex 的地图 Marker 位置
                    updateMarkersForEventIndex(currentEventIndex);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                pauseReplay();
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 手动楼层调整
        floorUpButton.setOnClickListener(v -> {
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
        // 初始时，显示楼层控制控件
        setFloorButtonVisibility(View.VISIBLE);
    }

    /**
     * 预处理WiFi样本数据，获取位置信息
     * 将所有WiFi样本发送到服务器获取位置，并缓存结果
     */
    private void processWifiSamplesForPositioning() {
        // 显示进度提示
        Toast.makeText(getContext(), "Processing WiFi data...", Toast.LENGTH_SHORT).show();
        
        final int[] completedRequests = {0};
        final int totalRequests = wifiSamples.size();
        
        for (Traj.WiFi_Sample sample : wifiSamples) {
            final long timestamp = sample.getRelativeTimestamp();
            
            // 创建WiFi指纹对象
            try {
                JSONObject wifiAccessPoints = new JSONObject();
                for (Traj.Mac_Scan scan : sample.getMacScansList()) {
                    wifiAccessPoints.put(String.valueOf(scan.getMac()), scan.getRssi());
                }
                
                // 创建POST请求对象
                JSONObject wifiFingerPrint = new JSONObject();
                wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
                
                // 发送请求到WiFiPositioning服务
                wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                    @Override
                    public void onSuccess(LatLng location, int floor) {
                        // 缓存位置结果
                        wifiPositionCache.put(timestamp, location);
                        completedRequests[0]++;
                        
                        // 当所有请求完成时，更新UI并重建事件列表
                        if (completedRequests[0] >= totalRequests) {
                            wifiPositionRequestsComplete = true;
                            // 在UI线程更新
                            new Handler(Looper.getMainLooper()).post(() -> {
                                Toast.makeText(getContext(), "WiFi positioning complete", Toast.LENGTH_SHORT).show();

                                if (mMap != null) {
                                    drawFullWifiTrack();

                                }
                            });
                        }
                    }
                    
                    @Override
                    public void onError(String message) {
                        // 处理错误情况
                        completedRequests[0]++;
                        Log.e("WiFiPositioning", "Error: " + message);
                        
                        // 即使有错误，当所有请求完成时也标记为完成
                        if (completedRequests[0] >= totalRequests) {
                            wifiPositionRequestsComplete = true;
                            new Handler(Looper.getMainLooper()).post(() -> {
                                Toast.makeText(getContext(), "WiFi positioning completed with some errors", Toast.LENGTH_SHORT).show();

                                if (mMap != null) {


                                }
                            });
                        }
                    }
                });
                
            } catch (JSONException e) {
                Log.e("WiFiPositioning", "JSON Error: " + e.getMessage());
                completedRequests[0]++;
            }
        }
    }
    
    /**
     * 构建WiFi轨迹点列表
     * 按时间戳排序并应用平滑处理
     */
    private void drawFullWifiTrack() {
        if (mMap == null) return;
        if (!wifiPositionRequestsComplete) return; // 确保 WiFi 坐标已就绪

        // 如果之前有 wifiPolyline，先移除
        if (wifiPolyline != null) {
            wifiPolyline.remove();
            wifiPolyline = null;
        }

        // 新建绿色折线
        PolylineOptions wifiOptions = new PolylineOptions()
                .width(10)
                .color(Color.GREEN)
                .geodesic(true);

        // 按时间戳顺序，依次把 WiFi 坐标点加进来
        // （如果顺序不敏感也可直接遍历 wifiSamples，但最好先按 relativeTimestamp 排序）
        List<Traj.WiFi_Sample> sortedWifiSamples = new ArrayList<>(wifiSamples);
        Collections.sort(sortedWifiSamples, new Comparator<Traj.WiFi_Sample>() {
            @Override
            public int compare(Traj.WiFi_Sample o1, Traj.WiFi_Sample o2) {
                return Long.compare(o1.getRelativeTimestamp(), o2.getRelativeTimestamp());
            }
        });

        for (Traj.WiFi_Sample sample : sortedWifiSamples) {
            long ts = sample.getRelativeTimestamp();
            LatLng wifiLatLng = wifiPositionCache.get(ts);
            if (wifiLatLng != null) {
                wifiOptions.add(wifiLatLng);
            }
        }

        // 最后 addPolyline
        wifiPolyline = mMap.addPolyline(wifiOptions);

        // 还可以给 WiFi 在第一个位置放个初始 Marker，跟 GNSS/PDR 做法一致
        if (wifiMarker != null) {
            wifiMarker.remove();
            wifiMarker = null;
        }
        if (!sortedWifiSamples.isEmpty()) {
            LatLng firstPos = wifiPositionCache.get(sortedWifiSamples.get(0).getRelativeTimestamp());
            if (firstPos != null) {
                wifiMarker = mMap.addMarker(new MarkerOptions()
                        .position(firstPos)
                        .title("WiFi Position")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
            }
        }
    }



    /**
     * 绘制WiFi轨迹
     * 使用构建好的轨迹点列表绘制连续的轨迹线
     */


    /** 将 GNSS/PDR/Pressure/WiFi 四类数据合并到一个列表 mergedEvents */
    private void buildMergedEvents() {
        mergedEvents.clear();

        // GNSS
        if (gnssPositions != null) {
            for (Traj.GNSS_Sample g : gnssPositions) {
                Event e = new Event();
                e.relativeTime = g.getRelativeTimestamp();
                e.eventType = 0;
                e.gnss = g;
                mergedEvents.add(e);
            }
        }
        // PDR
        if (pdrPositions != null) {
            for (int i = 0; i < pdrPositions.size(); i++) {
                Traj.Pdr_Sample p = pdrPositions.get(i);
                Event e = new Event();
                e.relativeTime = p.getRelativeTimestamp();
                e.eventType = 1;
                e.pdr = p;
                mergedEvents.add(e);
            }
        }
        // Pressure
        if (pressureinfos != null) {
            for (int i = 0; i < pressureinfos.size(); i++) {
                Traj.Pressure_Sample pr = pressureinfos.get(i);
                Event e = new Event();
                e.relativeTime = pr.getRelativeTimestamp();
                e.eventType = 2;
                e.pressure = pr;
                mergedEvents.add(e);
            }
        }
        // WiFi
        if (wifiSamples != null) {
            for (int i = 0; i < wifiSamples.size(); i++) {
                Traj.WiFi_Sample w = wifiSamples.get(i);
                Event e = new Event();
                e.relativeTime = w.getRelativeTimestamp();
                e.eventType = 3;
                e.wifi = w;
                mergedEvents.add(e);
            }
        }
        // 排序：按 relativeTime 升序
        Collections.sort(mergedEvents, new Comparator<Event>() {
            @Override
            public int compare(Event o1, Event o2) {
                return Long.compare(o1.relativeTime, o2.relativeTime);
            }
        });
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

    /**
     * 当手动拖动进度条或播放到某个 eventIndex 时，需要把地图 Marker
     * 更新到 mergedEvents[0..eventIndex] 的最后状态
     */
    private void updateMarkersForEventIndex(int eventIndex) {
        if (mMap == null || indoorMapManager == null || mergedEvents.isEmpty()) return;

        // 先从头到该 eventIndex，依次处理 event；这样能"模拟"到当前状态
        // 由于用户可能频繁拖拽，为了简化，这里就直接从0播到eventIndex
        // 若数据量很大，可以优化为"记住上一次的状态"，这里只求逻辑清晰

        // 重置marker
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
        if (pdrMarker != null) {
            pdrMarker.remove();
            pdrMarker = null;
        }
        if (wifiMarker != null) {
            wifiMarker.remove();
            wifiMarker = null;
        }

        currentLocation = null;  // 每次重头更新

        // 重置楼层检测计数
        currentMeasuredFloor = 0;
        upCounter = 0;
        downCounter = 0;

        for (int i = 0; i <= eventIndex && i < mergedEvents.size(); i++) {
            processEvent(mergedEvents.get(i), false);
        }
        // 同时更新进度条
        progressBar.setProgress(eventIndex);
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
            PolylineOptions gnssOptions = new PolylineOptions()
                .color(Color.BLUE)
                .width(10);
            for (Traj.GNSS_Sample sample : gnssPositions) {
                LatLng latLng = new LatLng(sample.getLatitude(), sample.getLongitude());
                gnssOptions.add(latLng);
            }
            gnssPolyline = mMap.addPolyline(gnssOptions);
            LatLng gnssStart = new LatLng(
                    gnssPositions.get(0).getLatitude(),
                    gnssPositions.get(0).getLongitude()
            );
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(gnssStart, 18f));
            gnssMarker = mMap.addMarker(new MarkerOptions()
                    .position(gnssStart)
                    .title("GNSS Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
            currentLocation = gnssStart;
        }

        // 绘制 PDR 轨迹（红色）
        if (pdrPositions != null && !pdrPositions.isEmpty()) {
            LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty())
                    ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude())
                    : new LatLng(0, 0);
            PolylineOptions pdrOptions = new PolylineOptions()
                .color(Color.RED)
                .width(10);
            for (Traj.Pdr_Sample sample : pdrPositions) {
                float[] pdrOffset = new float[]{sample.getX(), sample.getY()};
                LatLng latLng = UtilFunctions.calculateNewPos(pdrStart, pdrOffset);
                pdrOptions.add(latLng);
            }
            pdrPolyline = mMap.addPolyline(pdrOptions);
            if (!pdrOptions.getPoints().isEmpty()) {
                pdrMarker = mMap.addMarker(new MarkerOptions()
                        .position(pdrOptions.getPoints().get(0))
                        .title("PDR Position")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
            }
        }

        



        // 初始化 IndoorMapManager
        indoorMapManager = new IndoorMapManager(mMap);
    }

    /**
     * 开始回放：基于 mergedEvents（时间戳排序）逐条处理
     * 不再并行递增索引。
     */
    private void startReplay() {
        if (mergedEvents.isEmpty()) {
            return;
        }
        isPlaying = true;
        btnPlayPause.setText("Pause");

        currentEventIndex = 0;
        // 把地图 Marker 同步到第0个事件的状态
        updateMarkersForEventIndex(0);

        // 开始逐条调度
        scheduleNext();
    }

    /** 停止回放 */
    private void pauseReplay() {
        isPlaying = false;
        btnPlayPause.setText("Play");
        playbackHandler.removeCallbacksAndMessages(null);
    }

    /** 重新开始回放 */
    private void restartReplay() {
        pauseReplay();
        currentEventIndex = 0;
        updateMarkersForEventIndex(0);
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentFloor(0, true);
        }

        startReplay();
        startReplay();
    }

    /** 跳到末尾（最后一个事件） */
    private void goToEndReplay() {
        pauseReplay();
        if (!mergedEvents.isEmpty()) {
            currentEventIndex = mergedEvents.size() - 1;
            updateMarkersForEventIndex(currentEventIndex);
        }
    }

    private void exitReplay() {
        pauseReplay();
        getActivity().onBackPressed();
    }

    /**
     * 核心：调度下一条事件
     * 根据相邻事件的时间戳差值，动态计算延迟
     */
    private void scheduleNext() {
        if (!isPlaying || currentEventIndex >= mergedEvents.size()) {
            pauseReplay();
            return;
        }

        // 当前事件
        final Event current = mergedEvents.get(currentEventIndex);
        // 下一个事件（如果有）
        final int nextIndex = currentEventIndex + 1;
        long delayMs = 500; // 默认半秒
        if (nextIndex < mergedEvents.size()) {
            long dt = mergedEvents.get(nextIndex).relativeTime - current.relativeTime;
            // 你也可以把 dt 直接当延迟，也可做倍速: e.g. dt / 2
            // 这里简单地用 dt
            delayMs = dt;
            if (delayMs < 0) {
                // 极端情况：如果时间戳没排序好或重复，保证不出现负值
                delayMs = 0;
            }
        }

        // 立即处理当前事件
        processEvent(current, true);

        // 调度播放下一个事件
        currentEventIndex++;
        progressBar.setProgress(currentEventIndex);

        playbackHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                scheduleNext();
            }
        }, delayMs);
    }

    /**
     * 处理单个 Event：根据 eventType 更新 GNSS / PDR / Pressure（海拔+楼层检测）/ WiFi
     * @param immediate 是否是自动播放时按节奏触发；如果是 "手动一次性处理" 则 immediate=false
     */
    private void processEvent(Event e, boolean immediate) {
        switch (e.eventType) {
            case 0: // GNSS
                if (e.gnss != null) {
                    Traj.GNSS_Sample sample = e.gnss;
                    LatLng latLng = new LatLng(sample.getLatitude(), sample.getLongitude());
                    currentLocation = latLng;
                    if (gnssMarker != null) {
                        gnssMarker.setPosition(latLng);
                    } else {
                        gnssMarker = mMap.addMarker(new MarkerOptions()
                                .position(latLng)
                                .title("GNSS Position")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
                    }
                    if (indoorMapManager != null) {
                        indoorMapManager.setCurrentLocation(latLng);
                    }
                }
                break;
            case 1: // PDR
                if (e.pdr != null) {
                    Traj.Pdr_Sample pdrSample = e.pdr;
                    // PDR 假设相对 (gnss首点) 来计算位置
                    LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty())
                            ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude())
                            : new LatLng(0, 0);
                    float[] pdrOffset = new float[]{pdrSample.getX(), pdrSample.getY()};
                    LatLng latLng = UtilFunctions.calculateNewPos(pdrStart, pdrOffset);

                    if (pdrMarker != null) {
                        pdrMarker.setPosition(latLng);
                    } else {
                        pdrMarker = mMap.addMarker(new MarkerOptions()
                                .position(latLng)
                                .title("PDR Position")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
                    }
                    // 如果我们有海拔数据(pressure)已处理，则更新楼层检测
                    // 不过 PDR 事件本身不带海拔；海拔更新在 pressure event
                }
                break;
            case 2: // PRESSURE
                // 在 playback 时，用 relativeAltitudeList 来做海拔
                // 由于 pressure list 与 mergedEvents 不一定一一对应下标，
                // 需要找到在 pressureinfos 中的 index
                Traj.Pressure_Sample pr = e.pressure;
                if (pr != null && pressureinfos != null && !pressureinfos.isEmpty()) {
                    // 找到 pr 在 pressureinfos 中的顺序 index
                    int idx = pressureinfos.indexOf(pr);
                    if (idx >= 0 && idx < relativeAltitudeList.size()) {
                        float currentRelAlt = relativeAltitudeList.get(idx);
                        // 如果 pdrMarker 存在，就更新 snippet
                        if (pdrMarker != null) {
                            pdrMarker.setSnippet("Altitude: " + String.format("%.1f", currentRelAlt) + " m");
                        }
                        // 自动楼层检测
                        if (autoFloor != null && autoFloor.isChecked()) {
                            float buildingFloorHeight = FLOOR_HEIGHT;
                            if (currentLocation != null) {
                                if (BuildingPolygon.inNucleus(currentLocation)) {
                                    buildingFloorHeight = IndoorMapManager.NUCLEUS_FLOOR_HEIGHT;
                                } else if (BuildingPolygon.inLibrary(currentLocation)) {
                                    buildingFloorHeight = IndoorMapManager.LIBRARY_FLOOR_HEIGHT;
                                } else {
                                    buildingFloorHeight = 0;
                                }
                            }

                            if (buildingFloorHeight > 0 && indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
                                float expectedUp = (currentMeasuredFloor + 1) * buildingFloorHeight;
                                float expectedDown = (currentMeasuredFloor - 1) * buildingFloorHeight;

                                if (currentRelAlt >= (expectedUp - TOLERANCE) && currentRelAlt <= (expectedUp + TOLERANCE)) {
                                    upCounter++;
                                    downCounter = 0;
                                } else if (currentRelAlt >= (expectedDown - TOLERANCE) && currentRelAlt <= (expectedDown + TOLERANCE)) {
                                    downCounter++;
                                    upCounter = 0;
                                } else {
                                    upCounter = 0;
                                    downCounter = 0;
                                }

                                if (upCounter >= CONSECUTIVE_THRESHOLD) {
                                    currentMeasuredFloor++;
                                    indoorMapManager.setCurrentFloor(currentMeasuredFloor, true);
                                    if (currentLocation != null) {
                                        mMap.animateCamera(CameraUpdateFactory.newLatLng(currentLocation));
                                    }
                                    upCounter = 0;
                                }
                                if (downCounter >= CONSECUTIVE_THRESHOLD) {
                                    currentMeasuredFloor--;
                                    indoorMapManager.setCurrentFloor(currentMeasuredFloor, true);
                                    if (currentLocation != null) {
                                        mMap.animateCamera(CameraUpdateFactory.newLatLng(currentLocation));
                                    }
                                    downCounter = 0;
                                }
                            }
                        }
                    }
                }
                break;
            case 3: // WIFI
                if (e.wifi != null) {
                    Traj.WiFi_Sample wifiSample = e.wifi;
                    long timestamp = wifiSample.getRelativeTimestamp();
                    LatLng wifiPosition = wifiPositionCache.get(timestamp);
                    if (wifiPosition != null) {
                        // 动态移动 Marker
                        if (wifiMarker == null) {
                            wifiMarker = mMap.addMarker(new MarkerOptions()
                                    .position(wifiPosition)
                                    .title("WiFi Position")
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
                        } else {
                            wifiMarker.setPosition(wifiPosition);
                        }

                        // 也可以更新 Marker 的 snippet
                        wifiMarker.setSnippet("Networks: " + wifiSample.getMacScansCount());
                        // 如果想回放时摄像头跟随 WiFi，可以加上
                        if (immediate) {
                            mMap.animateCamera(CameraUpdateFactory.newLatLng(wifiPosition));
                        }
                    }
                }
                break;
        }
    }

    // 查找与给定时间戳最接近的GNSS样本
    private Traj.GNSS_Sample findClosestGnssSample(long timestamp) {
        if (gnssPositions == null || gnssPositions.isEmpty()) {
            return null;
        }
        
        Traj.GNSS_Sample closest = null;
        long minTimeDiff = Long.MAX_VALUE;
        
        for (Traj.GNSS_Sample sample : gnssPositions) {
            long timeDiff = Math.abs(sample.getRelativeTimestamp() - timestamp);
            if (timeDiff < minTimeDiff) {
                minTimeDiff = timeDiff;
                closest = sample;
            }
        }
        
        return closest;
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
        // 退出时删除临时文件
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
