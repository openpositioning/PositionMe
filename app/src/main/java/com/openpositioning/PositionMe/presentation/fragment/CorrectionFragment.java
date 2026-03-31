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

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.PathView;

/**
 * Allows the user to manually adjust step length after a recording session.
 */
public class CorrectionFragment extends Fragment {

    private static final String TAG = "CorrectionFragment";

    public GoogleMap mMap;
    private Button button;
    private final SensorFusion sensorFusion = SensorFusion.getInstance();
    private TextView averageStepLengthText;
    private EditText stepLengthInput;
    private float averageStepLength;
    private float newStepLength;
    private int secondPass = 0;
    private CharSequence changedText;
    private static float scalingRatio = 0f;
    private static LatLng start;
    private PathView pathView;

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

        Bundle args = getArguments();
        if (args != null) {
            hasVenue = args.getBoolean("has_venue", false);
            venueId = args.getString("venue_id", "");
            venueName = args.getString("venue_name", "");
            venueFloor = args.getString("venue_floor", "");

            if (hasVenue) {
                Log.d(TAG, "Venue context received: id=" + venueId
                        + ", name=" + venueName
                        + ", floor=" + venueFloor);
            } else {
                Log.d(TAG, "No venue selected; recording treated as outdoor");
            }
        }

        sensorFusion.sendTrajectoryToCloud();

        float[] startPosition = sensorFusion.getGNSSLatitude(true);

        SupportMapFragment supportMapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.map);

        if (supportMapFragment != null) {
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
                    mMap.addMarker(new MarkerOptions().position(start).title("Start Position"));

                    double zoom = Math.log(156543.03392f * Math.cos(startPosition[0] * Math.PI / 180)
                            * scalingRatio) / Math.log(2);
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(start, (float) zoom));
                }
            });
        }

        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        averageStepLengthText = view.findViewById(R.id.averageStepView);
        stepLengthInput = view.findViewById(R.id.inputStepLength);
        pathView = view.findViewById(R.id.pathView1);

        averageStepLength = sensorFusion.passAverageStepLength();
        averageStepLengthText.setText(getString(R.string.averageStepLgn) + ": "
                + String.format("%.2f", averageStepLength));

        stepLengthInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && changedText != null && changedText.length() > 0) {
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

        stepLengthInput.addTextChangedListener(new TextWatcher() {
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

        button = view.findViewById(R.id.correction_done);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (hasVenue) {
                    Log.d(TAG, "Trajectory finalized for venue=" + venueName + ", floor=" + venueFloor);
                } else {
                    Log.d(TAG, "Trajectory finalized without venue context");
                }
                ((RecordingActivity) requireActivity()).finishFlow();
            }
        });
    }

    public void setScalingRatio(float scalingRatio) {
        CorrectionFragment.scalingRatio = scalingRatio;
    }
}