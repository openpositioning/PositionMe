package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.material.textfield.TextInputEditText;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;

import java.util.Map;


public class RecordingFragment extends Fragment implements Observer, IndoorMapFragment.VenueSelectionCallback {


    private static final int AXIS_MODE = 1;


    private static final float DISTANCE_MULTIPLIER = 1.2f;

    private static final double ROTATION_FINE_TUNE = 0.0;

 

    private Button startStopButton, markerButton;
    private TextInputEditText trajectoryIdInput;
    private TextView statusTextView;

    private TrajectoryMapFragment trajectoryMapFragment;
    private SensorFusion sensorFusion;
    private boolean isRecording = false;

    private Handler uiHandler = new Handler();
    private Runnable updateMapTask;

    private String selectedBuildingId = null;
    private String selectedVenueName = null;

    // PDR Variables
    private LatLng pdrOrigin = null;
    private float[] pdrStartOffset = null;
    private LatLng currentPdrLocation = null;
    private static final double EARTH_RADIUS = 6378137.0;
    private double totalDistanceMeters = 0.0;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_recording, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        sensorFusion = SensorFusion.getInstance();

        trajectoryMapFragment = new TrajectoryMapFragment();
        trajectoryMapFragment.setOnVenueSelectedListener((buildingId, venueName) -> {
            selectedBuildingId = buildingId;
            selectedVenueName = venueName;
            if (isRecording && statusTextView != null) {
                statusTextView.append(" [" + venueName + "]");
            }
        });
        getChildFragmentManager().beginTransaction()
                .replace(R.id.mapFragmentContainer, trajectoryMapFragment)
                .commit();

        startStopButton = view.findViewById(R.id.bButton);
        markerButton = view.findViewById(R.id.markerButton);
        trajectoryIdInput = view.findViewById(R.id.trajectoryIdInput);
        statusTextView = view.findViewById(R.id.statusText);

       
        float[] startCoords = sensorFusion.getGNSSLatitude(true);
        if (startCoords[0] != 0) {
            new Handler().postDelayed(() -> {
                if (trajectoryMapFragment != null && trajectoryMapFragment.isAdded()) {
                    trajectoryMapFragment.setInitialCameraPosition(new LatLng(startCoords[0], startCoords[1]));
                }
            }, 600);
        }

        startStopButton.setOnClickListener(v -> {
            if (!isRecording) startRecording();
            else stopRecording();
        });

        markerButton.setOnClickListener(v -> {
            if (isRecording) {
                sensorFusion.addMarker();
                if (currentPdrLocation != null && trajectoryMapFragment != null) {
                    trajectoryMapFragment.addMarkerToMap(currentPdrLocation);
                }
                Toast.makeText(getContext(), "Marker Added", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startRecording() {
        String id = "";
        if (trajectoryIdInput.getText() != null) {
            id = trajectoryIdInput.getText().toString().trim();
        }
        if (id.isEmpty()) {
            id = "Traj_" + System.currentTimeMillis();
        }

        Log.e("RecordingFragment", "Starting Manual PDR recording: " + id);
        sensorFusion.startRecording(id);

        if (selectedVenueName != null) sensorFusion.setVenueName(selectedVenueName);
        if (selectedBuildingId != null) sensorFusion.setBuildingId(selectedBuildingId);

        isRecording = true;
        totalDistanceMeters = 0.0;

        // 1. Reset underlying PDR
        sensorFusion.resetPDR();

        // 2. Lock start point (Manual Origin)
        if (trajectoryMapFragment != null) {
            pdrOrigin = trajectoryMapFragment.getCameraTarget();
            currentPdrLocation = pdrOrigin;

            // 3. Record initial offset
            Map<SensorTypes, float[]> sensorData = sensorFusion.getSensorValueMap();
            if (sensorData != null) {
                float[] currentPDR = sensorData.get(SensorTypes.PDR);
                if (currentPDR != null) {
                    pdrStartOffset = new float[]{currentPDR[0], currentPDR[1]};
                } else {
                    pdrStartOffset = new float[]{0f, 0f};
                }
            }
        }

        statusTextView.setText("Recording (Mode " + AXIS_MODE + ")");
        statusTextView.setBackgroundResource(R.drawable.status_recording);

        startStopButton.setText("Stop");
        startStopButton.setBackgroundColor(getResources().getColor(android.R.color.holo_red_dark));
        trajectoryIdInput.setEnabled(false);
        markerButton.setEnabled(true);

        startUiUpdates();
    }

    private void startUiUpdates() {
        updateMapTask = new Runnable() {
            @Override
            public void run() {
                if (isRecording && isAdded()) {
                    Map<SensorTypes, float[]> sensorData = sensorFusion.getSensorValueMap();

                    if (sensorData != null) {
                        float orientation = sensorFusion.passOrientation();
                        float[] pdrMovement = sensorData.get(SensorTypes.PDR);
                        float[] gnssPos = sensorData.get(SensorTypes.GNSSLATLONG);

                        // 1. Update Blue Dot (Reference only)
                        if (gnssPos != null && gnssPos[0] != 0) {
                            if (trajectoryMapFragment != null) {
                                trajectoryMapFragment.updateGNSS(new LatLng(gnssPos[0], gnssPos[1]));
                            }
                        }

                        // 2. PDR Trajectory Logic
                        if (pdrOrigin != null && pdrMovement != null) {

                            // A. Calculate raw relative displacement
                            float startX = (pdrStartOffset != null) ? pdrStartOffset[0] : 0;
                            float startY = (pdrStartOffset != null) ? pdrStartOffset[1] : 0;
                            float rawX = pdrMovement[0] - startX;
                            float rawY = pdrMovement[1] - startY;

                            // B. [Key] Axis Mapping Correction
                            float mapX = rawX;
                            float mapY = rawY;

                            switch (AXIS_MODE) {
                                case 1: // Standard
                                    mapX = rawX; mapY = rawY;
                                    break;
                                case 2: // Swap XY - Solves common "Sin/Cos swapped" issue
                                    mapX = rawY; mapY = rawX;
                                    break;
                                case 3: // Flip Y - Solves North/South inversion
                                    mapX = rawX; mapY = -rawY;
                                    break;
                                case 4: // Flip X - Solves East/West inversion
                                    mapX = -rawX; mapY = rawY;
                                    break;
                            }

                            // C. Apply Distance Multiplier
                            mapX *= DISTANCE_MULTIPLIER;
                            mapY *= DISTANCE_MULTIPLIER;

                            // D. Apply Fine Tune Rotation
                            double theta = Math.toRadians(ROTATION_FINE_TUNE);
                            double rotatedX = mapX * Math.cos(theta) - mapY * Math.sin(theta);
                            double rotatedY = mapX * Math.sin(theta) + mapY * Math.cos(theta);

                            // E. Convert to LatLng and Update
                            LatLng newLocation = calculateLatLngFromMeters(pdrOrigin, (float)rotatedX, (float)rotatedY);

                            if (trajectoryMapFragment != null) {
                                // Also correct arrow orientation
                                float correctedOri = orientation;
                                // Simple handling, if XY swapped, orientation might need -90deg, keep original for now to observe red line
                                trajectoryMapFragment.updateUserLocation(newLocation, correctedOri);
                            }

                            currentPdrLocation = newLocation;
                            totalDistanceMeters = Math.sqrt(rotatedX*rotatedX + rotatedY*rotatedY);

                            if (statusTextView != null && isAdded()) {
                                String distStr = totalDistanceMeters < 1000 ?
                                        String.format("%.1f m", totalDistanceMeters) :
                                        String.format("%.2f km", totalDistanceMeters / 1000.0);
                                statusTextView.setText("PDR(M" + AXIS_MODE + ")\nDist: " + distStr);
                            }
                        }
                    }
                    uiHandler.postDelayed(this, 100);
                }
            }
        };
        uiHandler.post(updateMapTask);
    }

    private LatLng calculateLatLngFromMeters(LatLng origin, float xMeters, float yMeters) {
        double lat = origin.latitude;
        double dLat = (yMeters / EARTH_RADIUS) * (180 / Math.PI);
        double cosLat = Math.cos(Math.toRadians(lat));
        if (Math.abs(cosLat) < 0.000001) cosLat = 0.000001;
        double dLon = (xMeters / (EARTH_RADIUS * cosLat)) * (180 / Math.PI);
        return new LatLng(lat + dLat, origin.longitude + dLon);
    }

    private void stopRecording() {
        isRecording = false;
        sensorFusion.stopRecording();
        uiHandler.removeCallbacks(updateMapTask);
        pdrOrigin = null;
        pdrStartOffset = null;
        currentPdrLocation = null;
        totalDistanceMeters = 0.0;
        startStopButton.setText("Start");
        startStopButton.setBackgroundColor(getResources().getColor(R.color.purple_500));
        markerButton.setEnabled(false);
        trajectoryIdInput.setEnabled(true);
        statusTextView.setText("Recording stopped");
        statusTextView.setBackgroundResource(R.drawable.status_background);
        if (getActivity() instanceof RecordingActivity) {
            ((RecordingActivity) getActivity()).showCorrectionScreen();
        }
    }

    @Override public void update(Object[] data) { }
    @Override public void onVenueSelected(String buildingId, String venueName) {
        this.selectedBuildingId = buildingId;
        this.selectedVenueName = venueName;
        if (isRecording && statusTextView != null) statusTextView.append(" [" + venueName + "]");
    }
}