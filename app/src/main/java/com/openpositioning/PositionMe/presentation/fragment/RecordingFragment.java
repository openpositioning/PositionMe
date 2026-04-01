package com.openpositioning.PositionMe.presentation.fragment;

import static com.openpositioning.PositionMe.BuildConstants.DEBUG;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
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
import com.openpositioning.PositionMe.presentation.view.CompassView;
import com.openpositioning.PositionMe.presentation.view.WeatherView;
import com.openpositioning.PositionMe.presentation.view.WifiSignalView;
import com.openpositioning.PositionMe.data.remote.WeatherApiClient;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.sensors.Wifi;
import com.openpositioning.PositionMe.sensors.fusion.CalibrationManager;
import com.openpositioning.PositionMe.sensors.fusion.CoordinateTransform;
import com.openpositioning.PositionMe.sensors.fusion.FusionManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.model.LatLng;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
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
    public static final String ARG_INDOOR_MODE = "arg_indoor_mode";

    // UI elements
    private MaterialButton completeButton, cancelButton, printLocationButton;
    private ImageView recIcon;
    private ProgressBar timeRemaining;
    private TextView elevation, distanceTravelled, gnssError, wifiError;
    private View uploadButton;
    private View testPointButton;
    private View calibrationTouchBlocker;
    private CompassView compassView;
    private WeatherView weatherView;
    private WifiSignalView wifiSignalView;
    private WeatherApiClient weatherApiClient;
    private boolean weatherFetched = false;
    private boolean weakWifiToastShown = false; // avoid spamming toast
    private boolean calibrationLocked = false;
    private boolean indoorMode = false;
    private CountDownTimer calibrationLockTimer;
    private com.google.android.material.snackbar.Snackbar calibrationSnackbar;

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

    // Barometer calibration logger
    private com.openpositioning.PositionMe.utils.BarometerLogger baroLogger;

    // References to the child map fragment
    private TrajectoryMapFragment trajectoryMapFragment;

    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            updateUIandPosition();
            // Loop again
            refreshDataHandler.postDelayed(refreshDataTask, 200);
        }
    };

    /** Required empty public constructor. */
    public RecordingFragment() {}


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            indoorMode = getArguments().getBoolean(ARG_INDOOR_MODE, false);
        }
        this.sensorFusion = SensorFusion.getInstance();
        Context context = requireActivity();
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.refreshDataHandler = new Handler();

        // Load previously collected calibration data for WiFi-based corrections
        this.calibrationManager = new CalibrationManager();
        this.calibrationManager.loadFromFile(context);
        if (DEBUG) Log.i("RecordingFragment", "Calibration records loaded: "
                + calibrationManager.getRecordCount());

        // Load existing calibration records file so new records are APPENDED, not overwritten.
        // Also detect the most common floor for initial floor estimation.
        // BUG FIX: try primary file first, fall back to .bak if parse fails.
        // Never leave calibrationRecords empty when data exists on disk.
        {
            java.io.File calFile = new java.io.File(context.getExternalFilesDir(null),
                    "calibration_records.json");
            java.io.File bakFile = new java.io.File(context.getExternalFilesDir(null),
                    "calibration_records.json.bak");

            JSONArray loaded = null;
            // Try primary, then backup
            for (java.io.File f : new java.io.File[]{calFile, bakFile}) {
                if (f.exists() && f.length() > 0) {
                    try {
                        java.io.FileInputStream fis = new java.io.FileInputStream(f);
                        byte[] bytes = new byte[(int) f.length()];
                        fis.read(bytes);
                        fis.close();
                        loaded = new JSONArray(new String(bytes));
                        if (DEBUG) Log.i("RecordingFragment", "Loaded " + loaded.length()
                                + " calibration records from " + f.getName());
                        break; // success — stop trying
                    } catch (Exception parseEx) {
                        if (DEBUG) Log.w("RecordingFragment",
                                "Failed to parse " + f.getName() + ", trying next source", parseEx);
                    }
                }
            }

            if (loaded != null && loaded.length() > 0) {
                calibrationRecords = loaded;

                // CalDB floor seeding DISABLED: 2D haversine distance cannot
                // distinguish floors in the same building (all floors share
                // nearly identical lat/lng). Default to floor 0 and let
                // IndoorMapManager.floorBias map it to the correct ground floor.
                // Baro will handle relative floor changes from there.
                if (DEBUG) Log.i("RecordingFragment", String.format(
                        "[FloorInit] CalDB has %d records — floor seeding disabled (default=0, baro-relative)",
                        calibrationRecords.length()));

                if (DEBUG) Log.i("RecordingFragment", "Loaded " + calibrationRecords.length()
                        + " existing calibration records for appending");
            } else {
                if (DEBUG) Log.w("RecordingFragment", "No calibration data found in primary or backup file");
            }
        }
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
            getChildFragmentManager().executePendingTransactions();
        }

        // Wire manual correction: long-press map → drag → confirm → correct PF
        trajectoryMapFragment.setCorrectionCallback(correctedPosition -> {
            FusionManager fusion = sensorFusion.getFusionManager();

            // Capture position B (current estimated position before correction)
            LatLng estimatedPos = (fusion != null && fusion.isActive())
                    ? fusion.getFusedLatLng()
                    : trajectoryMapFragment.getCurrentLocation();

            // Log A (true), B (estimated) for analysis
            if (DEBUG) Log.i("Correction",
                    String.format("A(true)=(%.6f,%.6f) B(est)=(%.6f,%.6f) error=%.1fm",
                            correctedPosition.latitude, correctedPosition.longitude,
                            estimatedPos != null ? estimatedPos.latitude : 0,
                            estimatedPos != null ? estimatedPos.longitude : 0,
                            estimatedPos != null
                                    ? UtilFunctions.distanceBetweenPoints(correctedPosition, estimatedPos)
                                    : -1));

            // Feed position A as strong observation to particle filter
            if (fusion != null) {
                fusion.onManualCorrection(
                        correctedPosition.latitude, correctedPosition.longitude);
            }

            // Store as test point in protobuf (position A = ground truth)
            sensorFusion.addTestPointToProto(
                    System.currentTimeMillis(),
                    correctedPosition.latitude,
                    correctedPosition.longitude);

            // Remember for Upload button
            lastCorrectionPosition = correctedPosition;

            double errorM = estimatedPos != null
                    ? UtilFunctions.distanceBetweenPoints(correctedPosition, estimatedPos)
                    : 0;
            Toast.makeText(requireContext(),
                    String.format("Marked! Tap Upload to save (error: %.1fm)", errorM),
                    Toast.LENGTH_SHORT).show();
        });

        // Initialize UI references
        elevation = view.findViewById(R.id.currentElevation);
        distanceTravelled = view.findViewById(R.id.currentDistanceTraveled);
        gnssError = view.findViewById(R.id.gnssError);
        wifiError = view.findViewById(R.id.wifiError);

        completeButton = view.findViewById(R.id.stopButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        printLocationButton = view.findViewById(R.id.printLocationButton);
        recIcon = view.findViewById(R.id.redDot);
        timeRemaining = view.findViewById(R.id.timeRemainingBar);
        testPointButton = view.findViewById(R.id.btn_test_point);
        uploadButton = view.findViewById(R.id.btn_upload);
        calibrationTouchBlocker = view.findViewById(R.id.calibrationTouchBlocker);
        compassView = view.findViewById(R.id.compassView);
        weatherView = view.findViewById(R.id.weatherView);
        wifiSignalView = view.findViewById(R.id.wifiSignalView);
        weatherApiClient = new WeatherApiClient();
        testPointButton.setOnClickListener(v -> onAddTestPoint());

        // Indoor mode: hide bottom control bar and floating buttons
        if (indoorMode) {
            view.findViewById(R.id.controlLayout).setVisibility(View.GONE);
            if (testPointButton != null) testPointButton.setVisibility(View.GONE);
            if (uploadButton != null) uploadButton.setVisibility(View.GONE);
        }

        // Upload button — captures {A(true), B(estimated), WiFi} as a calibration record
        uploadButton.setOnClickListener(v -> {
            if (lastCorrectionPosition == null) {
                Toast.makeText(requireContext(),
                        "Long-press the map to mark your position first",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            saveCorrectionRecord();
        });


        // Hide or initialize default values
        gnssError.setVisibility(View.GONE);
        wifiError.setVisibility(View.GONE);
        elevation.setText(getString(R.string.elevation, "0"));
        distanceTravelled.setText(getString(R.string.meter, "0"));

        // Buttons
        completeButton.setOnClickListener(v -> {
            // Stop recording & go to correction
            if (autoStop != null) autoStop.cancel();
            if (baroLogger != null) { baroLogger.stop(); baroLogger = null; }

            // Capture trajectory and test points for CorrectionFragment
            sLastTestPoints = new ArrayList<>(testPoints);
            sLastTrajectoryPoints = trajectoryMapFragment != null
                    ? trajectoryMapFragment.getTrajectoryPoints() : new ArrayList<>();
            sLastEndPosition = trajectoryMapFragment != null
                    ? trajectoryMapFragment.getCurrentLocation() : null;
            sEndTimeMs = System.currentTimeMillis();
            sEndBuilding = trajectoryMapFragment != null
                    ? trajectoryMapFragment.getLocationBuilding() : "Outdoor";
            sEndFloor = trajectoryMapFragment != null
                    ? trajectoryMapFragment.getLocationFloor() : "--";

            sensorFusion.stopRecording();
            ((RecordingActivity) requireActivity()).showCorrectionScreen();
        });


        // Cancel button with confirmation dialog
        cancelButton.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                    .setTitle("Confirm Cancel")
                    .setMessage("Are you sure you want to cancel the recording? Your progress will be lost permanently!")
                    .setNegativeButton("Yes", (dialogInterface, which) -> {
                        // User confirmed cancellation
                        if (baroLogger != null) { baroLogger.stop(); baroLogger = null; }
                        sensorFusion.stopRecording();
                        if (autoStop != null) autoStop.cancel();
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

        // Print Location — show building/floor/location info and copy to clipboard
        printLocationButton.setOnClickListener(v -> {
            String info = trajectoryMapFragment != null
                    ? trajectoryMapFragment.getLocationSummary()
                    : "Location unavailable";
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("location", info));
            View anchor = requireView().findViewById(R.id.btn_upload);
            com.google.android.material.snackbar.Snackbar snackbar =
                    com.google.android.material.snackbar.Snackbar.make(
                            requireView(), getString(R.string.location_copied) + "\n" + info,
                            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT);
            snackbar.setAnchorView(anchor);
            TextView snackText = snackbar.getView().findViewById(
                    com.google.android.material.R.id.snackbar_text);
            if (snackText != null) {
                snackText.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            }
            snackbar.show();
        });

        // Start barometer calibration logger
        baroLogger = new com.openpositioning.PositionMe.utils.BarometerLogger(requireContext());
        baroLogger.start();
        if (DEBUG) Log.i("RecordingFragment", "BarometerLogger started → " + baroLogger.getFilePath());

        // Record start time and location info for CorrectionFragment
        sStartTimeMs = System.currentTimeMillis();
        if (trajectoryMapFragment != null) {
            sStartBuilding = trajectoryMapFragment.getLocationBuilding();
            sStartFloor = trajectoryMapFragment.getLocationFloor();
        } else {
            sStartBuilding = "Outdoor";
            sStartFloor = "--";
        }

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
                    if (baroLogger != null) { baroLogger.stop(); baroLogger = null; }
                    sensorFusion.stopRecording();
                    ((RecordingActivity) requireActivity()).showCorrectionScreen();
                }
            }.start();
        } else {
            // No set time limit, just keep refreshing
            refreshDataHandler.post(refreshDataTask);
        }

        if (indoorMode) {
            applyIndoorPositioningMode();
        } else {
            startInitialCalibrationLock();
        }
    }

    private void applyIndoorPositioningMode() {
        if (trajectoryMapFragment != null) {
            float[] startLocation = sensorFusion.getGNSSLatitude(true);
            trajectoryMapFragment.setInitialCameraPosition(new LatLng(
                    startLocation[0],
                    startLocation[1]
            ));
            trajectoryMapFragment.applyIndoorPositioningModeDefaults();
        }
        // 10-second calibration lock with position averaging (same as normal)
        setCalibrationLocked(true);
        showCalibrationSnackbar(10);

        sensorFusion.setPdrPaused(true);

        FusionManager fm = sensorFusion.getFusionManager();
        if (fm != null) {
            fm.startCalibrationMode();
        }

        calibrationLockTimer = new CountDownTimer(10_000, 1_000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsRemaining = (int) Math.ceil(millisUntilFinished / 1000.0);
                showCalibrationSnackbar(secondsRemaining);
            }

            @Override
            public void onFinish() {
                // Finalize heading from 10s of samples

                FusionManager fm = sensorFusion.getFusionManager();
                if (fm != null) {
                    float[] fallback = sensorFusion.getGNSSLatitude(true);
                    LatLng calibratedPos = fm.finishCalibrationMode(fallback[0], fallback[1]);
                    if (trajectoryMapFragment != null && calibratedPos != null) {
                        trajectoryMapFragment.setInitialCameraPosition(calibratedPos);
                    }
                    if (DEBUG) Log.i("RecordingFragment", "[CalibrationMode-Indoor] Final position: " + calibratedPos);
                }
                sensorFusion.setPdrPaused(false);

                setCalibrationLocked(false);
                dismissCalibrationSnackbar();
            }
        }.start();
    }

    private void onAddTestPoint() {
        // 1) Ensure the map fragment is ready
        if (trajectoryMapFragment == null) return;

        // 2) Read current position — prefer fused (triangle marker), fall back to PDR
        FusionManager fm = sensorFusion.getFusionManager();
        LatLng cur = (fm != null && fm.isActive()) ? fm.getFusedLatLng() : null;
        if (cur == null) cur = trajectoryMapFragment.getCurrentLocation();
        if (cur == null) {
            Toast.makeText(requireContext(),
                    "I haven't gotten my current location yet, let me take a couple of steps/wait for the map to load.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // 3) Generate index + timestamp (satisfies "save timestamp")
        int idx = ++testPointIndex;
        long ts = System.currentTimeMillis();

        // 4) Keep a local copy for in-session tracking
        String building = trajectoryMapFragment.getLocationBuilding();
        String floor = trajectoryMapFragment.getLocationFloor();
        testPoints.add(new TestPoint(idx, ts, cur.latitude, cur.longitude, building, floor));

        // Write test point into protobuf payload
        sensorFusion.addTestPointToProto(ts, cur.latitude, cur.longitude);

        // 5) Draw numbered marker on map (satisfies "leave numbered marker")
        trajectoryMapFragment.addTestPointMarker(idx, ts, cur);
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

        // Compass heading
        if (compassView != null) {
            compassView.setHeading((float) Math.toDegrees(sensorFusion.passOrientation()));
        }

        // ---- PDR position in unified ENU coordinates ----
        // PDR.positionX = easting (m), PDR.positionY = northing (m), origin = start location
        // Convert ENU → LatLng via CoordinateTransform
        FusionManager fm = sensorFusion.getFusionManager();
        if (fm != null && trajectoryMapFragment != null) {
            CoordinateTransform ct = fm.getCoordinateTransform();
            if (ct.isInitialized()) {
                // PDR position directly in ENU (posX=easting, posY=northing)
                double pdrEasting = pdrValues[0];
                double pdrNorthing = pdrValues[1];

                // [RoundTrip] verify ENU→LatLng is stable (log every 10th update)
                if (((int)(distance * 10)) % 50 == 0 && pdrEasting != 0) {
                    double[] rtEnu = ct.toEastNorth(ct.toLatLng(pdrEasting, pdrNorthing)[0],
                            ct.toLatLng(pdrEasting, pdrNorthing)[1]);
                    double rtErr = Math.hypot(rtEnu[0] - pdrEasting, rtEnu[1] - pdrNorthing);
                    if (DEBUG) Log.i("Step1Debug", String.format("[RoundTrip] pdrENU=(%.3f,%.3f) rt=(%.3f,%.3f) err=%.6fm",
                            pdrEasting, pdrNorthing, rtEnu[0], rtEnu[1], rtErr));
                }

                // PDR display: no wall constraints (following Meld convention).
                // Wall influence is handled inside the particle filter only.
                double[] pdrLatLng = ct.toLatLng(pdrEasting, pdrNorthing);
                LatLng newLocation = new LatLng(pdrLatLng[0], pdrLatLng[1]);

                trajectoryMapFragment.updateUserLocation(newLocation,
                        (float) Math.toDegrees(sensorFusion.passOrientation()));
            } else {
                // CoordinateTransform not yet initialized — fallback to start location
                float[] latLngArray = sensorFusion.getGNSSLatitude(true);
                if (latLngArray != null && (latLngArray[0] != 0 || latLngArray[1] != 0)) {
                    LatLng oldLocation = trajectoryMapFragment.getCurrentLocation();
                    LatLng newLocation = UtilFunctions.calculateNewPos(
                            oldLocation == null ? new LatLng(latLngArray[0], latLngArray[1]) : oldLocation,
                            new float[]{pdrValues[0] - previousPosX, pdrValues[1] - previousPosY}
                    );
                    trajectoryMapFragment.updateUserLocation(newLocation,
                            (float) Math.toDegrees(sensorFusion.passOrientation()));
                }
            }
        }

        // GNSS logic — show marker + error distance (tied to Show GNSS switch)
        float[] gnss = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        if (gnss != null && trajectoryMapFragment != null) {
            if (trajectoryMapFragment.isGnssEnabled()) {
                LatLng gnssLocation = new LatLng(gnss[0], gnss[1]);
                LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();
                if (currentLoc != null) {
                    double errorDist = UtilFunctions.distanceBetweenPoints(currentLoc, gnssLocation);
                    gnssError.setVisibility(View.VISIBLE);
                    gnssError.setText(String.format(getString(R.string.gnss_error) + " %.1fm", errorDist));
                }
                trajectoryMapFragment.updateGNSS(gnssLocation);
            } else {
                gnssError.setVisibility(View.GONE);
                trajectoryMapFragment.clearGNSS();
            }
        }

        // ---- Fused position from particle filter ---
        FusionManager fusion = sensorFusion.getFusionManager();

        if (fusion != null && trajectoryMapFragment != null) {
            boolean active = fusion.isActive();
            trajectoryMapFragment.setFusionActive(active);

            if (active) {
                LatLng fusedPos = fusion.getFusedLatLng();
                if (fusedPos != null) {
                    trajectoryMapFragment.updateFusedLocation(
                            fusedPos,
                            fusion.getFusedUncertainty(),
                            (float) Math.toDegrees(sensorFusion.passOrientation()));
                }
            }

            // Show latest WiFi fix as a green marker + WiFi error (tied to Show WiFi switch)
            LatLng wifiPos = sensorFusion.getLatLngWifiPositioning();
            if (wifiPos != null) {
                trajectoryMapFragment.updateWifiMarker(wifiPos);
                if (trajectoryMapFragment.isWifiEnabled()) {
                    LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();
                    if (currentLoc != null) {
                        double wifiDist = UtilFunctions.distanceBetweenPoints(currentLoc, wifiPos);
                        wifiError.setVisibility(View.VISIBLE);
                        wifiError.setText(String.format(getString(R.string.wifi_error) + " %.1fm", wifiDist));
                    }
                } else {
                    wifiError.setVisibility(View.GONE);
                }
            } else {
                wifiError.setVisibility(View.GONE);
            }

            // Calibration-based correction: match current WiFi to stored records
            // Also runs during calibration mode to feed position averaging
            long now = System.currentTimeMillis();
            boolean calDbAllowed = fusion.isActive() || fusion.isInCalibrationMode();
            if (fusion.isInCalibrationMode()) {
                if (DEBUG) Log.d("RecordingFragment", String.format(
                        "[CalDB-Check] calMode=true records=%d timeSinceLastCheck=%dms calDbAllowed=%b",
                        calibrationManager.getRecordCount(),
                        now - lastCalibrationCheckMs, calDbAllowed));
            }
            if (calibrationManager.getRecordCount() > 0
                    && now - lastCalibrationCheckMs > (fusion.isInCalibrationMode() ? 2000 : CALIBRATION_CHECK_INTERVAL_MS)
                    && calDbAllowed) {
                lastCalibrationCheckMs = now;
                List<Wifi> currentWifi = sensorFusion.getWifiList();
                int calFloor = fusion.isActive() ? fusion.getFusedFloor() : 0;

                if (fusion.isInCalibrationMode()) {
                    // During calibration: use unfiltered top-K weighted position
                    LatLng calPos = calibrationManager.findCalibrationPosition(currentWifi, calFloor);
                    if (calPos != null) {
                        fusion.addCalibrationFix(calPos.latitude, calPos.longitude,
                                3.0, "CAL_DB");
                    }
                } else {
                    // Normal mode: use quality-filtered match for fusion
                    CalibrationManager.MatchResult match =
                            calibrationManager.findBestMatch(currentWifi, calFloor);
                    if (match != null) {
                        CoordinateTransform ct = fusion.getCoordinateTransform();
                        if (ct.isInitialized()) {
                            double[] en = ct.toEastNorth(
                                    match.truePosition.latitude, match.truePosition.longitude);
                            fusion.onCalibrationObservation(
                                    en[0], en[1], match.uncertainty,
                                    fusion.getFusedFloor(), match.quality);
                        }
                        updateWifiSignal(match);
                    } else {
                        if (wifiSignalView != null) {
                            wifiSignalView.setLevel(currentWifi != null && !currentWifi.isEmpty() ? 1 : 0);
                        }
                    }
                    // DEBUG: show forward 120° 20m ref point weighted average
                    if (trajectoryMapFragment != null && trajectoryMapFragment.isEstimatedEnabled()
                            && fusion.isActive() && fusion.getCoordinateTransform().isInitialized()) {
                        double myX = fusion.getParticleFilter().getEstimatedX();
                        double myY = fusion.getParticleFilter().getEstimatedY();
                        double heading = sensorFusion.passOrientation();
                        double halfFov = Math.toRadians(60); // ±60° = 120°
                        double maxDist = 20.0;
                        List<LatLng> refs = calibrationManager.getRecordPositions(fusion.getFusedFloor());
                        if (refs != null && !refs.isEmpty()) {
                            CoordinateTransform ct = fusion.getCoordinateTransform();
                            double sumW = 0, wLat = 0, wLng = 0;
                            int count = 0;
                            for (LatLng ref : refs) {
                                double[] en = ct.toEastNorth(ref.latitude, ref.longitude);
                                double dx = en[0] - myX;
                                double dy = en[1] - myY;
                                double dist = Math.hypot(dx, dy);
                                if (dist > maxDist || dist < 0.5) continue;
                                double dir = Math.atan2(dx, dy);
                                double diff = dir - heading;
                                while (diff > Math.PI) diff -= 2 * Math.PI;
                                while (diff < -Math.PI) diff += 2 * Math.PI;
                                if (Math.abs(diff) > halfFov) continue;
                                double w = 1.0 / Math.max(dist, 0.5);
                                wLat += ref.latitude * w;
                                wLng += ref.longitude * w;
                                sumW += w;
                                count++;
                            }
                            if (sumW > 0) {
                                LatLng estPos = new LatLng(wLat / sumW, wLng / sumW);
                                trajectoryMapFragment.updateEstimatedMarker(estPos);
                                if (DEBUG) Log.d("DEBUG_EST", String.format("forward120° pts=%d pos=(%.6f,%.6f)",
                                        count, estPos.latitude, estPos.longitude));
                            }
                        }
                    }
                }
            }

        }

        // Update previous
        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];
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

    private void startInitialCalibrationLock() {
        if (trajectoryMapFragment != null) {
            trajectoryMapFragment.enterInitialCalibrationMode();
        }
        setCalibrationLocked(true);
        showCalibrationSnackbar(10);

        // Pause PDR and enter calibration averaging mode + extended heading
        sensorFusion.setPdrPaused(true);

        FusionManager fm = sensorFusion.getFusionManager();
        if (fm != null) {
            fm.startCalibrationMode();
        }

        calibrationLockTimer = new CountDownTimer(10_000, 1_000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsRemaining = (int) Math.ceil(millisUntilFinished / 1000.0);
                showCalibrationSnackbar(secondsRemaining);
            }

            @Override
            public void onFinish() {
                // Finalize heading from 10s of samples

                // Finalize calibrated position and resume PDR
                FusionManager fm = sensorFusion.getFusionManager();
                if (fm != null) {
                    float[] fallback = sensorFusion.getGNSSLatitude(true);
                    LatLng calibratedPos = fm.finishCalibrationMode(fallback[0], fallback[1]);
                    if (trajectoryMapFragment != null && calibratedPos != null) {
                        trajectoryMapFragment.setInitialCameraPosition(calibratedPos);
                    }
                    if (DEBUG) Log.i("RecordingFragment", "[CalibrationMode] Final position: " + calibratedPos);
                }
                sensorFusion.setPdrPaused(false);

                setCalibrationLocked(false);
                dismissCalibrationSnackbar();
            }
        }.start();
    }

    private void setCalibrationLocked(boolean locked) {
        calibrationLocked = locked;

        if (calibrationTouchBlocker != null) {
            calibrationTouchBlocker.setVisibility(View.GONE);
        }
        if (completeButton != null) completeButton.setEnabled(!locked);
        if (printLocationButton != null) printLocationButton.setEnabled(!locked);
        if (uploadButton != null) uploadButton.setEnabled(!locked);
        if (testPointButton != null) testPointButton.setEnabled(!locked);
        if (cancelButton != null) cancelButton.setEnabled(true);

        if (trajectoryMapFragment != null) {
            trajectoryMapFragment.setInteractionLocked(locked);
        }
    }

    private void showCalibrationSnackbar(int secondsRemaining) {
        if (!isAdded() || getView() == null) return;

        String message = indoorMode
                ? getString(R.string.calibration_countdown_indoor, secondsRemaining)
                : getString(R.string.calibration_countdown, secondsRemaining);
        if (calibrationSnackbar == null) {
            calibrationSnackbar = com.google.android.material.snackbar.Snackbar.make(
                    requireView(),
                    message,
                    com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
            );
            if (uploadButton != null && uploadButton.getVisibility() == View.VISIBLE) {
                calibrationSnackbar.setAnchorView(uploadButton);
            }
        } else {
            calibrationSnackbar.setText(message);
        }

        TextView snackText = calibrationSnackbar.getView().findViewById(
                com.google.android.material.R.id.snackbar_text);
        if (snackText != null) {
            snackText.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        }

        calibrationSnackbar.show();
    }

    private void dismissCalibrationSnackbar() {
        if (calibrationSnackbar != null) {
            calibrationSnackbar.dismiss();
            calibrationSnackbar = null;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        refreshDataHandler.removeCallbacks(refreshDataTask);
        if (calibrationLockTimer != null) {
            calibrationLockTimer.cancel();
            calibrationLockTimer = null;
        }
        setCalibrationLocked(false);
        dismissCalibrationSnackbar();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (refreshDataHandler != null) {
            refreshDataHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if(!this.settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
    }

    // Last confirmed correction position (A = true position)
    private LatLng lastCorrectionPosition = null;

    // Calibration records file
    private JSONArray calibrationRecords = new JSONArray();

    // WiFi fingerprint matching for position correction
    private CalibrationManager calibrationManager;
    private long lastCalibrationCheckMs = 0;
    private static final long CALIBRATION_CHECK_INTERVAL_MS = 5000; // check every 5s

    /**
     * Maps a CalibrationManager.MatchResult to the 3-bar WiFi signal indicator
     * and shows a toast when quality is poor.
     *
     * Level mapping:
     *   3 (green)  = GOOD with commonApCount >= 5
     *   2 (amber)  = GOOD with commonApCount < 5, or AMBIGUOUS
     *   1 (orange) = WEAK / other
     */
    private void updateWifiSignal(CalibrationManager.MatchResult match) {
        if (wifiSignalView == null) return;

        int level;
        switch (match.quality) {
            case "GOOD":
                level = (match.commonApCount >= 5) ? 3 : 2;
                weakWifiToastShown = false; // reset once quality recovers
                break;
            case "AMBIGUOUS":
                level = 2;
                break;
            default: // "WEAK" or anything else
                level = 1;
                break;
        }
        wifiSignalView.setLevel(level);

        // Show toast once when signal drops to weak
        if (level <= 1 && !weakWifiToastShown && isAdded()) {
            weakWifiToastShown = true;
            Toast.makeText(requireContext(),
                    "WiFi signal quality is poor (matched APs: "
                            + match.commonApCount + ")",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Captures a calibration record: position A (true), B (estimated), and latest WiFi scan.
     * Saves to a local JSON file for later analysis or upload.
     */
    private void saveCorrectionRecord() {
        try {
            FusionManager fusion = sensorFusion.getFusionManager();

            // A = true position (user-marked)
            double aLat = lastCorrectionPosition.latitude;
            double aLng = lastCorrectionPosition.longitude;

            // B = current estimated position
            LatLng estPos = (fusion != null && fusion.isActive())
                    ? fusion.getFusedLatLng()
                    : trajectoryMapFragment.getCurrentLocation();
            double bLat = estPos != null ? estPos.latitude : 0;
            double bLng = estPos != null ? estPos.longitude : 0;

            // WiFi fingerprint at this moment
            List<Wifi> wifiList = sensorFusion.getWifiList();
            JSONArray wifiArray = new JSONArray();
            if (wifiList != null) {
                for (Wifi w : wifiList) {
                    JSONObject ap = new JSONObject();
                    ap.put("bssid", w.getBssidString());
                    ap.put("rssi", w.getLevel());
                    ap.put("ssid", w.getSsid());
                    wifiArray.put(ap);
                }
            }

            // Build record
            JSONObject record = new JSONObject();
            record.put("timestamp", System.currentTimeMillis());
            record.put("true_lat", aLat);
            record.put("true_lng", aLng);
            record.put("estimated_lat", bLat);
            record.put("estimated_lng", bLng);
            record.put("error_m", estPos != null
                    ? UtilFunctions.distanceBetweenPoints(lastCorrectionPosition, estPos) : -1);
            // Floor priority: manual map selection > WiFi > fused > 0
            int floor = 0;
            // Try getting the floor the user is currently viewing on the indoor map
            if (trajectoryMapFragment != null) {
                try {
                    // Use reflection-free approach: the fused floor should match what's displayed
                    FusionManager fm2 = sensorFusion.getFusionManager();
                    if (fm2 != null) floor = fm2.getFusedFloor();
                } catch (Exception ignored) {}
            }
            if (floor == 0) {
                int wf = sensorFusion.getWifiFloor();
                if (wf >= 0) floor = wf;
            }
            if (floor == 0 && fusion != null) floor = fusion.getFusedFloor();
            record.put("floor", floor);
            record.put("wifi", wifiArray);

            calibrationRecords.put(record);

            // ---- Safe write with backup ----
            File dir = requireContext().getExternalFilesDir(null);
            File file = new File(dir, "calibration_records.json");
            File backup = new File(dir, "calibration_records.json.bak");
            File temp = new File(dir, "calibration_records.json.tmp");

            // Safety guard: refuse to overwrite if in-memory has fewer records than file on disk.
            // This prevents the bug where a failed load leaves calibrationRecords empty and
            // a subsequent save wipes all stored data.
            if (file.exists() && file.length() > 0) {
                try {
                    java.io.FileInputStream checkFis = new java.io.FileInputStream(file);
                    byte[] checkBytes = new byte[(int) file.length()];
                    checkFis.read(checkBytes);
                    checkFis.close();
                    JSONArray existingOnDisk = new JSONArray(new String(checkBytes));
                    if (calibrationRecords.length() < existingOnDisk.length()) {
                        // In-memory data lost some records — reload from disk first, then append new record
                        Log.e("Calibration", "SAFETY: in-memory (" + calibrationRecords.length()
                                + ") < on-disk (" + existingOnDisk.length()
                                + "). Reloading from disk to prevent data loss.");
                        existingOnDisk.put(record);
                        // Remove the record we already added to the stale in-memory array
                        calibrationRecords = existingOnDisk;
                        // Remove duplicate: we already put(record) above, undo the first put
                        // Actually calibrationRecords now IS existingOnDisk which already has the record
                        // The earlier put on the old calibrationRecords is discarded since we reassigned
                    }
                } catch (Exception checkEx) {
                    if (DEBUG) Log.w("Calibration", "Could not verify on-disk count, proceeding", checkEx);
                }

                // Backup current file before overwriting
                copyFile(file, backup);
            }

            // Write to temp file first, then rename (pseudo-atomic)
            FileWriter writer = new FileWriter(temp, false);
            writer.write(calibrationRecords.toString(2));
            writer.flush();
            writer.close();

            // Verify temp file is valid before replacing
            if (temp.exists() && temp.length() > 10) {
                if (file.exists()) file.delete();
                if (!temp.renameTo(file)) {
                    // renameTo can fail on some Android storage — fallback to copy
                    copyFile(temp, file);
                    temp.delete();
                }
            } else {
                Log.e("Calibration", "Temp file empty or missing, NOT replacing main file!");
            }

            // Clear last correction so user must mark again before next upload
            lastCorrectionPosition = null;

            Toast.makeText(requireContext(),
                    String.format("Saved! (%d records total) → %s",
                            calibrationRecords.length(), file.getName()),
                    Toast.LENGTH_LONG).show();

            if (DEBUG) Log.i("Calibration", "Record saved: " + record.toString());

        } catch (Exception e) {
            Log.e("Calibration", "Failed to save record", e);
            Toast.makeText(requireContext(), "Save failed: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private int testPointIndex = 0;

    static class TestPoint implements java.io.Serializable {
        final int index;
        final long timestampMs;
        final double lat;
        final double lng;
        final String building;
        final String floor;

        TestPoint(int index, long timestampMs, double lat, double lng,
                  String building, String floor) {
            this.index = index;
            this.timestampMs = timestampMs;
            this.lat = lat;
            this.lng = lng;
            this.building = building;
            this.floor = floor;
        }
    }

    private final List<TestPoint> testPoints = new ArrayList<>();

    // Static data passed to CorrectionFragment
    static List<TestPoint> sLastTestPoints;
    static List<LatLng> sLastTrajectoryPoints;
    static LatLng sLastEndPosition;
    static long sStartTimeMs;
    static long sEndTimeMs;
    static String sStartBuilding;
    static String sStartFloor;
    static String sEndBuilding;
    static String sEndFloor;

    /** Clears all static data shared with CorrectionFragment to prevent memory leaks. */
    public static void clearStaticData() {
        sLastTestPoints = null;
        sLastTrajectoryPoints = null;
        sLastEndPosition = null;
        sStartTimeMs = 0;
        sEndTimeMs = 0;
        sStartBuilding = null;
        sStartFloor = null;
        sEndBuilding = null;
        sEndFloor = null;
    }

    /**
     * Toggles weather view visibility and fetches weather data on first show.
     *
     * @param visible true to show the weather view
     */
    public void setWeatherVisible(boolean visible) {
        if (weatherView == null) return;
        weatherView.setVisibility(visible ? View.VISIBLE : View.GONE);

        if (visible && !weatherFetched) {
            weatherFetched = true;
            // Use GNSS coordinates for weather location
            float[] gnss = sensorFusion.getGNSSLatitude(true);
            if (gnss != null && (gnss[0] != 0 || gnss[1] != 0)) {
                weatherApiClient.fetchWeather(gnss[0], gnss[1],
                        new WeatherApiClient.WeatherCallback() {
                            @Override
                            public void onWeatherResult(int wmoCode, double temperatureC) {
                                if (weatherView != null) {
                                    weatherView.setWeather(wmoCode, temperatureC);
                                }
                            }
                            @Override
                            public void onError(String message) {
                                if (DEBUG) Log.w("RecordingFragment", "Weather fetch failed: " + message);
                            }
                        });
            }
        }
    }

    /**
     * Copy a file byte-for-byte. Used for backup before overwrite.
     */
    private static void copyFile(File src, File dst) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            in.close();
            out.flush();
            out.close();
        } catch (Exception e) {
            if (DEBUG) Log.w("Calibration", "copyFile failed: " + src.getName() + " → " + dst.getName(), e);
        }
    }
}
