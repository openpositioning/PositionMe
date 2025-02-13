package com.openpositioning.PositionMe.fragments;


import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;

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
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Replay;
import com.openpositioning.PositionMe.ServerCommunications;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;

import java.util.List;

/**
 * ReplayFragment 用于回放录制轨迹，同时也提供室内控制功能：
 * 包括 autoFloor 开关、GNSS 开关、楼层上/下按钮、换色按钮，
 * 以及播放、暂停、重启、跳到末尾、退出轨迹的操作。
 */
public class ReplayFragment extends Fragment {

    // 回放功能相关
    private ServerCommunications serverCommunications;
    private Replay replay;
    private GoogleMap gMap;
    private Switch gnss;
    // GNSS marker
    private Marker gnssMarker;
    private SensorFusion sensorFusion;
    private OnMapReadyCallback mapReadyCallback = new OnMapReadyCallback() {
        @Override
        public void onMapReady(GoogleMap googleMap) {
            gMap = googleMap;
            // 设置默认位置为悉尼
            LatLng defaultLocation = new LatLng(-34, 151);
            googleMap.addMarker(new MarkerOptions().position(defaultLocation).title("Default Location"));
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 10));

            // 若 replay 对象已初始化，则调用其 onMapReady 方法（内部可能绘制轨迹）
            if (replay != null) {
                replay.onMapReady(googleMap);
            }
            // 初始化室内地图管理器
            // 注意：实际项目中请确保 Replay 或 ReplayFragment 中能传入正确的 GoogleMap 对象
            if (gMap != null) {
                // 这里直接新建一个 IndoorMapManager 对象
                // 如果 replay 对象内部已包含室内地图管理，可以调用 replay.getIndoorMapManager() 代替
                // 此处仅作示例
                indoorMapManager = new IndoorMapManager(gMap);
            }
        }
    };

    // 播放控制按钮（位于底部区域）
    // 注意：这些按钮在 fragment_replay.xml 中定义
    // play, pause, restart, go-to-end, exit
    // 此处直接通过 findViewById 获取并设置监听
    // 控件 id 与布局文件保持一致，不需要改变
    // 室内控制相关控件
    private Switch autoFloor;
    private Switch gnssSwitch;
    private FloatingActionButton floorUpButton;
    private FloatingActionButton floorDownButton;
    private Button lineColorButton;

    // Replay 轨迹回放相关按钮
    // 这些按钮同样在布局中定义：play_button, pause_button, restart_button, go_to_end_button, exit_button
    // 用于控制轨迹回放
    private Button playButton;
    private Button pauseButton;
    private Button restartButton;
    private Button goToEndButton;
    private Button exitButton;

    // 室内地图管理对象
    private IndoorMapManager indoorMapManager;
    // 用于记录当前轨迹颜色状态（true 表示红色，false 表示黑色）
    private boolean isRed = true;

    // 新增：进度条控件
    private ProgressBar progressBar;
    // 新增：用于定期更新进度条的 Handler
    private Handler handler = new Handler();
    // 新增：更新进度条的时间间隔（毫秒）
    private static final int UPDATE_INTERVAL = 100;
    // 新增：更新进度条的 Runnable
    private Runnable updateProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (replay != null) {
                int totalDuration = replay.getTotalDuration();
                int currentProgress = replay.getCurrentProgress();
                if (totalDuration > 0) {
                    int progress = (int) ((float) currentProgress / totalDuration * 100);
                    progressBar.setProgress(progress);
                }
                // 继续定时更新
                handler.postDelayed(this, UPDATE_INTERVAL);
            }
        }
    };

    public ReplayFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        serverCommunications = new ServerCommunications(requireContext());
        this.sensorFusion = SensorFusion.getInstance();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // 加载 fragment_replay 布局文件
        View view = inflater.inflate(R.layout.fragment_replay, container, false);
        // 找到进度条控件
        progressBar = view.findViewById(R.id.progress_bar);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // ------------------ 回放轨迹部分 ------------------
        // 获取传递过来的轨迹 ID
        String trajectoryId = (getArguments() != null) ? getArguments().getString("trajectoryId") : null;
        if (trajectoryId != null) {
            Log.d("ReplayFragment", "接收到的轨迹 ID: " + trajectoryId);
        } else {
            Log.w("ReplayFragment", "未接收到轨迹 ID");
        }
        int trajId = trajectoryId != null ? Integer.parseInt(trajectoryId) : -1;
        ServerCommunications.DownloadStringResultCallback callback = new ServerCommunications.DownloadStringResultCallback() {
            @Override
            public void onResult(String trajectoryJson) {
                if (isAdded()) {
                    if (trajectoryJson != null) {
                        Log.d("ReplayFragment", "解析后的 JSON 数据: " + trajectoryJson);
                        replay = new Replay(requireContext(), trajectoryJson);
                        Log.d("ReplayFragment", "轨迹数据已成功加载并准备播放");
                        requireActivity().runOnUiThread(() -> {
                            SupportMapFragment mapFragment = (SupportMapFragment)
                                    getChildFragmentManager().findFragmentById(R.id.fragment_container);
                            if (mapFragment != null) {
                                mapFragment.getMapAsync(mapReadyCallback);
                            }
                        });
                    } else {
                        Log.e("ReplayFragment", "Failed to download trajectory JSON");
                    }
                } else {
                    Log.w("ReplayFragment", "Fragment is not attached");
                }
            }
        };
        if (trajId != -1) {
            serverCommunications.replayTrajectory(trajId, callback);
        }

        // 初始化地图
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.fragment_container);
        if (mapFragment == null) {
            mapFragment = new SupportMapFragment();
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, mapFragment)
                    .commit();
        }
        mapFragment.getMapAsync(mapReadyCallback);

        // 初始化回放控制按钮
        playButton = view.findViewById(R.id.play_button);
        pauseButton = view.findViewById(R.id.pause_button);
        restartButton = view.findViewById(R.id.restart_button);
        goToEndButton = view.findViewById(R.id.go_to_end_button);
        exitButton = view.findViewById(R.id.exit_button);

        playButton.setOnClickListener(v -> {
            if (replay != null) {
                replay.play();
                // 开始更新进度条
                handler.postDelayed(updateProgressRunnable, UPDATE_INTERVAL);
            } else {
                Log.w("ReplayFragment", "Replay 对象未初始化");
            }
        });
        pauseButton.setOnClickListener(v -> {
            if (replay != null) {
                replay.pause();
                // 停止更新进度条
                handler.removeCallbacks(updateProgressRunnable);
            } else {
                Log.w("ReplayFragment", "Replay 对象未初始化");
            }
        });
        restartButton.setOnClickListener(v -> {
            if (replay != null) {
                replay.replay();
                // 重置进度条
                progressBar.setProgress(0);
                // 重新开始更新进度条
                handler.postDelayed(updateProgressRunnable, UPDATE_INTERVAL);
            } else {
                Log.w("ReplayFragment", "Replay 对象未初始化");
            }
        });
        goToEndButton.setOnClickListener(v -> {
            if (replay != null) {
                replay.displayFullTrajectory();
                // 将进度条设置为 100%
                progressBar.setProgress(100);
                // 停止更新进度条
                handler.removeCallbacks(updateProgressRunnable);
            } else {
                Log.w("ReplayFragment", "Replay 对象未初始化");
            }
        });
        exitButton.setOnClickListener(v -> {
            // 停止更新进度条
            handler.removeCallbacks(updateProgressRunnable);
            getActivity().onBackPressed();
        });

        // ------------------ 室内控制相关部分 ------------------
        autoFloor = view.findViewById(R.id.autoFloor);
        gnssSwitch = view.findViewById(R.id.gnssSwitch);
        floorUpButton = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        lineColorButton = view.findViewById(R.id.lineColorButton);

        // 设置 AutoFloor 开关初始状态并监听状态变化
        autoFloor.setChecked(true);
        autoFloor.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d("ReplayFragment", "AutoFloor switched: " + isChecked);
            if (isChecked) {
                Log.d("ReplayFragment", "Auto floor mode enabled");
            } else {
                Log.d("ReplayFragment", "Auto floor mode disabled");
            }
        });

        // GNSS 开关监听器（实际功能根据需求扩展）
        gnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d("ReplayFragment", "GNSS switched: " + (isChecked ? "ON" : "OFF"));
            // 例如：显示或隐藏 GNSS marker
        });

        // 楼层上升按钮点击事件
        floorUpButton.setOnClickListener(v -> {
            Log.d("ReplayFragment", "Floor Up clicked");
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
            }
        });

        // 楼层下降按钮点击事件
        floorDownButton.setOnClickListener(v -> {
            Log.d("ReplayFragment", "Floor Down clicked");
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
            }
        });

        // 换色按钮点击事件：切换轨迹颜色示例（这里只改变按钮背景色并记录状态）
        lineColorButton.setOnClickListener(v -> {
            if (isRed) {
                lineColorButton.setBackgroundColor(Color.BLACK);
                Log.d("ReplayFragment", "Line color switched to Black");
                isRed = false;
            } else {
                lineColorButton.setBackgroundColor(Color.RED);
                Log.d("ReplayFragment", "Line color switched to Red");
                isRed = true;
            }
        });
        this.gnss= getView().findViewById(R.id.gnssSwitch);

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

                    // Set GNSS marker
                    gnssMarker=gMap.addMarker(
                            new MarkerOptions().title("GNSS position")
                                    .position(gnssLocation)
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                }else {
                    gnssMarker.remove();

                }
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 停止更新进度条
        handler.removeCallbacks(updateProgressRunnable);
    }
}