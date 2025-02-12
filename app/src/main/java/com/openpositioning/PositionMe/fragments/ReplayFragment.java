package com.openpositioning.PositionMe.fragments;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
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
 The ReplayFragment takes sensor data (trajectory points, GNSS coordinates, etc.) 、
 from the FileFragment via Bundle arguments and replays a recorded path on a Google Map.
 It initializes a polyline to draw the trajectory and places markers—such as a replay arrow for the current position
 and a GNSS marker that updates in real time using sensor fusion data. The fragment features a SeekBar for manual navigation
 through the replay and provides controls for pausing/resuming, restarting, and jumping to the end of the trajectory.
 Additional UI elements include a map type spinner and indoor floor controls, while dynamic updates (like cumulative distance and elevation) a
 re calculated and displayed continuously. This design ensures an interactive, real-time visualization of the user’s past movement.
 */
public class ReplayFragment extends Fragment {

    private static final String TAG = "ReplayFragment";

    // UI components
    private Button stopButton;
    private Button cancelButton;    // Acts as Pause/Resume toggle.
    private Button restartButton;   // Restart button.
    private Button gotoEndButton;   // Go to End button.
    private ImageView recIcon;
    private SeekBar seekBar;        // For replay progress.
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
    private List<LatLng> trajectoryPoints; // Loaded from Fragment arguments.
    private Polyline trajectoryPolyline;   // Drawn polyline.
    private Marker replayArrow;            // Marker that moves along the path.
    private Marker gnssMarker;             // GNSS marker.

    // Map and indoor overlay
    private GoogleMap googleMap;
    public IndoorMapManager indoorMapManager;

    // Sensor fusion singleton
    private SensorFusion sensorFusion;

    // Replay loop variables
    private Handler replayHandler = new Handler();
    private CountDownTimer autoStop;       // If using a timer-based replay.
    private int currentPointIndex = 0;
    private boolean isReplaying = false;
    private boolean usingAutoStop = false;

    // Variables for computing distance
    private double distance = 0.0;
    private double previousLat = 0.0;
    private double previousLng = 0.0;

    // Flag for polyline colour
    private boolean isRed = true;

    // SharedPreferences for settings
    private SharedPreferences settings;

    // Pause/resume flag
    private boolean isPaused = false;
    private long autoStopRemainingTime = 0;

    // The current position (starting point)
    private LatLng currentLocation;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sensorFusion = SensorFusion.getInstance();
        settings = PreferenceManager.getDefaultSharedPreferences(getContext());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_replay, container, false);

        // Initialize UI components.
        stopButton = view.findViewById(R.id.stopButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        restartButton = view.findViewById(R.id.restartButton);
        gotoEndButton = view.findViewById(R.id.gotoEndButton);
        recIcon = view.findViewById(R.id.redDot);
        seekBar = view.findViewById(R.id.timeRemainingBar);
        elevation = view.findViewById(R.id.currentElevation);
        distanceTravelled = view.findViewById(R.id.currentDistanceTraveled);
        gnssError = view.findViewById(R.id.gnssError);
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnss = view.findViewById(R.id.gnssSwitch);
        switchColor = view.findViewById(R.id.lineColorButton);
        floorUpButton = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        autoFloor = view.findViewById(R.id.autoFloor);

        // Set default UI texts and states.
        gnssError.setVisibility(View.GONE);
        elevation.setText(getString(R.string.elevation, "0"));
        distanceTravelled.setText(getString(R.string.meter, "0"));
        distance = 0.0;
        previousLat = 0.0;
        previousLng = 0.0;

        // Stop button: exit replay mode.
        stopButton.setOnClickListener(v -> {
            if (autoStop != null) autoStop.cancel();
            replayHandler.removeCallbacks(replayRunnable);
            NavDirections action = ReplayFragmentDirections.actionReplayFragmentToFilesFragment();
            Navigation.findNavController(v).navigate(action);
        });

        // Cancel button: Pause/Resume toggle.
        cancelButton.setText("Pause");
        cancelButton.setOnClickListener(v -> {
            if (!isPaused) {
                isPaused = true;
                cancelButton.setText("Resume");
                if (usingAutoStop && autoStop != null) {
                    autoStop.cancel();
                } else {
                    replayHandler.removeCallbacks(replayRunnable);
                }
            } else {
                isPaused = false;
                cancelButton.setText("Pause");
                if (usingAutoStop) {
                    long limit = autoStopRemainingTime > 0
                            ? autoStopRemainingTime
                            : settings.getInt("split_duration", 30) * 60000L;
                    autoStop = new CountDownTimer(limit, 1000) {
                        @Override
                        public void onTick(long millisUntilFinished) {
                            autoStopRemainingTime = millisUntilFinished;
                            updateReplay();
                        }
                        @Override
                        public void onFinish() {
                            isReplaying = false;
                            NavDirections action = ReplayFragmentDirections.actionReplayFragmentToFilesFragment();
                            Navigation.findNavController(v).navigate(action);
                        }
                    }.start();
                } else {
                    replayHandler.postDelayed(replayRunnable, 200);
                }
            }
        });

        // Restart button: resets replay to beginning.
        restartButton.setOnClickListener(v -> {
            if (usingAutoStop && autoStop != null) {
                autoStop.cancel();
            } else {
                replayHandler.removeCallbacks(replayRunnable);
            }
            isReplaying = true;
            currentPointIndex = 0;
            distance = 0.0;
            if (trajectoryPoints != null && !trajectoryPoints.isEmpty()) {
                // Reset polyline and markers.
                trajectoryPolyline.setPoints(new ArrayList<>(trajectoryPoints.subList(0, 1)));
                LatLng startPoint = trajectoryPoints.get(0);
                if (replayArrow != null) replayArrow.setPosition(startPoint);
                if (gnssMarker != null) gnssMarker.setPosition(startPoint);
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startPoint, 18f));
            }
            seekBar.setProgress(0);
            if (isPaused) {
                isPaused = false;
                cancelButton.setText("Pause");
            }
            if (usingAutoStop) {
                long limit = settings.getInt("split_duration", 30) * 60000L;
                autoStopRemainingTime = limit;
                autoStop = new CountDownTimer(limit, 1000) {
                    @Override
                    public void onTick(long millisUntilFinished) {
                        autoStopRemainingTime = millisUntilFinished;
                        updateReplay();
                    }
                    @Override
                    public void onFinish() {
                        isReplaying = false;
                        NavDirections action = ReplayFragmentDirections.actionReplayFragmentToFilesFragment();
                        Navigation.findNavController(v).navigate(action);
                    }
                }.start();
            } else {
                replayHandler.postDelayed(replayRunnable, 200);
            }
        });

        // Go to End button: jump to last point.
        gotoEndButton.setOnClickListener(v -> {
            if (trajectoryPoints != null && !trajectoryPoints.isEmpty()) {
                if (usingAutoStop && autoStop != null) {
                    autoStop.cancel();
                } else {
                    replayHandler.removeCallbacks(replayRunnable);
                }
                currentPointIndex = trajectoryPoints.size() - 1;
                updateReplayTo(currentPointIndex);
                trajectoryPolyline.setPoints(new ArrayList<>(trajectoryPoints));
                seekBar.setProgress(currentPointIndex);
                isReplaying = false;
            }
        });

        // Setup map type spinner.
        setupMapDropdown();

        // Setup floor controls.
        setupFloorControls();

        // GNSS switch listener.
        gnss.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked) {
                float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
                LatLng gnssLocation = new LatLng(location[0], location[1]);
                gnssError.setVisibility(View.VISIBLE);
                LatLng comparePoint = (trajectoryPoints != null && !trajectoryPoints.isEmpty())
                        ? trajectoryPoints.get(currentPointIndex > 0 ? currentPointIndex - 1 : 0)
                        : new LatLng(0, 0);
                double error = UtilFunctions.distanceBetweenPoints(comparePoint, gnssLocation);
                gnssError.setText(String.format(getString(R.string.gnss_error) + "%.2fm", error));
                if (gnssMarker == null) {
                    gnssMarker = googleMap.addMarker(new MarkerOptions()
                            .title("GNSS Position")
                            .position(gnssLocation)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                            .zIndex(997f));
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

        // Line colour switching.
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

        // Start blinking animation on recIcon.
        blinkingRecording();

        // Initialize the map.
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
                    googleMap.setIndoorEnabled(true);

                    // Initialize indoor map manager.
                    indoorMapManager = new IndoorMapManager(googleMap);

                    // Load trajectory data from arguments.
                    loadTrajectoryData();

                    if (trajectoryPoints != null && !trajectoryPoints.isEmpty()) {
                        // Initialize the replay (polyline, markers, camera, etc.)
                        initializeReplay();
                        // Set up the SeekBar.
                        seekBar.setMax(trajectoryPoints.size() - 1);
                        seekBar.setProgress(0);

                        // Listen for SeekBar changes.
                        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                            @Override
                            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                                if (fromUser) {
                                    currentPointIndex = progress;
                                    updateReplayTo(progress);
                                }
                            }

                            @Override
                            public void onStartTrackingTouch(SeekBar seekBar) {
                                replayHandler.removeCallbacks(replayRunnable);
                            }

                            @Override
                            public void onStopTrackingTouch(SeekBar seekBar) {
                                updateReplayTo(seekBar.getProgress());
                                if (!usingAutoStop && !isPaused) {
                                    isReplaying = true;
                                    replayHandler.postDelayed(replayRunnable, 200);
                                }
                            }
                        });

                        // Start the replay loop (if not using autoStop mode).
                        if (!settings.getBoolean("split_trajectory", false)) {
                            usingAutoStop = false;
                            replayHandler.postDelayed(replayRunnable, 200);
                        } else {
                            usingAutoStop = true;
                            long limit = settings.getInt("split_duration", 30) * 60000L;
                            autoStopRemainingTime = limit;
                            autoStop = new CountDownTimer(limit, 1000) {
                                @Override
                                public void onTick(long millisUntilFinished) {
                                    autoStopRemainingTime = millisUntilFinished;
                                    updateReplay();
                                }
                                @Override
                                public void onFinish() {
                                    isReplaying = false;
                                    NavDirections action = ReplayFragmentDirections.actionReplayFragmentToFilesFragment();
                                    Navigation.findNavController(getView()).navigate(action);
                                }
                            }.start();
                        }
                    } else {
                        Log.e(TAG, "No trajectory data found!");
                    }
                    // Let the indoor map update its overlays first...
                    indoorMapManager.setCurrentLocation(currentLocation);
                    // Then indicate available indoor maps.
                    indoorMapManager.setIndicationOfIndoorMap();
                }
            });
        }

        return view;
    }

    /**
     * Initializes the replay: sets starting point, creates polyline and markers, and starts the replay loop.
     */
    private void initializeReplay() {
        // Set starting position.
        currentLocation = trajectoryPoints.get(0);
        // Create polyline with starting point and assign a high z-index (999f) so it appears above indoor overlays.
        PolylineOptions polylineOptions = new PolylineOptions()
                .add(currentLocation)
                .width(8f)
                .color(Color.RED)
                .zIndex(999f);
        trajectoryPolyline = googleMap.addPolyline(polylineOptions);
        // Create markers with high z-indices.
        gnssMarker = googleMap.addMarker(new MarkerOptions()
                .position(currentLocation)
                .title("GNSS Position")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                .zIndex(997f));
        replayArrow = googleMap.addMarker(new MarkerOptions()
                .position(currentLocation)
                .title("Replay Position")
                .flat(true)
                .icon(BitmapDescriptorFactory.fromBitmap(
                        UtilFunctions.getBitmapFromVector(getContext(), R.drawable.ic_baseline_navigation_24)))
                .zIndex(998f));
        // Center camera.
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 18f));
        // Reset replay index.
        currentPointIndex = 0;
        isReplaying = true;
    }

    /**
     * Updates the replay state by moving the replay arrow, updating indoor location,
     * polyline, seek bar, and computing distance.
     */
    private void updateReplay() {
        if (currentPointIndex < trajectoryPoints.size()) {
            LatLng nextPoint = trajectoryPoints.get(currentPointIndex);

            // Update replay arrow position and bearing.
            if (replayArrow != null) {
                replayArrow.setPosition(nextPoint);
                if (currentPointIndex > 0) {
                    LatLng prevPoint = trajectoryPoints.get(currentPointIndex - 1);
                    double dx = nextPoint.longitude - prevPoint.longitude;
                    double dy = nextPoint.latitude - prevPoint.latitude;
                    float bearing = (float) Math.toDegrees(Math.atan2(dy, dx));
                    replayArrow.setRotation(bearing);
                }
            }
            // Animate camera.
            googleMap.animateCamera(CameraUpdateFactory.newLatLng(nextPoint));

            // First, update indoor map manager so its overlays are drawn...
            if (indoorMapManager != null) {
                indoorMapManager.setCurrentLocation(nextPoint);
                Log.d("IndoorMapManager", "setCurrentLocation called with: " + nextPoint);
            } else {
                Log.e(TAG, "indoorMapManager is null!");
            }

            // Update cumulative distance.
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

            // Update GNSS marker if enabled.
            if (gnss.isChecked() && gnssMarker != null) {
                float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
                LatLng gnssLocation = new LatLng(location[0], location[1]);
                double errorDistance = UtilFunctions.distanceBetweenPoints(nextPoint, gnssLocation);
                gnssError.setText(String.format(getString(R.string.gnss_error) + "%.2fm", errorDistance));
                gnssMarker.setPosition(gnssLocation);
                gnssError.setVisibility(View.VISIBLE);
            }

            // Now extend polyline with new point so it is drawn above the indoor overlays.
            List<LatLng> polyPoints = trajectoryPolyline.getPoints();
            polyPoints.add(nextPoint);
            trajectoryPolyline.setPoints(polyPoints);

            // Update SeekBar progress.
            seekBar.setProgress(currentPointIndex);
            currentPointIndex++;

            // Schedule next update if replay is still active.
            if (!usingAutoStop && isReplaying) {
                replayHandler.postDelayed(replayRunnable, 200);
            }
        } else {
            isReplaying = false;
        }
    }

    /**
     * Runnable for the replay loop.
     */
    private final Runnable replayRunnable = new Runnable() {
        @Override
        public void run() {
            updateReplay();
        }
    };

    /**
     * Immediately jumps the replay to the specified index.
     */
    private void updateReplayTo(int index) {
        if (trajectoryPoints == null || trajectoryPoints.isEmpty() || index < 0 || index >= trajectoryPoints.size()) {
            return;
        }
        LatLng point = trajectoryPoints.get(index);
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
        List<LatLng> newPoints = new ArrayList<>(trajectoryPoints.subList(0, index + 1));
        trajectoryPolyline.setPoints(newPoints);
        seekBar.setProgress(index);
    }

    /**
     * Sets up the map type spinner.
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
     * Sets up the floor controls and auto-floor switch.
     */
    private void setupFloorControls() {
        setFloorButtonVisibility(View.VISIBLE);
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
     * Sets the visibility of floor control buttons and the auto-floor switch.
     */
    private void setFloorButtonVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        autoFloor.setVisibility(visibility);
    }

    /**
     * Starts a blinking animation on the recIcon.
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
     * Loads trajectory data passed via Fragment arguments.
     */
    @SuppressWarnings("unchecked")
    private void loadTrajectoryData() {
        if (getArguments() != null) {
            trajectoryPoints = getArguments().getParcelableArrayList("trajectoryPoints");
        }
        if (trajectoryPoints == null || trajectoryPoints.isEmpty()) {
            Log.e(TAG, "Trajectory data is empty or not received!");
        } else {
            Log.d(TAG, "Trajectory data loaded successfully! Points: " + trajectoryPoints.size());
        }
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
