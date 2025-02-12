package com.openpositioning.PositionMe.fragments;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.dataParser.GnssData;
import com.openpositioning.PositionMe.dataParser.PdrData;
import com.openpositioning.PositionMe.dataParser.TrajectoryData;
import com.openpositioning.PositionMe.dataParser.TrajectoryParser;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.ArrayList;
import java.util.List;

/**
 * ReplayFragment 用于回放先前录制的轨迹数据，
 * 根据录制时保存的轨迹点生成回放轨迹，并在地图上动态显示。
 * 当回放点进入建筑区域时，将利用 NucleusBuildingManager 显示对应的室内楼层图。
 */
public class PlaybackFragment extends Fragment implements OnMapReadyCallback {

    // region UI components
    private GoogleMap mMap;
    private Spinner mapSpinner;
    private Switch autoFloorSwitch;
    private FloatingActionButton floorUpBtn, floorDownBtn;
    private ProgressBar playbackProgressBar;
    private Button playPauseBtn, restartBtn, goToEndBtn, exitBtn;
    // endregion

    // region Trajectory playback
    private List<LatLng> recordedTrajectory = new ArrayList<>();
    private Polyline replayPolyline;
    private Marker replayMarker;

    private Handler replayHandler;
    private Runnable playbackRunnable;
    private int currentIndex = 0;
    private boolean isPlaying = false; // for Play/Pause
    private static final long PLAYBACK_INTERVAL_MS = 500;

    // For indoor map
    private NucleusBuildingManager nucleusBuildingManager;

    // If you pass "trajectoryId" from FilesFragment
    private String trajectoryId;

    // endregion

    // Storing the google map object
    private GoogleMap gMap;

    // Starting point coordinates
    private LatLng start;

    // Manages overlaying of the indoor maps
    public IndoorMapManager indoorMapManager;

    private Marker orientationMarker;
    // Current Location coordinates
    private LatLng currentLocation;
    // Next Location coordinates
    private LatLng nextLocation;

    // Stores the polyline object for plotting path
    private Polyline polyline;
    //Button to end PDR recording
    private Button stopButton;
    private Button cancelButton;
    //Recording icon to show user recording is in progress
    private ImageView recIcon;
    //Loading bar to show time remaining before recording automatically ends
    private ProgressBar timeRemaining;
    //Text views to display distance travelled and elevation since beginning of recording

    private TextView elevation;
    private TextView distanceTravelled;
    // Text view to show the error between current PDR and current GNSS
    private TextView gnssError;

    //App settings
    private SharedPreferences settings;
    //Singleton class to collect all sensor data
    private SensorFusion sensorFusion;
    //Timer to end recording
    private CountDownTimer autoStop;
    // Responsible for updating UI in Loop
    private Handler refreshDataHandler;

    //variables to store data of the trajectory
    private float distance;
    private float previousPosX;
    private float previousPosY;
    // 定义一个全局索引，用于跟踪当前读取到 pdrDataList 中的哪个数据点
    private int currentPdrIndex = 0;

    //Switch Map Dropdown
    private Spinner switchMapSpinner;
    //Map Marker

    // Floor Up button
    public FloatingActionButton floorUpButton;
    // Floor Down button
    public FloatingActionButton floorDownButton;
    // GNSS Switch
    private Switch gnss;
    // GNSS marker
    private Marker gnssMarker;
    // Button used to switch colour
    private Button switchColor;
    // Current color of polyline
    private boolean isRed = true;
    // Switch used to set auto floor
    private Switch autoFloor;

    // region Trajectory playback
    private List<LatLng> gnssTrajectory = new ArrayList<>();  // 用于存储 GNSS 数据


    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // inflate the fragment_playback layout
        return inflater.inflate(R.layout.fragment_playback, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // 1) Get arguments from Navigation
        if (getArguments() != null) {
            PlaybackFragmentArgs args = PlaybackFragmentArgs.fromBundle(getArguments());
            trajectoryId = args.getTrajectoryId();
        }

        // 2) Init UI references
        mapSpinner = view.findViewById(R.id.mapSwitchSpinner_playback);
        autoFloorSwitch = view.findViewById(R.id.autoFloorSwitch_playback);
        floorUpBtn = view.findViewById(R.id.floorUpButton_playback);
        floorDownBtn = view.findViewById(R.id.floorDownButton_playback);
        playbackProgressBar = view.findViewById(R.id.playbackProgressBar);
        playPauseBtn = view.findViewById(R.id.playPauseButton);
        restartBtn = view.findViewById(R.id.restartButton);
        goToEndBtn = view.findViewById(R.id.goToEndButton);
        exitBtn = view.findViewById(R.id.exitButton);

        // 3) Handler for playback
        replayHandler = new Handler(Looper.getMainLooper());

        // 4) Get the Map Fragment
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.replayMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        // 5) Setup button listeners
        setupControlButtons();
        setupMapSpinner();
    }

    /**
     * Setup the controlling buttons: Play/Pause, Restart, End, Exit
     */
    private void setupControlButtons() {
        // Play / Pause
        playPauseBtn.setOnClickListener(v -> {
            if (!isPlaying) {
                // Start or resume
                isPlaying = true;
                playPauseBtn.setText("Pause");
                replayHandler.post(playbackRunnable);
            } else {
                // Pause
                isPlaying = false;
                playPauseBtn.setText("Play");
                replayHandler.removeCallbacks(playbackRunnable);
            }
        });

        // Restart
        restartBtn.setOnClickListener(v -> {
            // Stop the current run
            isPlaying = false;
            replayHandler.removeCallbacks(playbackRunnable);

            // Reset
            currentIndex = 0;
            playPauseBtn.setText("Play");
            playbackProgressBar.setProgress(0);
            // clear polyline
            if (replayPolyline != null) {
                replayPolyline.setPoints(new ArrayList<>());
            }
            if (replayMarker != null && !recordedTrajectory.isEmpty()) {
                replayMarker.setPosition(recordedTrajectory.get(0));
            }
        });

        // Go to end
        goToEndBtn.setOnClickListener(v -> {
            if (!recordedTrajectory.isEmpty()) {
                // Stop
                isPlaying = false;
                replayHandler.removeCallbacks(playbackRunnable);

                // Jump to last
                currentIndex = recordedTrajectory.size() - 1;
                // update map
                List<LatLng> newPoints = new ArrayList<>(recordedTrajectory);
                replayPolyline.setPoints(newPoints);
                replayMarker.setPosition(recordedTrajectory.get(currentIndex));
                playbackProgressBar.setProgress(100);
            }
        });

        // Exit
        exitBtn.setOnClickListener(v -> {
            // 假设直接导航回上一级
            // or getActivity().onBackPressed();
            NavHostFragment.findNavController(this).popBackStack();
        });
    }

    /**
     * Setup the map spinner to switch map type (Hybrid, Normal, Satellite).
     */
    private void setupMapSpinner() {
        String[] maps = new String[]{"Hybrid", "Normal", "Satellite"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, maps);
        mapSpinner.setAdapter(adapter);

        mapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
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
            public void onNothingSelected(AdapterView<?> parent) {
                // default
                if (mMap != null) {
                    mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                }
            }
        });
    }
//    @Override
//    public void onMapReady(GoogleMap map) {
//        gMap = map;
//        //Initialising the indoor map manager object
//        indoorMapManager =new IndoorMapManager(map);
//        // Setting map attributes
//        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);
//        map.getUiSettings().setCompassEnabled(true);
//        map.getUiSettings().setTiltGesturesEnabled(true);
//        map.getUiSettings().setRotateGesturesEnabled(true);
//        map.getUiSettings().setScrollGesturesEnabled(true);
//
//        // 解析数据
//        TrajectoryData data = TrajectoryParser.parseTrajectoryFile(getActivity());
//        List<GnssData> gnssDataList = data.getGnssData();
//
//        if (gnssDataList != null && !gnssDataList.isEmpty()) {
//            GnssData firstGnssData = gnssDataList.get(0);
//            start = new LatLng(firstGnssData.getLatitude(), firstGnssData.getLongitude());
//            recordedTrajectory.add(start);
//        }
//
//        currentLocation=start;
//        orientationMarker=map.addMarker(new MarkerOptions().position(start).title("Current Position")
//                .flat(true)
//                .icon(BitmapDescriptorFactory.fromBitmap(
//                        UtilFunctions.getBitmapFromVector(getContext(),R.drawable.ic_baseline_navigation_24))));
//        //Center the camera
//        map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, (float) 19f));
//        // Adding polyline to map to plot real-time trajectory
//        PolylineOptions polylineOptions=new PolylineOptions()
//                .color(Color.RED)
//                .add(currentLocation);
//        polyline = gMap.addPolyline(polylineOptions);
//        // Setting current location to set Ground Overlay for indoor map (if in building)
//        indoorMapManager.setCurrentLocation(currentLocation);
//        //Showing an indication of available indoor maps using PolyLines
//        indoorMapManager.setIndicationOfIndoorMap();
//    }
//
//
//    private void updateUIandPosition(){
//        TrajectoryData data = TrajectoryParser.parseTrajectoryFile(getActivity());
//        List<PdrData> pdrDataList = data.getPdrData();
//
//        // 检查 pdrDataList 是否存在数据
//        if (pdrDataList == null || pdrDataList.isEmpty() || currentPdrIndex >= pdrDataList.size()) {
//            // 如果没有数据，则直接返回或者执行其他逻辑
//            return;
//        }
//
//        // 从 pdrDataList 中获取当前的 PdrData 数据，并构造 pdrValues 数组
//        PdrData pdrData = pdrDataList.get(currentPdrIndex);
//        float[] pdrValues = new float[] { pdrData.getX(), pdrData.getY() };
//
//        // 计算本次移动的距离（欧氏距离）
//        distance += Math.sqrt(Math.pow(pdrValues[0] - previousPosX, 2) + Math.pow(pdrValues[1] - previousPosY, 2));
//        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));
//
//        // 计算本次移动的增量
//        float[] pdrMoved = new float[] { pdrValues[0] - previousPosX, pdrValues[1] - previousPosY };
//
//        // 如果检测到移动则绘制轨迹线
//        if (pdrMoved[0] != 0 || pdrMoved[1] != 0) {
//            plotLines(pdrMoved);
//        }
//
//        // 初始化室内地图管理器（如果还未初始化）
//        if (indoorMapManager == null) {
//            indoorMapManager = new IndoorMapManager(gMap);
//        }
////
////        // 如果用户启用了 GNSS 显示，并且 GNSS Marker 不为空，则更新 GNSS 位置及误差显示
////        if (gnss.isChecked() && gnssMarker != null) {
////            float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
////            LatLng gnssLocation = new LatLng(location[0], location[1]);
////            gnssError.setVisibility(View.VISIBLE);
////            gnssError.setText(String.format(getString(R.string.gnss_error) + "%.2fm",
////                    UtilFunctions.distanceBetweenPoints(currentLocation, gnssLocation)));
////            gnssMarker.setPosition(gnssLocation);
////        }
//
//        // 更新室内地图上用户当前位置（如果适用）
//        indoorMapManager.setCurrentLocation(currentLocation);
//
////        // 获取海拔值
////        float elevationVal = sensorFusion.getElevation();
////
////        // 如果室内地图可见，则显示楼层切换按钮，并根据海拔自动调整楼层（如果启用自动楼层）
////        if (indoorMapManager.getIsIndoorMapSet()){
////            setFloorButtonVisibility(View.VISIBLE);
////            if (autoFloor.isChecked()){
////                indoorMapManager.setCurrentFloor((int)(elevationVal / indoorMapManager.getFloorHeight()), true);
////            }
////        } else {
////            // 如果室内地图不可见，则隐藏楼层切换按钮
////            setFloorButtonVisibility(View.GONE);
////        }
//
//        // 存储本次的 PDR 坐标，供下一次调用使用
//        previousPosX = pdrValues[0];
//        previousPosY = pdrValues[1];
//
////        // 更新显示海拔
////        elevation.setText(getString(R.string.elevation, String.format("%.1f", elevationVal)));
////
////        // 根据运动方向旋转指南针 Marker
////        if (orientationMarker != null) {
////            orientationMarker.setRotation((float) Math.toDegrees(sensorFusion.passOrientation()));
////        }
//
//        // 增加索引，以便下一次调用时获取下一个 PdrData 数据
//        currentPdrIndex++;
//    }
//
//    /**
//     * Plots the users location based on movement in Real-time
//     * @param pdrMoved Contains the change in PDR in X and Y directions
//     */
//    private void plotLines(float[] pdrMoved){
//        if (currentLocation!=null){
//            // Calculate new position based on net PDR movement
//            nextLocation=UtilFunctions.calculateNewPos(currentLocation,pdrMoved);
//            //Try catch to prevent exceptions from crashing the app
//            try{
//                // Adds new location to polyline to plot the PDR path of user
//                List<LatLng> pointsMoved = polyline.getPoints();
//                pointsMoved.add(nextLocation);
//                polyline.setPoints(pointsMoved);
//                // Change current location to new location and zoom there
//                orientationMarker.setPosition(nextLocation);
//                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(nextLocation, (float) 19f));
//            }
//            catch (Exception ex){
//                Log.e("PlottingPDR","Exception: "+ex);
//            }
//            currentLocation=nextLocation;
//        }
//        else {
//            TrajectoryData data = TrajectoryParser.parseTrajectoryFile(getActivity());
//            List<GnssData> gnssDataList = data.getGnssData();
//            if (gnssDataList != null && !gnssDataList.isEmpty()) {
//                // 从 GnssDataList 中取出第一个数据点
//                GnssData firstGnssData = gnssDataList.get(0);
//                // 使用 GnssData 中的 latitude 和 longitude 初始化位置
//                currentLocation = new LatLng(firstGnssData.getLatitude(), firstGnssData.getLongitude());
//                nextLocation = currentLocation;
//            } else {
//                // 如果列表为空，可以添加其他逻辑（例如提示错误或使用默认位置）
//            }
//        }
//    }
//}

    /**
     * Called when the GoogleMap is ready.
     * We do the main setup: load the recorded trajectory, prepare polyline, etc.
     */
//    @Override
//    public void onMapReady(GoogleMap googleMap) {
//        this.mMap = googleMap;
//        // set default map type
//        googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
//        googleMap.getUiSettings().setCompassEnabled(true);
//
//        // Initialize the NucleusBuildingManager to handle indoor maps
//        nucleusBuildingManager = new NucleusBuildingManager(googleMap);
//
//        // (可选) 如果你要测试自动楼层切换 / 手动楼层切换:
//        setupFloorButtons();
//
//        // 载入轨迹 (根据 trajectoryId 来加载)
//        loadRecordedTrajectory(trajectoryId);
//
//        // 如果有数据，初始化回放
//        if (!recordedTrajectory.isEmpty()) {
//            // init polyline
//            PolylineOptions polylineOptions = new PolylineOptions()
//                    .color(getResources().getColor(R.color.pastelBlue))
//                    .add(recordedTrajectory.get(0));
//            replayPolyline = mMap.addPolyline(polylineOptions);
//
//            // init marker
//            replayMarker = mMap.addMarker(new MarkerOptions()
//                    .position(recordedTrajectory.get(0))
//                    .title("Playback Marker"));
//
//            // 初始化 GNSS 标记
//            if (!gnssTrajectory.isEmpty()) {
//                gnssMarker = mMap.addMarker(new MarkerOptions()
//                        .position(gnssTrajectory.get(0))
//                        .title("GNSS Position"));
//            }
//
//            // camera move to start
//            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(recordedTrajectory.get(0), 19f));
//        }
//
//        // 设置进度条
//        playbackProgressBar.setMax(recordedTrajectory.size());
//
//        // 定义 playbackRunnable，用于每隔 PLAYBACK_INTERVAL_MS 回放下一个点
//        playbackRunnable = new Runnable() {
//            @Override
//            public void run() {
//                if (!isPlaying) return; // 如果被暂停，则不继续
//
//                if (currentIndex < recordedTrajectory.size()) {
//                    LatLng point = recordedTrajectory.get(currentIndex);
//
//                    // 向折线里添加当前点
//                    List<LatLng> currentPoints = replayPolyline.getPoints();
//                    currentPoints.add(point);
//                    replayPolyline.setPoints(currentPoints);
//
//                    // 移动 marker
//                    replayMarker.setPosition(point);
//                    mMap.animateCamera(CameraUpdateFactory.newLatLng(point));
//
//                    // 如果有 GNSS 数据，更新 GNSS 标记
//                    if (currentIndex < gnssTrajectory.size()) {
//                        LatLng gnssPoint = gnssTrajectory.get(currentIndex);
//                        gnssMarker.setPosition(gnssPoint);
//                    }
//
//                    // 如果点在大楼内，显示对应楼层图
//                    if (nucleusBuildingManager.isPointInBuilding(point)) {
//                        if (autoFloorSwitch.isChecked()) {
//                            // 根据高度或其他逻辑自动算楼层
//                            // 这里仅演示固定切到 2 楼
//                            nucleusBuildingManager.getIndoorMapManager().switchFloor(2);
//                        }
//                    } else {
//                        nucleusBuildingManager.getIndoorMapManager().hideMap();
//                    }
//
//                    // 更新进度条
//                    playbackProgressBar.setProgress(currentIndex);
//
//                    // 索引+1
//                    currentIndex++;
//                    // 再次延迟调用
//                    replayHandler.postDelayed(this, PLAYBACK_INTERVAL_MS);
//
//                } else {
//                    // 播放结束
//                    isPlaying = false;
//                    playPauseBtn.setText("Play");
//                }
//            }
//        };
//    }
    @Override
    public void onMapReady(GoogleMap googleMap) {
        this.mMap = googleMap;
        // 设置地图类型和 UI 选项
        googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        googleMap.getUiSettings().setCompassEnabled(true);

        // 初始化室内地图管理器
        nucleusBuildingManager = new NucleusBuildingManager(googleMap);
        // (可选) 如果需要自动或手动切换楼层，调用 setupFloorButtons()
        setupFloorButtons();

        // 根据 trajectoryId 载入轨迹数据
        loadRecordedTrajectory(trajectoryId);

        if (!recordedTrajectory.isEmpty()) {
            // 初始化轨迹折线
            PolylineOptions polylineOptions = new PolylineOptions()
                    .color(getResources().getColor(R.color.pastelBlue))
                    .add(recordedTrajectory.get(0));
            replayPolyline = mMap.addPolyline(polylineOptions);

            // 初始化回放标记
            replayMarker = mMap.addMarker(new MarkerOptions()
                    .position(recordedTrajectory.get(0))
                    .title("Playback Marker"));

            // 初始化 GNSS 标记（如果有 GNSS 数据）
            if (!gnssTrajectory.isEmpty()) {
                gnssMarker = mMap.addMarker(new MarkerOptions()
                        .position(gnssTrajectory.get(0))
                        .title("GNSS Position"));
            }

            // 计算所有轨迹点的边界，并更新摄像头以适应屏幕
            LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder();
            for (LatLng point : recordedTrajectory) {
                boundsBuilder.include(point);
            }
            LatLngBounds bounds = boundsBuilder.build();
            int padding = 100; // 可根据屏幕和需求调整
            googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding));
        }

        // 设置进度条最大值为轨迹点总数
        playbackProgressBar.setMax(recordedTrajectory.size());

        // 定义 playbackRunnable，用于每隔一定时间更新下一个轨迹点……
        playbackRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPlaying) return;
                if (currentIndex < recordedTrajectory.size()) {
                    LatLng point = recordedTrajectory.get(currentIndex);
                    List<LatLng> currentPoints = replayPolyline.getPoints();
                    currentPoints.add(point);
                    replayPolyline.setPoints(currentPoints);
                    replayMarker.setPosition(point);
                    mMap.animateCamera(CameraUpdateFactory.newLatLng(point));

                    if (currentIndex < gnssTrajectory.size()) {
                        LatLng gnssPoint = gnssTrajectory.get(currentIndex);
                        gnssMarker.setPosition(gnssPoint);
                    }

                    if (nucleusBuildingManager.isPointInBuilding(point)) {
                        if (autoFloorSwitch.isChecked()) {
                            nucleusBuildingManager.getIndoorMapManager().switchFloor(2);
                        }
                    } else {
                        nucleusBuildingManager.getIndoorMapManager().hideMap();
                    }

                    playbackProgressBar.setProgress(currentIndex);
                    currentIndex++;
                    replayHandler.postDelayed(this, PLAYBACK_INTERVAL_MS);
                } else {
                    isPlaying = false;
                    playPauseBtn.setText("Play");
                }
            }
        };
    }

//    /**
//     * (示例) 根据 trajectoryId 载入记录好的坐标点
//     * 实际可以从本地数据库、文件或服务器获取 proto 后再解析成 LatLng 列表
//     */
//    private void loadRecordedTrajectory(String trajectoryId) {
////        // TODO: 真正实现：例如用ServerCommunications下载proto或从本地文件解析
//        // 这里为演示，仅伪造一组坐标
//        if (trajectoryId == null) return;

//        recordedTrajectory.clear();
//        // Mock data
//        recordedTrajectory.add(new LatLng(55.9229, -3.1745));
//        recordedTrajectory.add(new LatLng(55.9230, -3.1744));
//        recordedTrajectory.add(new LatLng(55.9231, -3.1743));
//        recordedTrajectory.add(new LatLng(55.9232, -3.1742));
//        // ...

//
//        recordedTrajectory.clear();
//        gnssTrajectory.clear();
//
//        TrajectoryData data = TrajectoryParser.parseTrajectoryFile(getActivity());

    //        // PDR 数据（使用x, y坐标生成 LatLng）
//        for (PdrData pdr : data.getPdrData()) {
//            LatLng pdrPoint = new LatLng(pdr.getY(), pdr.getX());  // 假设 PDR 数据中的 x, y 就是经纬度
//            recordedTrajectory.add(pdrPoint);
//        }
//
//        // GNSS 数据（使用经纬度创建 LatLng）
//        for (GnssData gnss : data.getGnssData()) {
//            LatLng gnssPoint = new LatLng(gnss.getLatitude(), gnss.getLongitude());
//            gnssTrajectory.add(gnssPoint);
//        }
//    }
//    private void loadRecordedTrajectory(String trajectoryId) {
//        recordedTrajectory.clear();
//
//        // 解析数据
//        TrajectoryData data = TrajectoryParser.parseTrajectoryFile(getActivity());
//
//        for (GnssData gnssData : data.getGnssData()) {
//            // 将当前的LatLng点添加到轨迹列表中
//            LatLng newLatLng = new LatLng(gnssData.getLatitude(), gnssData.getLongitude());
//            recordedTrajectory.add(newLatLng);
//        }
//
//    }
    private void loadRecordedTrajectory(String trajectoryId) {
        recordedTrajectory.clear();
        // 解析数据
        TrajectoryData data = TrajectoryParser.parseTrajectoryFile(getActivity(),trajectoryId);
        List<GnssData> gnssDataList = data.getGnssData();
        List<PdrData> pdrDataList = data.getPdrData();

        // 1. 从 gnssDataList 获取初始位置
        if (gnssDataList != null && !gnssDataList.isEmpty()) {
            GnssData firstGnssData = gnssDataList.get(0);
            double initialLat = firstGnssData.getLatitude();
            double initialLon = firstGnssData.getLongitude();

            // 初始经纬度坐标（原点）
            LatLng initialLocation = new LatLng(initialLat, initialLon);
            recordedTrajectory.add(initialLocation);

            // 2. 遍历 pdrDataList，将 pdrData 中的 x 和 y（单位：米）直接转换为经纬度偏移，
            //    这里使用初始点作为基准
            for (PdrData pdrData : pdrDataList) {
                // pdrData 中的 x 表示东西方向位移，y 表示南北方向位移
                double offsetX = pdrData.getX(); // 单位：米（向东为正）
                double offsetY = pdrData.getY(); // 单位：米（向北为正）

                // 计算经纬度偏移，使用初始点的纬度来换算经度
                double deltaLat = offsetY / 111320.0;
                double deltaLon = offsetX / (111320.0 * Math.cos(Math.toRadians(initialLat)));

                double newLat = initialLat + deltaLat;
                double newLon = initialLon + deltaLon;

                // 新点即为初始点加上对应的偏移，加入轨迹列表
                recordedTrajectory.add(new LatLng(newLat, newLon));
            }
        }
    }


    /**
     * Setup floor up/down button and autoFloor switch
     */
    private void setupFloorButtons() {
        floorUpBtn.setOnClickListener(v -> {
            // 如果手动点楼层按钮，则关掉autoFloor
            autoFloorSwitch.setChecked(false);
            nucleusBuildingManager.getIndoorMapManager().switchFloor(
                    // floorIndex + 1
                    // 这里自行获取 currentFloor 并 +1
                    2
            );
        });

        floorDownBtn.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            nucleusBuildingManager.getIndoorMapManager().switchFloor(
                    // floorIndex - 1
                    0
            );
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        // 暂停回放
        isPlaying = false;
        replayHandler.removeCallbacks(playbackRunnable);
    }
}

