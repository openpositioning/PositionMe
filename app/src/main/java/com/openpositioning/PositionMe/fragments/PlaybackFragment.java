package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.dataParser.GnssData;
import com.openpositioning.PositionMe.dataParser.PdrData;
import com.openpositioning.PositionMe.dataParser.TrajectoryData;
import com.openpositioning.PositionMe.dataParser.TrajectoryParser;


import java.util.ArrayList;
import java.util.List;

/**
 * PlaybackFragment is used to replay previously recorded trajectory data.
 * It generates a replay trajectory based on the recorded points and dynamically displays it on the map.
 * When the playback point enters a building, the corresponding indoor floor map is shown using NucleusBuildingManager.
 */
public class PlaybackFragment extends Fragment implements OnMapReadyCallback {

    // UI components
    private GoogleMap mMap;
    private Spinner mapSpinner;
    private Switch autoFloorSwitch;
    private FloatingActionButton floorUpBtn, floorDownBtn;
    private ProgressBar playbackProgressBar;
    private Button playPauseBtn, restartBtn, goToEndBtn, exitBtn;

    // Trajectory playback variables
    private List<LatLng> recordedTrajectory = new ArrayList<>();
    private Polyline replayPolyline;
    private Marker replayMarker;
    private Handler replayHandler;
    private Runnable playbackRunnable;
    private int currentIndex = 0;
    private boolean isPlaying = false; // for Play/Pause functionality
    private static final long PLAYBACK_INTERVAL_MS = 500;

    // Indoor map manager
    private NucleusBuildingManager nucleusBuildingManager;

    // Manages overlaying of the indoor maps
    public IndoorMapManager indoorMapManager;

    // Trajectory ID passed from another fragment
    private String trajectoryId;

    // GNSS trajectory data
    private List<LatLng> gnssTrajectory = new ArrayList<>();
    private Marker gnssMarker;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate the fragment_playback layout
        return inflater.inflate(R.layout.fragment_playback, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // 1) Retrieve arguments from Navigation
        if (getArguments() != null) {
            PlaybackFragmentArgs args = PlaybackFragmentArgs.fromBundle(getArguments());
            trajectoryId = args.getTrajectoryId();
        }

        // 2) Initialize UI references
        mapSpinner = view.findViewById(R.id.mapSwitchSpinner_playback);
        autoFloorSwitch = view.findViewById(R.id.autoFloorSwitch_playback);
        floorUpBtn = view.findViewById(R.id.floorUpButton_playback);
        floorDownBtn = view.findViewById(R.id.floorDownButton_playback);
        playbackProgressBar = view.findViewById(R.id.playbackProgressBar);
        playPauseBtn = view.findViewById(R.id.playPauseButton);
        restartBtn = view.findViewById(R.id.restartButton);
        goToEndBtn = view.findViewById(R.id.goToEndButton);
        exitBtn = view.findViewById(R.id.exitButton);

        // 3) Initialize handler for playback
        replayHandler = new Handler(Looper.getMainLooper());

        // 4) Get the Map Fragment
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.replayMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        // 5) Set up control buttons and map spinner
        setupControlButtons();
        setupMapSpinner();
    }

    /**
     * Set up control buttons: Play/Pause, Restart, Go to End, Exit.
     */
    private void setupControlButtons() {
        // Play / Pause button
        playPauseBtn.setOnClickListener(v -> {
            if (!isPlaying) {
                // Start or resume playback
                isPlaying = true;
                playPauseBtn.setText("Pause");
                replayHandler.post(playbackRunnable);
            } else {
                // Pause playback
                isPlaying = false;
                playPauseBtn.setText("Play");
                replayHandler.removeCallbacks(playbackRunnable);
            }
        });

        // Restart button
        restartBtn.setOnClickListener(v -> {
            // Stop current playback
            isPlaying = false;
            replayHandler.removeCallbacks(playbackRunnable);

            // Reset playback
            currentIndex = 0;
            playPauseBtn.setText("Play");
            playbackProgressBar.setProgress(0);
            // Clear polyline
            if (replayPolyline != null) {
                replayPolyline.setPoints(new ArrayList<>());
            }
            if (replayMarker != null && !recordedTrajectory.isEmpty()) {
                replayMarker.setPosition(recordedTrajectory.get(0));
            }
        });

        // Go to End button
        goToEndBtn.setOnClickListener(v -> {
            if (!recordedTrajectory.isEmpty()) {
                // Stop playback
                isPlaying = false;
                replayHandler.removeCallbacks(playbackRunnable);

                // Jump to the last point
                currentIndex = recordedTrajectory.size() - 1;
                List<LatLng> newPoints = new ArrayList<>(recordedTrajectory);
                replayPolyline.setPoints(newPoints);
                replayMarker.setPosition(recordedTrajectory.get(currentIndex));
                playbackProgressBar.setProgress(100);
            }
        });

        // Exit button
        exitBtn.setOnClickListener(v -> {
            // Navigate back to the previous screen
            NavHostFragment.findNavController(this).popBackStack();
        });
    }

    /**
     * Set up the map spinner to switch map types (Hybrid, Normal, Satellite).
     */
    private void setupMapSpinner() {
        String[] maps = new String[]{"Hybrid", "Normal", "Satellite"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, maps);
        mapSpinner.setAdapter(adapter);

        mapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                if (mMap == null) return;
                switch (position) {
                    case 0:
                        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                    case 1:
                        mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                        break;
                    case 2:
                        mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                        break;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                if (mMap != null) {
                    mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                }
            }
        });
    }

    /**
     * Called when the GoogleMap is ready.
     * Performs main setup: loads recorded trajectory, prepares polyline, etc.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        this.mMap = googleMap;
        // Set map type and UI options
        googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        googleMap.getUiSettings().setCompassEnabled(true);

        // Initialize the indoor map manager
        nucleusBuildingManager = new NucleusBuildingManager(googleMap);
        // Set up floor buttons for manual floor switching
        setupFloorButtons();

        // Load trajectory data based on trajectoryId
        loadRecordedTrajectory(trajectoryId);

        if (!recordedTrajectory.isEmpty()) {
            // Initialize polyline for trajectory
            PolylineOptions polylineOptions = new PolylineOptions()
                    .color(getResources().getColor(R.color.pastelBlue))
                    .add(recordedTrajectory.get(0));
            replayPolyline = mMap.addPolyline(polylineOptions);

            // Initialize playback marker
            replayMarker = mMap.addMarker(new MarkerOptions()
                    .position(recordedTrajectory.get(0))
                    .title("Playback Marker"));

            // Initialize GNSS marker if GNSS data exists
            if (!gnssTrajectory.isEmpty()) {
                gnssMarker = mMap.addMarker(new MarkerOptions()
                        .position(gnssTrajectory.get(0))
                        .title("GNSS Position"));
            }

            // Calculate bounds for all trajectory points and update camera view
            LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder();
            for (LatLng point : recordedTrajectory) {
                boundsBuilder.include(point);
            }
            LatLngBounds bounds = boundsBuilder.build();
            int padding = 100; // Adjust padding as needed
            googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding));
        }

        // Set progress bar maximum to total number of trajectory points
        playbackProgressBar.setMax(recordedTrajectory.size());

        // Define playbackRunnable to update to the next trajectory point at fixed intervals
        playbackRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPlaying) return;
                if (currentIndex < recordedTrajectory.size()) {
                    LatLng point = recordedTrajectory.get(currentIndex);
                    List<LatLng> currentPoints = replayPolyline.getPoints();
                    currentPoints.add(point);
                    replayPolyline.setPoints(currentPoints);
                    replayMarker.setPosition(point);
                    mMap.animateCamera(CameraUpdateFactory.newLatLng(point));

                    if (currentIndex < gnssTrajectory.size()) {
                        LatLng gnssPoint = gnssTrajectory.get(currentIndex);
                        gnssMarker.setPosition(gnssPoint);
                    }

                    if (nucleusBuildingManager.isPointInBuilding(point)) {
                        if (autoFloorSwitch.isChecked()) {
                            nucleusBuildingManager.getIndoorMapManager().switchFloor(2);
                        }
                    }

                    playbackProgressBar.setProgress(currentIndex);
                    currentIndex++;
                    replayHandler.postDelayed(this, PLAYBACK_INTERVAL_MS);
                } else {
                    isPlaying = false;
                    playPauseBtn.setText("Play");
                }
            }
        };
    }

    /**
     * Loads the recorded trajectory based on trajectoryId.
     * It parses the trajectory file and converts PDR data (x, y in meters) to latitude/longitude offsets.
     */
    private void loadRecordedTrajectory(String trajectoryId) {
        recordedTrajectory.clear();
        // Parse trajectory data
        TrajectoryData data = TrajectoryParser.parseTrajectoryFile(getActivity(), trajectoryId);
        List<GnssData> gnssDataList = data.getGnssData();
        List<PdrData> pdrDataList = data.getPdrData();

        // 1. Get initial position from gnssDataList
        if (gnssDataList != null && !gnssDataList.isEmpty()) {
            GnssData firstGnssData = gnssDataList.get(0);
            double initialLat = firstGnssData.getLatitude();
            double initialLon = firstGnssData.getLongitude();

            // Initial latitude/longitude coordinate (origin)
            LatLng initialLocation = new LatLng(initialLat, initialLon);
            recordedTrajectory.add(initialLocation);

            // 2. Iterate through pdrDataList and convert x and y (in meters) to latitude/longitude offsets.
            // Use the initial point as the reference.
            for (PdrData pdrData : pdrDataList) {
                // pdrData's x represents east-west displacement and y represents north-south displacement
                double offsetX = pdrData.getX(); // in meters (east positive)
                double offsetY = pdrData.getY(); // in meters (north positive)

                // Calculate latitude and longitude offsets using the initial latitude to convert longitude
                double deltaLat = offsetY / 111320.0;
                double deltaLon = offsetX / (111320.0 * Math.cos(Math.toRadians(initialLat)));

                double newLat = initialLat + deltaLat;
                double newLon = initialLon + deltaLon;

                // Add the new point (initial point plus offset) to the trajectory list
                recordedTrajectory.add(new LatLng(newLat, newLon));
            }
        }
    }

    /**
     * Set up floor up/down buttons for manual floor switching.
     */
    private void setupFloorButtons() {
        floorUpBtn.setOnClickListener(v -> {
            // Disable auto-floor switching when manual button is pressed.
            autoFloorSwitch.setChecked(false);
            // Get the current floor from IndoorMapManager
            int currentFloor = nucleusBuildingManager.getIndoorMapManager().getCurrentFloor();
            // Since your NucleusBuildingManager sets up 5 floors (indices 0 to 4),
            // increment the floor if not already at the top floor.
            int maxFloor = 5; // Adjust if you have a different number of floors
            if (currentFloor < maxFloor) {
                nucleusBuildingManager.getIndoorMapManager().switchFloor(currentFloor + 1);
            }
        });

        floorDownBtn.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            int currentFloor = nucleusBuildingManager.getIndoorMapManager().getCurrentFloor();
            // Decrement the floor if not already at the ground floor.
            if (currentFloor > 0) {
                nucleusBuildingManager.getIndoorMapManager().switchFloor(currentFloor - 1);
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        // Pause playback
        isPlaying = false;
        replayHandler.removeCallbacks(playbackRunnable);
    }
}
