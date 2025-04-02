package com.openpositioning.PositionMe.presentation.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.local.DataFileManager;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.sensors.Wifi;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import org.json.JSONObject;

import java.util.List;

public class RecordingFragment extends Fragment {

    private static final String TAG = "RecordingFragment";

    private Button completeButton;
    // NEW: Info button to toggle call-out fragment
    private Button moreInfoButton;

    private TrajectoryMapFragment trajectoryMapFragment;
    private CalibrationFragment calibrationFragment;
    private StatusFragment recordingStatusFragment;

    private SensorFusion sensorFusion;
    private DataFileManager dataFileManager;
    private SharedPreferences settings;

    private Handler refreshDataHandler;
    private CountDownTimer autoStop;
    private Handler sensorUpdateHandler;
    private Runnable sensorUpdateTask;

    private float distance = 0f;
    private float previousPosX = 0f;
    private float previousPosY = 0f;

    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            updateUIandPosition();
            refreshDataHandler.postDelayed(this, 200);
        }
    };

    private static final long WIFI_REQUEST_INTERVAL_MS = 8000;
    private static final long PASSIVE_COLLECTION_INTERVAL_MS = 500;
    private long lastWifiRequestTime = 0;

    public RecordingFragment() {}

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sensorFusion = SensorFusion.getInstance();
        Context context = requireActivity();
        settings = PreferenceManager.getDefaultSharedPreferences(context);
        refreshDataHandler = new Handler();
        dataFileManager = new DataFileManager(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_recording, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        sensorFusion.startRecording();

        completeButton = view.findViewById(R.id.finishRecordingButton);
        // NEW: Initialize the info button for toggling call-out fragment
        moreInfoButton = view.findViewById(R.id.moreInfoButton);
        final View callOutContainer = view.findViewById(R.id.recordingStatusFragmentContainer);

        trajectoryMapFragment = (TrajectoryMapFragment) getChildFragmentManager()
                .findFragmentById(R.id.trajectoryMapFragmentContainer);
        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment.RecordingTrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.trajectoryMapFragmentContainer, trajectoryMapFragment)
                    .commitNow();
        }

        calibrationFragment = (CalibrationFragment) getChildFragmentManager()
                .findFragmentById(R.id.calibrationFragmentContainer);
        if (calibrationFragment == null) {
            calibrationFragment = CalibrationFragment.newInstance();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.calibrationFragmentContainer, calibrationFragment)
                    .commitNow();
        }
        calibrationFragment.setSensorFusion(sensorFusion);
        calibrationFragment.setDataFileManager(dataFileManager);
        calibrationFragment.setTrajectoryMapFragment(trajectoryMapFragment);

        recordingStatusFragment = (StatusFragment) getChildFragmentManager()
                .findFragmentById(R.id.recordingStatusFragmentContainer);
        if (recordingStatusFragment == null) {
            recordingStatusFragment = new StatusFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.recordingStatusFragmentContainer, recordingStatusFragment)
                    .commitNow();
        }
        // Initially hide the call-out container
        callOutContainer.setVisibility(View.GONE);

        // NEW: Set click listener on the info button to toggle the call-out fragment
        moreInfoButton.setOnClickListener(v -> {
            if (callOutContainer.getVisibility() == View.GONE) {
                // Slide up animation to show call-out
                callOutContainer.setVisibility(View.VISIBLE);
            } else {
                // Slide down animation to hide call-out
                callOutContainer.setVisibility(View.GONE);
            }
        });

        Button toggleAdvancedMenuButton = view.findViewById(R.id.toggleCalibrationMenuButton);
        View calibrationContainer = view.findViewById(R.id.calibrationFragmentContainer);
        toggleAdvancedMenuButton.setOnClickListener(v -> {
            if (calibrationContainer.getVisibility() == View.VISIBLE) {
                calibrationContainer.setVisibility(View.GONE);
                toggleAdvancedMenuButton.setText("Tag");
            } else {
                calibrationContainer.setVisibility(View.VISIBLE);
                toggleAdvancedMenuButton.setText("Hide");
            }
        });

        completeButton.setOnClickListener(v -> {
            if (autoStop != null) autoStop.cancel();
            sensorFusion.stopRecording();
            ((RecordingActivity) requireActivity()).showCorrectionScreen();
        });

        if (settings.getBoolean("split_trajectory", false)) {
            long limit = settings.getInt("split_duration", 30) * 60000L;
            recordingStatusFragment.setMaxProgress((int) (limit / 1000));
            recordingStatusFragment.setProgress(0);
            recordingStatusFragment.setScaleY(3f);
            autoStop = new CountDownTimer(limit, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    recordingStatusFragment.incrementProgress();
                    updateUIandPosition();
                }

                @Override
                public void onFinish() {
                    sensorFusion.stopRecording();
                    ((RecordingActivity) requireActivity()).showCorrectionScreen();
                }
            }.start();
        } else {
            refreshDataHandler.post(refreshDataTask);
        }

        sensorUpdateHandler = new Handler(Looper.getMainLooper());
        sensorUpdateTask = new Runnable() {
            @Override
            public void run() {
                collectFullSensorData();
                sensorUpdateHandler.postDelayed(this, PASSIVE_COLLECTION_INTERVAL_MS);
            }
        };
        sensorUpdateHandler.post(sensorUpdateTask);
    }

    private void collectFullSensorData() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastWifiRequestTime > WIFI_REQUEST_INTERVAL_MS) {
                lastWifiRequestTime = now;
                requestWifiLocationUpdate();
            }
            JSONObject record = sensorFusion.getAllSensorData();
            dataFileManager.addRecord(record);
            Log.d(TAG, "Collected sensor data.");
        } catch (Exception e) {
            Log.e(TAG, "Error collecting sensor data", e);
        }
    }

    private void requestWifiLocationUpdate() {
        List<Wifi> wifiList = sensorFusion.getWifiList();
        if (wifiList == null || wifiList.isEmpty()) {
            Log.w(TAG, "No WiFi data available yet, skipping WiFi positioning request.");
            return;
        }
        try {
            JSONObject wifiAccessPoints = new JSONObject();
            for (Wifi wifi : wifiList) {
                wifiAccessPoints.put(String.valueOf(wifi.getBssid()), wifi.getLevel());
            }
            JSONObject fingerprint = new JSONObject();
            fingerprint.put("wf", wifiAccessPoints);
            sensorFusion.getWiFiPositioning().request(fingerprint, new com.openpositioning.PositionMe.data.remote.WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    Log.d(TAG, "WiFi Positioning Success: lat=" + wifiLocation.latitude +
                            ", lon=" + wifiLocation.longitude + ", floor=" + floor);
                }
                @Override
                public void onError(String message) {
                    Log.e(TAG, "WiFi Positioning Failed: " + message);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error building WiFi fingerprint request", e);
        }
    }

    private void updateUIandPosition() {
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null) return;

        float elevationVal = sensorFusion.getElevation();
        recordingStatusFragment.updateElevation(String.format("%.1f", elevationVal));

        float[] latLngArray = sensorFusion.getGNSSLatitude(true);
        if (latLngArray != null) {
            LatLng oldLocation = trajectoryMapFragment.getCurrentLocation();
            LatLng newLocation = UtilFunctions.calculateNewPos(
                    oldLocation == null ? new LatLng(latLngArray[0], latLngArray[1]) : oldLocation,
                    new float[]{ pdrValues[0] - previousPosX, pdrValues[1] - previousPosY }
            );
            if (trajectoryMapFragment != null) {
                trajectoryMapFragment.updateUserLocation(newLocation,
                        (float) Math.toDegrees(sensorFusion.passOrientation()));
            }
        }

        float[] gnss = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        if (gnss != null && trajectoryMapFragment != null) {
            if (trajectoryMapFragment.isGnssEnabled()) {
                LatLng gnssLocation = new LatLng(gnss[0], gnss[1]);
                LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();
                if (currentLoc != null) {
                    double errorDist = UtilFunctions.distanceBetweenPoints(currentLoc, gnssLocation);
                    recordingStatusFragment.updateGnssError(String.format("%.2fm", errorDist), View.VISIBLE);
                }
                trajectoryMapFragment.updateGNSS(gnssLocation);
            } else {
                recordingStatusFragment.updateGnssError("", View.GONE);
                trajectoryMapFragment.clearGNSS();
            }
        }

        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];
    }

    @Override
    public void onPause() {
        super.onPause();
        refreshDataHandler.removeCallbacks(refreshDataTask);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
    }
}
