package com.openpositioning.PositionMe.presentation.fragment;

import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.sensors.Wifi;
import com.openpositioning.PositionMe.presentation.viewitems.WifiListAdapter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StatusBottomSheetFragment extends BottomSheetDialogFragment {

    private ProgressBar circularProgressBar;
    private ImageView recordingIcon;
    private TextView elevationTextView;
    private TextView gnssErrorTextView;
    private RecyclerView wifiListView;

    private int progress = 0;
    private int maxProgress = 100;

    private SensorFusion sensorFusion;
    private Handler refreshDataHandler;
    private static final long REFRESH_TIME = 2000;

    // 映射每个传感器到其对应 TextView 的 ID 数组
    private final Map<SensorTypes, Integer[]> sensorViewMap = new HashMap<>();

    private final Runnable refreshTableTask = new Runnable() {
        @Override
        public void run() {
            Map<SensorTypes, float[]> sensorValueMap = sensorFusion.getSensorValueMap();
            for (Map.Entry<SensorTypes, Integer[]> entry : sensorViewMap.entrySet()) {
                SensorTypes sensor = entry.getKey();
                float[] values = sensorValueMap.get(sensor);
                Integer[] viewIds = entry.getValue();

                if (values == null || viewIds == null) continue;

                for (int i = 0; i < Math.min(values.length, viewIds.length); i++) {
                    TextView textView = getView().findViewById(viewIds[i]);
                    if (textView != null) {
                        String formatted = String.format("%.2f", values[i]);
                        if (sensor == SensorTypes.GNSSLATLONG && i < 2) {
                            int labelId = (i == 0) ? R.string.lati : R.string.longi;
                            textView.setText(getString(labelId, formatted));
                        } else if (values.length == 1) {
                            textView.setText(getString(R.string.level, formatted));
                        } else {
                            int labelId = (i == 0) ? R.string.x : (i == 1) ? R.string.y : R.string.z;
                            textView.setText(getString(labelId, formatted));
                        }
                    }
                }
            }

            List<Wifi> wifiObjects = sensorFusion.getWifiList();
            if (wifiObjects != null) {
                wifiListView.setAdapter(new WifiListAdapter(getActivity(), wifiObjects));
            }

            refreshDataHandler.postDelayed(this, REFRESH_TIME);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_status, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        circularProgressBar = view.findViewById(R.id.circularProgressBar);
        recordingIcon = view.findViewById(R.id.recordingIcon);
        elevationTextView = view.findViewById(R.id.elevationTextView);
        gnssErrorTextView = view.findViewById(R.id.gnssErrorTextView);
        wifiListView = view.findViewById(R.id.wifiList);
        wifiListView.setLayoutManager(new LinearLayoutManager(getActivity()));

        sensorFusion = SensorFusion.getInstance();
        refreshDataHandler = new Handler();

        setupSensorViewMap();
        startBlinkingAnimation();

        refreshDataHandler.post(refreshTableTask);
    }

    private void setupSensorViewMap() {
        sensorViewMap.put(SensorTypes.ACCELEROMETER,
                new Integer[]{R.id.accelerometerX, R.id.accelerometerY, R.id.accelerometerZ});
        sensorViewMap.put(SensorTypes.GRAVITY,
                new Integer[]{R.id.gravityX, R.id.gravityY, R.id.gravityZ});
        sensorViewMap.put(SensorTypes.MAGNETICFIELD,
                new Integer[]{R.id.magneticFieldX, R.id.magneticFieldY, R.id.magneticFieldZ});
        sensorViewMap.put(SensorTypes.GYRO,
                new Integer[]{R.id.gyroX, R.id.gyroY, R.id.gyroZ});
        sensorViewMap.put(SensorTypes.LIGHT,
                new Integer[]{R.id.lightLevel});
        sensorViewMap.put(SensorTypes.PRESSURE,
                new Integer[]{R.id.pressureLevel});
        sensorViewMap.put(SensorTypes.PROXIMITY,
                new Integer[]{R.id.proximityLevel});
        sensorViewMap.put(SensorTypes.GNSSLATLONG,
                new Integer[]{R.id.gnssLat, R.id.gnssLong});
        sensorViewMap.put(SensorTypes.PDR,
                new Integer[]{R.id.pdrX, R.id.pdrY});
    }

    private void startBlinkingAnimation() {
        Animation blinking = new AlphaAnimation(1, 0);
        blinking.setDuration(800);
        blinking.setInterpolator(new LinearInterpolator());
        blinking.setRepeatCount(Animation.INFINITE);
        blinking.setRepeatMode(Animation.REVERSE);
        recordingIcon.startAnimation(blinking);
    }

    public void updateElevation(String elevation) {
        if (elevationTextView != null) {
            elevationTextView.setText(getString(R.string.elevation, elevation));
        }
    }

    public void updateGnssError(String error, int visibility) {
        if (gnssErrorTextView != null) {
            gnssErrorTextView.setVisibility(visibility);
            gnssErrorTextView.setText(error);
        }
    }

    public void setMaxProgress(int max) {
        this.maxProgress = max;
        if (circularProgressBar != null) {
            circularProgressBar.setMax(max);
        }
    }

    public void setProgress(int progress) {
        this.progress = progress;
        if (circularProgressBar != null) {
            circularProgressBar.setProgress(progress);
        }
    }

    public void incrementProgress() {
        setProgress(++this.progress);
    }

    public void setScaleY(float scale) {
        if (circularProgressBar != null) {
            circularProgressBar.setScaleY(scale);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (refreshDataHandler != null) {
            refreshDataHandler.removeCallbacks(refreshTableTask);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (refreshDataHandler != null) {
            refreshDataHandler.postDelayed(refreshTableTask, REFRESH_TIME);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // 设置 BottomSheet 的 peekHeight 为屏幕高度的 1/3
        View view = getView();
        if (view != null) {
            View parent = (View) view.getParent();
            if (parent != null) {
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(parent);
                int screenHeight = Resources.getSystem().getDisplayMetrics().heightPixels;
                behavior.setPeekHeight((int) (screenHeight * 0.33f));
                behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            }
        }
    }
}
