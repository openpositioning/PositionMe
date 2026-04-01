package com.openpositioning.PositionMe.presentation.fragment;

import static com.openpositioning.PositionMe.BuildConstants.DEBUG;

import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.TrajectoryValidator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Displayed after a recording session to show the trajectory summary with
 * start/end positions and test points on the Google Map.
 */
public class CorrectionFragment extends Fragment {

    private GoogleMap mMap;
    private Button button;
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
    private boolean isNormalMap = false;
    private IndoorMapManager indoorMapManager;

    /** Required empty public constructor. */
    public CorrectionFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null && activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().hide();
        }
        View rootView = inflater.inflate(R.layout.fragment_correction, container, false);

        validateAndUpload();

        float[] startPosition = sensorFusion.getGNSSLatitude(true);

        SupportMapFragment supportMapFragment = (SupportMapFragment)
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

                start = new LatLng(startPosition[0], startPosition[1]);
                long startTime = RecordingFragment.sStartTimeMs;

                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

                // Initialize indoor map manager and load floor shapes
                indoorMapManager = new IndoorMapManager(mMap);
                String selectedBuilding = sensorFusion.getSelectedBuildingId();
                if (selectedBuilding != null) {
                    indoorMapManager.forceSetBuilding(selectedBuilding);
                } else {
                    // Try auto-detect from start position
                    indoorMapManager.setCurrentLocation(start);
                }

                // Get trajectory and test points from RecordingFragment
                List<LatLng> trajectory = RecordingFragment.sLastTrajectoryPoints;
                List<RecordingFragment.TestPoint> testPoints = RecordingFragment.sLastTestPoints;
                LatLng endPos = RecordingFragment.sLastEndPosition;

                LatLngBounds.Builder bounds = new LatLngBounds.Builder();
                bounds.include(start);

                // Draw trajectory polyline on the map
                if (trajectory != null && trajectory.size() >= 2) {
                    mMap.addPolyline(new PolylineOptions()
                            .addAll(trajectory)
                            .color(Color.rgb(156, 39, 176)) // purple
                            .width(6f));
                    for (LatLng pt : trajectory) bounds.include(pt);
                }

                // Start position marker (green) — auto-show info window
                String startBuilding = RecordingFragment.sStartBuilding;
                String startFloor = RecordingFragment.sStartFloor;
                Marker startMarker = mMap.addMarker(new MarkerOptions()
                        .position(start)
                        .title("Start Position | " + sdf.format(new Date(startTime)))
                        .snippet((startBuilding != null ? startBuilding : "Outdoor")
                                + ", Floor: " + (startFloor != null ? startFloor : "--"))
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
                if (startMarker != null) startMarker.showInfoWindow();

                // Test point markers (red) — index starts at 2
                Marker lastEndMarker = null;
                if (testPoints != null) {
                    for (RecordingFragment.TestPoint tp : testPoints) {
                        LatLng pos = new LatLng(tp.lat, tp.lng);
                        bounds.include(pos);
                        int displayIndex = tp.index + 1; // shift: TP1 in recording → "Test Point 2" here
                        mMap.addMarker(new MarkerOptions()
                                .position(pos)
                                .title("Test Point " + displayIndex + " | " + sdf.format(new Date(tp.timestampMs)))
                                .snippet(tp.building + ", Floor: " + tp.floor)
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
                    }
                }

                // End position marker (blue) — auto-show info window
                if (endPos != null) {
                    bounds.include(endPos);
                    long endTime = RecordingFragment.sEndTimeMs;
                    String endBuilding = RecordingFragment.sEndBuilding;
                    String endFloor = RecordingFragment.sEndFloor;
                    lastEndMarker = mMap.addMarker(new MarkerOptions()
                            .position(endPos)
                            .title("End Position | " + sdf.format(new Date(endTime)))
                            .snippet((endBuilding != null ? endBuilding : "Outdoor")
                                    + ", Floor: " + (endFloor != null ? endFloor : "--"))
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
                    if (lastEndMarker != null) lastEndMarker.showInfoWindow();
                }

                // Fit camera to show all points
                try {
                    mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100));
                } catch (Exception e) {
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 19f));
                }

                // Clear static data to prevent memory leaks
                RecordingFragment.clearStaticData();
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

        averageStepLength = sensorFusion.passAverageStepLength();
        averageStepLengthText.setText(getString(R.string.averageStepLgn) + ": "
                + String.format("%.2f", averageStepLength));

        this.stepLengthInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                newStepLength = Float.parseFloat(changedText.toString());
                sensorFusion.redrawPath(newStepLength / averageStepLength);
                averageStepLengthText.setText(getString(R.string.averageStepLgn)
                        + ": " + String.format("%.2f", newStepLength));
                pathView.invalidate();

                secondPass++;
                if (secondPass == 2) {
                    averageStepLength = newStepLength;
                    secondPass = 0;
                }
            }
            return false;
        });

        this.stepLengthInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { changedText = s; }
        });

        this.button = view.findViewById(R.id.correction_done);
        this.button.setOnClickListener(v ->
                ((RecordingActivity) requireActivity()).finishFlow());

        // Map type toggle
        view.findViewById(R.id.mapTypeToggle).setOnClickListener(v -> {
            if (mMap == null) return;
            isNormalMap = !isNormalMap;
            if (isNormalMap) {
                mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                try {
                    mMap.setMapStyle(com.google.android.gms.maps.model.MapStyleOptions
                            .loadRawResourceStyle(requireContext(), R.raw.map_style_pink));
                } catch (Exception e) {
                    if (DEBUG) Log.w("CorrectionFragment", "Failed to apply pink map style", e);
                }
            } else {
                mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                mMap.setMapStyle(null);
            }
        });
    }

    /** Sets the scaling ratio for path rendering. */
    public void setScalingRatio(float scalingRatio) {
        this.scalingRatio = scalingRatio;
    }

    private void validateAndUpload() {
        TrajectoryValidator.ValidationResult result = sensorFusion.validateTrajectory();

        if (result.isClean()) {
            if (DEBUG) Log.i("CorrectionFragment", "Trajectory validation passed, uploading");
            sensorFusion.sendTrajectoryToCloud();
            return;
        }

        String summary = result.buildSummary();
        if (DEBUG) Log.w("CorrectionFragment", "Trajectory quality issues:\n" + summary);

        if (!result.isPassed()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.validation_error_title)
                    .setMessage(getString(R.string.validation_error_message, summary))
                    .setPositiveButton(R.string.upload_anyway, (dialog, which) ->
                            sensorFusion.sendTrajectoryToCloud())
                    .setNegativeButton(R.string.cancel_upload, (dialog, which) ->
                            dialog.dismiss())
                    .setCancelable(false)
                    .show();
        } else {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.validation_warning_title)
                    .setMessage(getString(R.string.validation_warning_message, summary))
                    .setPositiveButton(R.string.upload_anyway, (dialog, which) ->
                            sensorFusion.sendTrajectoryToCloud())
                    .setNegativeButton(R.string.cancel_upload, (dialog, which) ->
                            dialog.dismiss())
                    .setCancelable(false)
                    .show();
        }
    }
}
