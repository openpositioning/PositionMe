package com.openpositioning.PositionMe.presentation.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
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
import android.widget.Toast;
import com.google.android.material.button.MaterialButton;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.Traj;
import com.google.protobuf.util.JsonFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.util.Log;

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
    private MaterialButton completeButton, cancelButton, testPointButton;
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

    //Marker
    private LatLng lastMarkerLocation = null;
    private int markerIndex = 0;

    // Treat “same location” within ~1 meter as no movement
    private static final double MARKER_MIN_MOVE_METERS = 1.0;

    private static final double MIN_MARKER_DISTANCE_M = 1.5; // tune (1.0–3.0)
    @Nullable private LatLng lastMarkerLatLng = null;
    private boolean hasMovedEnoughForNewMarker(@NonNull LatLng current) {
        if (lastMarkerLatLng == null) return true;
        double d = UtilFunctions.distanceBetweenPoints(lastMarkerLatLng, current);
        return d >= MIN_MARKER_DISTANCE_M;
    }

    // Map fragment
    private TrajectoryMapFragment trajectoryMapFragment;
    //Ensure only move the camera once to a sensible start position.
    private boolean initialCameraSet = false;
    //Periodic update loop
    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            updateUIandPosition();
            refreshDataHandler.postDelayed(this, 200);
        }
    };

    public RecordingFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //SensorFusion singleton is shared across the app
        sensorFusion = SensorFusion.getInstance();
        Context context = requireActivity();
        settings = PreferenceManager.getDefaultSharedPreferences(context);
        //Always use the main looper so UI updates never happen on a background
        refreshDataHandler = new Handler(Looper.getMainLooper());
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

        // Attach/find map fragment inside container
        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMapFragmentContainer);

        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            // commitNow() ensures fragment is attached immediately
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.trajectoryMapFragmentContainer, trajectoryMapFragment)
                    .commitNow();
        }

        // Initialize UI references
        elevation = view.findViewById(R.id.currentElevation);
        distanceTravelled = view.findViewById(R.id.currentDistanceTraveled);
        gnssError = view.findViewById(R.id.gnssError);

        completeButton = view.findViewById(R.id.stopButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        testPointButton = view.findViewById(R.id.testPointButton);

        recIcon = view.findViewById(R.id.redDot);
        timeRemaining = view.findViewById(R.id.timeRemainingBar);

        // Hide or initialize default values
        gnssError.setVisibility(View.GONE);
        elevation.setText(getString(R.string.elevation, "0"));
        distanceTravelled.setText(getString(R.string.meter, "0"));

        // Buttons
        completeButton.setOnClickListener(v -> {
                    // Stop recording & go to correction
                    if (autoStop != null) autoStop.cancel();
                    refreshDataHandler.removeCallbacks(refreshDataTask);
                    sensorFusion.stopRecording();
                    new AlertDialog.Builder(requireActivity())
                            .setTitle("Save trajectory?")
                            .setMessage("Do you want to save trajectory into JSON locally?")
                            .setPositiveButton("Save", (dialog, which) ->
                            {
                                Traj.Trajectory trajectory = sensorFusion.getBuiltTrajectory();
                                if (trajectory != null) {
                                    // save trajectory into a JSON file locally
                                    saveTrajectoryLocally(trajectory);
                                }
                                // Show Correction screen
                                ((RecordingActivity) requireActivity()).showCorrectionScreen();
                            })
                            .setNegativeButton("Don't save", (dialog, which) -> {
                                // Show Correction screen
                                ((RecordingActivity) requireActivity()).showCorrectionScreen();
                            }).setCancelable(false)
                            .show();
                }
        );

        // Cancel button with confirmation dialog
        cancelButton.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                    .setTitle("Confirm Cancel")
                    .setMessage("Are you sure you want to cancel the recording? Your progress will be lost permanently!")
                    .setNegativeButton("Yes", (dialogInterface, which) -> {
                        sensorFusion.stopRecording();
                        refreshDataHandler.removeCallbacks(refreshDataTask);
                        if (autoStop != null) autoStop.cancel();
                        requireActivity().onBackPressed();
                    })
                    .setPositiveButton("No", (dialogInterface, which) -> dialogInterface.dismiss())
                    .create();

            dialog.setOnShowListener(dialogInterface -> {
                Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negativeButton.setTextColor(Color.RED);
            });

            dialog.show();
        });

        // Marker / test-point button
        testPointButton.setOnClickListener(v -> {
            if (trajectoryMapFragment == null || !trajectoryMapFragment.isMapReady()) return;

            LatLng current = trajectoryMapFragment.getCurrentLocation();
            if (current == null) {
                // No current position on map yet
                return;
            }

            // Do NOT create a new marker / protobuf test point if the user hasn't moved enough
            if (!hasMovedEnoughForNewMarker(current)) {
                // Optional: show message, but avoid spamming
                // toast("Move a bit more before adding the next marker.");
                return;
            }

            // Save into protobuf + get the new marker index
            // (Altitude: you can pass 0 if you don’t have it here)
            int idx = sensorFusion.recordGnssTestPointAt(current.latitude, current.longitude, 0.0);
            if (idx < 0) return;

            // Draw numbered marker on map
            trajectoryMapFragment.addTestPointMarker(current, idx);

            // Update last marker location AFTER successful add
            lastMarkerLatLng = current;
        });

        // The blinking effect for recIcon
        blinkingRecordingIcon();

        // Start the timed or indefinite UI refresh
        if (settings.getBoolean("split_trajectory", false)) {
            long limit = settings.getInt("split_duration", 30) * 60000L;

            timeRemaining.setMax((int) (limit / 1000));
            timeRemaining.setProgress(0);

            autoStop = new CountDownTimer(limit, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    timeRemaining.incrementProgressBy(1);
                    updateUIandPosition();
                }

                @Override
                public void onFinish() {
                    sensorFusion.stopRecording();
                    refreshDataHandler.removeCallbacks(refreshDataTask);
                    ((RecordingActivity) requireActivity()).showCorrectionScreen();
                }
            }.start();
        } else {
            refreshDataHandler.post(refreshDataTask);
        }
    }
    private void saveTrajectoryLocally(Traj.Trajectory trajectory) {
        // Decide where to store the file
        File directory;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // App-specific external Documents directory (no permission needed)
            directory = requireActivity()
                    .getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);

            // Fallback to internal storage if external is unavailable
            if (directory == null) {
                directory = requireActivity().getFilesDir();
            }
        } else {
            // Older Android versions: internal storage
            directory = requireActivity().getFilesDir();
        }

        // Create a unique filename using timestamp
        String timestamp = new SimpleDateFormat(
                "dd-MM-yy-HH-mm-ss",
                Locale.US
        ).format(new Date());

        File file = new File(directory, "trajectory_" + timestamp + ".json");

        try {
            // Convert protobuf object to JSON
            String json = JsonFormat.printer()
                    .includingDefaultValueFields()
                    .print(trajectory);

            // Write JSON to file using UTF-8
            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(file),
                    StandardCharsets.UTF_8
            )) {
                writer.write(json);
            }

            // Log success
            Log.d("SaveTag", "Trajectory saved to: " + file.getAbsolutePath());
            Toast.makeText(this.getContext(), "Saved to " + file, Toast.LENGTH_SHORT).show(); // show error message to users

        } catch (IOException e) {
            // Log error with stack trace
            Log.e("SaveTag", "Failed to save trajectory", e);
        }
    }

    /**
     * Pulls latest sensor values from SensorFusion and updates:
     * - distance & elevation text
     * - map orientation marker + trajectory polyline (PDR-derived)
     * - optional GNSS marker + GNSS error display
     *
     * NOTE: The map must be ready before calling map functions.
     */
    private void updateUIandPosition() {
        if (trajectoryMapFragment == null) return;
        // Prevent calling map methods before GoogleMap is initialized.
        if (!trajectoryMapFragment.isMapReady()) return;

        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null) return;

        // Prevent calling map methods before GoogleMap is initialized.
        float dx = pdrValues[0] - previousPosX;
        float dy = pdrValues[1] - previousPosY;

        // Distance accumulation
        distance += (float) Math.sqrt(dx * dx + dy * dy);
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));

        // Elevation from barometer
        float elevationVal = sensorFusion.getElevation();
        elevation.setText(getString(R.string.elevation, String.format("%.1f", elevationVal)));

        // Start / anchor location:
        float[] startLatLng = sensorFusion.getGNSSLatitude(true);
        if (startLatLng == null || (startLatLng[0] == 0f && startLatLng[1] == 0f)) {
            startLatLng = sensorFusion.getGNSSLatitude(false);
        }
        if (startLatLng != null) {
            Log.d("POS TAG" , "Pos is " + startLatLng);
        }
        else{
            Log.d("POS TAG" , "isnull");
        }

        //Set initial camera once when a valid start is available
        if (startLatLng != null) {
            LatLng start = new LatLng(startLatLng[0], startLatLng[1]);

            if (!initialCameraSet) {
                trajectoryMapFragment.setInitialCameraPosition(start);
                initialCameraSet = true;
            }

            //Base location for incremental movement
            LatLng oldLocation = trajectoryMapFragment.getCurrentLocation();
            LatLng base = (oldLocation == null) ? start : oldLocation;

            //Convert PDR into a new LatLng step
            LatLng newLocation = UtilFunctions.calculateNewPos(base, new float[]{dx, dy});

            //Update orientation marker + extend trajectory polyline
            trajectoryMapFragment.updateUserLocation(
                    newLocation,
                    (float) Math.toDegrees(sensorFusion.passOrientation())
            );
        }

        // GNSS marker + GNSS error
        float[] gnss = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        if (gnss != null) {
            if (trajectoryMapFragment.isGnssEnabled()) {
                LatLng gnssLocation = new LatLng(gnss[0], gnss[1]);
                LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();

                //Display error distance between GNSS and fused trajectory
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

        // Store previous for next delta computation
        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];
    }

    /**
     * Visual recording indicator: makes the red dot fade in/out repeatedly.
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
        //Avoid Leaking callbacks when fragment is not visible
        refreshDataHandler.removeCallbacks(refreshDataTask);
    }

    @Override
    public void onResume() {
        super.onResume();
        //Restart refresh Loop when returning
        if(!this.settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
    }
}