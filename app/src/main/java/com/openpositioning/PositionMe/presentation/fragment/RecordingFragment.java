package com.openpositioning.PositionMe.presentation.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

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
import com.openpositioning.PositionMe.sensors.ParticleAttribute;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import androidx.lifecycle.ViewModelProvider;
import com.openpositioning.PositionMe.presentation.viewitems.SensorDataViewModel;


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
 * @author Sofea Jazlan Arif
 * @author Stone Anderson
 */

public class RecordingFragment extends Fragment {

    // UI elements
    private MaterialButton completeButton, cancelButton, addTagButton;
    private ImageView recIcon;
    private ProgressBar timeRemaining;
    private TextView timer, elevation, distanceTravelled, gnssError, wifiError;
    private TextView ekfAccuracyText, particleAccuracyText;

    // App settings
    private SharedPreferences settings;

    // Sensor & data logic
    private SensorFusion sensorFusion;
    private Handler refreshDataHandler;
    private CountDownTimer autoStop;
    // Timer starts at 0
    private int seconds = 0;

    // Distance tracking
    private float distance = 0f;
    private float previousPosX = 0f;
    private float previousPosY = 0f;

    // References to the child map fragment
    private TrajectoryMapFragment trajectoryMapFragment;

    // New: List to store EKF trajectory points
    private List<LatLng> ekfTrajectoryPoints = new ArrayList<>();
    private List<LatLng> particleTrajectoryPoints = new ArrayList<>();

    private boolean hasRecordingWifiData=false;
    private SensorDataViewModel sensorDataViewModel;

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

    // Semih - NEW
    /**
     * Runnable task to update the timer every second.
     * Formats the elapsed time as MM:SS and updates the UI.
     */
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            int minutes = seconds / 60;
            int secs = seconds % 60;
            String timeText = String.format(Locale.getDefault(), "%02d:%02d", minutes, secs);

            timer.setText(timeText);
            seconds++;

            refreshDataHandler.postDelayed(this, 1000); // Runs every 1 second
        }
    };
    // Semih - END

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
        timer = view.findViewById(R.id.timerText);
        elevation = view.findViewById(R.id.currentElevation);
        distanceTravelled = view.findViewById(R.id.currentDistanceTraveled);
        gnssError = view.findViewById(R.id.gnssError);
        wifiError = view.findViewById(R.id.wifiError);
        ekfAccuracyText = view.findViewById(R.id.ekfAccuracyText);
        particleAccuracyText = view.findViewById(R.id.particleAccuracyText);


        completeButton = view.findViewById(R.id.stopButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        addTagButton = view.findViewById(R.id.addTagButton);
        recIcon = view.findViewById(R.id.redDot);
        timeRemaining = view.findViewById(R.id.timeRemainingBar);

        // Hide or initialize default values
        timer.setText(getString(R.string.timer_format, "00", "00"));
        gnssError.setVisibility(View.GONE);
        wifiError.setVisibility(View.GONE);
        elevation.setText(getString(R.string.elevation, "0"));
        distanceTravelled.setText(getString(R.string.meter, "0"));

        // Buttons
        completeButton.setOnClickListener(v -> {
            // Stop recording & go to correction
            if (autoStop != null) autoStop.cancel();
            sensorFusion.saveTagsToTrajectory();
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
        
        // Add Tag Button - adds the current position to the GNSS data array in the trajectory
        addTagButton.setOnClickListener(v -> {
            // Get current fused latitude, longitude, and altitude
            LatLng latLng = sensorFusion.getEKFStateAsLatLng();
            float latitude = (float) latLng.latitude;
            float longitude = (float) latLng.longitude;
            float altitude = sensorFusion.getElevation();
            long relativeTimestamp = SystemClock.uptimeMillis() - sensorFusion.getBootTime();
            
            // Add tag data to trajectory using the same method that's used for GNSS data
            sensorFusion.addTagToTrajectory(latitude, longitude, altitude, relativeTimestamp);
            
            // Provide feedback to the user
            Toast.makeText(requireContext(), R.string.tag_added, Toast.LENGTH_SHORT).show();
            
            // Add a visual indicator on the map (optional)
            LatLng tagLocation = new LatLng(latitude, longitude);
            if (trajectoryMapFragment != null) {
                trajectoryMapFragment.addTagMarker(tagLocation, relativeTimestamp);
            }
        });


        sensorDataViewModel = new ViewModelProvider(requireActivity()).get(SensorDataViewModel.class);


        // Timer updates every 1 second
        refreshDataHandler.post(timerRunnable);

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

        // Current location
        // Convert PDR coordinates to actual LatLng if you have a known starting lat/lon
        // Or simply pass relative data for the TrajectoryMapFragment to handle
        // For example:
        float[] latLngArray = sensorFusion.getGNSSLatitude(true);
        //LatLng gnssLocation = new LatLng(gnss[0], gnss[1]);
        Log.d("RecordingFragment", "sensorFusion.getGNSSLatitude returned: " + Arrays.toString(latLngArray));
        if (latLngArray != null) {
            LatLng oldLocation = trajectoryMapFragment.getCurrentLocation(); // or store locally
            LatLng newLocation = UtilFunctions.calculateNewPos(
                    oldLocation == null ? new LatLng(latLngArray[0], latLngArray[1]) : oldLocation,
                    new float[]{ pdrValues[0] - previousPosX, pdrValues[1] - previousPosY }
            );

            // Pass the location + orientation to the map
            if (trajectoryMapFragment != null) {
                trajectoryMapFragment.updateUserLocation(newLocation,
                        (float) Math.toDegrees(sensorFusion.passOrientation()));
            }
        }

        // GNSS logic if you want to show GNSS error, etc.
        float[] gnss = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        if (gnss != null && trajectoryMapFragment != null) {
            sensorDataViewModel.setHasGnssData(true);
            LatLng gnssLocation = new LatLng(gnss[0], gnss[1]);
            trajectoryMapFragment.updateGNSS(gnssLocation);
            // If user toggles showing GNSS in the map, call e.g.
            if (trajectoryMapFragment.isGnssEnabled()) {
                LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();
                if (currentLoc != null) {
                    double errorDist = UtilFunctions.distanceBetweenPoints(currentLoc, gnssLocation);
                    gnssError.setVisibility(View.VISIBLE);
                    gnssError.setText(String.format(getString(R.string.gnss_error) + "%.2fm", errorDist));
                }

            } else {
                gnssError.setVisibility(View.GONE);
                trajectoryMapFragment.clearGNSS();
            }
        }
        else {
            sensorDataViewModel.setHasGnssData(false);
        }

        /** // ----- Update Wifi mapping -----
         * @author Stone Anderson
         */

        LatLng wifiLocation = sensorFusion.getLatLngWifiPositioning();
        int floor = sensorFusion.getWifiFloor();
        if (wifiLocation != null) {
            trajectoryMapFragment.autoFloorHandler(floor); //
        }
        if (wifiLocation  != null) {
            if (trajectoryMapFragment != null) {
                sensorDataViewModel.setHasWifiData(true);
                Log.d("WIFI DETECTION", "setRecordWifiMode called as true" );
                trajectoryMapFragment.updateWifi(wifiLocation);
            }
        }
        else {
            if (trajectoryMapFragment != null) {
                sensorDataViewModel.setHasWifiData(false);}

        }


        // Wifi logic if you want to show wifi error, etc.
        LatLng wifi = sensorFusion.getLatLngWifiPositioning();
        if (wifi != null && trajectoryMapFragment != null) {
            if (trajectoryMapFragment.isWifiEnabled()) {
                // Optionally compare with the current location or WiFi-specific location if available
                LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();
                if (currentLoc != null) {
                    double errorDist = UtilFunctions.distanceBetweenPoints(currentLoc, wifi);
                    wifiError.setVisibility(View.VISIBLE);  // Assuming you have a TextView for WiFi error similar to gnssError
                    wifiError.setText(String.format(getString(R.string.wifiError) + "%.2fm", errorDist));
                }
                trajectoryMapFragment.updateWifi(wifi);
            } else {
                wifiError.setVisibility(View.GONE);
                trajectoryMapFragment.clearWifi();
            }
        }


        /** // ----- Update EKF mapping -----
         * @author Stone Anderson
         */
        LatLng ekfLocation = sensorFusion.getEKFStateAsLatLng(); // Get the latest EKF fused state [x, y]
        if (ekfLocation != null) {
            if (trajectoryMapFragment != null) {
                trajectoryMapFragment.updateEKF(ekfLocation,(float) Math.toDegrees(sensorFusion.passOrientation()));
            }
            double ekfAccuracy = sensorFusion.getEKFPositionAccuracy(); // update EKF accuracy display
            if (!Double.isNaN(ekfAccuracy)) {
                ekfAccuracyText.setText(String.format(getString(R.string.ekf_accuracy), ekfAccuracy));
            } else {
                ekfAccuracyText.setText("EKF Accuracy: --");
            }
        }


        /**
         * Update Particle Trajectory
         * Get the latest particle fused state (assumed to be an [x, y] coordinate in the same metric space)
         *
         * @author Sofea Jazlan Arif
         */
        ParticleAttribute particleAttribute = sensorFusion.updateParticle();
        LatLng particleLocation = sensorFusion.getParticleStateAsLatLng(particleAttribute);
        if (particleLocation != null) {
            if (trajectoryMapFragment != null) {
                trajectoryMapFragment.updateParticle(particleLocation, (float) Math.toDegrees(sensorFusion.passOrientation()));
            }
            
            // Update Particle Filter accuracy display
            double particleAccuracy = sensorFusion.getParticlePositionAccuracy();
            if (!Double.isNaN(particleAccuracy)) {
                //particleAccuracyText.setText(String.format(getString(R.string.particle_accuracy), particleAccuracy));
                particleAccuracyText.setText("");
            } else {
                particleAccuracyText.setText("");
                //particleAccuracyText.setText("Particle Accuracy: --");
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

    @Override
    public void onPause() {
        super.onPause();
        refreshDataHandler.removeCallbacks(refreshDataTask);
        refreshDataHandler.removeCallbacks(timerRunnable);
        seconds = 0;  // Reset the timer when paused
    }

    @Override
    public void onResume() {
        super.onResume();
        if(!this.settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
    }
}
