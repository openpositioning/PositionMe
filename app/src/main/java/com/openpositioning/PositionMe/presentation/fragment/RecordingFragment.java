package com.openpositioning.PositionMe.presentation.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.model.LatLng;

public class RecordingFragment extends Fragment {

    // UI elements
    private MaterialButton completeButton, cancelButton,addTagButton;
    private PathView pathView;
    private ImageView recIcon;
    private ProgressBar timeRemaining;
    private TextView elevation, distanceTravelled, gnssError;

    // App settings
    private SharedPreferences settings;

    // Sensor & data logic
    private SensorFusion sensorFusion;
    private Handler refreshDataHandler;
    private CountDownTimer autoStop;

    // Distance tracking
    private float distance = 0f;
    private float previousPosX = 0f;
    private float previousPosY = 0f;

    // References to the child map fragment
    private TrajectoryMapFragment trajectoryMapFragment;

    // Add Tag counter
    private int tagCount = 0;

    // Save the test point of the user pressing "Add Tag"
    private final java.util.ArrayList<com.openpositioning.PositionMe.Traj.GNSSPosition> testPoints =
            new java.util.ArrayList<>();
    // Timestamp
    private long startTimestampMs = 0L;

    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            updateUIandPosition();
            // Loop again
            refreshDataHandler.postDelayed(refreshDataTask, 200);
        }
    };

    public RecordingFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.sensorFusion = SensorFusion.getInstance();
        Context context = requireActivity();
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.refreshDataHandler = new Handler();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_recording, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        testPoints.clear();
        tagCount = 0;
        startTimestampMs = System.currentTimeMillis();

        sensorFusion.setTestPoints(testPoints);
        sensorFusion.setStartTimestampMs(startTimestampMs);

        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMapFragmentContainer);

        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.trajectoryMapFragmentContainer, trajectoryMapFragment)
                    .commit();
        }

        elevation = view.findViewById(R.id.currentElevation);
        distanceTravelled = view.findViewById(R.id.currentDistanceTraveled);
        gnssError = view.findViewById(R.id.gnssError);
        pathView = view.findViewById(R.id.pathView);
        completeButton = view.findViewById(R.id.stopButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        addTagButton = view.findViewById(R.id.addTagButton);
        addTagButton.bringToFront();
        addTagButton.setElevation(20f);
        recIcon = view.findViewById(R.id.redDot);
        timeRemaining = view.findViewById(R.id.timeRemainingBar);

        gnssError.setVisibility(View.GONE);
        elevation.setText(getString(R.string.elevation, "0"));
        distanceTravelled.setText(getString(R.string.meter, "0"));

        completeButton.setOnClickListener(v -> {
            sensorFusion.setStartTimestampMs(startTimestampMs);
            sensorFusion.setTestPoints(testPoints);

            if (autoStop != null) autoStop.cancel();
            sensorFusion.stopRecording();
            ((RecordingActivity) requireActivity()).showCorrectionScreen();
        });

        cancelButton.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                    .setTitle("Confirm Cancel")
                    .setMessage("Are you sure you want to cancel the recording? Your progress will be lost permanently!")
                    .setNegativeButton("Yes", (dialogInterface, which) -> {
                        sensorFusion.stopRecording();
                        if (autoStop != null) autoStop.cancel();
                        requireActivity().onBackPressed();
                    })
                    .setPositiveButton("No", (dialogInterface, which) -> {
                        dialogInterface.dismiss();
                    })
                    .create();

            dialog.setOnShowListener(dialogInterface -> {
                Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negativeButton.setTextColor(Color.RED);
            });

            dialog.show();
        });

        addTagButton.setOnClickListener(v -> {
            tagCount++;

            LatLng current = null;
            if (trajectoryMapFragment != null) {
                current = trajectoryMapFragment.getCurrentLocation();
            }

            if (current != null && trajectoryMapFragment != null) {
                trajectoryMapFragment.addTagPoint(current, tagCount);

                long relativeTs = System.currentTimeMillis() - startTimestampMs;

                com.openpositioning.PositionMe.Traj.GNSSPosition p =
                        com.openpositioning.PositionMe.Traj.GNSSPosition.newBuilder()
                                .setRelativeTimestamp(relativeTs)
                                .setLatitude(current.latitude)
                                .setLongitude(current.longitude)
                                .setAltitude((double) sensorFusion.getElevation())
                                .build();

                testPoints.add(p);
            }
        });

        blinkingRecordingIcon();

        if (this.settings.getBoolean("split_trajectory", false)) {
            long limit = this.settings.getInt("split_duration", 30) * 60000L;
            timeRemaining.setMax((int) (limit / 1000));
            timeRemaining.setProgress(0);
            timeRemaining.setScaleY(3f);

            autoStop = new CountDownTimer(limit, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    timeRemaining.incrementProgressBy(1);
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
    }

    /**
     * Update the UI with sensor data and pass map updates to TrajectoryMapFragment.
     */
    private void updateUIandPosition() {
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null) return;

        // Distance
        distance += Math.sqrt(Math.pow(pdrValues[0] - previousPosX, 2)
                + Math.pow(pdrValues[1] - previousPosY, 2));
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));

        // Elevation
        float elevationVal = sensorFusion.getElevation();
        elevation.setText(getString(R.string.elevation, String.format("%.1f", elevationVal)));


        if (trajectoryMapFragment != null) {
            trajectoryMapFragment.updateElevation(elevationVal);
        }


        // Current location
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
                    gnssError.setVisibility(View.VISIBLE);
                    gnssError.setText(String.format(getString(R.string.gnss_error) + "%.2fm", errorDist));
                }
                trajectoryMapFragment.updateGNSS(gnssLocation);
            } else {
                gnssError.setVisibility(View.GONE);
                trajectoryMapFragment.clearGNSS();
            }
        }

        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];
    }

    private void blinkingRecordingIcon() {
        Animation blinking = new AlphaAnimation(1, 0);
        blinking.setDuration(800);
        blinking.setInterpolator(new LinearInterpolator());
        blinking.setRepeatCount(Animation.INFINITE);
        blinking.setRepeatMode(Animation.REVERSE);
        recIcon.startAnimation(blinking);
    }

    @Override
    public void onPause() {
        super.onPause();
        refreshDataHandler.removeCallbacks(refreshDataTask);
    }

    @Override
    public void onResume() {
        super.onResume();
        SensorFusion sensorFusion = SensorFusion.getInstance();
        if (this.pathView != null) {
            sensorFusion.setPathView(this.pathView);
        } else {
            PathView pv = getView().findViewById(R.id.pathView);
            if (pv != null) {
                this.pathView = pv;
                sensorFusion.setPathView(pv);
            }
        }
        sensorFusion.resumeListening();
        if(!this.settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
    }
}