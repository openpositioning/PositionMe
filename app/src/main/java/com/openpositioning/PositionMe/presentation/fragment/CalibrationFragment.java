package com.openpositioning.PositionMe.presentation.fragment;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.button.MaterialButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.local.DataFileManager;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import org.json.JSONObject;

/**
 * CalibrationFragment that:
 *  - Allows starting/stopping passive recording of sensor data at 0.5s intervals.
 *  - Enables calibration tagging only if recording is active.
 *  - Counts and displays how many total data records have been stored.
 */
public class CalibrationFragment extends Fragment {

    private static final String TAG = "CalibrationFragment";

    // UI references
    private Spinner floorSpinner;
    private Spinner indoorStateSpinner;
    private EditText buildingNameEditText;
    private MaterialButton calibrationButton, startRecordingButton;
    private TextView pointCountTextView;   // Displays how many data points have been collected

    // Data references
    private SensorFusion sensorFusion;
    private DataFileManager dataFileManager;
    private TrajectoryMapFragment trajectoryMapFragment;

    // Marker & calibration state
    private Marker calibrationMarker = null;
    private boolean markerPlaced = false;
    private int selectedFloorLevel = -1;
    private int selectedIndoorState = 0;
    private String buildingName = null;

    // Passive recording
    private boolean passiveRecordingActive = false;
    private Handler passiveRecordingHandler;
    private Runnable passiveRecordingTask;

    // Count of how many total points have been collected (both passive and calibration)
    private int recordCount = 0;

    public CalibrationFragment() {
        // Required empty constructor
    }

    public static CalibrationFragment newInstance() {
        return new CalibrationFragment();
    }

    /**
     * Optional: these setter methods let the parent inject references if needed.
     */
    public void setSensorFusion(SensorFusion sensorFusion) {
        this.sensorFusion = sensorFusion;
    }
    public void setDataFileManager(DataFileManager dataFileManager) {
        this.dataFileManager = dataFileManager;
    }
    public void setTrajectoryMapFragment(TrajectoryMapFragment mapFragment) {
        this.trajectoryMapFragment = mapFragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_calibration, container, false);

        // Initialize UI references
        buildingNameEditText   = view.findViewById(R.id.buildingNameEditText);
        floorSpinner           = view.findViewById(R.id.floorSpinner);
        indoorStateSpinner     = view.findViewById(R.id.indoorStateSpinner);
        calibrationButton      = view.findViewById(R.id.toggleCalibrationButton);
        startRecordingButton   = view.findViewById(R.id.startRecordingButton);
        pointCountTextView     = view.findViewById(R.id.pointCountTextView);

        // Setup spinners
        setupFloorSpinner();
        setupIndoorStateSpinner();

        // Initially disable calibration button until recording is started
        calibrationButton.setEnabled(false);
        calibrationButton.setText("Add Tag");
        calibrationButton.setOnClickListener(v -> {
            if (!markerPlaced) {
                placeCalibrationMarker();
                markerPlaced = true;
                calibrationButton.setText("Confirm");
                Log.d(TAG, "Calibration marker placed.");
            } else {
                confirmCalibration();
                calibrationMarker = null;
                markerPlaced = false;
                calibrationButton.setText("Add Tag");
            }
        });

        startRecordingButton.setOnClickListener(v -> {
            if (!passiveRecordingActive) {
                startPassiveRecording();
                passiveRecordingActive = true;

                // Change icon to "stop" icon
                startRecordingButton.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_file_check_alt_svgrepo_com));

                // Optional: also change background tint to match
                startRecordingButton.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.md_theme_errorContainer_mediumContrast))
                );

            } else {
                stopPassiveRecording();
                passiveRecordingActive = false;

                // Change icon to "start" icon
                startRecordingButton.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_file_arrow_down_alt_svgrepo_com));

                // Reset background tint
                startRecordingButton.setBackgroundTintList(
                        ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.md_theme_primary))
                );
            }
        });



        // Show initial count = 0
        updatePointCountDisplay();

        return view;
    }

    /**
     * Floor level spinner
     */
    private void setupFloorSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                requireContext(),
                R.array.floor_levels,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        floorSpinner.setAdapter(adapter);
        floorSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String selected = parent.getItemAtPosition(position).toString();
                if (selected.contains("Outdoors") || selected.startsWith("-1")) {
                    selectedFloorLevel = -1;
                } else {
                    try {
                        selectedFloorLevel = Integer.parseInt(selected.split(" ")[0]);
                    } catch (NumberFormatException e) {
                        selectedFloorLevel = -1;
                    }
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    /**
     * Indoor state spinner
     */
    private void setupIndoorStateSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                requireContext(),
                R.array.indoor_states,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        indoorStateSpinner.setAdapter(adapter);
        indoorStateSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                try {
                    // We assume the array items are like: "0 - Outdoor", "1 - Indoor", etc.
                    selectedIndoorState = Integer.parseInt(
                            parent.getItemAtPosition(position).toString().substring(0, 1)
                    );
                } catch (Exception e) {
                    selectedIndoorState = 0;
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    /**
     * Place a draggable marker for calibration
     */
    private void placeCalibrationMarker() {
        if (trajectoryMapFragment == null || trajectoryMapFragment.getGoogleMap() == null) {
            Toast.makeText(getContext(), "Map not ready!", Toast.LENGTH_SHORT).show();
            return;
        }
        GoogleMap map = trajectoryMapFragment.getGoogleMap();

        if (calibrationMarker != null) {
            map.animateCamera(
                    com.google.android.gms.maps.CameraUpdateFactory.newLatLng(
                            calibrationMarker.getPosition())
            );
            return;
        }
        LatLng userLocation = trajectoryMapFragment.getCurrentLocation();
        if (userLocation == null) {
            userLocation = new LatLng(55.9228, -3.1746); // fallback
        }
        calibrationMarker = map.addMarker(new MarkerOptions()
                .position(userLocation)
                .title("Calibration Marker")
                .draggable(true));

        map.animateCamera(
                com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(userLocation, 19f)
        );

        trajectoryMapFragment.updateCalibrationPinLocation(userLocation, false);

        map.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
            @Override public void onMarkerDragStart(@NonNull Marker marker) {}
            @Override public void onMarkerDrag(@NonNull Marker marker) {}
            @Override public void onMarkerDragEnd(@NonNull Marker marker) {
                if (calibrationMarker != null) {
                    calibrationMarker.setPosition(marker.getPosition());
                    trajectoryMapFragment.updateCalibrationPinLocation(marker.getPosition(), false);
                }
            }
        });
    }

    /**
     * Confirm the marker location, store calibration record
     */
    private void confirmCalibration() {
        if (calibrationMarker == null) {
            Toast.makeText(getContext(), "No calibration marker placed!", Toast.LENGTH_SHORT).show();
            return;
        }
        buildingName = buildingNameEditText.getText().toString();
        if (TextUtils.isEmpty(buildingName)) {
            buildingName = null;
        }
        LatLng markerPos = calibrationMarker.getPosition();
        double lat = markerPos.latitude;
        double lng = markerPos.longitude;

        trajectoryMapFragment.updateCalibrationPinLocation(markerPos, true);
        Toast.makeText(getContext(),
                "Calibration confirmed at (" + lat + ", " + lng + ")",
                Toast.LENGTH_SHORT).show();

        onCalibrationTriggered(lat, lng, selectedIndoorState, selectedFloorLevel, buildingName);
        calibrationMarker.remove();
    }

    /**
     * Create a calibration record and add to dataFileManager.
     */
    private void onCalibrationTriggered(double userLat,
                                        double userLng,
                                        int indoorState,
                                        int floorLevel,
                                        String buildingName) {
        if (sensorFusion == null || dataFileManager == null) {
            Log.e(TAG, "Cannot trigger calibration; sensorFusion or dataFileManager is null.");
            return;
        }
        try {
            JSONObject calibrationRecord = sensorFusion.getAllSensorData();
            calibrationRecord.put("isCalibration", true);
            calibrationRecord.put("userLat", userLat);
            calibrationRecord.put("userLng", userLng);
            calibrationRecord.put("floorLevel", floorLevel);
            calibrationRecord.put("indoorState", indoorState);
            calibrationRecord.put("buildingName", buildingName);

            dataFileManager.addRecord(calibrationRecord);
            recordCount++;
            updatePointCountDisplay();
            Log.d(TAG, "Calibration triggered and saved.");
        } catch (Exception e) {
            Log.e(TAG, "Error triggering calibration", e);
        }
    }

    /************************************************
     *  Passive Recording (toggle on/off)
     ************************************************/
    private void startPassiveRecording() {
        passiveRecordingActive = true;
        calibrationButton.setEnabled(true); // Now user can do tagging
        // make the calibration button to be look like enabled
        calibrationButton.setBackgroundColor(getResources().getColor(R. color. md_theme_primaryContainer_highContrast));

        passiveRecordingHandler = new Handler();
        passiveRecordingTask = new Runnable() {
            @Override
            public void run() {
                try {
                    if (sensorFusion != null && dataFileManager != null) {
                        JSONObject record = sensorFusion.getAllSensorData();
                        record.put("isCalibration", false);
                        dataFileManager.addRecord(record);
//                        recordCount++;    // don't count it into the total calibration tag
                        updatePointCountDisplay();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in passive recording loop", e);
                }
                if (passiveRecordingActive) {
                    passiveRecordingHandler.postDelayed(this, 500);
                }
            }
        };
        passiveRecordingHandler.post(passiveRecordingTask);
    }

    private void stopPassiveRecording() {
        passiveRecordingActive = false;
        calibrationButton.setEnabled(false); // User can't tag if not recording
        calibrationButton.setBackgroundColor(getResources().getColor(R. color. md_theme_inverseOnSurface));


        if (passiveRecordingHandler != null && passiveRecordingTask != null) {
            passiveRecordingHandler.removeCallbacks(passiveRecordingTask);
        }

        // Optionally flush or finalize data here
        if (dataFileManager != null) {
            dataFileManager.flushBuffer();
            // dataFileManager.close(); // only if you want to close the file entirely
        }

        Toast.makeText(getContext(), "Recording stopped and data saved.", Toast.LENGTH_SHORT).show();
    }

    private void updatePointCountDisplay() {
        if (pointCountTextView != null) {
            pointCountTextView.setText(
                    getString(R.string.point_count_format, recordCount)
            );
        }
    }

    /************************************************
     *  Lifecycle
     ************************************************/
    @Override
    public void onDestroy() {
        // If still recording, stop
        if (passiveRecordingActive && passiveRecordingHandler != null && passiveRecordingTask != null) {
            passiveRecordingHandler.removeCallbacks(passiveRecordingTask);
        }
        super.onDestroy();
    }
}
