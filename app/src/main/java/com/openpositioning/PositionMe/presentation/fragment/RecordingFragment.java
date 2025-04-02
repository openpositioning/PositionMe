package com.openpositioning.PositionMe.presentation.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;
import com.openpositioning.PositionMe.sensors.Wifi;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.model.LatLng;

import org.json.JSONObject;

import java.util.List;
import java.util.UUID;


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

    private LatLng prevPdrLocation = null;
    private LatLng prevWiFiLocation = null;
    private int prevFloorLocation = 0;
    private float prevBearing;


    // References to the child map fragment
    private TrajectoryMapFragment trajectoryMapFragment;

    // Code by Guilherme: New button for adding a tag
    //private Button tagButton;

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

        completeButton = view.findViewById(R.id.stopButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        recIcon = view.findViewById(R.id.redDot);
        timeRemaining = view.findViewById(R.id.timeRemainingBar);
        // Code by Guilherme: Find the new Tag button (added in XML)
        //Button tagButton = view.findViewById(R.id.tagButton);

        // Hide or initialize default values
        gnssError.setVisibility(View.GONE);
        elevation.setText(getString(R.string.elevation, "0"));
        distanceTravelled.setText(getString(R.string.meter, "0"));

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
        // Import UUID at the top if not already imported:
// import java.util.UUID;

// ... inside onViewCreated (or after initializing UI controls)
        Button tagButton = view.findViewById(R.id.tagButton);
        tagButton.setOnClickListener(v -> {
            LatLng currentLocation = trajectoryMapFragment.getCurrentLocation();
            if (currentLocation != null) {
                long elapsedMillis = SystemClock.uptimeMillis() - sensorFusion.bootTime;

                // Add GNSS_Sample with "fusion" provider
                Traj.GNSS_Sample fusionTag = Traj.GNSS_Sample.newBuilder()
                        .setRelativeTimestamp(elapsedMillis)
                        .setLatitude((float) currentLocation.latitude)
                        .setLongitude((float) currentLocation.longitude)
                        .setAltitude(sensorFusion.getElevation()) // use elevation if available
                        .setProvider("fusion")
                        .build();
                // check the format of the GNSS tag and check the entire trajectory to see if the points have been added

                sensorFusion.trajectory.addGnssData(fusionTag);

                // Optional: Show on map
                String tagLabel = "Fusion Tag\nLat: " + currentLocation.latitude + "\nLon: " + currentLocation.longitude;
                trajectoryMapFragment.addTagMarker(currentLocation, tagLabel);

                Toast.makeText(getContext(), "Fusion tag added", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "Current location not available", Toast.LENGTH_SHORT).show();
         }
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

    /**
     * Update the UI with sensor data and pass map updates to TrajectoryMapFragment.
     * Uses EKF_replay() from SensorFusion to plot fused data in real time.
     */

    // code by Jamie Arnott: EKF positioning in real time using PDR, GNSS and WiFi data
    private LatLng originLatLng = null; // This stays fixed once set

    private void updateUIandPosition() {
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null) return;

        // Add WiFi sample to trajectory if recording
        if (sensorFusion.saveRecording) {
            List<Wifi> wifiList = sensorFusion.getWifiList();
            if (wifiList != null && !wifiList.isEmpty()) {
                Traj.WiFi_Sample.Builder wifiSample = Traj.WiFi_Sample.newBuilder()
                        .setRelativeTimestamp(SystemClock.uptimeMillis() - sensorFusion.bootTime);

                for (Wifi wifi : wifiList) {
                    wifiSample.addMacScans(Traj.Mac_Scan.newBuilder()
                            .setRelativeTimestamp(SystemClock.uptimeMillis() - sensorFusion.bootTime)
                            .setMac(wifi.getBssid())
                            .setRssi(wifi.getLevel()));
                }
                sensorFusion.trajectory.addWifiData(wifiSample.build());
            }
        }

        // Get fixed origin once (GNSS preferred, fallback to WiFi later)
        if (originLatLng == null) {
            float[] gnss = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
            if (gnss != null) {
                originLatLng = new LatLng(gnss[0], gnss[1]);
                Log.d("EKF", "Origin set from GNSS: " + originLatLng);
            }
        }

        // Distance
        distance += Math.sqrt(Math.pow(pdrValues[0] - previousPosX, 2)
                + Math.pow(pdrValues[1] - previousPosY, 2));
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));

        // Elevation
        float elevationVal = sensorFusion.getElevation();
        elevation.setText(getString(R.string.elevation, String.format("%.1f", elevationVal)));

        // Convert PDR to LatLng relative to fixed origin
        LatLng pdrLatLng = null;
        if (originLatLng != null) {
            float deltaX = pdrValues[0]-previousPosX;
            float deltaY = pdrValues[1]-previousPosY;
            Log.d("Deltas", "Deltax " + deltaX + "DeltaY " + deltaY);

            if (Math.abs(deltaX) > 20 || Math.abs(deltaY) > 20) {
                Log.w("PDR", "Suspiciously large PDR delta: " + deltaX + "," + deltaY);
            }

            pdrLatLng = UtilFunctions.calculateNewPos(originLatLng, new float[]{deltaX, deltaY});
        }

        // GNSS live reading
        float[] gnss = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        LatLng gnssLatLng = (gnss != null) ? new LatLng(gnss[0], gnss[1]) : null;

        // Get WiFi fingerprint if available
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();

        if (isConnected) {
            List<Wifi> wifiList = sensorFusion.getWifiList();
            if (wifiList != null && !wifiList.isEmpty()) {
                JSONObject wifiFingerprint = new JSONObject();
                JSONObject wifiAccessPoints = new JSONObject();
                for (Wifi wifi : wifiList) {
                    try {
                        wifiAccessPoints.put(String.valueOf(wifi.getBssid()), wifi.getLevel());
                    } catch (Exception e) {
                        Log.e("EKF", "Failed to create fingerprint", e);
                    }
                }
                // obtain wifi fingerprint and process data using EKF
                try {
                    wifiFingerprint.put("wf", wifiAccessPoints);
                    LatLng finalPdrLatLng = pdrLatLng;

                    new WiFiPositioning(getContext()).request(wifiFingerprint, new WiFiPositioning.VolleyCallback() {
                        @Override
                        public void onSuccess(LatLng wifiLocation, int floor) {
                            prevWiFiLocation = wifiLocation;
                            prevFloorLocation = floor;
                            if (originLatLng == null) {
                                originLatLng = wifiLocation; // fallback origin if GNSS was missing
                                Log.d("EKF", "Origin set from WiFi: " + originLatLng);
                            }

                            LatLng ekfPoint = SensorFusion.getInstance().EKF_replay(
                                    wifiLocation,
                                    finalPdrLatLng,
                                    prevPdrLocation != null ? prevPdrLocation : finalPdrLatLng,
                                    gnssLatLng
                            );

                            Log.d("EKF", "EKF Point: " + ekfPoint);
                            if (ekfPoint != null && trajectoryMapFragment != null) {
                                float bearing = (float) Math.toDegrees(sensorFusion.passOrientation());
                                prevBearing = bearing;
                                trajectoryMapFragment.updateUserLocation(ekfPoint, bearing);
                                trajectoryMapFragment.displayNucleusFloorLevel(floor);
                            }
                        }

                        @Override
                        public void onError(String message) {
                            Log.w("EKF", "WiFi Positioning failed: " + message);
                        }
                    });
                } catch (Exception e) {
                    Log.e("EKF", "Failed to send WiFi request", e);
                }
            } else if (prevWiFiLocation != null && pdrLatLng != null) {
                // fallback EKF update with previous WiFi
                LatLng ekfPoint = SensorFusion.getInstance().EKF_replay(
                        prevWiFiLocation,
                        pdrLatLng,
                        prevPdrLocation != null ? prevPdrLocation : pdrLatLng,
                        gnssLatLng
                );
                if (ekfPoint != null) {
                    float bearing = (float) Math.toDegrees(sensorFusion.passOrientation());
                    prevBearing = bearing;
                    trajectoryMapFragment.updateUserLocation(ekfPoint, bearing);
                    trajectoryMapFragment.displayNucleusFloorLevel(prevFloorLocation);
                }
            }
        } else {
            Log.d("RecordingFragment", "Phone not connected to WiFi");
            // if phone loses wifi signal then continue from last point with new PDR step
            // create new position as PDR displacement from prevWiFiLocation

            LatLng newPos = UtilFunctions.calculateNewPos(prevWiFiLocation,new float[]{pdrValues[0] - previousPosX, pdrValues[1] - previousPosY});
            trajectoryMapFragment.updateUserLocation(newPos, prevBearing);
            trajectoryMapFragment.displayNucleusFloorLevel(prevFloorLocation);
        }

        // Update previous PDR
        prevPdrLocation = pdrLatLng;
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
    }

    @Override
    public void onResume() {
        super.onResume();
        if(!this.settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
    }
}
