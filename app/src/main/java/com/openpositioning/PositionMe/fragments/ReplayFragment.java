package com.openpositioning.PositionMe.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.openpositioning.PositionMe.BuildingPolygon;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.ServerCommunications;
import com.openpositioning.PositionMe.Traj;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import java.lang.Float;

/**
 * Fragment that replays a hardcoded trajectory on a Google Map.
 *
 * In this version the progress view is a scrubbable SeekBar.
 */
public class ReplayFragment extends Fragment implements OnMapReadyCallback {

    //==============================================================================================
    // Constants
    //==============================================================================================
    private static final String TAG = ReplayFragment.class.getSimpleName();
    private static final long REPLAY_INTERVAL_MS = 350;
    private static final float DEFAULT_ZOOM_LEVEL = 19.3f;
    private static final int[] MAP_TYPES = {
            GoogleMap.MAP_TYPE_NORMAL,
            GoogleMap.MAP_TYPE_HYBRID
    };
    private static final int CAMERA_PADDING = 100; // Padding (in pixels) for camera bounds

    //==============================================================================================
    // Map & Replay Members
    //==============================================================================================
    private GoogleMap googleMap;
    private Polyline replayPolyline;
    private Marker currentPositionMarker;
    private List<LatLng> trajectoryPoints;
    private List<LatLng> drawnPoints;
    private Handler handler;

    private Timer replayTimer;
    private TimerTask replayTimerTask;
    private int currentPointIndex = 0;
    private boolean replayActive = false;

    //==============================================================================================
    // File Processing Members
    //==============================================================================================
    private int position;
    private ServerCommunications serverCommunications;
    private List<File> localTrajectories;

    //==============================================================================================
    // UI Components
    //==============================================================================================
    // Changed from ProgressBar to SeekBar so that users can scrub the progress.
    private SeekBar replaySeekBar;
    private ImageButton playPauseButton;
    private ImageButton restartButton;
    private ImageButton goToEndButton;
    private SwitchMaterial indoorMapSwitch;
    private ChipGroup mapTypeChipGroup;
    private Chip chipNormal, chipHybrid;
    private int currentMapTypeIndex = 0; // 0 = Normal, 1 = Hybrid

    //==============================================================================================
    // Indoor Map Members
    //==============================================================================================
    private IndoorMapManager indoorMapManager;
    private int currentFloor = 1;

    //==============================================================================================
    // Lifecycle Methods
    //==============================================================================================
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Retrieve position of the file
        position = ReplayFragmentArgs.fromBundle(getArguments()).getPosition();
        // Get communication class
        serverCommunications = new ServerCommunications(getActivity());
        // Load local trajectories
        localTrajectories = Stream.of(getActivity().getFilesDir().listFiles((file, name) -> name.contains("trajectory_") && name.endsWith(".txt")))
                .filter(file -> !file.isDirectory())
                .collect(Collectors.toList());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        getActivity().setTitle("Trajectory Replay");
        return inflater.inflate(R.layout.fragment_replay, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initUI(view);
        if (position == -1) {
            createHardcodedTrajectory();
        } else {
            trajectoryPoints = serverCommunications.retrieveLocalTrajectory(localTrajectories.get(position));
        }
        SupportMapFragment mapFragment =
                (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.replayMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        this.googleMap = map;
        Log.d(TAG, "onMapReady called!");

        indoorMapManager = new IndoorMapManager(googleMap);

        if (trajectoryPoints != null && !trajectoryPoints.isEmpty()) {
            // Initialize polyline and marker
            replayPolyline = googleMap.addPolyline(new PolylineOptions().color(Color.RED).zIndex(10));
            drawnPoints = new ArrayList<>();
            currentPositionMarker = googleMap.addMarker(new MarkerOptions()
                    .position(trajectoryPoints.get(0))
                    .icon(bitmapDescriptorFromVector(getContext(), R.drawable.ic_baseline_navigation_24))
                    .anchor(0.5f, 0.5f)
                    .zIndex(10));

            // Center the camera on the starting point
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(trajectoryPoints.get(0), DEFAULT_ZOOM_LEVEL));
            updateIndoorMapState();
        }

        // Set the SeekBar’s maximum to the number of trajectory points.
        if (replaySeekBar != null && trajectoryPoints != null) {
            replaySeekBar.setMax(trajectoryPoints.size());
        }
        setMapType(currentMapTypeIndex);
    }

    @Override
    public void onPause() {
        super.onPause();
        pauseReplay();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (replayTimer != null) {
            replayTimer.cancel();
        }
    }

    //==============================================================================================
    // UI Initialization
    //==============================================================================================
    private void initUI(@NonNull View view) {
        // Make sure your layout XML contains a SeekBar with id "replaySeekBar"
        replaySeekBar = view.findViewById(R.id.replaySeekBar);
        playPauseButton = view.findViewById(R.id.playPauseButton);
        restartButton = view.findViewById(R.id.restartButton);
        goToEndButton = view.findViewById(R.id.goToEndButton);
        indoorMapSwitch = view.findViewById(R.id.indoorMapSwitch);
        // Default: indoor map is on
        indoorMapSwitch.setChecked(true);

        mapTypeChipGroup = view.findViewById(R.id.mapTypeChipGroup);
        chipNormal = view.findViewById(R.id.chipNormal);
        chipHybrid = view.findViewById(R.id.chipHybrid);

        // Set initial map type chip based on currentMapTypeIndex
        if (currentMapTypeIndex == 0) {
            chipNormal.setChecked(true);
        } else {
            chipHybrid.setChecked(true);
        }

        // Listen for map type chip changes
        mapTypeChipGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.chipNormal) {
                currentMapTypeIndex = 0;
            } else if (checkedId == R.id.chipHybrid) {
                currentMapTypeIndex = 1;
            }
            setMapType(currentMapTypeIndex);
        });

        // Indoor map switch listener
        indoorMapSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> updateIndoorMapState());

        // Playback button listeners
        playPauseButton.setOnClickListener(v -> {
            if (replayActive) {
                pauseReplay();
            } else {
                startReplay();
            }
        });
        restartButton.setOnClickListener(v -> restartReplay());
        goToEndButton.setOnClickListener(v -> goToEnd());

        // Set up the SeekBar for scrubbing
        replaySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            // While the user is scrubbing, update the replay state to show the corresponding point.
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && trajectoryPoints != null && !trajectoryPoints.isEmpty()) {
                    updateReplayToPoint(progress);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Pause the replay when the user starts scrubbing.
                pauseReplay();
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Optionally, you can resume the replay here.
            }
        });
    }

    //==============================================================================================
    // Replay Control Methods
    //==============================================================================================
    /**
     * Starts the replay. If the replay is already finished, it restarts from the beginning.
     */
    private void startReplay() {
        indoorMapSwitch.setChecked(false);
        // If we have reached (or exceeded) the end, restart the replay.
        if (currentPointIndex >= trajectoryPoints.size()) {
            restartReplay();
        }

        // If replay is already active, do nothing.
        if (replayActive) {
            return;
        }

        replayActive = true;
        playPauseButton.setImageResource(android.R.drawable.ic_media_pause);

        // Ensure indoor map overlay is enabled during replay.
        indoorMapSwitch.setChecked(true);

        // Create and schedule the replay timer task.
        replayTimer = new Timer();
        replayTimerTask = new TimerTask() {
            @Override
            public void run() {
                // TimerTask runs on a background thread, so post UI updates to the main thread.
                handler.post(() -> {
                    if (currentPointIndex < trajectoryPoints.size()) {
                        LatLng currentPoint = trajectoryPoints.get(currentPointIndex);
                        drawnPoints.add(currentPoint);
                        replayPolyline.setPoints(drawnPoints);

                        // Update marker rotation if not at the first point.
                        if (currentPointIndex > 0) {
                            LatLng previousPoint = trajectoryPoints.get(currentPointIndex - 1);
                            float bearing = calculateBearing(previousPoint, currentPoint);
                            currentPositionMarker.setRotation(bearing);
                        }
                        currentPositionMarker.setPosition(currentPoint);
                        googleMap.animateCamera(CameraUpdateFactory.newLatLng(currentPoint));
                        updateIndoorMapForPoint(currentPoint);

                        // Update the SeekBar progress and internal counter.
                        replaySeekBar.setProgress(currentPointIndex + 1);
                        currentPointIndex++;
                    } else {
                        finalizeReplay();
                        // Cancel the timer when the replay is finished.
                        if (replayTimer != null) {
                            replayTimer.cancel();
                        }
                    }
                });
            }
        };
        // Schedule the task to run immediately and then every REPLAY_INTERVAL_MS milliseconds.
        replayTimer.schedule(replayTimerTask, 0, REPLAY_INTERVAL_MS);
    }

    /** Pauses the replay if it is running. */
    private void pauseReplay() {
        if (!replayActive) {
            return;
        }
        replayActive = false;
        playPauseButton.setImageResource(android.R.drawable.ic_media_play);
        if (replayTimerTask != null) {
            replayTimerTask.cancel();
        }
        if (replayTimer != null) {
            replayTimer.cancel();
        }
    }

    /** Resets the replay to the starting point. */
    private void restartReplay() {
        pauseReplay();
        currentPointIndex = 0;
        replaySeekBar.setProgress(0);

        if (replayPolyline != null) {
            replayPolyline.setPoints(new ArrayList<>());
        }
        if (drawnPoints != null) {
            drawnPoints.clear();
        }
        if (currentPositionMarker != null && !trajectoryPoints.isEmpty()) {
            currentPositionMarker.setPosition(trajectoryPoints.get(0));
        }
        if (googleMap != null && !trajectoryPoints.isEmpty()) {
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(trajectoryPoints.get(0), DEFAULT_ZOOM_LEVEL));
        }
        updateIndoorMapState();
    }

    /**
     * Skips the replay to the end and zooms out to show the full trajectory.
     */
    private void goToEnd() {
        pauseReplay();
        currentPointIndex = trajectoryPoints.size();
        replaySeekBar.setProgress(currentPointIndex);

        if (!trajectoryPoints.isEmpty()) {
            if (replayPolyline != null) {
                replayPolyline.setPoints(trajectoryPoints);
            }
            if (currentPositionMarker != null) {
                currentPositionMarker.setPosition(trajectoryPoints.get(trajectoryPoints.size() - 1));
            }
        }
        updateIndoorMapState();
        zoomToBounds(trajectoryPoints);
    }

    /**
     * Called when the replay reaches the final point.
     */
    private void finalizeReplay() {
        replayActive = false;
        playPauseButton.setImageResource(android.R.drawable.ic_media_play);
        LatLng lastPoint = trajectoryPoints.get(trajectoryPoints.size() - 1);
        updateIndoorMapForPoint(lastPoint);
        zoomToBounds(trajectoryPoints);
    }

    /**
     * Helper method to animate the camera to include all given points.
     */
    private void zoomToBounds(@NonNull List<LatLng> points) {
        LatLngBounds.Builder builder = new LatLngBounds.Builder();
        for (LatLng point : points) {
            builder.include(point);
        }
        googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), CAMERA_PADDING));
    }

    //==============================================================================================
    // Indoor Map Handling Methods
    //==============================================================================================
    /**
     * Updates the indoor map overlay based on the given point.
     */
    private void updateIndoorMapForPoint(@NonNull LatLng point) {
        if (indoorMapManager == null) {
            return;
        }
        if (isInKnownBuilding(point)) {
            indoorMapManager.setCurrentLocation(point);
            indoorMapManager.setCurrentFloor(currentFloor, false);
        } else {
            // Clear the indoor map overlay if outside known areas.
            indoorMapManager.setCurrentLocation(new LatLng(0, 0));
        }
    }

    /**
     * Updates the indoor map overlay based on the current marker position and switch state.
     */
    private void updateIndoorMapState() {
        if (googleMap == null || trajectoryPoints == null || trajectoryPoints.isEmpty()) {
            return;
        }
        // Use current marker position or fallback to starting point.
        LatLng currentPosition = (currentPositionMarker != null)
                ? currentPositionMarker.getPosition()
                : trajectoryPoints.get(0);
        // Only update if the replay is not active.
        if (!replayActive) {
            if (indoorMapSwitch.isChecked()) {
                updateIndoorMapForPoint(currentPosition);
            } else if (indoorMapManager != null) {
                indoorMapManager.setCurrentLocation(new LatLng(0, 0));
            }
        }
    }

    /**
     * Checks whether the given point is inside a known building.
     */
    private boolean isInKnownBuilding(@NonNull LatLng point) {
        return BuildingPolygon.inNucleus(point) || BuildingPolygon.inLibrary(point);
    }

    //==============================================================================================
    // Utility Methods
    //==============================================================================================
    /**
     * Calculates the bearing (in degrees) between two LatLng points.
     */
    private float calculateBearing(LatLng from, LatLng to) {
        double deltaLng = Math.toRadians(to.longitude - from.longitude);
        double fromLat = Math.toRadians(from.latitude);
        double toLat = Math.toRadians(to.latitude);

        double y = Math.sin(deltaLng) * Math.cos(toLat);
        double x = Math.cos(fromLat) * Math.sin(toLat)
                - Math.sin(fromLat) * Math.cos(toLat) * Math.cos(deltaLng);
        return (float) ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360);
    }

    /**
     * Converts a vector drawable resource into a BitmapDescriptor for map markers.
     */
    private BitmapDescriptor bitmapDescriptorFromVector(Context context, int vectorResId) {
        Drawable vectorDrawable = ContextCompat.getDrawable(context, vectorResId);
        if (vectorDrawable == null) {
            Log.e(TAG, "Resource not found: " + vectorResId);
            return null;
        }
        vectorDrawable.setBounds(0, 0, vectorDrawable.getIntrinsicWidth(), vectorDrawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(vectorDrawable.getIntrinsicWidth(),
                vectorDrawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        vectorDrawable.draw(canvas);
        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    //==============================================================================================
    // Map Type Handling
    //==============================================================================================
    /**
     * Sets the map type according to the given index.
     */
    private void setMapType(int mapTypeIndex) {
        if (googleMap == null) {
            return;
        }
        if (mapTypeIndex < 0 || mapTypeIndex >= MAP_TYPES.length) {
            mapTypeIndex = 0; // Fallback to default
        }
        googleMap.setMapType(MAP_TYPES[mapTypeIndex]);
    }

    //==============================================================================================
    // Trajectory Initialization
    //==============================================================================================
    /**
     * Creates a hardcoded trajectory for replay.
     */
    private void createHardcodedTrajectory() {
        trajectoryPoints = new ArrayList<>();
        trajectoryPoints.add(new LatLng(55.92320, -3.17415));
        trajectoryPoints.add(new LatLng(55.92315, -3.17415));
        trajectoryPoints.add(new LatLng(55.92315, -3.17415));
        trajectoryPoints.add(new LatLng(55.92305, -3.17415));
        trajectoryPoints.add(new LatLng(55.92295, -3.17405));
        trajectoryPoints.add(new LatLng(55.92285, -3.17395));
        trajectoryPoints.add(new LatLng(55.92275, -3.17405));
        trajectoryPoints.add(new LatLng(55.92275, -3.17415));
        trajectoryPoints.add(new LatLng(55.92275, -3.17425));
        trajectoryPoints.add(new LatLng(55.92275, -3.17435));
        trajectoryPoints.add(new LatLng(55.92275, -3.17445));
        trajectoryPoints.add(new LatLng(55.92275, -3.1746));
        trajectoryPoints.add(new LatLng(55.92275, -3.1746));
        trajectoryPoints.add(new LatLng(55.92275, -3.1747));
        trajectoryPoints.add(new LatLng(55.92275, -3.1748));
        trajectoryPoints.add(new LatLng(55.92280, -3.17485));
        trajectoryPoints.add(new LatLng(55.92285, -3.17485));
        trajectoryPoints.add(new LatLng(55.92285, -3.1749));
        trajectoryPoints.add(new LatLng(55.92290, -3.1749));
        trajectoryPoints.add(new LatLng(55.92290, -3.17495));
        trajectoryPoints.add(new LatLng(55.92290, -3.1750));
    }

    //==============================================================================================
    // Scrubbing Progress Bar
    //==============================================================================================
    /**
     * Updates the replay to show the state at a given point in the trajectory.
     * This method updates the polyline, marker, camera, and indoor map.
     *
     * @param index the trajectory point index to move to.
     */
    /**
     * Updates the replay to show the state at a given point in the trajectory.
     * This method updates the polyline, marker, camera, and indoor map.
     *
     * @param index the trajectory point index to move to.
     */
    private void updateReplayToPoint(int index) {
        if (trajectoryPoints == null || trajectoryPoints.isEmpty()) {
            return;
        }
        int safeIndex = Math.min(index, trajectoryPoints.size());
        List<LatLng> partialPoints = new ArrayList<>();
        for (int i = 0; i < safeIndex; i++) {
            partialPoints.add(trajectoryPoints.get(i));
        }

        // *** FIX: Update drawnPoints to match the new partial points ***
        if (drawnPoints == null) {
            drawnPoints = new ArrayList<>();
        }
        drawnPoints.clear();
        drawnPoints.addAll(partialPoints);

        if (replayPolyline != null) {
            replayPolyline.setPoints(partialPoints);
        }
        if (currentPositionMarker != null) {
            // Set marker to the last point in the partial list.
            LatLng markerPosition = trajectoryPoints.get(Math.max(0, safeIndex - 1));
            currentPositionMarker.setPosition(markerPosition);
            if (safeIndex > 1) {
                float bearing = calculateBearing(trajectoryPoints.get(safeIndex - 2), markerPosition);
                currentPositionMarker.setRotation(bearing);
            }
            googleMap.animateCamera(CameraUpdateFactory.newLatLng(markerPosition));
            updateIndoorMapForPoint(markerPosition);
        }
        // Update the SeekBar progress and the internal current point index.
        replaySeekBar.setProgress(safeIndex);
        currentPointIndex = safeIndex;
    }
}