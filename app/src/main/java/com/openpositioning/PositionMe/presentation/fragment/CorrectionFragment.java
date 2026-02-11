package com.openpositioning.PositionMe.presentation.fragment;

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

/**
 * CorrectionFragment - allows manual adjustments to the PDR after recording.
 * 🆕 Updated to support venue tracking (venue info logged but not yet saved to proto).
 */
public class CorrectionFragment extends Fragment {

    private static final String TAG = "CorrectionFragment";

    // Map variable
    public GoogleMap mMap;
    // Button to go to next
    private Button button;
    // Singleton SensorFusion class
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

    // 🆕 NEW: Venue information (不需要proto，只是记录日志)
    private boolean hasVenue = false;
    private String venueId = "";
    private String venueName = "";
    private String venueFloor = "";

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

        // 🆕 Read venue information (from Arguments)
        Bundle args = getArguments();
        if (args != null) {
            hasVenue = args.getBoolean("has_venue", false);
            venueId = args.getString("venue_id", "");
            venueName = args.getString("venue_name", "");
            venueFloor = args.getString("venue_floor", "");

            // Record venue information to the log
            if (hasVenue) {
                Log.d(TAG, "========================================");
                Log.d(TAG, "📍 Venue Information (for future proto integration):");
                Log.d(TAG, "   Venue ID: " + venueId);
                Log.d(TAG, "   Venue Name: " + venueName);
                Log.d(TAG, "   Floor: " + venueFloor);
                Log.d(TAG, "========================================");
                Log.d(TAG, "ℹ️ Note: Venue data will be saved to proto once proto is updated by team.");
            } else {
                Log.d(TAG, "📍 Outdoor trajectory (no venue)");
            }
        }

        // Send trajectory data to the cloud
        sensorFusion.sendTrajectoryToCloud();

        // Obtain start position
        float[] startPosition = sensorFusion.getGNSSLatitude(true);

        // Initialize map fragment
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

                // Add a marker at the start position
                start = new LatLng(startPosition[0], startPosition[1]);
                mMap.addMarker(new MarkerOptions().position(start).title("Start Position"));

                // Calculate zoom for demonstration
                double zoom = Math.log(156543.03392f * Math.cos(startPosition[0] * Math.PI / 180)
                        * scalingRatio) / Math.log(2);
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(start, (float) zoom));
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

        // Listen for ENTER key
        this.stepLengthInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                newStepLength = Float.parseFloat(changedText.toString());
                // Rescale path
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
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

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
                // 🆕 Log final venue information
                if (hasVenue) {
                    Log.d(TAG, "========================================");
                    Log.d(TAG, "✅ Trajectory finalized with venue:");
                    Log.d(TAG, "   Venue: " + venueName);
                    Log.d(TAG, "   Floor: " + venueFloor);
                    Log.d(TAG, "========================================");
                    Log.d(TAG, "⏳ Waiting for proto update to save venue data");
                } else {
                    Log.d(TAG, "✅ Outdoor trajectory finalized");
                }

                // Finish the recording flow
                ((RecordingActivity) requireActivity()).finishFlow();
            }
        });
    }

    public void setScalingRatio(float scalingRatio) {
        this.scalingRatio = scalingRatio;
    }

    // 🆕 Auxiliary methods for future proto integration.
    public boolean hasVenue() {
        return hasVenue;
    }

    public String getVenueId() {
        return venueId;
    }

    public String getVenueName() {
        return venueName;
    }

    public String getVenueFloor() {
        return venueFloor;
    }

    /**
     * 🆕 After a teammate updates the proto, call this method in sendTrajectoryToCloud() or a similar method.
     * Retrieve venue information and add it to Trajectory.Builder
     */
    public Bundle getVenueInfoForProto() {
        Bundle info = new Bundle();
        info.putBoolean("has_venue", hasVenue);
        info.putString("venue_id", venueId);
        info.putString("venue_name", venueName);
        info.putString("venue_floor", venueFloor);
        return info;
    }
}