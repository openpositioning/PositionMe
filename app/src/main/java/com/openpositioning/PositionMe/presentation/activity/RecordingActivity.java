package com.openpositioning.PositionMe.presentation.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.Wifi;
import com.openpositioning.PositionMe.service.SensorCollectionService;
import com.openpositioning.PositionMe.presentation.fragment.StartLocationFragment;
import com.openpositioning.PositionMe.presentation.fragment.RecordingFragment;
import com.openpositioning.PositionMe.presentation.fragment.CorrectionFragment;

import java.util.ArrayList;
import java.util.List;


/**
 * The RecordingActivity manages the recording flow of the application, guiding the user through a sequence
 * of screens for location selection, recording, and correction before finalizing the process.
 * <p>
 * This activity follows a structured workflow:
 * <ol>
 *     <li>StartLocationFragment - Allows users to select their starting location.</li>
 *     <li>RecordingFragment - Handles the recording process and contains a TrajectoryMapFragment.</li>
 *     <li>CorrectionFragment - Enables users to review and correct recorded data before completion.</li>
 * </ol>
 * <p>
 * The activity ensures that the screen remains on during the recording process to prevent interruptions.
 * It also provides fragment transactions for seamless navigation between different stages of the workflow.
 * <p>
 * This class is referenced in various fragments such as HomeFragment, StartLocationFragment,
 * RecordingFragment, and CorrectionFragment to control navigation through the recording flow.
 *
 * @see StartLocationFragment The first step in the recording process where users select their starting location.
 * @see RecordingFragment Handles data recording and map visualization.
 * @see CorrectionFragment Allows users to review and make corrections before finalizing the process.
 * @see com.openpositioning.PositionMe.R.layout#activity_recording The associated layout for this activity.
 *
 * @author ShuGu
 */

public class RecordingActivity extends AppCompatActivity {
    public static final String EXTRA_LAUNCH_INDOOR_MODE = "extra_launch_indoor_mode";
    private boolean launchIndoorMode;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Hide status bar before inflating layout to avoid flash/reflow
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);

        setContentView(R.layout.activity_recording);
        launchIndoorMode = getIntent().getBooleanExtra(EXTRA_LAUNCH_INDOOR_MODE, false);

        if (savedInstanceState == null) {
            if (launchIndoorMode) {
                launchIndoorPositioningMode();
            } else {
                // Show trajectory name input dialog before proceeding to start location
                showTrajectoryNameDialog();
            }
        }

        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Hide system status bar for immersive experience
        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.hide(WindowInsetsCompat.Type.statusBars());
        insetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    /**
     * {@inheritDoc}
     * Re-registers sensor listeners so that IMU, step detection, barometer and other
     * movement sensors remain active while this activity is in the foreground.
     * Without this, sensors are unregistered when {@link MainActivity#onPause()} fires
     * during the activity transition, leaving PDR and elevation updates dead.
     */
    @Override
    protected void onResume() {
        super.onResume();
        SensorFusion.getInstance().resumeListening();
    }

    /**
     * {@inheritDoc}
     * Stops sensor listeners when this activity is no longer visible, unless
     * the foreground {@link SensorCollectionService} is running (recording in progress).
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (!SensorCollectionService.isRunning()) {
            SensorFusion.getInstance().stopListening();
        }
    }

    /**
     * Shows an AlertDialog prompting the user to enter a trajectory name.
     * The name is stored in SensorFusion as trajectory_id and later written to the protobuf.
     * After input, proceeds to StartLocationFragment.
     */
    private void showTrajectoryNameDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("e.g. Nucleus_Walk_01");
        input.setPadding(48, 24, 48, 24);

        new AlertDialog.Builder(this)
                .setTitle("Trajectory Name")
                .setMessage("Enter a name for this recording session:")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        // Default name based on timestamp
                        name = "traj_" + System.currentTimeMillis();
                    }
                    SensorFusion.getInstance().setTrajectoryId(name);
                    showStartLocationScreen();
                })
                .setNegativeButton("Skip", (dialog, which) -> {
                    // Use default name
                    SensorFusion.getInstance().setTrajectoryId(
                            "traj_" + System.currentTimeMillis());
                    showStartLocationScreen();
                })
                .show();
    }

    /**
     * Show the StartLocationFragment (beginning of flow).
     */
    public void showStartLocationScreen() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(R.id.mainFragmentContainer, new StartLocationFragment());
        ft.commit();
    }

    /**
     * Show the RecordingFragment, which contains the TrajectoryMapFragment internally.
     */
    public void showRecordingScreen() {
        showRecordingScreen(false);
    }

    public void showRecordingScreen(boolean indoorMode) {
        RecordingFragment fragment = new RecordingFragment();
        Bundle args = new Bundle();
        args.putBoolean(RecordingFragment.ARG_INDOOR_MODE, indoorMode);
        fragment.setArguments(args);

        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(R.id.mainFragmentContainer, fragment);
        if (!indoorMode) {
            ft.addToBackStack(null);
        }
        // Indoor mode: no back stack — back/cancel goes straight to home
        ft.commit();
    }

    private void launchIndoorPositioningMode() {
        SensorFusion sensorFusion = SensorFusion.getInstance();
        sensorFusion.ensureWirelessScanning();
        sensorFusion.setTrajectoryId(nextIndoorTrajectoryName());

        LatLng preferredLocation = sensorFusion.getLatLngWifiPositioning();
        if (preferredLocation == null) {
            float[] gnss = sensorFusion.getGNSSLatitude(false);
            preferredLocation = new LatLng(gnss[0], gnss[1]);
        }

        final LatLng requestLocation = preferredLocation;
        List<String> observedMacs = new ArrayList<>();
        List<Wifi> wifiList = sensorFusion.getWifiList();
        if (wifiList != null) {
            for (Wifi wifi : wifiList) {
                String mac = wifi.getBssidString();
                if (mac != null && !mac.isEmpty()) {
                    observedMacs.add(mac);
                }
            }
        }

        new FloorplanApiClient().requestFloorplan(
                requestLocation.latitude,
                requestLocation.longitude,
                observedMacs,
                new FloorplanApiClient.FloorplanCallback() {
                    @Override
                    public void onSuccess(List<FloorplanApiClient.BuildingInfo> buildings) {
                        startIndoorRecording(sensorFusion, requestLocation, buildings);
                    }

                    @Override
                    public void onFailure(String error) {
                        startIndoorRecording(sensorFusion, requestLocation, new ArrayList<>());
                    }
                }
        );
    }

    private void startIndoorRecording(SensorFusion sensorFusion,
                                      LatLng fallbackLocation,
                                      List<FloorplanApiClient.BuildingInfo> buildings) {
        sensorFusion.setFloorplanBuildings(buildings);

        LatLng startLocation = fallbackLocation;
        String selectedBuildingId = null;

        if (buildings != null && !buildings.isEmpty()) {
            double bestDist = Double.MAX_VALUE;
            for (FloorplanApiClient.BuildingInfo building : buildings) {
                LatLng center = building.getCenter();
                double dLat = center.latitude - fallbackLocation.latitude;
                double dLon = center.longitude - fallbackLocation.longitude;
                double distance = Math.hypot(dLat, dLon);
                if (distance < bestDist) {
                    bestDist = distance;
                    selectedBuildingId = building.getName();
                    startLocation = center;
                }
            }
        }

        sensorFusion.setSelectedBuildingId(selectedBuildingId);

        sensorFusion.setStartGNSSLatitude(new float[]{
                (float) startLocation.latitude,
                (float) startLocation.longitude
        });
        sensorFusion.startRecording();
        sensorFusion.writeInitialMetadata();
        showRecordingScreen(true);
    }

    private String nextIndoorTrajectoryName() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int nextValue = prefs.getInt("indoor_history_counter", 0) + 1;
        prefs.edit().putInt("indoor_history_counter", nextValue).apply();
        return "navigate history" + nextValue;
    }

    /**
     * Show the CorrectionFragment after the user stops recording.
     */
    public void showCorrectionScreen() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(R.id.mainFragmentContainer, new CorrectionFragment());
        ft.addToBackStack(null);
        ft.commit();
    }

    /**
     * Finish the Activity (or do any final steps) once corrections are done.
     */
    public void finishFlow() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        finish();
    }
}
