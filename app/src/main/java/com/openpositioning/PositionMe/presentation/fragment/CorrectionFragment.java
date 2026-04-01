package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.PathView;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.List;
import android.widget.Switch;

/**
 * A simple {@link Fragment} subclass. Corrections Fragment is displayed after a recording session
 * is finished to enable manual adjustments to the PDR. The adjustments are not saved as of now.
 */
public class CorrectionFragment extends Fragment {

    //Map variable
    public GoogleMap mMap;
    //Button to go to next
    private Button button;
    //Singleton SensorFusion class
    private SensorFusion sensorFusion = SensorFusion.getInstance();
    private TextView averageStepLengthText;
    private EditText stepLengthInput;
    private float averageStepLength;
    private float newStepLength;
    private int secondPass = 0;
    private CharSequence changedText;
    private static float scalingRatio = 0f;
    private static LatLng start;
    private PathView pathView;

    // ✅ ADD THESE NEW VARIABLES FOR FUSED TRAJECTORY CORRECTION
    private float fusedOffsetLat = 0f;  // Translation offset in latitude
    private float fusedOffsetLng = 0f;  // Translation offset in longitude
    private float fusedRotationDegrees = 0f;  // Rotation in degrees
    private LatLng lastTouchPosition = null;  // For drag gesture
    private Switch togglePDR;
    private boolean showPDR = false;

    public CorrectionFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null && activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().hide();
        }
        View rootView = inflater.inflate(R.layout.fragment_correction, container, false);

        // Send trajectory data to the cloud
        sensorFusion.sendTrajectoryToCloud();

        //Obtain start position
        float[] startPosition = sensorFusion.getGNSSLatitude(true);

        // Initialize map fragment
        SupportMapFragment supportMapFragment=(SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.map);

        supportMapFragment.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(GoogleMap map) {
                mMap = map;
                mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                mMap.getUiSettings().setCompassEnabled(true);
                mMap.getUiSettings().setTiltGesturesEnabled(true);
                mMap.getUiSettings().setRotateGesturesEnabled(true);
                mMap.getUiSettings().setScrollGesturesEnabled(true);

                // Add a marker at the start position
                start = new LatLng(startPosition[0], startPosition[1]);
                mMap.addMarker(new MarkerOptions().position(start).title("Start Position"));

                // Calculate zoom for demonstration
                double zoom = Math.log(156543.03392f * Math.cos(startPosition[0] * Math.PI / 180)
                        * scalingRatio) / Math.log(2);
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(start, (float) zoom));

                // Draw the fused trajectory on the correction map
                updateFusedTrajectoryOnMap();

                // ✅ ADD TOUCH LISTENERS FOR INTERACTIVE CORRECTION
                setupMapTouchListeners();

            }
        });
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.averageStepLengthText = view.findViewById(R.id.averageStepView);
        this.stepLengthInput = view.findViewById(R.id.inputStepLength);
        this.pathView = view.findViewById(R.id.pathView1);

        togglePDR = view.findViewById(R.id.toggle_pdr);
        togglePDR.setChecked(false); // default OFF
        pathView.setVisibility(View.GONE);

        togglePDR.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showPDR = isChecked;

            if (showPDR) {
                pathView.setVisibility(View.VISIBLE);
            } else {
                pathView.setVisibility(View.GONE);
            }
        });

        averageStepLength = sensorFusion.passAverageStepLength();
        averageStepLengthText.setText(getString(R.string.averageStepLgn) + ": "
                + String.format("%.2f", averageStepLength));

        // Listen for ENTER key
        this.stepLengthInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                if (changedText != null && !changedText.toString().isEmpty()) {
                    newStepLength = Float.parseFloat(changedText.toString());
                } else {
                    return false;
                }
                float scalingFactor = newStepLength / averageStepLength;
                // Rescale path
                sensorFusion.redrawPath(scalingFactor);

                // Rescale FUSED trajectory
                sensorFusion.rescaleFusedTrajectory(scalingFactor);


                averageStepLengthText.setText(getString(R.string.averageStepLgn)
                        + ": " + String.format("%.2f", newStepLength));
                pathView.invalidate();

                // Update the fused trajectory on the map
                updateFusedTrajectoryOnMap();

                secondPass++;
                if (secondPass == 2) {
                    averageStepLength = newStepLength;
                    secondPass = 0;
                }
            }
            return false;
        });

        this.stepLengthInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count,int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before,int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                changedText = s;
            }
        });

        // Button to finalize corrections
        this.button = view.findViewById(R.id.correction_done);
        this.button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ((RecordingActivity) requireActivity()).finishFlow();
            }
        });
    }

    private void updateFusedTrajectoryOnMap() {
        if (mMap != null) {
            // Clear the map (removes all polylines and markers)
            mMap.clear();

            // Re-add the start marker
            mMap.addMarker(new MarkerOptions().position(start).title("Start Position"));

            // Redraw the fused trajectory with updated points
            List<LatLng> fusedPoints = sensorFusion.getFusedTrajectoryPoints();
            if (fusedPoints != null && fusedPoints.size() > 1) {
                // Apply transformations (rotation + translation)
                List<LatLng> transformedPoints = applyFusedTransformations(fusedPoints);

                mMap.addPolyline(new PolylineOptions()
                        .addAll(transformedPoints)
                        .color(Color.RED)
                        .width(6f));
            }
        }
    }

    /**
     * Set up touch listeners for interactive fused trajectory correction.
     * - Single finger drag: translate the fused trajectory
     * - Two finger rotation gesture: rotate the fused trajectory
     */
    private void setupMapTouchListeners() {
        if (mMap == null) return;

        mMap.setOnCameraMoveListener(new GoogleMap.OnCameraMoveListener() {
            @Override
            public void onCameraMove() {
                LatLng currentCenter = mMap.getCameraPosition().target;

                if (lastTouchPosition != null) {
                    fusedOffsetLat += (currentCenter.latitude - lastTouchPosition.latitude);
                    fusedOffsetLng += (currentCenter.longitude - lastTouchPosition.longitude);
                }

                // Always update rotation from map bearing
                fusedRotationDegrees = mMap.getCameraPosition().bearing;

                lastTouchPosition = currentCenter;
                updateFusedTrajectoryOnMap();
            }
        });

        mMap.setOnCameraIdleListener(new GoogleMap.OnCameraIdleListener() {
            @Override
            public void onCameraIdle() {
                lastTouchPosition = null;
            }
        });

        mMap.setOnMapLongClickListener(new GoogleMap.OnMapLongClickListener() {
            @Override
            public void onMapLongClick(LatLng latLng) {
                // Reset corrections
                fusedOffsetLat = 0f;
                fusedOffsetLng = 0f;
                fusedRotationDegrees = 0f;
                updateFusedTrajectoryOnMap();
            }
        });
    }

    /**
     * Apply rotation and translation transformations to the fused trajectory.
     *
     * @param originalPoints Original fused trajectory points
     * @return Transformed points with rotation and translation applied
     */
    private List<LatLng> applyFusedTransformations(List<LatLng> originalPoints) {
        if (originalPoints == null || originalPoints.isEmpty()) {
            return originalPoints;
        }

        List<LatLng> transformedPoints = new ArrayList<>();

        // ✅ Step 1: Normalize to first point
        LatLng firstPoint = originalPoints.get(0);

        // ✅ Step 2: Use center for rotation (better UX)
        double avgLat = 0;
        double avgLng = 0;

        for (LatLng p : originalPoints) {
            avgLat += p.latitude;
            avgLng += p.longitude;
        }

        avgLat /= originalPoints.size();
        avgLng /= originalPoints.size();

        LatLng rotationCenter = new LatLng(avgLat, avgLng);

        // ✅ Step 3: Convert rotation to radians
        double rotationRad = Math.toRadians(fusedRotationDegrees);
        double cosTheta = Math.cos(rotationRad);
        double sinTheta = Math.sin(rotationRad);

        for (LatLng point : originalPoints) {

            // 🔹 Normalize relative to first point (so both start same)
            double lat = point.latitude - firstPoint.latitude;
            double lng = point.longitude - firstPoint.longitude;

            // 🔹 Also shift rotation center to normalized space
            double centerLat = rotationCenter.latitude - firstPoint.latitude;
            double centerLng = rotationCenter.longitude - firstPoint.longitude;

            // 🔹 Translate to rotation center
            double relLat = lat - centerLat;
            double relLng = lng - centerLng;

            // 🔹 Apply rotation
            double rotatedLat = relLat * cosTheta - relLng * sinTheta;
            double rotatedLng = relLat * sinTheta + relLng * cosTheta;

            // 🔹 Translate back from center
            rotatedLat += centerLat;
            rotatedLng += centerLng;

            // 🔹 Move everything to GNSS start + offset
            double finalLat = start.latitude + rotatedLat + fusedOffsetLat;
            double finalLng = start.longitude + rotatedLng + fusedOffsetLng;

            transformedPoints.add(new LatLng(finalLat, finalLng));
        }

        return transformedPoints;
    }
    public void setScalingRatio(float scalingRatio) {
        this.scalingRatio = scalingRatio;
    }
}
