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
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.dataParser.GnssData;
import com.openpositioning.PositionMe.dataParser.PressureData;
import com.openpositioning.PositionMe.dataParser.TrajectoryData;
import com.openpositioning.PositionMe.dataParser.TrajectoryParser;
// IMPORTANT: Use the IndoorMapManager from the PositionMe package (the same one used in RecordingFragment).
import com.openpositioning.PositionMe.IndoorMapManager;

import java.util.ArrayList;
import java.util.List;

/**
 * PlaybackFragment for replaying recorded trajectories.
 *
 * This fragment uses a master playback clock (in ms) to trigger events from recorded GNSS
 * and Pressure data. It uses the PositionMe package’s IndoorMapManager for overlaying indoor maps.
 * On initialization the indoor maps are created by calling setCurrentLocation() and setIndicationOfIndoorMap().
 * Auto-floor switching is implemented so that if auto-floor is enabled the overlay updates based on the current elevation
 * (and, when in nucleus, a bias is applied so that ground appears as floor 1). Manual switching is handled via the
 * floor up/down buttons.
 */
public class PlaybackFragment extends Fragment implements OnMapReadyCallback {

    // --- UI Components ---
    private GoogleMap mMap;
    private Spinner mapSpinner;
    private Switch autoFloorSwitch;
    private FloatingActionButton floorUpBtn, floorDownBtn;
    private ProgressBar playbackProgressBar;
    private Button playPauseBtn, restartBtn, goToEndBtn, exitBtn;
    private Polyline replayPolyline;
    private Marker replayMarker;

    // --- Recorded Data ---
    private List<LatLng> recordedTrajectory = new ArrayList<>();
    private List<GnssData> recordedGnssData = new ArrayList<>();
    private List<PressureData> recordedPressureData = new ArrayList<>();
    // (Placeholder: PDR data list could be added later.)

    // --- Playback Control ---
    private Handler replayHandler;
    private Runnable playbackRunnable;
    private boolean isPlaying = false;
    private long playbackStartTime = 0;
    private int currentGnssIndex = 0;
    private int currentPressureIndex = 0;
    private static final long PLAYBACK_INTERVAL_MS = 200; // Update interval in ms

    // --- Indoor Mapping ---
    // We use only the IndoorMapManager from the PositionMe package.
    private IndoorMapManager autoFloorMapManager;
    // We'll update current elevation from recorded pressure data.
    private float currentElevation = 0f;

    // --- Other ---
    private String trajectoryId;

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
            PlaybackFragmentArgs args = PlaybackFragmentArgs.fromBundle(getArguments());
            trajectoryId = args.getTrajectoryId();
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
                currentGnssIndex = 0;
                currentPressureIndex = 0;
                if (replayPolyline != null) {
                    replayPolyline.setPoints(new ArrayList<>());
                }
                replayHandler.post(playbackRunnable);
            } else {
                isPlaying = false;
                playPauseBtn.setText("Play");
                replayHandler.removeCallbacks(playbackRunnable);
            }
        });

        restartBtn.setOnClickListener(v -> {
            isPlaying = false;
            replayHandler.removeCallbacks(playbackRunnable);
            playPauseBtn.setText("Play");
            currentGnssIndex = 0;
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
            while (currentGnssIndex < recordedGnssData.size()) {
                GnssData event = recordedGnssData.get(currentGnssIndex);
                LatLng pt = new LatLng(event.getLatitude(), event.getLongitude());
                List<LatLng> pts = replayPolyline.getPoints();
                pts.add(pt);
                replayPolyline.setPoints(pts);
                replayMarker.setPosition(pt);
                currentGnssIndex++;
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

        // Initialize IndoorMapManager (from PositionMe package).
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
            // IMPORTANT: Initialize the indoor maps.
            autoFloorMapManager.setCurrentLocation(recordedTrajectory.get(0));
            autoFloorMapManager.setIndicationOfIndoorMap();
        }
        playbackProgressBar.setMax((int)getPlaybackDuration());

        // Define the playback runnable.
        playbackRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsedTime = System.currentTimeMillis() - playbackStartTime;
                // Process GNSS events.
                while (currentGnssIndex < recordedGnssData.size() &&
                        recordedGnssData.get(currentGnssIndex).getRelativeTimestamp() <= elapsedTime) {
                    GnssData gnssEvent = recordedGnssData.get(currentGnssIndex);
                    LatLng pt = new LatLng(gnssEvent.getLatitude(), gnssEvent.getLongitude());
                    List<LatLng> pts = replayPolyline.getPoints();
                    pts.add(pt);
                    replayPolyline.setPoints(pts);
                    replayMarker.setPosition(pt);
                    mMap.animateCamera(CameraUpdateFactory.newLatLng(pt));
                    currentGnssIndex++;
                }
                // Process Pressure events to update current elevation.
                while (currentPressureIndex < recordedPressureData.size() &&
                        recordedPressureData.get(currentPressureIndex).getRelativeTimestamp() <= elapsedTime) {
                    PressureData pressureEvent = recordedPressureData.get(currentPressureIndex);
                    currentElevation = pressureEvent.getRelativeAltitude();
                    currentPressureIndex++;
                }
                // Update indoor overlay.
                LatLng currentPoint = currentPlaybackPoint();
                if (currentPoint != null) {
                    autoFloorMapManager.setCurrentLocation(currentPoint);
                    if (autoFloorSwitch.isChecked()) {
                        // Here we pass 'true' to apply the bias when in nucleus.
                        // (Assuming IndoorMapManager internally checks the current location.)
                        autoFloorMapManager.setCurrentFloor((int)(currentElevation / autoFloorMapManager.getFloorHeight()), true);
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

    private void loadRecordedTrajectory(String trajectoryId) {
        recordedTrajectory.clear();
        recordedGnssData.clear();
        recordedPressureData.clear();

        TrajectoryData trajectoryData = TrajectoryParser.parseTrajectoryFile(getContext());
        List<GnssData> gnssSamples = trajectoryData.getGnssData();
        if (gnssSamples != null) {
            for (GnssData sample : gnssSamples) {
                LatLng pt = new LatLng(sample.getLatitude(), sample.getLongitude());
                recordedTrajectory.add(pt);
                recordedGnssData.add(sample);
            }
        }
        if (trajectoryData.getPressureData() != null) {
            recordedPressureData.addAll(trajectoryData.getPressureData());
        }
    }

    /**
     * Returns the current playback point: the last point in the polyline (or the first if none have been added).
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
     * Returns overall playback duration in milliseconds (based on the last pressure event,
     * or falls back to the last GNSS event).
     */
    private long getPlaybackDuration() {
        if (!recordedPressureData.isEmpty()) {
            return recordedPressureData.get(recordedPressureData.size() - 1).getRelativeTimestamp();
        }
        if (!recordedGnssData.isEmpty()) {
            return recordedGnssData.get(recordedGnssData.size() - 1).getRelativeTimestamp();
        }
        return 0;
    }

    @Override
    public void onPause() {
        super.onPause();
        isPlaying = false;
        replayHandler.removeCallbacks(playbackRunnable);
    }

    @Override
    public void onResume() {
        super.onResume();
    }
}
