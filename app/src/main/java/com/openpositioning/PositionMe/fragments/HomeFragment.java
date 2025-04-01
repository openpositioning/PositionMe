package com.openpositioning.PositionMe.fragments;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;

/**
 * Home Fragment displays the main buttons to navigate through the app.
 * The fragment has 4 buttons to:
 * 1) Start the recording process
 * 2) Navigate to the sensor information screen to have more detail
 * 3) Navigate to the measurements screen to check values in real time
 * 4) Navigate to the files page to upload trajectories and download from the cloud.
 *
 * @see FilesFragment The Files Fragment
 * @see InfoFragment Sensor information Fragment
 * @see MeasurementsFragment The measurements Fragment
 * @see StartLocationFragment The First fragment to start recording
 *
 * @author Michal Dvorak, Virginia Cangelosi
 */
public class HomeFragment extends Fragment implements OnMapReadyCallback {

    private Button startStopButton;
    private Button sensorInfoButton;
    private Button measurementButton;
    private Button filesButton;
    private GoogleMap mMap;
    private SensorFusion sensorFusion;
    private static final String TAG = "HomeFragment";

    /**
     * Default empty constructor, unused.
     */
    public HomeFragment() {
        // Required empty public constructor
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 检查并请求位置权限
        checkLocationPermissions();
    }

    /**
     * 检查并请求位置权限
     */
    private void checkLocationPermissions() {
        if (getActivity() != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (getActivity().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) 
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    // 请求位置权限
                    requestPermissions(
                        new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                        1001);
                    Log.d(TAG, "请求位置权限");
                } else {
                    Log.d(TAG, "已有位置权限");
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        return view;
    }

    /**
     * {@inheritDoc}
     * Initialise UI elements and set onClick actions for the buttons.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 初始化SensorFusion实例
        sensorFusion = SensorFusion.getInstance();
        
        // 获取各个按钮
        startStopButton = view.findViewById(R.id.startStopButton);
        sensorInfoButton = view.findViewById(R.id.sensorInfoButton);
        measurementButton = view.findViewById(R.id.measurementButton);
        filesButton = view.findViewById(R.id.filesButton);
        
        // 设置点击监听器
        startStopButton.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToStartLocationFragment();
            Navigation.findNavController(v).navigate(action);
        });
        
        sensorInfoButton.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToInfoFragment();
            Navigation.findNavController(v).navigate(action);
        });
        
        measurementButton.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToMeasurementsFragment();
            Navigation.findNavController(v).navigate(action);
        });
        
        filesButton.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToFilesFragment();
            Navigation.findNavController(v).navigate(action);
        });
        
        // 设置开始按钮的启用状态
        startStopButton.setEnabled(!PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean("permanentDeny", false));
                
        // 初始化地图
        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager()
                .findFragmentById(R.id.homeMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        } else {
            Log.e(TAG, "地图片段未找到");
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        
        // 设置地图类型为卫星地图
        mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
        
        try {
            // 隐藏默认的缩放控制按钮
            mMap.getUiSettings().setZoomControlsEnabled(false);
            
            // 确保启用默认的位置按钮
            mMap.getUiSettings().setMyLocationButtonEnabled(true);
            
            // 检查位置权限
            if (getActivity() != null && 
                getActivity().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) 
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                
                // 启用我的位置图层
                mMap.setMyLocationEnabled(true);
                
                // 添加地图点击事件监听器，辅助解决可能的位置按钮点击问题
                mMap.setOnMyLocationButtonClickListener(() -> {
                    addCurrentLocationMarker();
                    return false; // 返回false让默认行为也执行
                });
                
                Log.d(TAG, "已启用默认定位按钮");
            } else {
                Log.e(TAG, "没有位置权限，无法启用定位功能");
                // 尝试请求权限
                checkLocationPermissions();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "位置权限未授予: " + e.getMessage());
        }
        
        // 在地图初始化时添加当前位置标记
        addCurrentLocationMarker();
        
        // 启用其他地图手势
        mMap.getUiSettings().setCompassEnabled(true);
        mMap.getUiSettings().setScrollGesturesEnabled(true);
        mMap.getUiSettings().setZoomGesturesEnabled(true);
        
        // 移除地图点击监听器，防止点击地图移动标记
        // mMap.setOnMapClickListener(null);
    }

    /**
     * 将地图中心移动到我的位置
     */
    private void centerMapOnMyLocation() {
        if (mMap == null) return;
        
        try {
            LatLng position = getCurrentLocation();
            if (position != null) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 19f));
                Log.d(TAG, "移动地图到我的位置: " + position.latitude + ", " + position.longitude);
                
                // 更新标记位置
                updateMarker(position);
            }
        } catch (Exception e) {
            Log.e(TAG, "定位到我的位置时出错: " + e.getMessage());
        }
    }

    /**
     * 获取当前真实位置
     */
    private LatLng getCurrentLocation() {
        try {
            // 首先尝试使用系统定位服务获取最新位置
            if (getActivity() != null && 
                getActivity().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) 
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                
                android.location.LocationManager locationManager = 
                        (android.location.LocationManager) getActivity().getSystemService(android.content.Context.LOCATION_SERVICE);
                
                // 尝试获取GPS位置，这通常更准确
                android.location.Location lastKnownLocation = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
                
                // 如果GPS位置不可用，尝试网络位置
                if (lastKnownLocation == null) {
                    lastKnownLocation = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
                }
                
                if (lastKnownLocation != null) {
                    return new LatLng(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude());
                }
            }
            
            // 如果系统定位失败，尝试SensorFusion
            float[] currentLocation = sensorFusion.getGNSSLatitude(true); // 获取最新位置
            if (currentLocation != null && currentLocation.length >= 2 && currentLocation[0] != 0 && currentLocation[1] != 0) {
                return new LatLng(currentLocation[0], currentLocation[1]);
            }
        } catch (Exception e) {
            Log.e(TAG, "获取当前位置时出错: " + e.getMessage());
        }
        
        // 如果都失败，返回默认位置
        return new LatLng(55.9533, -3.1883); // 爱丁堡
    }

    /**
     * 添加当前位置的标记
     */
    private void addCurrentLocationMarker() {
        if (mMap == null) return;
        
        try {
            LatLng position = getCurrentLocation();
            if (position != null) {
                // 先清除旧标记
                mMap.clear();
                
                // 添加新标记
                mMap.addMarker(new MarkerOptions()
                        .position(position)
                        .title("当前位置"));
                
                // 移动相机到当前位置
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 19f));
                
                Log.d(TAG, "添加当前位置标记: " + position.latitude + ", " + position.longitude);
            }
        } catch (Exception e) {
            Log.e(TAG, "添加位置标记时出错: " + e.getMessage());
        }
    }

    /**
     * 更新标记位置
     */
    private void updateMarker(LatLng position) {
        if (mMap == null) return;
        
        try {
            // 先清除旧标记
            mMap.clear();
            
            // 添加新标记
            mMap.addMarker(new MarkerOptions()
                    .position(position)
                    .title("当前位置"));
            
            Log.d(TAG, "更新标记位置: " + position.latitude + ", " + position.longitude);
        } catch (Exception e) {
            Log.e(TAG, "更新标记位置时出错: " + e.getMessage());
        }
    }

    /**
     * 处理权限请求结果
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == 1001) {
            // 位置权限请求结果
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "位置权限已授予");
                
                // 如果地图已准备好，启用我的位置功能
                if (mMap != null) {
                    try {
                        mMap.setMyLocationEnabled(true);
                        mMap.getUiSettings().setMyLocationButtonEnabled(true);
                    } catch (SecurityException e) {
                        Log.e(TAG, "启用我的位置失败: " + e.getMessage());
                    }
                }
            } else {
                Log.d(TAG, "位置权限被拒绝");
            }
        }
    }
}