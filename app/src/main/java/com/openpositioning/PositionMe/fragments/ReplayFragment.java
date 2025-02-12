package com.openpositioning.PositionMe.fragments;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.SeekBar; // Changed from ProgressBar to SeekBar
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * A merged ReplayFragment that combines the UI and functionality of the RecordingFragment
 * with the replay of a previously recorded trajectory.
 *
 * This version uses a SeekBar (instead of a ProgressBar) so you can drag forward and backward
 * through the replay.
 */
public class ReplayFragment extends Fragment {

    private static final String TAG = "ReplayFragment";

    // UI components
    private Button stopButton;
    private Button cancelButton;
    private ImageView recIcon;
    private SeekBar seekBar;  // Changed from ProgressBar to SeekBar
    private TextView elevation;
    private TextView distanceTravelled;
    private TextView gnssError;
    private Spinner switchMapSpinner;
    private Switch gnss;
    private Button switchColor;
    private FloatingActionButton floorUpButton;
    private FloatingActionButton floorDownButton;
    private Switch autoFloor;

    // Replay trajectory
    private List<LatLng> trajectoryPoints;  // Passed in as an argument (serialized)
    private Polyline trajectoryPolyline;    // Drawn polyline for the trajectory
    private Marker replayArrow;             // Marker that moves along the trajectory
    private Marker gnssMarker;              // GNSS marker shown when the GNSS switch is enabled

    // Map and indoor overlay
    private GoogleMap googleMap;
    public IndoorMapManager indoorMapManager;

    // Sensor fusion singleton
    private SensorFusion sensorFusion;

    // For updating UI/replay automatically (if needed)
    private Handler replayHandler = new Handler();
    private CountDownTimer autoStop; // Used when a max duration (split_trajectory) is set
    private int currentPointIndex = 0;
    private boolean isReplaying = false;
    private boolean usingAutoStop = false; // if true, the CountDownTimer drives the replay

    // Variables for computing distance
    private double distance = 0.0;
    private double previousLat = 0.0;
    private double previousLng = 0.0;

    // Flag for polyline colour
    private boolean isRed = true;

    // SharedPreferences for settings
    private SharedPreferences settings;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sensorFusion = SensorFusion.getInstance();
        settings = PreferenceManager.getDefaultSharedPreferences(getContext());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the merged layout
        View view = inflater.inflate(R.layout.fragment_replay, container, false);

        // Initialize UI components
        stopButton = view.findViewById(R.id.stopButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        recIcon = view.findViewById(R.id.redDot);
        seekBar = view.findViewById(R.id.timeRemainingBar); // Using SeekBar now
        elevation = view.findViewById(R.id.currentElevation);
        distanceTravelled = view.findViewById(R.id.currentDistanceTraveled);
        gnssError = view.findViewById(R.id.gnssError);
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnss = view.findViewById(R.id.gnssSwitch);
        switchColor = view.findViewById(R.id.lineColorButton);
        floorUpButton = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        autoFloor = view.findViewById(R.id.autoFloor);

        // Set default texts and hide GNSS error until needed
        gnssError.setVisibility(View.GONE);
        elevation.setText(getString(R.string.elevation, "0"));
        distanceTravelled.setText(getString(R.string.meter, "0"));
        distance = 0.0;
        previousLat = 0.0;
        previousLng = 0.0;

        // Set up button listeners for stopping and cancelling replay
        stopButton.setOnClickListener(v -> {
            if (autoStop != null) autoStop.cancel();
            replayHandler.removeCallbacks(replayRunnable);
            NavDirections action = ReplayFragmentDirections.actionReplayFragmentToFilesFragment();
            Navigation.findNavController(v).navigate(action);
        });

        cancelButton.setOnClickListener(v -> {
            if (autoStop != null) autoStop.cancel();
            replayHandler.removeCallbacks(replayRunnable);
            NavDirections action = ReplayFragmentDirections.actionReplayFragmentToFilesFragment();
            Navigation.findNavController(v).navigate(action);
        });

        // Set up map type dropdown and floor controls
        setupMapDropdown();
        setupFloorControls();

        // Set up GNSS switch listener
        gnss.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked) {
                float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
                LatLng gnssLocation = new LatLng(location[0], location[1]);
                gnssError.setVisibility(View.VISIBLE);
                double error = UtilFunctions.distanceBetweenPoints(
                        (trajectoryPoints != null && !trajectoryPoints.isEmpty()) ?
                                trajectoryPoints.get(currentPointIndex > 0 ? currentPointIndex - 1 : 0)
                                : new LatLng(0,0),
                        gnssLocation);
                gnssError.setText(String.format(getString(R.string.gnss_error) + "%.2fm", error));
                if (gnssMarker == null) {
                    gnssMarker = googleMap.addMarker(new MarkerOptions()
                            .title("GNSS Position")
                            .position(gnssLocation)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                } else {
                    gnssMarker.setPosition(gnssLocation);
                }
            } else {
                if (gnssMarker != null) {
                    gnssMarker.remove();
                    gnssMarker = null;
                }
                gnssError.setVisibility(View.GONE);
            }
        });

        // Set up line colour switching button
        switchColor.setOnClickListener(v -> {
            if (trajectoryPolyline != null) {
                if (isRed) {
                    switchColor.setBackgroundColor(Color.BLACK);
                    trajectoryPolyline.setColor(Color.BLACK);
                    isRed = false;
                } else {
                    switchColor.setBackgroundColor(Color.RED);
                    trajectoryPolyline.setColor(Color.RED);
                    isRed = true;
                }
            }
        });

        // Start the blinking animation on the recIcon
        blinkingRecording();

        // Initialize the map
        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager()
                .findFragmentById(R.id.replayMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(GoogleMap map) {
                    googleMap = map;
                    googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                    googleMap.getUiSettings().setCompassEnabled(true);
                    googleMap.getUiSettings().setZoomControlsEnabled(true);
                    googleMap.getUiSettings().setTiltGesturesEnabled(true);
                    googleMap.getUiSettings().setRotateGesturesEnabled(true);
                    googleMap.getUiSettings().setScrollGesturesEnabled(true);

                    // Initialize indoor map manager
                    indoorMapManager = new IndoorMapManager(googleMap);

                    // Load trajectory data from arguments
                    loadTrajectoryData();

                    if (trajectoryPoints != null && !trajectoryPoints.isEmpty()) {
                        // Draw the trajectory and add the replay arrow
                        drawTrajectoryPath();

                        // Initialize the replay position variables
                        LatLng startPoint = trajectoryPoints.get(0);
                        previousLat = startPoint.latitude;
                        previousLng = startPoint.longitude;
                        currentPointIndex = 0;
                        isReplaying = true;

                        // Set the maximum value of the seek bar based on trajectory length
                        seekBar.setMax(trajectoryPoints.size() - 1);
                        seekBar.setProgress(0);

                        // Set up the draggable functionality
                        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                            @Override
                            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                                if (fromUser) {
                                    // Update the replay position based on the new progress value
                                    currentPointIndex = progress;
                                    updateReplayTo(progress);
                                }
                            }

                            @Override
                            public void onStartTrackingTouch(SeekBar seekBar) {
                                // Optionally, pause any auto-replay updates while dragging
                                replayHandler.removeCallbacks(replayRunnable);
                            }

                            @Override
                            public void onStopTrackingTouch(SeekBar seekBar) {
                                // Optionally, resume auto-replay updates if desired
                                updateReplayTo(seekBar.getProgress());
                            }
                        });

                        // If using auto replay (via CountDownTimer), start it; otherwise, use the handler
                        if (settings.getBoolean("split_trajectory", false)) {
                            usingAutoStop = true;
                            long limit = settings.getInt("split_duration", 30) * 60000L;
                            // Optionally, update the seekBar max if you want time-based progress
                            autoStop = new CountDownTimer(limit, 1000) {
                                @Override
                                public void onTick(long millisUntilFinished) {
                                    // You could update the seekBar progress here if needed
                                    // For example: seekBar.setProgress(calculateProgress());
                                    updateReplay();
                                }

                                @Override
                                public void onFinish() {
                                    isReplaying = false;
                                    NavDirections action = ReplayFragmentDirections.actionReplayFragmentToFilesFragment();
                                    Navigation.findNavController(view).navigate(action);
                                }
                            }.start();
                        } else {
                            usingAutoStop = false;
                            replayHandler.postDelayed(replayRunnable, 200);
                        }
                    } else {
                        Log.e(TAG, "No trajectory data found!");
                    }
                }
            });
        }

        return view;
    }

    /**
     * Updates the replay state by moving the replay arrow to the specified index.
     * This method is called when the user drags the SeekBar.
     */
    private void updateReplayTo(int index) {
        if (trajectoryPoints == null || trajectoryPoints.isEmpty() || index < 0 || index >= trajectoryPoints.size()) {
            return;
        }
        LatLng point = trajectoryPoints.get(index);
        // Update the replay arrow's position and rotation
        if (replayArrow != null) {
            replayArrow.setPosition(point);
            if (index > 0) {
                LatLng prevPoint = trajectoryPoints.get(index - 1);
                double dx = point.longitude - prevPoint.longitude;
                double dy = point.latitude - prevPoint.latitude;
                float bearing = (float) Math.toDegrees(Math.atan2(dy, dx));
                replayArrow.setRotation(bearing);
            }
        }
        googleMap.animateCamera(CameraUpdateFactory.newLatLng(point));

        // **Update the polyline to include all points from the start up to the specified index**
        List<LatLng> newPoints = new ArrayList<>(trajectoryPoints.subList(0, index + 1));
        trajectoryPolyline.setPoints(newPoints);
    }


    /**
     * Runnable that calls updateReplay() for automatic replay.
     */
    private final Runnable replayRunnable = new Runnable() {
        @Override
        public void run() {
            updateReplay();
        }
    };

    /**
     * Regularly updates the replay if auto replay is active.
     * Also updates the SeekBar progress accordingly.
     */
    private void updateReplay() {
        if (currentPointIndex < trajectoryPoints.size()) {
            LatLng nextPoint = trajectoryPoints.get(currentPointIndex);

            // Update replay arrow position and rotation
            if (replayArrow != null) {
                replayArrow.setPosition(nextPoint);
            }
            if (currentPointIndex > 0) {
                LatLng prevPoint = trajectoryPoints.get(currentPointIndex - 1);
                double dx = nextPoint.longitude - prevPoint.longitude;
                double dy = nextPoint.latitude - prevPoint.latitude;
                float bearing = (float) Math.toDegrees(Math.atan2(dy, dx));
                replayArrow.setRotation(bearing);
            }
            googleMap.animateCamera(CameraUpdateFactory.newLatLng(nextPoint));

            // Update indoor overlay and cumulative distance
            indoorMapManager.setCurrentLocation(nextPoint);
            if (currentPointIndex == 0) {
                previousLat = nextPoint.latitude;
                previousLng = nextPoint.longitude;
            } else {
                LatLng prevPoint = trajectoryPoints.get(currentPointIndex - 1);
                double segmentDistance = UtilFunctions.distanceBetweenPoints(prevPoint, nextPoint);
                distance += segmentDistance;
            }
            distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));
            float elevationVal = sensorFusion.getElevation();
            elevation.setText(getString(R.string.elevation, String.format("%.1f", elevationVal)));

            // Update GNSS marker if enabled
            if (gnss.isChecked() && gnssMarker != null) {
                float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
                LatLng gnssLocation = new LatLng(location[0], location[1]);
                gnssError.setVisibility(View.VISIBLE);
                double errorDistance = UtilFunctions.distanceBetweenPoints(nextPoint, gnssLocation);
                gnssError.setText(String.format(getString(R.string.gnss_error) + "%.2fm", errorDistance));
                gnssMarker.setPosition(gnssLocation);
            }

            // **Update the polyline: add the new point so the red path grows**
            List<LatLng> polyPoints = trajectoryPolyline.getPoints();
            polyPoints.add(nextPoint);
            trajectoryPolyline.setPoints(polyPoints);

            // Update the SeekBar progress and increment the replay index
            seekBar.setProgress(currentPointIndex);
            currentPointIndex++;

            if (!usingAutoStop && isReplaying) {
                replayHandler.postDelayed(replayRunnable, 200);
            }
        } else {
            isReplaying = false;
        }
    }


    /**
     * Sets up the map type dropdown spinner.
     */
    private void setupMapDropdown() {
        String[] maps = new String[]{getString(R.string.hybrid), getString(R.string.normal), getString(R.string.satellite)};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, maps);
        switchMapSpinner.setAdapter(adapter);
        switchMapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0:
                        googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                    case 1:
                        googleMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                        break;
                    case 2:
                        googleMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                        break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
            }
        });
    }

    /**
     * Sets up the floor control buttons and auto-floor switch.
     */
    private void setupFloorControls() {
        setFloorButtonVisibility(View.GONE);
        floorUpButton.setOnClickListener(v -> {
            autoFloor.setChecked(false);
            indoorMapManager.increaseFloor();
        });
        floorDownButton.setOnClickListener(v -> {
            autoFloor.setChecked(false);
            indoorMapManager.decreaseFloor();
        });
    }

    /**
     * Sets the visibility of the floor control buttons and auto-floor switch.
     */
    private void setFloorButtonVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        autoFloor.setVisibility(visibility);
    }

    /**
     * Starts a blinking animation on the recIcon to indicate replay is active.
     */
    private void blinkingRecording() {
        Animation blinkingAnim = new AlphaAnimation(1, 0);
        blinkingAnim.setDuration(800);
        blinkingAnim.setInterpolator(new LinearInterpolator());
        blinkingAnim.setRepeatCount(Animation.INFINITE);
        blinkingAnim.setRepeatMode(Animation.REVERSE);
        recIcon.startAnimation(blinkingAnim);
    }

    /**
     * Loads the trajectory data (a list of LatLng points) passed in via Fragment arguments.
     */
    @SuppressWarnings("unchecked")
    private void loadTrajectoryData() {
        if (getArguments() != null) {
            trajectoryPoints = (ArrayList<LatLng>) getArguments().getSerializable("trajectoryPoints");
        }
        if (trajectoryPoints == null || trajectoryPoints.isEmpty()) {
            Log.e(TAG, "Trajectory data is empty or not received!");
        } else {
            Log.d(TAG, "Trajectory data loaded successfully! Points: " + trajectoryPoints.size());
        }
    }

    /**
     * Draws the stored trajectory on the map as a polyline and adds a GNSS marker and replay arrow.
     */
    private void drawTrajectoryPath() {
        if (trajectoryPoints == null || trajectoryPoints.isEmpty()) {
            return;
        }
        // Initialize the polyline with only the starting point
        PolylineOptions polylineOptions = new PolylineOptions()
                .add(trajectoryPoints.get(0))
                .width(8f)
                .color(Color.RED);
        trajectoryPolyline = googleMap.addPolyline(polylineOptions);

        // Place the GNSS marker at the starting point
        gnssMarker = googleMap.addMarker(new MarkerOptions()
                .position(trajectoryPoints.get(0))
                .title("GNSS Position")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));

        // Initialize the replay arrow marker (with your navigation icon)
        replayArrow = googleMap.addMarker(new MarkerOptions()
                .position(trajectoryPoints.get(0))
                .title("Replay Position")
                .flat(true)
                .icon(BitmapDescriptorFactory.fromBitmap(
                        UtilFunctions.getBitmapFromVector(getContext(), R.drawable.ic_baseline_navigation_24))));
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(trajectoryPoints.get(0), 18f));
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        replayHandler.removeCallbacks(replayRunnable);
        if (autoStop != null) {
            autoStop.cancel();
        }
    }
}
