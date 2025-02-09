package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.R;

import java.util.ArrayList;
import java.util.List;


import com.openpositioning.PositionMe.dataParser.GnssData;
import com.openpositioning.PositionMe.dataParser.TrajectoryData;
import com.openpositioning.PositionMe.dataParser.TrajectoryParser;

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

    /**
     * Called when the GoogleMap is ready.
     * We do the main setup: load the recorded trajectory, prepare polyline, etc.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        this.mMap = googleMap;
        // set default map type
        googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        googleMap.getUiSettings().setCompassEnabled(true);

        // Initialize the NucleusBuildingManager to handle indoor maps
        nucleusBuildingManager = new NucleusBuildingManager(googleMap);

        // (可选) 如果你要测试自动楼层切换 / 手动楼层切换:
        setupFloorButtons();

        // 载入轨迹 (根据 trajectoryId 来加载)
        loadRecordedTrajectory(trajectoryId);

        // 如果有数据，初始化回放
        if (!recordedTrajectory.isEmpty()) {
            // init polyline
            PolylineOptions polylineOptions = new PolylineOptions()
                    .color(getResources().getColor(R.color.pastelBlue))
                    .add(recordedTrajectory.get(0));
            replayPolyline = mMap.addPolyline(polylineOptions);

            // init marker
            replayMarker = mMap.addMarker(new MarkerOptions()
                    .position(recordedTrajectory.get(0))
                    .title("Playback Marker"));

            // camera move to start
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(recordedTrajectory.get(0),19f));
        }

        // 设置进度条
        playbackProgressBar.setMax(recordedTrajectory.size());

        // 定义 playbackRunnable，用于每隔 PLAYBACK_INTERVAL_MS 回放下一个点
        playbackRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPlaying) return; // 如果被暂停，则不继续

                if (currentIndex < recordedTrajectory.size()) {
                    LatLng point = recordedTrajectory.get(currentIndex);

                    // 向折线里添加当前点
                    List<LatLng> currentPoints = replayPolyline.getPoints();
                    currentPoints.add(point);
                    replayPolyline.setPoints(currentPoints);

                    // 移动 marker
                    replayMarker.setPosition(point);
                    mMap.animateCamera(CameraUpdateFactory.newLatLng(point));

                    // 如果点在大楼内，显示对应楼层图
                    if (nucleusBuildingManager.isPointInBuilding(point)) {
                        if (autoFloorSwitch.isChecked()) {
                            // 根据高度或其他逻辑自动算楼层
                            // 这里仅演示固定切到 2 楼
                            nucleusBuildingManager.getIndoorMapManager().switchFloor(2);
                        }
                    } else {
                        nucleusBuildingManager.getIndoorMapManager().hideMap();
                    }

                    // 更新进度条
                    playbackProgressBar.setProgress(currentIndex);

                    // 索引+1
                    currentIndex++;
                    // 再次延迟调用
                    replayHandler.postDelayed(this, PLAYBACK_INTERVAL_MS);

                } else {
                    // 播放结束
                    isPlaying = false;
                    playPauseBtn.setText("Play");
                }
            }
        };
    }

    /**
     * (示例) 根据 trajectoryId 载入记录好的坐标点
     * 实际可以从本地数据库、文件或服务器获取 proto 后再解析成 LatLng 列表
     */
    private void loadRecordedTrajectory(String trajectoryId) {
        // Clear any existing trajectory data
        recordedTrajectory.clear();

        // Parse the trajectory file from internal storage
        // (Ensure that your parser and model classes are in the package com.openpositioning.PositionMe.playback)
        TrajectoryData trajectoryData = TrajectoryParser.parseTrajectoryFile(getContext());

        // Retrieve the list of GNSS samples from the parsed data
        List<GnssData> gnssSamples = trajectoryData.getGnssData();

        // For each GNSS sample, create a LatLng point using the latitude and longitude values,
        // and add it to the recordedTrajectory list.
        for (GnssData sample : gnssSamples) {
            LatLng point = new LatLng(sample.getLatitude(), sample.getLongitude());
            recordedTrajectory.add(point);
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
