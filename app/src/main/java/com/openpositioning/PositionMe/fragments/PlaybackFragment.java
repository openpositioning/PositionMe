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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.dataParser.GnssData;
import com.openpositioning.PositionMe.dataParser.PdrData;
import com.openpositioning.PositionMe.dataParser.PressureData;
import com.openpositioning.PositionMe.dataParser.TrajectoryData;
import com.openpositioning.PositionMe.dataParser.TrajectoryParser;
import com.openpositioning.PositionMe.IndoorMapManager;

import java.util.ArrayList;
import java.util.List;

/**
 * PlaybackFragment for replaying recorded trajectories using converted PDR data.
 *
 * This version uses the base implementation for processing PDR data (with a hard-coded start
 * position) and adds functionality to display the current GNSS (latitude/longitude) in a view.
 */
public class PlaybackFragment extends Fragment implements OnMapReadyCallback {

    private static final String TAG = "PlaybackFragment";

    // --- UI Components ---
    private GoogleMap mMap;
    private Spinner mapSpinner;
    private Switch autoFloorSwitch;
    private FloatingActionButton floorUpBtn, floorDownBtn;
    private ProgressBar playbackProgressBar;
    private Button playPauseBtn, restartBtn, goToEndBtn, exitBtn;
    private Polyline replayPolyline;
    private Marker replayMarker;

    // Additional UI for displaying GNSS data:
    private LinearLayout gnssDataLayout;
    private TextView gnssDataLat, gnssDataLon;

    // --- Recorded Data ---
    private List<LatLng> recordedTrajectory = new ArrayList<>();
    private List<PdrPoint> recordedPdrPoints = new ArrayList<>();
    private List<PressureData> recordedPressureData = new ArrayList<>();

    // --- Playback Control ---
    private Handler replayHandler;
    private Runnable playbackRunnable;
    private boolean isPlaying = false;
    private long playbackStartTime = 0;
    private int currentPdrIndex = 0;
    private int currentPressureIndex = 0;
    private static final long PLAYBACK_INTERVAL_MS = 200; // Update interval in ms

    // --- Indoor Mapping ---
    private IndoorMapManager autoFloorMapManager;
    private float currentElevation = 0f;

    // --- Other ---
    private String trajectoryId;

    /**
     * Inner class to hold a converted PDR data point (LatLng + relative timestamp).
     */
    private static class PdrPoint {
        LatLng latLng;
        long relativeTimestamp;
        PdrPoint(LatLng latLng, long relativeTimestamp) {
            this.latLng = latLng;
            this.relativeTimestamp = relativeTimestamp;
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playback, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Retrieve passed arguments.
        if (getArguments() != null) {
            trajectoryId = PlaybackFragmentArgs.fromBundle(getArguments()).getTrajectoryId();
        }

        // Initialize UI components.
        mapSpinner = view.findViewById(R.id.mapSwitchSpinner_playback);
        autoFloorSwitch = view.findViewById(R.id.autoFloorSwitch_playback);
        floorUpBtn = view.findViewById(R.id.floorUpButton_playback);
        floorDownBtn = view.findViewById(R.id.floorDownButton_playback);
        playbackProgressBar = view.findViewById(R.id.playbackProgressBar);
        playPauseBtn = view.findViewById(R.id.playPauseButton);
        restartBtn = view.findViewById(R.id.restartButton);
        goToEndBtn = view.findViewById(R.id.goToEndButton);
        exitBtn = view.findViewById(R.id.exitButton);

        // Initialize GNSS data UI components from the layout.
        gnssDataLayout = view.findViewById(R.id.gnss_data_layout);
        gnssDataLat = view.findViewById(R.id.gnss_data_lat);
        gnssDataLon = view.findViewById(R.id.gnss_data_lon);

        // Force auto-floor to be enabled.
        autoFloorSwitch.setChecked(true);

        replayHandler = new Handler(Looper.getMainLooper());

        // Get the Map Fragment.
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.replayMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        setupControlButtons();
        setupMapSpinner();
        setupFloorButtons();
    }

    private void setupControlButtons() {
        playPauseBtn.setOnClickListener(v -> {
            if (!isPlaying) {
                isPlaying = true;
                playPauseBtn.setText("Pause");
                playbackStartTime = System.currentTimeMillis();
                currentPdrIndex = 0;
                currentPressureIndex = 0;
                if (replayPolyline != null) {
                    replayPolyline.setPoints(new ArrayList<>());
                }
                replayHandler.post(playbackRunnable);
                // Make GNSS data visible when playback starts.
                gnssDataLayout.setVisibility(View.VISIBLE);
            } else {
                isPlaying = false;
                playPauseBtn.setText("Play");
                replayHandler.removeCallbacks(playbackRunnable);
                // Hide GNSS data when paused.
                gnssDataLayout.setVisibility(View.GONE);
            }
        });

        restartBtn.setOnClickListener(v -> {
            isPlaying = false;
            replayHandler.removeCallbacks(playbackRunnable);
            playPauseBtn.setText("Play");
            currentPdrIndex = 0;
            currentPressureIndex = 0;
            if (replayPolyline != null) {
                replayPolyline.setPoints(new ArrayList<>());
            }
            if (!recordedTrajectory.isEmpty()) {
                replayMarker.setPosition(recordedTrajectory.get(0));
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(recordedTrajectory.get(0), 19f));
            }
            playbackProgressBar.setProgress(0);
        });

        goToEndBtn.setOnClickListener(v -> {
            isPlaying = false;
            replayHandler.removeCallbacks(playbackRunnable);
            while (currentPdrIndex < recordedPdrPoints.size()) {
                PdrPoint pdrPoint = recordedPdrPoints.get(currentPdrIndex);
                LatLng pt = pdrPoint.latLng;
                List<LatLng> pts = replayPolyline.getPoints();
                pts.add(pt);
                replayPolyline.setPoints(pts);
                replayMarker.setPosition(pt);
                currentPdrIndex++;
            }
            playbackProgressBar.setProgress((int)getPlaybackDuration());
            playPauseBtn.setText("Play");
        });

        exitBtn.setOnClickListener(v ->
                NavHostFragment.findNavController(this).popBackStack());
    }

    private void setupMapSpinner() {
        String[] maps = new String[]{"Hybrid", "Normal", "Satellite"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, maps);
        mapSpinner.setAdapter(adapter);
        mapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mMap == null) return;
                switch (position) {
                    case 0: mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID); break;
                    case 1: mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL); break;
                    case 2: mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE); break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                if (mMap != null) mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
            }
        });
    }

    private void setupFloorButtons() {
        floorUpBtn.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            autoFloorMapManager.increaseFloor();
        });
        floorDownBtn.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            autoFloorMapManager.decreaseFloor();
        });
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        mMap.getUiSettings().setCompassEnabled(true);

        // Initialize IndoorMapManager.
        autoFloorMapManager = new IndoorMapManager(mMap);

        // Load recorded trajectory data.
        loadRecordedTrajectory(trajectoryId);
        if (!recordedTrajectory.isEmpty()) {
            PolylineOptions polylineOptions = new PolylineOptions()
                    .color(getResources().getColor(R.color.pastelBlue))
                    .add(recordedTrajectory.get(0));
            replayPolyline = mMap.addPolyline(polylineOptions);
            replayPolyline.setZIndex(10);
            replayMarker = mMap.addMarker(new MarkerOptions()
                    .position(recordedTrajectory.get(0))
                    .title("Playback Marker"));
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(recordedTrajectory.get(0), 19f));
            // Initialize indoor maps.
            autoFloorMapManager.setCurrentLocation(recordedTrajectory.get(0));
            autoFloorMapManager.setIndicationOfIndoorMap();
        }
        playbackProgressBar.setMax((int)getPlaybackDuration());

        // Define the playback runnable.
        playbackRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsedTime = System.currentTimeMillis() - playbackStartTime;

                // Process PDR events: draw the path and update the marker.
                while (currentPdrIndex < recordedPdrPoints.size() &&
                        recordedPdrPoints.get(currentPdrIndex).relativeTimestamp <= elapsedTime) {
                    PdrPoint pdrPoint = recordedPdrPoints.get(currentPdrIndex);
                    LatLng pt = pdrPoint.latLng;
                    List<LatLng> pts = replayPolyline.getPoints();
                    pts.add(pt);
                    replayPolyline.setPoints(pts);
                    replayMarker.setPosition(pt);
                    mMap.animateCamera(CameraUpdateFactory.newLatLng(pt));
                    currentPdrIndex++;
                }

                // Process pressure events to update current elevation.
                while (currentPressureIndex < recordedPressureData.size() &&
                        recordedPressureData.get(currentPressureIndex).getRelativeTimestamp() <= elapsedTime) {
                    PressureData pressureEvent = recordedPressureData.get(currentPressureIndex);
                    currentElevation = pressureEvent.getRelativeAltitude();
                    currentPressureIndex++;
                }

                // Update GNSS view with the current playback point.
                LatLng currentPoint = currentPlaybackPoint();
                if (currentPoint != null) {
                    String latStr = "" + currentPoint.latitude;
                    String lonStr = "" + currentPoint.longitude;
                    gnssDataLat.setText(latStr);
                    gnssDataLon.setText(lonStr);

                    // Update indoor overlay.
                    autoFloorMapManager.setCurrentLocation(currentPoint);
                    if (autoFloorSwitch.isChecked()) {
                        int newFloor = (int)(currentElevation / autoFloorMapManager.getFloorHeight());
                        autoFloorMapManager.setCurrentFloor(newFloor, true);
                    }
                }

                playbackProgressBar.setProgress((int) elapsedTime);
                if (elapsedTime < getPlaybackDuration()) {
                    replayHandler.postDelayed(this, PLAYBACK_INTERVAL_MS);
                } else {
                    isPlaying = false;
                    playPauseBtn.setText("Play");
                }
            }
        };
    }

    /**
     * Loads the recorded trajectory.
     * If PDR data is available, converts its (x,y in meters) into lat/lng offsets
     * using the hard-coded GNSS fix as the origin. Both the master trajectory and the
     * playback list (recordedPdrPoints) are populated.
     */
    private void loadRecordedTrajectory(String trajectoryId) {
        recordedTrajectory.clear();
        recordedPdrPoints.clear();
        recordedPressureData.clear();

        TrajectoryData trajectoryData = TrajectoryParser.parseTrajectoryFile(getContext(), trajectoryId);
        List<PdrData> pdrDataList = trajectoryData.getPdrData();

        double initialLat = 55.922913;
        double initialLon = -3.174322;
        LatLng manualLocation = new LatLng(initialLat, initialLon);

        long manualTimeStamp = 110;
        recordedTrajectory.add(manualLocation);
        recordedPdrPoints.add(new PdrPoint(manualLocation, manualTimeStamp));

        for (PdrData pdrData : pdrDataList) {
            double offsetX = pdrData.getX();
            double offsetY = pdrData.getY();
            double deltaLat = offsetY / 111320.0;
            double deltaLon = offsetX / (111320.0 * Math.cos(Math.toRadians(initialLat)));
            double newLat = initialLat + deltaLat;
            double newLon = initialLon + deltaLon;
            LatLng newPoint = new LatLng(newLat, newLon);
            recordedTrajectory.add(newPoint);
            recordedPdrPoints.add(new PdrPoint(newPoint, pdrData.getRelativeTimestamp()));

        }

        // Load pressure data.
        if (trajectoryData.getPressureData() != null) {
            recordedPressureData.addAll(trajectoryData.getPressureData());
        }
    }

    /**
     * Returns the current playback point: the last point in the polyline (or the first if none exist).
     */
    private LatLng currentPlaybackPoint() {
        if (replayPolyline != null && !replayPolyline.getPoints().isEmpty()) {
            List<LatLng> pts = replayPolyline.getPoints();
            return pts.get(pts.size() - 1);
        } else if (!recordedTrajectory.isEmpty()) {
            return recordedTrajectory.get(0);
        }
        return null;
    }

    /**
     * Returns the overall playback duration in milliseconds based on the PDR data,
     * or falls back to pressure data if needed.
     */
    private long getPlaybackDuration() {
        if (!recordedPdrPoints.isEmpty()) {
            return recordedPdrPoints.get(recordedPdrPoints.size() - 1).relativeTimestamp;
        }
        if (!recordedPressureData.isEmpty()) {
            return recordedPressureData.get(recordedPressureData.size() - 1).getRelativeTimestamp();
        }
        return 0;
    }

    @Override
    public void onPause() {
        super.onPause();
        isPlaying = false;
        // Hide the GNSS data layout when playback is paused.
        gnssDataLayout.setVisibility(View.GONE);
        replayHandler.removeCallbacks(playbackRunnable);
    }

    @Override
    public void onResume() {
        super.onResume();
    }
}