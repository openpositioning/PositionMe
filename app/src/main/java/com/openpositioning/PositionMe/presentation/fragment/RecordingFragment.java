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
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.material.textfield.TextInputEditText;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.utils.BuildingPolygon;
import com.openpositioning.PositionMe.utils.GeometryUtils;

import java.util.Locale;
import java.util.Map;


public class RecordingFragment extends Fragment implements Observer, IndoorMapFragment.VenueSelectionCallback {


    private static final int AXIS_MODE = 1;


    private static final float DISTANCE_MULTIPLIER = 1.0f;

    private static final double ROTATION_FINE_TUNE = 0.0;

 

    private Button startStopButton, markerButton;
    private TextInputEditText trajectoryIdInput;
    private TextView statusTextView;

    private TrajectoryMapFragment trajectoryMapFragment;
    private SensorFusion sensorFusion;
    private boolean isRecording = false;

    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo biometricPromptInfo;
    private boolean biometricVerified = false;

    private Handler uiHandler = new Handler();
    private Runnable updateMapTask;
    private boolean uiUpdatesRunning = false;

    private String selectedBuildingId = null;
    private String selectedVenueName = null;

    // PDR Variables
    private LatLng pdrOrigin = null;
    private float[] pdrStartOffset = null;
    private LatLng currentPdrLocation = null;
    private LatLng currentDisplayLocation = null;
    private LatLng lastAbsoluteDistanceSample = null;
    private static final double EARTH_RADIUS = 6378137.0;
    private double totalDistanceMeters = 0.0;
    private double absoluteDistanceMeters = 0.0;

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
                updateRecordingStatus();
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
            if (!isRecording) {
                if (biometricVerified) {
                    startRecording();
                } else {
                    authenticateAndStart();
                }
            } else {
                stopRecording();
            }
        });

        initBiometric();

        markerButton.setOnClickListener(v -> {
            if (isRecording) {
                LatLng markerLocation = currentDisplayLocation != null ? currentDisplayLocation : currentPdrLocation;
                if (markerLocation != null) {
                    sensorFusion.addMarkerAt(markerLocation, resolveDisplayAltitudeMeters());
                    if (trajectoryMapFragment != null) {
                        trajectoryMapFragment.addMarkerToMap(markerLocation);
                    }
                    Toast.makeText(getContext(), "Marker Added", Toast.LENGTH_SHORT).show();
                }
            }
        });

        startUiUpdates();
    }

    private void initBiometric() {
        BiometricManager biometricManager = BiometricManager.from(requireContext());
        if (biometricManager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS) {
            biometricPrompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(requireContext()),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                            super.onAuthenticationError(errorCode, errString);
                            Log.w("RecordingFragment", "Biometric error: " + errString);
                            Toast.makeText(getContext(), "Fingerprint auth failed: " + errString, Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                            super.onAuthenticationSucceeded(result);
                            biometricVerified = true;
                            Toast.makeText(getContext(), "Fingerprint authentication succeeded", Toast.LENGTH_SHORT).show();
                            startRecording();
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            super.onAuthenticationFailed();
                            Log.d("RecordingFragment", "Biometric auth failed.");
                        }
                    });

            biometricPromptInfo = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("PositionMe Secure Recording")
                    .setSubtitle("Use fingerprint to authorize trajectory recording")
                    .setNegativeButtonText("Cancel")
                    .setDeviceCredentialAllowed(false)
                    .build();
        } else {
            biometricPrompt = null;
        }
    }

    private void authenticateAndStart() {
        if (biometricPrompt != null && biometricPromptInfo != null) {
            biometricPrompt.authenticate(biometricPromptInfo);
        } else {
            // Biometric unavailable, fallback with passwordless confirmation
            biometricVerified = true;
            startRecording();
        }
    }

    private void startRecording() {
        String id = "";
        if (trajectoryIdInput.getText() != null) {
            id = trajectoryIdInput.getText().toString().trim();
        }
        if (id.isEmpty()) {
            id = "Traj_" + System.currentTimeMillis();
        }

        Log.e("RecordingFragment", "Starting recording with dynamic origin policy: " + id);
        pdrOrigin = resolveRecordingOrigin();
        if (pdrOrigin != null) {
            sensorFusion.setStartGNSSLatitude(new float[]{(float) pdrOrigin.latitude, (float) pdrOrigin.longitude});
        }

        sensorFusion.startRecording(id);

        if (selectedVenueName != null) sensorFusion.setVenueName(selectedVenueName);
        if (selectedBuildingId != null) sensorFusion.setBuildingId(selectedBuildingId);

        isRecording = true;
        totalDistanceMeters = 0.0;
        absoluteDistanceMeters = 0.0;

        // Lock start point (Manual Origin)
        if (pdrOrigin != null) {
            currentPdrLocation = pdrOrigin;
            currentDisplayLocation = pdrOrigin;
            lastAbsoluteDistanceSample = pdrOrigin;

            // Record initial offset
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

        if (trajectoryMapFragment != null) {
            trajectoryMapFragment.detectCurrentFloorOnce();
        }

        updateRecordingStatus();
        statusTextView.setBackgroundResource(R.drawable.status_recording);

        startStopButton.setText("Stop");
        startStopButton.setBackgroundColor(getResources().getColor(R.color.ios_red));
        trajectoryIdInput.setEnabled(false);
        markerButton.setEnabled(true);

        startUiUpdates();
    }

    private void startUiUpdates() {
        if (uiUpdatesRunning) {
            return;
        }
        uiUpdatesRunning = true;

        updateMapTask = new Runnable() {
            @Override
            public void run() {
                if (isAdded()) {
                    Map<SensorTypes, float[]> sensorData = sensorFusion.getSensorValueMap();

                    if (sensorData != null) {
                        float orientation = sensorFusion.passOrientation();

                        // Keep GNSS/WiFi visible in initial screen even before pressing Start.
                        float[] gnssPos = sensorData.get(SensorTypes.GNSSLATLONG);
                        if (gnssPos != null && gnssPos[0] != 0) {
                            if (trajectoryMapFragment != null) {
                                trajectoryMapFragment.updateGNSS(new LatLng(gnssPos[0], gnssPos[1]));
                            }
                        }

                        float[] wifiPos = sensorData.get(SensorTypes.WIFI);
                        if (wifiPos != null && wifiPos[0] != 0 && wifiPos[1] != 0) {
                            if (trajectoryMapFragment != null) {
                                trajectoryMapFragment.updateWifi(new LatLng(wifiPos[0], wifiPos[1]));
                            }
                        }

                        if (isRecording) {
                            float[] pdrMovement = sensorData.get(SensorTypes.PDR);
                            float[] fusedPos = sensorData.get(SensorTypes.FUSED);

                            LatLng fusedLocation = null;
                            if (fusedPos != null && fusedPos[0] != 0 && fusedPos[1] != 0) {
                                fusedLocation = new LatLng(fusedPos[0], fusedPos[1]);
                            }

                            // PDR Trajectory Logic
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
                            currentPdrLocation = newLocation;
                            totalDistanceMeters = Math.sqrt(rotatedX*rotatedX + rotatedY*rotatedY);

                            if (trajectoryMapFragment != null) {
                                // Keep trajectory direction fully PDR-driven during recording.
                                LatLng primaryLocation = newLocation;
                                updateAbsoluteDistance(primaryLocation);
                                currentDisplayLocation = primaryLocation;
                                trajectoryMapFragment.updateUserLocation(primaryLocation, orientation);
                            }

                                if (statusTextView != null && isAdded()) {
                                    String venueSuffix = selectedVenueName != null ? "\n" + selectedVenueName : "";
                                    statusTextView.setText("Recording" + buildErrorStatusSuffix() + venueSuffix);
                                }
                            } else if (fusedLocation != null && trajectoryMapFragment != null) {
                                updateAbsoluteDistance(fusedLocation);
                                currentDisplayLocation = fusedLocation;
                                trajectoryMapFragment.updateUserLocation(fusedLocation, orientation);
                                if (statusTextView != null && isAdded()) {
                                    String venueSuffix = selectedVenueName != null ? "\n" + selectedVenueName : "";
                                    statusTextView.setText("Recording\nFusion locked" + buildErrorStatusSuffix() + venueSuffix);
                                }
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
        pdrOrigin = null;
        pdrStartOffset = null;
        currentPdrLocation = null;
        currentDisplayLocation = null;
        lastAbsoluteDistanceSample = null;
        totalDistanceMeters = 0.0;
        absoluteDistanceMeters = 0.0;
        startStopButton.setText("Start");
        startStopButton.setBackgroundColor(getResources().getColor(R.color.ios_blue));
        markerButton.setEnabled(false);
        trajectoryIdInput.setEnabled(true);
        statusTextView.setText("Recording stopped");
        statusTextView.setBackgroundResource(R.drawable.status_background);
        if (getActivity() instanceof RecordingActivity) {
            ((RecordingActivity) getActivity()).showCorrectionScreen();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (updateMapTask != null) {
            uiHandler.removeCallbacks(updateMapTask);
        }
        uiUpdatesRunning = false;
    }

    @Override public void update(Object[] data) { }
    @Override public void onVenueSelected(String buildingId, String venueName) {
        this.selectedBuildingId = buildingId;
        this.selectedVenueName = venueName;
        if (isRecording && statusTextView != null) {
            updateRecordingStatus();
        }
    }

    private LatLng resolveRecordingOrigin() {
        LatLng wifiLocation = sensorFusion.getLatLngWifiPositioning();
        boolean indoorBySelection = selectedBuildingId != null && !selectedBuildingId.isEmpty();
        if (wifiLocation != null && (indoorBySelection || isInsideKnownIndoorBuildings(wifiLocation))) {
            return wifiLocation;
        }

        float[] gnssCoords = sensorFusion.getGNSSLatitude(false);
        LatLng gnssLocation = null;
        if (gnssCoords[0] != 0 || gnssCoords[1] != 0) {
            gnssLocation = new LatLng(gnssCoords[0], gnssCoords[1]);
        }

        if (gnssLocation != null && !indoorBySelection && !isInsideKnownIndoorBuildings(gnssLocation)) {
            return gnssLocation;
        }

        if (wifiLocation != null) {
            return wifiLocation;
        }

        if (gnssLocation != null) {
            return gnssLocation;
        }

        float[] startCoords = sensorFusion.getGNSSLatitude(true);
        if (startCoords[0] != 0 || startCoords[1] != 0) {
            return new LatLng(startCoords[0], startCoords[1]);
        }

        return null;
    }

    private boolean isInsideKnownIndoorBuildings(@NonNull LatLng point) {
        return BuildingPolygon.inAnyKnownBuilding(point);
    }

    private LatLng blendLocations(LatLng primary, LatLng correction, double correctionWeight) {
        if (primary == null) {
            return correction;
        }
        if (correction == null) {
            return primary;
        }
        double w = Math.max(0.0, Math.min(0.25, correctionWeight));
        double lat = primary.latitude + w * (correction.latitude - primary.latitude);
        double lon = primary.longitude + w * (correction.longitude - primary.longitude);
        return new LatLng(lat, lon);
    }

    private float resolveDisplayAltitudeMeters() {
        float estimatedAltitude = sensorFusion.getEstimatedAbsoluteAltitude();
        if (!Float.isNaN(estimatedAltitude)) {
            return estimatedAltitude;
        }
        return Float.NaN;
    }

    private String buildErrorStatusSuffix() {
        return "\nErr  PDR: " + formatLocationError(resolvePdrLocation(), currentDisplayLocation)
                + "  GNSS: " + formatLocationError(resolveGnssLocation(), currentDisplayLocation)
                + "  WiFi: " + formatLocationError(resolveWifiLocation(), currentDisplayLocation);
    }

    private void updateAbsoluteDistance(LatLng currentLocation) {
        if (currentLocation == null) {
            return;
        }

        if (lastAbsoluteDistanceSample != null) {
            absoluteDistanceMeters += GeometryUtils.distanceBetween(lastAbsoluteDistanceSample, currentLocation);
        }
        lastAbsoluteDistanceSample = currentLocation;
    }

    private String formatDistance(double distanceMeters) {
        return distanceMeters < 1000
                ? String.format(Locale.US, "%.1f m", distanceMeters)
                : String.format(Locale.US, "%.2f km", distanceMeters / 1000.0);
    }

    private LatLng resolvePdrLocation() {
        return currentPdrLocation;
    }

    private LatLng resolveGnssLocation() {
        float[] gnssCoords = sensorFusion.getGNSSLatitude(false);
        if ((gnssCoords[0] == 0f && gnssCoords[1] == 0f)) {
            return null;
        }
        return new LatLng(gnssCoords[0], gnssCoords[1]);
    }

    private LatLng resolveWifiLocation() {
        return sensorFusion.getLatLngWifiPositioning();
    }

    private String formatLocationError(LatLng sourceLocation, LatLng referenceLocation) {
        if (sourceLocation == null || referenceLocation == null) {
            return "--";
        }

        double distanceMeters = GeometryUtils.distanceBetween(sourceLocation, referenceLocation);
        return distanceMeters < 1000
                ? String.format(Locale.US, "%.1f m", distanceMeters)
                : String.format(Locale.US, "%.2f km", distanceMeters / 1000.0);
    }

    private void updateRecordingStatus() {
        if (statusTextView == null) {
            return;
        }

        StringBuilder status = new StringBuilder("Recording");
        status.append(buildErrorStatusSuffix());
        if (selectedVenueName != null && !selectedVenueName.isEmpty()) {
            status.append("\n").append(selectedVenueName);
        }
        statusTextView.setText(status.toString());
    }
}

