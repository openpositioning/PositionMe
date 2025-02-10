package com.openpositioning.PositionMe.presentation.fragment;

import static com.openpositioning.PositionMe.data.remote.TrajectoryFileHandler.getReplayPointByTimestamp;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.gson.JsonObject;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.TrajectoryFileHandler;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ReplayFragment extends Fragment {

    // 地图子Fragment
    private TrajectoryMapFragment trajectoryMapFragment;

    // UI 控件
    private Button playPauseButton, restartButton, exitButton, goEndButton;
    private SeekBar playbackSeekBar;

    // 播放逻辑
    private final Handler playbackHandler = new Handler();
    private final long PLAYBACK_INTERVAL_MS = 500; // 每帧时间间隔
    private List<ReplayPoint> replayData; // 轨迹数据帧列表
    private int currentIndex = 0;
    private boolean isPlaying = false;

    // 用于统一传递轨迹文件路径
    private String filePath;

    // ReplayPoint 用于保存每一帧数据
    public static class ReplayPoint {
        LatLng pdrLocation;  // 用户位置（例如 PDR 得到的位置）
        LatLng gnssLocation; // GNSS 位置（可选）
        float orientation;   // 方向（单位：度）
        long timestamp;      // 时间戳

        public ReplayPoint(LatLng pdr, LatLng gnss, float orientation, long ts) {
            this.pdrLocation = pdr;
            this.gnssLocation = gnss;
            this.orientation = orientation;
            this.timestamp = ts;
        }
    }

    /**
     * 根据 IMU 数据（JsonObject）转换为 ReplayPoint 对象。
     * 这里暂时使用固定示例数据，实际开发中请根据数据计算真实位置和方向。
     */
    private ReplayPoint convertImuDataToReplayPoint(JsonObject imuData) {
        double dummyLat = 37.4219999;
        double dummyLng = -122.0840575;
        // TODO: 根据 imuData 中的旋转向量或其它数据计算实际的 orientation
        float orientation = 0f;
        long timestamp = imuData.get("relativeTimestamp").getAsLong();
        LatLng gnssLocation = null;
        return new ReplayPoint(new LatLng(dummyLat, dummyLng), gnssLocation, orientation, timestamp);
    }

    /**
     * 利用 TrajectoryFileHandler.getSmoothedImuData() 解析轨迹文件，
     * 按固定时间步长生成每一帧数据，并返回 ReplayPoint 列表。
     */
    private List<ReplayPoint> loadTrajectoryFromProto(String filePath) {
        List<ReplayPoint> replayPoints = new ArrayList<>();

        try {
            // 1. 获取时间范围
            long[] range = TrajectoryFileHandler.getTimeRange(filePath);
            long minTimestamp = range[0];
            long maxTimestamp = range[1];

            // 2. 按时间步长逐帧解析
            final long PLAYBACK_INTERVAL_MS = 500;

            for (long ts = minTimestamp; ts <= maxTimestamp; ts += PLAYBACK_INTERVAL_MS) {
                try {
                    // 调用 `getReplayPointByTimestamp`
                    ReplayPoint point = getReplayPointByTimestamp(filePath, String.valueOf(ts));

                    if (point != null) {
                        replayPoints.add(point);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return replayPoints;
    }



    private final Runnable playbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying || replayData == null) return;

            updateMapForIndex(currentIndex);
            currentIndex++;
            playbackSeekBar.setProgress(currentIndex);
            if (currentIndex < replayData.size()) {
                playbackHandler.postDelayed(this, PLAYBACK_INTERVAL_MS);
            } else {
                isPlaying = false;
                playPauseButton.setText("Play");
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 从传入参数中获取轨迹文件路径
        if (getArguments() != null) {
            filePath = getArguments().getString(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, "placeholder-path");
        } else {
            filePath = "placeholder-path";
        }
        Log.i("ReplayFragment", "Loading trajectory from file: " + filePath);
        // 根据实际文件路径加载轨迹数据
        replayData = loadTrajectoryFromProto(filePath);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_replay, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 1. 获取地图子Fragment
        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.replayMapFragmentContainer);
        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.replayMapFragmentContainer, trajectoryMapFragment)
                    .commit();
        }

        // 2. 获取 UI 控件引用
        playPauseButton = view.findViewById(R.id.playPauseButton);
        restartButton = view.findViewById(R.id.restartButton);
        exitButton = view.findViewById(R.id.exitButton);
        goEndButton = view.findViewById(R.id.goEndButton);
        playbackSeekBar = view.findViewById(R.id.playbackSeekBar);

        // 3. 设置 SeekBar 最大值
        if (replayData != null && !replayData.isEmpty()) {
            playbackSeekBar.setMax(replayData.size() - 1);
        }

        // 4. 设置各按钮监听器
        playPauseButton.setOnClickListener(v -> {
            if (replayData == null || replayData.isEmpty()) return;
            if (isPlaying) {
                isPlaying = false;
                playPauseButton.setText("Play");
            } else {
                isPlaying = true;
                playPauseButton.setText("Pause");
                if (currentIndex >= replayData.size()) {
                    currentIndex = 0;
                }
                playbackHandler.post(playbackRunnable);
            }
        });

        restartButton.setOnClickListener(v -> {
            if (replayData == null) return;
            currentIndex = 0;
            playbackSeekBar.setProgress(0);
            updateMapForIndex(0);
        });

        goEndButton.setOnClickListener(v -> {
            if (replayData == null || replayData.isEmpty()) return;
            currentIndex = replayData.size() - 1;
            playbackSeekBar.setProgress(currentIndex);
            updateMapForIndex(currentIndex);
            isPlaying = false;
            playPauseButton.setText("Play");
        });

        exitButton.setOnClickListener(v -> {
            if (getActivity() instanceof ReplayActivity) {
                ((ReplayActivity) getActivity()).finishFlow();
            } else {
                requireActivity().onBackPressed();
            }
        });

        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentIndex = progress;
                    updateMapForIndex(currentIndex);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        // 初始显示第一帧
        if (replayData != null && !replayData.isEmpty()) {
            updateMapForIndex(0);
        }
    }

    /**
     * 根据当前帧索引更新地图显示。
     */
    private void updateMapForIndex(int index) {
        if (replayData == null || index < 0 || index >= replayData.size()) return;
        ReplayPoint point = replayData.get(index);
        if (trajectoryMapFragment != null) {
            trajectoryMapFragment.updateUserLocation(point.pdrLocation, point.orientation);
            if (point.gnssLocation != null) {
                trajectoryMapFragment.updateGNSS(point.gnssLocation);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        isPlaying = false;
        playbackHandler.removeCallbacks(playbackRunnable);
        if (playPauseButton != null) {
            playPauseButton.setText("Play");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        playbackHandler.removeCallbacks(playbackRunnable);
    }
}
