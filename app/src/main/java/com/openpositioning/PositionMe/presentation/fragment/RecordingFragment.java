package com.openpositioning.PositionMe.presentation.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
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
import com.openpositioning.PositionMe.fusion.KnnPositioner;
import com.openpositioning.PositionMe.fusion.ParticleFilter;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.List;

import android.widget.Toast;




/**
 * Fragment responsible for managing the recording process of trajectory data.
 * <p>
 * The RecordingFragment serves as the interface for users to initiate, monitor, and
 * complete trajectory recording. It integrates sensor fusion data to track user movement
 * and updates a map view in real time. Additionally, it provides UI controls to cancel,
 * stop, and monitor recording progress.
 * <p>
 * Features:
 * - Starts and stops trajectory recording.
 * - Displays real-time sensor data such as elevation and distance traveled.
 * - Provides UI controls to cancel or complete recording.
 * - Uses {@link TrajectoryMapFragment} to visualize recorded paths.
 * - Manages GNSS tracking and error display.
 *
 * @see TrajectoryMapFragment The map fragment displaying the recorded trajectory.
 * @see RecordingActivity The activity managing the recording workflow.
 * @see SensorFusion Handles sensor data collection.
 * @see SensorTypes Enumeration of available sensor types.
 *
 * @author Shu Gu
 */

public class RecordingFragment extends Fragment {

    // UI elements
    private MaterialButton completeButton, cancelButton;
    private ImageView recIcon;
    private ProgressBar timeRemaining;
    private TextView elevation, distanceTravelled, gnssError, posSourceText;

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

    // Particle filter for fused position estimation
    private final ParticleFilter particleFilter = new ParticleFilter();
    private boolean pfInitialized = false;
    private boolean pfInitFromGnss = false;
    private double lastGnssLat = 0, lastGnssLng = 0;
    private LatLng lastWifiPos = null;
    private LatLng lastKnnPos = null;
    private int lastPFFloor = -1, lastPFBuilding = -1;

    // KNN WiFi positioning
    private KnnPositioner knnPositioner;
    private static final double KNN_SIGMA_M = 3.0; // KNN is more accurate than API WiFi

    // Separate trackers for observation dots (avoid ordering bug with PF update vars)
    private LatLng lastObsWifi = null;
    private double lastObsGnssLat = 0, lastObsGnssLng = 0;

    // Stuck detection: if particles don't move despite PDR input, reinitialize
    private int stuckCounter = 0;
    private static final int STUCK_THRESHOLD = 8; // ~1.6 seconds at 200ms ticks

    // Stair detection: track elevation change to scale down PDR on stairs
    private float lastElevation = 0f;
    private static final float STAIR_ELEV_RATE_THRESHOLD = 0.3f; // m per 200ms tick
    private static final float STAIR_STEP_SCALE = 0.35f; // horizontal fraction on stairs

    // Calibration: collect multiple KNN/WiFi samples before initializing PF
    private static final int CALIBRATION_SAMPLES = 3;
    private static final long CALIBRATION_TIMEOUT_MS = 9000;
    private long calibrationStartTime = 0;
    private final java.util.List<LatLng> calibrationPositions = new java.util.ArrayList<>();
    private boolean calibrating = false;
    private TextView calibrationCountdown;

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
        this.knnPositioner = new KnnPositioner(context);
        Log.d("PF_Init", "KNN loaded: " + knnPositioner.getTrainingSize() + " points");
        this.refreshDataHandler = new Handler();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate only the "recording" UI parts (no map)
        return inflater.inflate(R.layout.fragment_recording, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Child Fragment: the container in fragment_recording.xml
        // where TrajectoryMapFragment is placed
        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMapFragmentContainer);

        // If not present, create it
        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.trajectoryMapFragmentContainer, trajectoryMapFragment)
                    .commit();
        }

        // Initialize UI references
        elevation = view.findViewById(R.id.currentElevation);
        distanceTravelled = view.findViewById(R.id.currentDistanceTraveled);
        gnssError = view.findViewById(R.id.gnssError);
        posSourceText = view.findViewById(R.id.posSource);

        completeButton = view.findViewById(R.id.stopButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        recIcon = view.findViewById(R.id.redDot);
        timeRemaining = view.findViewById(R.id.timeRemainingBar);
        view.findViewById(R.id.btn_test_point).setOnClickListener(v -> onAddTestPoint());


        // Hide or initialize default values
        gnssError.setVisibility(View.GONE);
        elevation.setText(getString(R.string.elevation, "0"));
        distanceTravelled.setText(getString(R.string.meter, "0"));
        calibrationCountdown = view.findViewById(R.id.calibrationCountdown);

        // Start calibration phase
        calibrating = true;
        calibrationStartTime = System.currentTimeMillis();
        calibrationPositions.clear();
        if (calibrationCountdown != null) calibrationCountdown.setVisibility(View.VISIBLE);

        // Buttons
        completeButton.setOnClickListener(v -> {
            // Stop recording & go to correction
            if (autoStop != null) autoStop.cancel();
            sensorFusion.stopRecording();
            // Show Correction screen
            ((RecordingActivity) requireActivity()).showCorrectionScreen();
        });


        // Cancel button with confirmation dialog
        cancelButton.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                    .setTitle("Confirm Cancel")
                    .setMessage("Are you sure you want to cancel the recording? Your progress will be lost permanently!")
                    .setNegativeButton("Yes", (dialogInterface, which) -> {
                        // User confirmed cancellation
                        sensorFusion.stopRecording();
                        if (autoStop != null) autoStop.cancel();
                        pfInitialized = false;
                        lastPFFloor = -1;
                        lastPFBuilding = -1;
                        lastWifiPos = null;
                        lastGnssLat = 0;
                        lastGnssLng = 0;
                        requireActivity().onBackPressed();
                    })
                    .setPositiveButton("No", (dialogInterface, which) -> {
                        // User cancelled the dialog. Do nothing.
                        dialogInterface.dismiss();
                    })
                    .create(); // Create the dialog but do not show it yet

            // Show the dialog and change the button color
            dialog.setOnShowListener(dialogInterface -> {
                Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negativeButton.setTextColor(Color.RED); // Set "Yes" button color to red
            });

            dialog.show(); // Finally, show the dialog
        });

        // The blinking effect for recIcon
        blinkingRecordingIcon();

        // Start the timed or indefinite UI refresh
        if (this.settings.getBoolean("split_trajectory", false)) {
            // A maximum recording time is set
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
            // No set time limit, just keep refreshing
            refreshDataHandler.post(refreshDataTask);
        }
    }

    private void onAddTestPoint() {
        // 1) Ensure the map fragment is ready
        if (trajectoryMapFragment == null) return;

        // 2) Read current track position (must lie on the current path)
        LatLng cur = trajectoryMapFragment.getCurrentLocation();
        if (cur == null) {
            Toast.makeText(requireContext(), "" +
                    "I haven't gotten my current location yet, let me take a couple of steps/wait for the map to load.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // 3) Generate index + timestamp (satisfies "save timestamp")
        int idx = ++testPointIndex;
        long ts = System.currentTimeMillis();

        // 4) Keep a local copy for in-session tracking
        testPoints.add(new TestPoint(idx, ts, cur.latitude, cur.longitude));

        // Write test point into protobuf payload
        sensorFusion.addTestPointToProto(ts, cur.latitude, cur.longitude);

        // 5) Draw numbered marker on map (satisfies "leave numbered marker")
        trajectoryMapFragment.addTestPointMarker(idx, ts, cur);
    }


    private void updateUIandPosition() {
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null) return;

        float dxMeters = pdrValues[0] - previousPosX;
        float dyMeters = pdrValues[1] - previousPosY;

        // Update distance display
        distance += Math.sqrt(Math.pow(dxMeters, 2) + Math.pow(dyMeters, 2));
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));

        // Update elevation display
        float elevationVal = sensorFusion.getElevation();
        float elevDeltaDisplay = elevationVal - lastElevation;
        elevation.setText(String.format("Elev: %.2fm  Δ%.2fm", elevationVal, elevDeltaDisplay));

        // Get current sensor readings
        float[] startArr = sensorFusion.getGNSSLatitude(true);
        float[] gnssArr = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        LatLng gnssPos = (gnssArr != null && (gnssArr[0] != 0f || gnssArr[1] != 0f))
                ? new LatLng(gnssArr[0], gnssArr[1]) : null;
        LatLng wifiPos = sensorFusion.getLatLngWifiPositioning();

        // KNN position estimate (from our own collected fingerprints)
        LatLng knnPos = (knnPositioner != null && knnPositioner.isLoaded())
                ? knnPositioner.estimatePosition() : null;

        // Initialize particle filter with calibration (multi-sample averaging)
        if (!pfInitialized) {
            float gnssAcc = sensorFusion.getGNSSAccuracy();

            if (calibrating) {
                // Collect KNN/WiFi samples during calibration
                LatLng sample = (knnPos != null) ? knnPos : wifiPos;
                if (sample != null && !sample.equals(lastCalibSample())) {
                    calibrationPositions.add(sample);
                    Log.d("PF_Init", "Calibration sample #" + calibrationPositions.size()
                            + " at " + sample.latitude + "," + sample.longitude);
                }

                long elapsed = System.currentTimeMillis() - calibrationStartTime;
                long remaining = Math.max(0, CALIBRATION_TIMEOUT_MS - elapsed);

                if (calibrationCountdown != null) {
                    int secs = (int) Math.ceil(remaining / 1000.0);
                    calibrationCountdown.setText("Calibrating... " + secs + "s\nPlease stand still"
                            + "\nSamples: " + calibrationPositions.size() + "/" + CALIBRATION_SAMPLES);
                }

                // Finish: enough samples or timeout
                if (calibrationPositions.size() >= CALIBRATION_SAMPLES || remaining <= 0) {
                    calibrating = false;
                    if (calibrationCountdown != null) calibrationCountdown.setVisibility(View.GONE);

                    LatLng initPos;
                    String src;
                    if (!calibrationPositions.isEmpty()) {
                        double avgLat = 0, avgLng = 0;
                        for (LatLng p : calibrationPositions) {
                            avgLat += p.latitude; avgLng += p.longitude;
                        }
                        avgLat /= calibrationPositions.size();
                        avgLng /= calibrationPositions.size();
                        initPos = new LatLng(avgLat, avgLng);
                        src = "KNN/WiFi avg(" + calibrationPositions.size() + " samples)";
                    } else if (gnssPos != null) {
                        initPos = gnssPos;
                        src = "GNSS(fallback,acc=" + String.format("%.1f", gnssAcc) + "m)";
                    } else {
                        return;
                    }

                    particleFilter.initialize(initPos);
                    pfInitialized = true;
                    pfInitFromGnss = calibrationPositions.isEmpty();
                    if (trajectoryMapFragment != null) trajectoryMapFragment.enableTrajectory();
                    refreshPFWallData();
                    Log.d("PF_Init", "PF initialized from " + src
                            + " at " + initPos.latitude + "," + initPos.longitude);
                }
            }
        }

        // If PF was seeded from GNSS, re-scatter when better fix arrives
        if (pfInitialized && pfInitFromGnss) {
            LatLng betterPos = (knnPos != null) ? knnPos : wifiPos;
            if (betterPos != null) {
                particleFilter.reinitializeAround(betterPos, 5.0);
                pfInitFromGnss = false;
                Log.d("PF_Init", "Re-initialized around "
                        + (knnPos != null ? "KNN" : "WiFi") + ": "
                        + betterPos.latitude + "," + betterPos.longitude);
            }
        }

        LatLng newLocation;
        if (pfInitialized) {
            // Refresh wall data if indoor floor changed
            refreshPFWallData();

            // Stair/lift detection: check both barometric change AND transition zone
            float elevDelta = Math.abs(elevationVal - lastElevation);
            lastElevation = elevationVal;
            boolean elevChanging = elevDelta > STAIR_ELEV_RATE_THRESHOLD;

            // Check if user is in a stairs or lift zone from the indoor map
            IndoorMapManager mgr = (trajectoryMapFragment != null)
                    ? trajectoryMapFragment.getIndoorMapManager() : null;
            LatLng currentEstimate = particleFilter.getEstimate();
            String zoneType = (mgr != null) ? mgr.getNearestTransitionZoneType(currentEstimate) : null;

            float stepScale = 1.0f;
            if ("lift".equals(zoneType) && elevChanging) {
                // In elevator: no horizontal movement at all
                stepScale = 0.0f;
            } else if ("stairs".equals(zoneType) && elevChanging) {
                // On stairs AND elevation changing: reduce horizontal step
                stepScale = STAIR_STEP_SCALE;
            }

            // Predict step: move particles by PDR displacement (scaled on stairs/lift)
            particleFilter.predict(dxMeters * stepScale, dyMeters * stepScale);

            // GNSS update step — indoor: clamp sigma to at least 15m to reduce pull
            if (gnssPos != null
                    && (gnssPos.latitude != lastGnssLat || gnssPos.longitude != lastGnssLng)) {
                float gnssAcc = sensorFusion.getGNSSAccuracy();
                boolean indoors = (mgr != null && mgr.getIsIndoorMapSet());
                double gnssSigma = indoors ? Math.max(gnssAcc, 15.0) : gnssAcc;
                particleFilter.updateWithGNSS(gnssPos, gnssSigma);
                lastGnssLat = gnssPos.latitude;
                lastGnssLng = gnssPos.longitude;
            }

            // WiFi API update step (only on new measurement)
            if (wifiPos != null && !wifiPos.equals(lastWifiPos)) {
                particleFilter.updateWithWifi(wifiPos);
                lastWifiPos = wifiPos;
            }

            // KNN update step (more accurate than WiFi API, tighter sigma)
            if (knnPos != null && !knnPos.equals(lastKnnPos)) {
                particleFilter.updateWithGNSS(knnPos, KNN_SIGMA_M); // reuse GNSS method with 3m sigma
                lastKnnPos = knnPos;
            }

            // Show WiFi marker on map
            if (wifiPos != null && trajectoryMapFragment != null) {
                trajectoryMapFragment.updateWifiMarker(wifiPos);
            }

            // Stuck detection: if PDR says we're moving but estimate barely changes,
            // particles are likely trapped behind walls. Reinitialize around best fix.
            newLocation = particleFilter.getEstimate();
            float stepDist = (float) Math.sqrt(dxMeters * dxMeters + dyMeters * dyMeters);
            if (stepDist > 0.2f && newLocation != null && currentEstimate != null) {
                double estMove = Math.sqrt(
                        Math.pow((newLocation.latitude - currentEstimate.latitude) * 111320, 2) +
                        Math.pow((newLocation.longitude - currentEstimate.longitude) * 111320
                                * Math.cos(Math.toRadians(newLocation.latitude)), 2));
                if (estMove < 0.1) {
                    stuckCounter++;
                    if (stuckCounter >= STUCK_THRESHOLD) {
                        LatLng rescue = (knnPos != null) ? knnPos : wifiPos;
                        if (rescue != null) {
                            particleFilter.reinitializeAround(rescue, 5.0);
                            Log.w("PF_Stuck", "Particles stuck — reinitialised around "
                                    + (knnPos != null ? "KNN" : "WiFi"));
                            stuckCounter = 0;
                        }
                    }
                } else {
                    stuckCounter = 0;
                }
            }
        } else if (startArr != null && (startArr[0] != 0f || startArr[1] != 0f)) {
            // PF not ready — fall back to plain PDR
            LatLng old = trajectoryMapFragment.getCurrentLocation();
            LatLng anchor = new LatLng(startArr[0], startArr[1]);
            newLocation = UtilFunctions.calculateNewPos(
                    old == null ? anchor : old,
                    new float[]{dxMeters, dyMeters});
        } else {
            newLocation = trajectoryMapFragment.getCurrentLocation();
        }

        if (newLocation != null && trajectoryMapFragment != null) {
            trajectoryMapFragment.updateUserLocation(newLocation,
                    (float) Math.toDegrees(sensorFusion.passOrientation()));
        }

        // Display current positioning source
        if (posSourceText != null) {
            if (!pfInitialized) {
                posSourceText.setText("...");
            } else {
                String src = "PDR";
                if (knnPos != null) src = "KNN";
                else if (wifiPos != null) src = "WiFi";
                if (gnssPos != null) src += "+GPS";
                posSourceText.setText(src);
            }
        }

        // Observation dots: PDR (yellow), WiFi (green), GNSS (blue)
        if (trajectoryMapFragment != null) {
            // PDR dot: raw PDR position = start + accumulated displacement
            if (startArr != null && (startArr[0] != 0f || startArr[1] != 0f)
                    && (dxMeters != 0 || dyMeters != 0)) {
                LatLng pdrPos = UtilFunctions.calculateNewPos(
                        new LatLng(startArr[0], startArr[1]),
                        new float[]{pdrValues[0], pdrValues[1]});
                trajectoryMapFragment.addPdrDot(pdrPos);
            }
            // WiFi dot (compare with separate tracker to avoid ordering bug)
            if (wifiPos != null && !wifiPos.equals(lastObsWifi)) {
                trajectoryMapFragment.addWifiDot(wifiPos);
                lastObsWifi = wifiPos;
            }
            // GNSS dot
            if (gnssPos != null && (gnssPos.latitude != lastObsGnssLat || gnssPos.longitude != lastObsGnssLng)) {
                trajectoryMapFragment.addGnssDot(gnssPos);
                lastObsGnssLat = gnssPos.latitude;
                lastObsGnssLng = gnssPos.longitude;
            }
        }

        // GNSS display and error
        if (gnssArr != null && trajectoryMapFragment != null) {
            if (trajectoryMapFragment.isGnssEnabled()) {
                LatLng gnssLocation = new LatLng(gnssArr[0], gnssArr[1]);
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

    /** Feeds current-floor wall segments into the particle filter if the floor/building changed. */
    private void refreshPFWallData() {
        if (trajectoryMapFragment == null) return;
        IndoorMapManager mgr = trajectoryMapFragment.getIndoorMapManager();
        if (mgr == null || !mgr.getIsIndoorMapSet()) return;

        int floor    = mgr.getCurrentFloor();
        int building = mgr.getCurrentBuilding();
        if (floor == lastPFFloor && building == lastPFBuilding) return;

        List<double[]> walls = mgr.getCurrentFloorWallSegments();
        particleFilter.setWallSegments(walls);
        lastPFFloor    = floor;
        lastPFBuilding = building;
        Log.d("PF_Walls", "Wall segments loaded: " + walls.size()
                + " floor=" + floor + " building=" + building);
    }

    /**
     * Start the blinking effect for the recording icon.
     */
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
        if(!this.settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
    }

    private int testPointIndex = 0;

    private static class TestPoint {
        final int index;
        final long timestampMs;
        final double lat;
        final double lng;

        TestPoint(int index, long timestampMs, double lat, double lng) {
            this.index = index;
            this.timestampMs = timestampMs;
            this.lat = lat;
            this.lng = lng;
        }
    }

    private LatLng lastCalibSample() {
        return calibrationPositions.isEmpty() ? null
                : calibrationPositions.get(calibrationPositions.size() - 1);
    }

    private final List<TestPoint> testPoints = new ArrayList<>();


}
