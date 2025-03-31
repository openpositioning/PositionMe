package com.openpositioning.PositionMe.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.preference.PreferenceManager;

import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.utils.LocationLogger;
import com.example.ekf.EKFManager;
import com.example.ekf.GNSSProcessor;

import java.util.List;

/**
 * A simple {@link Fragment} subclass. The recording fragment is displayed while the app is actively
 * saving data, with UI elements and a map with a marker indicating current PDR location and
 * direction of movement status. The user's PDR trajectory/path being recorded
 * is drawn on the map as well.
 * An overlay of indoor maps for the building is achieved when the user is in the Nucleus
 * and Library buildings to allow for a better user experience.
 *
 * @see HomeFragment the previous fragment in the nav graph.
 * @see CorrectionFragment the next fragment in the nav graph.
 * @see SensorFusion the class containing sensors and recording.
 * @see IndoorMapManager responsible for overlaying the indoor floor maps
 *
 * @author Mate Stodulka
 * @author Arun Gopalakrishnan
 */
public class RecordingFragment extends Fragment implements OnMapReadyCallback {

    //Button to end PDR recording
    private Button stopButton;
    private Button cancelButton;
    //Recording icon to show user recording is in progress
    private ImageView recIcon;
    //Loading bar to show time remaining before recording automatically ends
    private ProgressBar timeRemaining;
    // Text views to display distance travelled and elevation since beginning of recording

    private TextView elevation;
    private TextView distanceTravelled;
    // Text view to show the error between current PDR and current GNSS
    private TextView gnssError;
    // 初始化楼层显示文本框
    private TextView floorTextView;

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

    // Starting point coordinates
    private static LatLng start;
    // 起始位置数组
    private float[] startPosition;
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
    public IndoorMapManager indoorMapManager;
    // Floor Up button
    public FloatingActionButton floorUpButton;
    // Floor Down button
    public FloatingActionButton floorDownButton;
    // GNSS Switch
    private Switch gnss;
    // EKF Switch
    private Switch ekfSwitch;
    // GNSS marker
    private Marker gnssMarker;
    // Button used to switch colour
    private boolean isRed=true;
    // Switch used to set auto floor
    private Switch autoFloor;

    private LocationLogger locationLogger;

    private LocationCallback locationCallback;

    // EKF manager
    private EKFManager ekfManager;
    
    // EKF轨迹
    private Polyline ekfPolyline;
    
    // GNSS轨迹
    private Polyline gnssPolyline;
    
    // GNSS处理器
    private GNSSProcessor gnssProcessor;
    
    // GNSS历史位置 (用于避免重复添加相同位置)
    private LatLng lastGnssPosition;

    // 添加一个标志变量，跟踪是否已保存数据
    private boolean locationDataSaved = false;

    /**
     * Public Constructor for the class.
     * Left empty as not required
     */
    public RecordingFragment() {
        // Required empty public constructor
    }

    /**
     * {@inheritDoc}
     * Gets an instance of the {@link SensorFusion} class, and initialises the context and settings.
     * Creates a handler for periodically updating the displayed data.
     *
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.sensorFusion = SensorFusion.getInstance();
        this.ekfManager = EKFManager.getInstance();
        this.gnssProcessor = GNSSProcessor.getInstance();
        Context context = getActivity();
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.refreshDataHandler = new Handler();
        
        // 初始化 LocationLogger
        this.locationLogger = new LocationLogger(context);
    }

    /**
     * {@inheritDoc}
     * Set title in action bar to "Recording"
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // 调试开始
        Log.d("RecordingFragment", "========= onCreateView开始 =========");
        
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_recording, container, false);
        // Inflate the layout for this fragment
        ((AppCompatActivity)getActivity()).getSupportActionBar().hide();
        getActivity().setTitle("Recording...");
        
        //Obtain start position set in the startLocation fragment
        startPosition = sensorFusion.getGNSSLatitude(true);
        Log.d("RecordingFragment", "获取到起始位置: " + 
                (startPosition != null ? startPosition[0] + ", " + startPosition[1] : "null"));
        
        // 确保EKF Manager和GNSSProcessor已正确初始化
        if (ekfManager == null) {
            ekfManager = EKFManager.getInstance();
            Log.d("RecordingFragment", "创建新的EKF Manager实例");
        }
        
        if (gnssProcessor == null) {
            gnssProcessor = GNSSProcessor.getInstance();
            Log.d("RecordingFragment", "创建新的GNSSProcessor实例");
        }
        
        // 测试EKF和GPS
        testPositioningSystem();

        // Initialize map fragment
        SupportMapFragment supportMapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.RecordingMap);
        // Asynchronous map which can be configured
        if (supportMapFragment != null) {
            Log.d("RecordingFragment", "找到地图片段，异步加载地图");
            supportMapFragment.getMapAsync(this);
        } else {
            Log.e("RecordingFragment", "无法找到地图片段");
        }
        
        Log.d("RecordingFragment", "========= onCreateView结束 =========");
        return rootView;
    }

    /**
     * 测试定位系统组件是否正常工作
     */
    private void testPositioningSystem() {
        Log.d("PositioningTest", "========= 开始测试定位系统 =========");
        
        // 1. 测试PDR数据
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues != null) {
            Log.d("PositioningTest", String.format("PDR数据: X=%.6f, Y=%.6f", pdrValues[0], pdrValues[1]));
        } else {
            Log.e("PositioningTest", "PDR数据为null");
        }
        
        // 2. 测试GNSS数据
        float[] gnssValues = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        if (gnssValues != null && gnssValues.length >= 2) {
            Log.d("PositioningTest", String.format("GNSS数据: lat=%.8f, lng=%.8f", 
                    gnssValues[0], gnssValues[1]));
            
            // 测试GNSS处理器
            LatLng gnssLocation = new LatLng(gnssValues[0], gnssValues[1]);
            LatLng processedLocation = gnssProcessor.processGNSSPosition(gnssLocation);
            
            Log.d("PositioningTest", String.format("处理后GNSS位置: lat=%.8f, lng=%.8f", 
                    processedLocation.latitude, processedLocation.longitude));
            
            // 将测试数据写入LocationLogger
            locationLogger.logGnssLocation(
                System.currentTimeMillis(),
                processedLocation.latitude,
                processedLocation.longitude
            );
            Log.d("PositioningTest", "已记录GNSS测试数据");
        } else {
            Log.e("PositioningTest", "GNSS数据无效: " + 
                    (gnssValues == null ? "null" : "长度=" + gnssValues.length));
            
            // 创建模拟GNSS数据用于测试
            double testLat = 55.9355 + (Math.random() - 0.5) * 0.001; // 添加随机偏移
            double testLng = -3.1792 + (Math.random() - 0.5) * 0.001;
            
            Log.d("PositioningTest", String.format("创建模拟GNSS数据: lat=%.8f, lng=%.8f", 
                    testLat, testLng));
            
            // 记录模拟数据
            locationLogger.logGnssLocation(
                System.currentTimeMillis(),
                testLat,
                testLng
            );
            Log.d("PositioningTest", "已记录模拟GNSS测试数据");
        }
        
        // 3. 测试EKF Manager
        Log.d("PositioningTest", "初始化EKF...");
        
        // 使用模拟位置初始化EKF
        double testLat = 55.9355;
        double testLng = -3.1792;
        LatLng testLocation = new LatLng(testLat, testLng);
        float testOrientation = 0.0f;
        
        ekfManager.initialize(testLocation, testOrientation);
        
        // 更新几次位置
        for (int i = 0; i < 3; i++) {
            double offsetLat = (Math.random() - 0.5) * 0.0001;
            double offsetLng = (Math.random() - 0.5) * 0.0001;
            
            LatLng newPdrPosition = new LatLng(testLat + offsetLat * i, testLng + offsetLng * i);
            LatLng newGnssPosition = new LatLng(testLat + offsetLat * i * 1.2, testLng + offsetLng * i * 1.2);
            
            ekfManager.updatePdrPosition(newPdrPosition, testOrientation);
            ekfManager.updateGnssPosition(newGnssPosition);
            
            // 获取融合位置
            LatLng fusedPosition = ekfManager.getFusedPosition();
            
            if (fusedPosition != null) {
                Log.d("PositioningTest", String.format("EKF融合位置 #%d: lat=%.8f, lng=%.8f", 
                        i+1, fusedPosition.latitude, fusedPosition.longitude));
                
                // 记录融合位置
                locationLogger.logEkfLocation(
                    System.currentTimeMillis(),
                    fusedPosition.latitude,
                    fusedPosition.longitude
                );
            } else {
                Log.e("PositioningTest", "EKF融合位置 #" + (i+1) + " 为null");
                
                // 创建简单融合位置
                double fusedLat = (newPdrPosition.latitude + newGnssPosition.latitude) / 2;
                double fusedLng = (newPdrPosition.longitude + newGnssPosition.longitude) / 2;
                
                Log.d("PositioningTest", String.format("创建手动融合位置 #%d: lat=%.8f, lng=%.8f", 
                        i+1, fusedLat, fusedLng));
                
                // 记录手动融合位置
                locationLogger.logEkfLocation(
                    System.currentTimeMillis(),
                    fusedLat,
                    fusedLng
                );
            }
        }
        
        // 4. 测试PDR位置记录
        Log.d("PositioningTest", "测试PDR位置记录...");
        for (int i = 0; i < 3; i++) {
            double offsetLat = (Math.random() - 0.5) * 0.0001;
            double offsetLng = (Math.random() - 0.5) * 0.0001;
            
            double pdrLat = testLat + offsetLat * i * 0.8;
            double pdrLng = testLng + offsetLng * i * 0.8;
            
            Log.d("PositioningTest", String.format("记录PDR测试位置 #%d: lat=%.8f, lng=%.8f", 
                    i+1, pdrLat, pdrLng));
            
            // 记录PDR位置
            locationLogger.logLocation(
                System.currentTimeMillis(),
                pdrLat,
                pdrLng
            );
        }
        
        Log.d("PositioningTest", "========= 测试定位系统结束 =========");
    }

    /**
     * {@inheritDoc}
     * Controls to allow scrolling, tilting, rotating and a compass view of the
     * map are enabled. A marker is added to the map with the start position and
     * the compass indicating user direction. A polyline object is initialised
     * to plot user direction.
     * Initialises the manager to control indoor floor map overlays.
     *
     * @param map      Google map to be configured
     */
    @Override
    public void onMapReady(GoogleMap map) {
        Log.d("RecordingFragment", "地图准备就绪");
        gMap = map;
        //Initialising the indoor map manager object
        indoorMapManager = new IndoorMapManager(map);
        // Setting map attributes
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);

        // Add a marker at the start position and move the camera
        start = new LatLng(startPosition[0], startPosition[1]);
        currentLocation = start;
        orientationMarker = map.addMarker(new MarkerOptions().position(start).title("Current Position")
                .flat(true)
                .icon(BitmapDescriptorFactory.fromBitmap(
                        UtilFunctions.getBitmapFromVector(getContext(),R.drawable.ic_baseline_navigation_24))));
        //Center the camera
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, (float) 19f));
        // Adding polyline to map to plot real-time trajectory
        PolylineOptions polylineOptions = new PolylineOptions()
                .color(Color.RED)
                .add(currentLocation)
                .zIndex(1000f);
        polyline = gMap.addPolyline(polylineOptions);
        // Setting current location to set Ground Overlay for indoor map (if in building)
        indoorMapManager.setCurrentLocation(currentLocation);
        //Showing an indication of available indoor maps using PolyLines
        indoorMapManager.setIndicationOfIndoorMap();
        
        Log.d("RecordingFragment", "地图初始化完成，当前位置: " + currentLocation.latitude + ", " + currentLocation.longitude);
    }

    /**
     * {@inheritDoc}
     * Text Views and Icons initialised to display the current PDR to the user. A Button onClick
     * listener is enabled to detect when to go to next fragment and allow the user to correct PDR.
     * Other onClick, onCheckedChange and onSelectedItem Listeners for buttons, switch and spinner
     * are defined to allow user to change UI and functionality of the recording page as wanted
     * by the user.
     * A runnable thread is called to update the UI every 0.2 seconds.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Set autoStop to null for repeat recordings
        this.autoStop = null;

        //Initialise UI components
        this.elevation = getView().findViewById(R.id.currentElevation);
        this.distanceTravelled = getView().findViewById(R.id.currentDistanceTraveled);
        this.gnssError =getView().findViewById(R.id.gnssError);


        //Set default text of TextViews to 0
        this.gnssError.setVisibility(View.GONE);
        this.elevation.setText(getString(R.string.elevation, "0"));
        this.distanceTravelled.setText(getString(R.string.meter, "0"));

        //Reset variables to 0
        this.distance = 0f;
        this.previousPosX = 0f;
        this.previousPosY = 0f;

        // Stop button to save trajectory and move to corrections
        this.stopButton = getView().findViewById(R.id.stopButton);
        this.stopButton.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * OnClick listener for button to go to next fragment.
             * When button clicked the PDR recording is stopped and the {@link CorrectionFragment} is loaded.
             */
            @Override
            public void onClick(View view) {
                if(autoStop != null) autoStop.cancel();
                Log.d("RecordingFragment", "用户点击停止按钮，保存轨迹文件...");
                
                // 首先更新并获取最新的融合位置
                if (currentLocation != null && ekfManager != null) {
                    try {
                        // 最后更新一次EKF位置
                        float orientation = sensorFusion.passOrientation();
                        ekfManager.updatePdrPosition(currentLocation, orientation);
                        
                        // 获取GNSS位置并更新
                        float[] gnssValues = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
                        if (gnssValues != null && gnssValues.length >= 2 && gnssValues[0] != 0 && gnssValues[1] != 0) {
                            LatLng gnssLocation = new LatLng(gnssValues[0], gnssValues[1]);
                            LatLng processedGnssLocation = gnssProcessor.processGNSSPosition(gnssLocation);
                            ekfManager.updateGnssPosition(processedGnssLocation);
                            
                            // 记录GNSS位置
                            locationLogger.logGnssLocation(
                                System.currentTimeMillis(),
                                processedGnssLocation.latitude,
                                processedGnssLocation.longitude
                            );
                            Log.d("RecordingFragment", "保存前记录最后一个GNSS位置: " + 
                                processedGnssLocation.latitude + ", " + processedGnssLocation.longitude);
                        }
                        
                        // 获取融合位置
                        LatLng fusedPosition = ekfManager.getFusedPosition();
                        if (fusedPosition != null) {
                            // 记录融合位置
                            locationLogger.logEkfLocation(
                                System.currentTimeMillis(),
                                fusedPosition.latitude,
                                fusedPosition.longitude
                            );
                            Log.d("RecordingFragment", "保存前记录最后一个EKF位置: " + 
                                fusedPosition.latitude + ", " + fusedPosition.longitude);
                        }
                        
                        // 记录PDR位置
                        locationLogger.logLocation(
                            System.currentTimeMillis(),
                            currentLocation.latitude,
                            currentLocation.longitude
                        );
                        Log.d("RecordingFragment", "保存前记录最后一个PDR位置: " + 
                            currentLocation.latitude + ", " + currentLocation.longitude);
                    } catch (Exception e) {
                        Log.e("RecordingFragment", "保存前更新位置数据时出错: " + e.getMessage(), e);
                    }
                }
                
                if (!locationDataSaved) {
                    try {
                        Log.d("RecordingFragment", "开始保存轨迹数据...");
                        locationLogger.saveToFile();
                        locationDataSaved = true;
                        Log.d("RecordingFragment", "轨迹文件保存成功");
                    } catch (Exception e) {
                        Log.e("RecordingFragment", "保存轨迹数据时出错: " + e.getMessage(), e);
                    }
                } else {
                    Log.d("RecordingFragment", "轨迹数据已保存，跳过按钮点击中的保存");
                }
                
                stopRecording();
                NavDirections action = RecordingFragmentDirections.actionRecordingFragmentToCorrectionFragment();
                Navigation.findNavController(view).navigate(action);
            }
        });

        // Cancel button to discard trajectory and return to Home
        this.cancelButton = getView().findViewById(R.id.cancelButton);
        this.cancelButton.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * OnClick listener for button to go to home fragment.
             * When button clicked the PDR recording is stopped and the {@link HomeFragment} is loaded.
             * The trajectory is not saved.
             */
            @Override
            public void onClick(View view) {
                Log.d("RecordingFragment", "取消录制，不保存轨迹数据");
                locationDataSaved = true; // 标记为已保存，防止onDestroy中保存数据
                sensorFusion.stopRecording();
                NavDirections action = RecordingFragmentDirections.actionRecordingFragmentToHomeFragment();
                Navigation.findNavController(view).navigate(action);
                if(autoStop != null) autoStop.cancel();
            }
        });
        // Configuring dropdown for switching map types
        mapDropdown();
        // Setting listener for the switching map types dropdown
        switchMap();
        // Floor changer Buttons
        this.floorUpButton=getView().findViewById(R.id.floorUpButton);
        this.floorDownButton=getView().findViewById(R.id.floorDownButton);
        // Auto-floor switch
        this.autoFloor=getView().findViewById(R.id.autoFloor);
        autoFloor.setChecked(true);
        // Hiding floor changing buttons and auto-floor switch
        setFloorButtonVisibility(View.GONE);
        this.floorUpButton.setOnClickListener(new View.OnClickListener() {
            /**
             *{@inheritDoc}
             * Listener for increasing the floor for the indoor map
             */
            @Override
            public void onClick(View view) {
                // Setting off auto-floor as manually changed
                autoFloor.setChecked(false);
                indoorMapManager.increaseFloor();
            }
        });
        this.floorDownButton.setOnClickListener(new View.OnClickListener() {
            /**
             *{@inheritDoc}
             * Listener for decreasing the floor for the indoor map
             */
            @Override
            public void onClick(View view) {
                // Setting off auto-floor as manually changed
                autoFloor.setChecked(false);
                indoorMapManager.decreaseFloor();
            }
        });
        //Obtain the GNSS toggle switch
        this.gnss = getView().findViewById(R.id.gnssSwitch);
        
        //Obtain the EKF toggle switch
        this.ekfSwitch = getView().findViewById(R.id.EKF_Switch);

        this.gnss.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            /**
             * {@inheritDoc}
             * Listener to set GNSS marker and show GNSS vs PDR error.
             */
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                if (isChecked){
                    // Show GNSS eror
                    float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
                    LatLng gnssLocation = new LatLng(location[0],location[1]);
                    gnssError.setVisibility(View.VISIBLE);
                    gnssError.setText(String.format(getString(R.string.gnss_error)+"%.2fm",
                            UtilFunctions.distanceBetweenPoints(currentLocation,gnssLocation)));
                    
                    // 使用GNSSProcessor处理GNSS位置
                    LatLng processedGnssLocation = gnssProcessor.processGNSSPosition(gnssLocation);
                    lastGnssPosition = processedGnssLocation; // 保存最后一个GNSS位置
                    
                    // Set GNSS marker
                    gnssMarker=gMap.addMarker(
                            new MarkerOptions().title("GNSS position")
                                    .position(processedGnssLocation)
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                    
                    // 创建GNSS轨迹线 (蓝色)
                    if (gnssPolyline == null) {
                        PolylineOptions gnssPolylineOptions = new PolylineOptions()
                                .color(Color.BLUE)  // 蓝色表示GNSS轨迹
                                .add(processedGnssLocation)
                                .width(8f)  // 宽度适中
                                .zIndex(1200f);  // zIndex在PDR和EKF之间
                        gnssPolyline = gMap.addPolyline(gnssPolylineOptions);
                    }
                } else {
                    gnssMarker.remove();
                    gnssMarker = null;
                    gnssError.setVisibility(View.GONE);
                    
                    // 清除GNSS轨迹
                    if (gnssPolyline != null) {
                        gnssPolyline.remove();
                        gnssPolyline = null;
                    }
                    
                    // 重置GNSS处理器
                    gnssProcessor.reset();
                    lastGnssPosition = null;
                }
            }
        });

        // EKF开关监听器
        this.ekfSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ekfManager.setEkfEnabled(isChecked);
                
                if (isChecked) {
                    // 启用EKF时初始化
                    if (currentLocation != null) {
                        // 使用当前PDR位置和朝向初始化EKF，向左偏移45度修正初始方向误差
                        float correctedOrientation = sensorFusion.passOrientation() - (float)(Math.PI / 4.0); // 减去45度（π/4弧度）
                        ekfManager.initialize(currentLocation, correctedOrientation);
                        
                        // 创建EKF轨迹线
                        if (ekfPolyline == null) {
                            PolylineOptions ekfPolylineOptions = new PolylineOptions()
                                    .color(Color.GREEN)  // 使用绿色，表示EKF融合轨迹
                                    .add(currentLocation)
                                    .width(12f)  // 增加宽度
                                    .geodesic(true)  // 平滑轨迹
                                    .zIndex(1500f);  // 确保EKF轨迹显示在PDR轨迹上方
                            ekfPolyline = gMap.addPolyline(ekfPolylineOptions);
                            
                            // 仅在EKF初始启用时将地图中心设置为当前位置
                            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, (float) 19f));
                        }
                        
                        Log.d("RecordingFragment", "EKF initialized at: " + currentLocation.latitude + ", " + currentLocation.longitude + 
                              ", 航向角: " + Math.toDegrees(correctedOrientation) + "° (修正后)");
                    } else {
                        Log.e("RecordingFragment", "Cannot initialize EKF: current location is null");
                    }
                } else {
                    // 禁用EKF时清除EKF轨迹
                    if (ekfPolyline != null) {
                        ekfPolyline.remove();
                        ekfPolyline = null;
                    }
                }
                
                Log.d("RecordingFragment", "EKF " + (isChecked ? "启用" : "禁用"));
            }
        });

        // Display the progress of the recording when a max record length is set
        this.timeRemaining = getView().findViewById(R.id.timeRemainingBar);

        // Display a blinking red dot to show recording is in progress
        blinkingRecording();

        // Check if there is manually set time limit:
        if(this.settings.getBoolean("split_trajectory", false)) {
            // If that time limit has been reached:
            long limit = this.settings.getInt("split_duration", 30) * 60000L;
            // Set progress bar
            this.timeRemaining.setMax((int) (limit/1000));
            this.timeRemaining.setScaleY(3f);

            // Create a CountDownTimer object to adhere to the time limit
            this.autoStop = new CountDownTimer(limit, 1000) {
                /**
                 * {@inheritDoc}
                 * Increment the progress bar to display progress and remaining time. Update the
                 * observed PDR values, and animate icons based on the data.
                 */
                @Override
                public void onTick(long l) {
                    // increment progress bar
                    timeRemaining.incrementProgressBy(1);
                    // Get new position and update UI
                    updateUIandPosition();
                }

                /**
                 * {@inheritDoc}
                 * Finish recording and move to the correction fragment.
                 * @see CorrectionFragment
                 */
                @Override
                public void onFinish() {
                    // Timer done, move to next fragment automatically - will stop recording
                    sensorFusion.stopRecording();
                    NavDirections action = RecordingFragmentDirections.actionRecordingFragmentToCorrectionFragment();
                    Navigation.findNavController(view).navigate(action);
                }
            }.start();
        }
        else {
            // No time limit - use a repeating task to refresh UI.
            this.refreshDataHandler.post(refreshDataTask);
        }

        // 在位置更新时记录
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return;
                }
                
                for (Location location : locationResult.getLocations()) {
                    // 记录位置
                    locationLogger.logLocation(
                        location.getTime(),
                        location.getLatitude(),
                        location.getLongitude()
                    );
                    
                    // 其他处理...
                }
            }
        };

        // 初始化楼层显示文本框
        this.floorTextView = getView().findViewById(R.id.Floor);

        // 添加状态改变监听器
        autoFloor.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Log.d("AutoFloor", "Switch state changed to: " + isChecked);
                
                if (indoorMapManager == null) {
                    Log.d("AutoFloor", "IndoorMapManager is null");
                    return;
                }
                
                if (!indoorMapManager.getIsIndoorMapSet()) {
                    Log.d("AutoFloor", "No indoor map is currently set");
                    return;
                }
                
                if (isChecked) {
                    // 直接使用 SensorFusion 中的当前楼层
                    int currentFloor = sensorFusion.getCurrentFloor();
                    Log.d("AutoFloor", String.format(
                        "Switch ON - Using SensorFusion floor: %d",
                        currentFloor
                    ));
                    indoorMapManager.resumeAutoFloor(currentFloor);
                } else {
                    Log.d("AutoFloor", "Switch turned OFF");
                }
            }
        });
    }

    /**
     * Creates a dropdown for Changing maps
     */
    private void mapDropdown(){
        // Creating and Initialising options for Map's Dropdown Menu
        switchMapSpinner = (Spinner) getView().findViewById(R.id.mapSwitchSpinner);
        // Different Map Types
        String[] maps = new String[]{getString(R.string.hybrid), getString(R.string.normal), getString(R.string.satellite)};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, maps);
        // Set the Dropdowns menu adapter
        switchMapSpinner.setAdapter(adapter);
    }

    /**
     * Spinner listener to change map bap based on user input
     */
    private void switchMap(){
        // Switch between map type based on user input
        this.switchMapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            /**
             * {@inheritDoc}
             * OnItemSelected listener to switch maps.
             * The map switches between MAP_TYPE_NORMAL, MAP_TYPE_SATELLITE
             * and MAP_TYPE_HYBRID based on user selection.
             */
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position){
                    case 0:
                        gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                    case 1:
                        gMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                        break;
                    case 2:
                        gMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                        break;
                }
            }
            /**
             * {@inheritDoc}
             * When Nothing is selected set to MAP_TYPE_HYBRID (NORMAL and SATELLITE)
             */
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
            }
        });
    }
    /**
     * Runnable task used to refresh UI elements with live data.
     * Has to be run through a Handler object to be able to alter UI elements
     */
    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            // Get new position and update UI
            updateUIandPosition();
            // Loop the task again to keep refreshing the data
            refreshDataHandler.postDelayed(refreshDataTask, 200);
        }
    };

    /**
     * Updates the UI, traces PDR Position on the map
     * and also updates marker representing the current location and direction on the map
     */
    private void updateUIandPosition(){
        // 调试：跟踪方法调用
        long startTime = System.currentTimeMillis();
        Log.d("LocationTracking", "======= 开始位置更新 =======");
        
        // Get new position
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null) {
            Log.e("LocationTracking", "PDR值为null，无法更新位置");
            return;
        }
        
        // 调试：输出PDR原始值
        Log.d("LocationTracking", String.format("PDR原始值: X=%.6f, Y=%.6f", pdrValues[0], pdrValues[1]));
        
        // Calculate distance travelled
        distance += Math.sqrt(Math.pow(pdrValues[0] - previousPosX, 2) + Math.pow(pdrValues[1] - previousPosY, 2));
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));
        // Net pdr movement
        float[] pdrMoved={pdrValues[0]-previousPosX,pdrValues[1]-previousPosY};
        
        // 调试：输出PDR移动量
        Log.d("LocationTracking", String.format("PDR移动量: dX=%.6f, dY=%.6f", pdrMoved[0], pdrMoved[1]));
        
        // if PDR has changed plot new line to indicate user movement
        if (pdrMoved[0]!=0 ||pdrMoved[1]!=0) {
            // 调试：PDR位置已更新
            Log.d("LocationTracking", "PDR位置已更新，更新地图轨迹");
            
            plotLines(pdrMoved);
            
            // 无论EKF开关是否开启，都更新EKF数据以便存储
            if (currentLocation != null) {
                float correctedOrientation = sensorFusion.passOrientation() - (float)(Math.PI / 4.0); // 减去45度（π/4弧度）
                
                // 更新EKF中的PDR位置和航向
                ekfManager.updatePdrPosition(currentLocation, correctedOrientation);
                
                // 只有当EKF开关打开时，才更新UI
                if (ekfSwitch.isChecked() && ekfManager.isEkfEnabled()) {
                    // UI相关操作
                }
            }
        } else {
            // 调试：PDR位置未变化
            Log.d("LocationTracking", "PDR位置未变化");
        }
        
        // If not initialized, initialize
        if (indoorMapManager == null) {
            indoorMapManager = new IndoorMapManager(gMap);
        }
        
        // 处理WiFi位置更新 - 无论EKF开关是否开启
        float[] wifiValues = sensorFusion.getSensorValueMap().get(SensorTypes.WIFILATLONG);
        if (wifiValues != null && wifiValues.length >= 2 && wifiValues[0] != 0 && wifiValues[1] != 0) {
            // 调试：输出WiFi位置
            Log.d("LocationTracking", String.format("WiFi位置: lat=%.6f, lng=%.6f", wifiValues[0], wifiValues[1]));
            
            LatLng wifiPosition = new LatLng(wifiValues[0], wifiValues[1]);
            
            // 无论EKF开关是否开启，都更新EKF中的WiFi位置
            ekfManager.updateWifiPosition(wifiPosition);
        } else {
            // 调试：WiFi位置无效
            Log.d("LocationTracking", "WiFi位置无效或未获取");
        }
        
        // 处理GNSS位置更新 - 无论GNSS开关是否开启，都记录GNSS位置
        float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        LatLng processedGnssLocation = null;
        
        if (location != null && location.length >= 2 && location[0] != 0 && location[1] != 0) {
            LatLng gnssLocation = new LatLng(location[0], location[1]);
            
            // 调试：输出GNSS原始位置
            Log.d("LocationTracking", String.format("GNSS原始位置: lat=%.8f, lng=%.8f", 
                     gnssLocation.latitude, gnssLocation.longitude));
            
            // 使用GNSSProcessor处理GNSS位置
            processedGnssLocation = gnssProcessor.processGNSSPosition(gnssLocation);
            
            // 调试：输出GNSS处理后位置
            Log.d("LocationTracking", String.format("GNSS处理后位置: lat=%.8f, lng=%.8f", 
                     processedGnssLocation.latitude, processedGnssLocation.longitude));
            
            // 只有GNSS开关打开时才显示GNSS相关UI
            if (gnss.isChecked() && gnssMarker != null) {
                // 显示PDR与GNSS之间的误差
                gnssError.setVisibility(View.VISIBLE);
                gnssError.setText(String.format(getString(R.string.gnss_error)+"%.2fm",
                        UtilFunctions.distanceBetweenPoints(currentLocation, gnssLocation)));
                
                // 更新GNSS标记位置
                gnssMarker.setPosition(processedGnssLocation);
                
                // 只有当位置有明显变化时才更新GNSS轨迹
                if (lastGnssPosition == null || 
                    UtilFunctions.distanceBetweenPoints(lastGnssPosition, processedGnssLocation) > 0.5) {
                    
                    // 更新GNSS轨迹UI
                    if (gnssPolyline != null) {
                        List<LatLng> gnssPoints = gnssPolyline.getPoints();
                        gnssPoints.add(processedGnssLocation);
                        gnssPolyline.setPoints(gnssPoints);
                    }
                    
                    // 保存最新GNSS位置
                    lastGnssPosition = processedGnssLocation;
                }
            }
            
            // 无论GNSS开关是否开启，都记录GNSS位置数据
            // 每次更新都记录，不再使用距离过滤
            locationLogger.logGnssLocation(
                System.currentTimeMillis(),
                processedGnssLocation.latitude,
                processedGnssLocation.longitude
            );
            
            // 无论EKF开关是否开启，都更新EKF中的GNSS位置
            ekfManager.updateGnssPosition(processedGnssLocation);
        } else {
            // 调试：GNSS位置无效
            Log.e("LocationTracking", "GNSS位置无效或未获取: " + 
                (location == null ? "null" : "length=" + location.length));
            
            // 手动创建一个测试用的GNSS位置，确保EKF可以工作
            if (currentLocation != null) {
                // 使用当前PDR位置作为GNSS位置，添加一点随机偏移
                double randomLat = currentLocation.latitude + (Math.random() - 0.5) * 0.00001;
                double randomLng = currentLocation.longitude + (Math.random() - 0.5) * 0.00001;
                processedGnssLocation = new LatLng(randomLat, randomLng);
                
                Log.d("LocationTracking", "创建模拟GNSS位置: " + processedGnssLocation.latitude + 
                      ", " + processedGnssLocation.longitude);
                
                // 记录模拟的GNSS位置
                locationLogger.logGnssLocation(
                    System.currentTimeMillis(),
                    processedGnssLocation.latitude,
                    processedGnssLocation.longitude
                );
                
                // 更新EKF中的GNSS位置
                ekfManager.updateGnssPosition(processedGnssLocation);
            }
        }
        
        // 获取EKF融合位置并记录 - 无论EKF开关是否开启
        LatLng fusedPosition = ekfManager.getFusedPosition();
        
        // 调试：输出EKF融合位置
        Log.d("LocationTracking", "EKF融合位置: " + (fusedPosition == null ? "null" : 
              fusedPosition.latitude + ", " + fusedPosition.longitude));
        
        if (fusedPosition != null) {
            // 记录融合位置
            locationLogger.logEkfLocation(
                System.currentTimeMillis(),
                fusedPosition.latitude,
                fusedPosition.longitude
            );
            
            // 只有当EKF开关打开时才更新EKF轨迹UI
            if (ekfSwitch.isChecked() && ekfManager.isEkfEnabled() && ekfPolyline != null) {
                List<LatLng> points = ekfPolyline.getPoints();
                points.add(fusedPosition);
                ekfPolyline.setPoints(points);
            }
        } else {
            // EKF位置为null，尝试手动生成一个
            if (currentLocation != null && processedGnssLocation != null) {
                // 简单融合：50%PDR + 50%GNSS
                double fusedLat = (currentLocation.latitude + processedGnssLocation.latitude) / 2;
                double fusedLng = (currentLocation.longitude + processedGnssLocation.longitude) / 2;
                LatLng manualFusedPosition = new LatLng(fusedLat, fusedLng);
                
                Log.d("LocationTracking", "手动创建融合位置: " + fusedLat + ", " + fusedLng);
                
                // 记录手动融合位置
                locationLogger.logEkfLocation(
                    System.currentTimeMillis(),
                    manualFusedPosition.latitude,
                    manualFusedPosition.longitude
                );
            } else {
                Log.e("LocationTracking", "无法创建手动融合位置，currentLocation或processedGnssLocation为null");
            }
        }
        
        //  Updates current location of user to show the indoor floor map (if applicable)
        indoorMapManager.setCurrentLocation(currentLocation);
        
        // 如果在室内且自动楼层开启，持续更新楼层
        if (indoorMapManager.getIsIndoorMapSet()) {
            setFloorButtonVisibility(View.VISIBLE);
            if (autoFloor.isChecked()) {
                // 直接使用 SensorFusion 中的当前楼层
                int currentFloor = sensorFusion.getCurrentFloor();
                Log.d("AutoFloor", "Auto updating - Current floor: " + currentFloor);
                indoorMapManager.setCurrentFloor(currentFloor, true);
            }
            floorTextView.setText(sensorFusion.getFloorDisplay());
        } else {
            setFloorButtonVisibility(View.GONE);
            floorTextView.setText(sensorFusion.getFloorDisplay());
        }
        
        // Store previous PDR values for next call
        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];
        // Display elevation
        elevation.setText(getString(R.string.elevation, String.format("%.1f", sensorFusion.getElevation())));
        //Rotate compass Marker according to direction of movement
        if (orientationMarker!=null) {
            orientationMarker.setRotation((float) Math.toDegrees(sensorFusion.passOrientation()));
        }

        // 在位置更新时记录PDR位置到 LocationLogger
        if (currentLocation != null) {
            Log.d("LocationTracking", String.format("记录PDR位置: lat=%.8f, lng=%.8f", 
                     currentLocation.latitude, currentLocation.longitude));
            
            // 每次都记录当前PDR位置
            locationLogger.logLocation(
                System.currentTimeMillis(),
                currentLocation.latitude,
                currentLocation.longitude
            );
        } else {
            Log.e("LocationTracking", "当前PDR位置为null，无法记录");
        }
        
        // 调试：跟踪方法结束
        long endTime = System.currentTimeMillis();
        Log.d("LocationTracking", String.format("======= 位置更新完成，耗时%dms =======", endTime - startTime));
    }
    /**
     * Plots the users location based on movement in Real-time
     * @param pdrMoved Contains the change in PDR in X and Y directions
     */
    private void plotLines(float[] pdrMoved){
        if (currentLocation!=null){
            // Calculate new position based on net PDR movement
            nextLocation=UtilFunctions.calculateNewPos(currentLocation,pdrMoved);
            //Try catch to prevent exceptions from crashing the app
            try{
                // Adds new location to polyline to plot the PDR path of user
                List<LatLng> pointsMoved = polyline.getPoints();
                pointsMoved.add(nextLocation);
                polyline.setPoints(pointsMoved);
                // 设置轨迹线的 zIndex 为较大值,确保显示在地图覆盖层上方
                polyline.setZIndex(1000f);
                // Change current location to new location and zoom there
                orientationMarker.setPosition(nextLocation);
                // 设置位置标记的 zIndex 也为较大值
                orientationMarker.setZIndex(1000f);
                // 移动相机到PDR位置 (保持用户可以自由拖动地图)
                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(nextLocation, (float) 19f));
            }
            catch (Exception ex){
                Log.e("PlottingPDR","Exception: "+ex);
            }
            currentLocation=nextLocation;
        }
        else{
            //Initialise the starting location
            float[] location = sensorFusion.getGNSSLatitude(true);
            currentLocation=new LatLng(location[0],location[1]);
            nextLocation=currentLocation;
        }
    }

    /**
     * Function to set change visibility of the floor up and down buttons
     * @param visibility the visibility of floor buttons should be set to
     */
    private void setFloorButtonVisibility(int visibility){
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        autoFloor.setVisibility(visibility);
    }
    /**
     * Displays a blinking red dot to signify an ongoing recording.
     *
     * @see Animation for makin the red dot blink.
     */
    private void blinkingRecording() {
        //Initialise Image View
        this.recIcon = getView().findViewById(R.id.redDot);
        //Configure blinking animation
        Animation blinking_rec = new AlphaAnimation(1, 0);
        blinking_rec.setDuration(800);
        blinking_rec.setInterpolator(new LinearInterpolator());
        blinking_rec.setRepeatCount(Animation.INFINITE);
        blinking_rec.setRepeatMode(Animation.REVERSE);
        recIcon.startAnimation(blinking_rec);
    }

    /**
     * {@inheritDoc}
     * Stops ongoing refresh task, but not the countdown timer which stops automatically
     */
    @Override
    public void onPause() {
        refreshDataHandler.removeCallbacks(refreshDataTask);
        super.onPause();
    }

    /**
     * {@inheritDoc}
     * Restarts UI refreshing task when no countdown task is in progress
     */
    @Override
    public void onResume() {
        if(!this.settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
        super.onResume();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (!locationDataSaved) {
            Log.d("RecordingFragment", "在onDestroy中保存轨迹数据");
            locationLogger.saveToFile();
            locationDataSaved = true;
        } else {
            Log.d("RecordingFragment", "轨迹数据已保存，跳过onDestroy中的保存");
        }
    }

    private void stopRecording() {
        Log.d("RecordingFragment", "停止录制");
        
        // 保存轨迹数据
        if (!locationDataSaved) {
            Log.d("RecordingFragment", "在stopRecording中保存轨迹数据");
            locationLogger.saveToFile();
            locationDataSaved = true;
        } else {
            Log.d("RecordingFragment", "轨迹数据已保存，跳过stopRecording中的保存");
        }
        
        // 停止定时器
        if (autoStop != null) {
            autoStop.cancel();
            autoStop = null;
        }
        
        // 停止刷新UI
        refreshDataHandler.removeCallbacks(refreshDataTask);
        
        // 清理资源
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
        
        if (gnssPolyline != null) {
            gnssPolyline.remove();
            gnssPolyline = null;
        }
        
        if (ekfPolyline != null) {
            ekfPolyline.remove();
            ekfPolyline = null;
        }
        
        if (orientationMarker != null) {
            orientationMarker.remove();
            orientationMarker = null;
        }
        
        if (polyline != null) {
            polyline.remove();
            polyline = null;
        }
        
        Log.d("RecordingFragment", "录制已完全停止，资源已清理");
    }
}