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
import com.openpositioning.PositionMe.fragments.NucleusBuildingManager;
// IMPORTANT: Import the IndoorMapManager from the com.openpositioning.PositionMe package (auto floor version)
import com.openpositioning.PositionMe.IndoorMapManager;

import java.util.ArrayList;
import java.util.List;

/**
 * PlaybackFragment for replaying recorded trajectories.
 * <p>
 * This fragment uses a master playback clock (in ms) to trigger events from different sensor streams
 * (GNSS and Pressure for elevation/auto-floor updates) based on their relative timestamps.
 * PDR events are left as a placeholder.
 * <p>
 * When playback points enter a building (as determined by NucleusBuildingManager), the auto floor
 * manager updates the floor (if the auto-floor switch is enabled), while manual floor changes via buttons
 * disable auto-floor.
 */
public class PlaybackFragment extends Fragment implements OnMapReadyCallback {

    // region UI components
    private GoogleMap mMap;
    private Spinner mapSpinner;
    private Switch autoFloorSwitch;
    private FloatingActionButton floorUpBtn, floorDownBtn;
    private ProgressBar playbackProgressBar;
    private Button playPauseBtn, restartBtn, goToEndBtn, exitBtn;
    private Polyline replayPolyline;
    private Marker replayMarker;
    // endregion

    // region Playback event data
    // List of LatLng points created from GNSS samples (used for drawing the polyline)
    private List<LatLng> recordedTrajectory = new ArrayList<>();
    // Separate list of raw GNSS data (with relative timestamps) for synchronizing events.
    private List<GnssData> recordedGnssData = new ArrayList<>();
    // List of PressureData samples (for elevation/auto-floor switching)
    private List<PressureData> recordedPressureData = new ArrayList<>();
    // (Placeholder) List for PDR events if needed in the future.
    // private List<PdrData> recordedPdrData = new ArrayList<>();
    // endregion

    // Playback control variables
    private Handler replayHandler;
    private Runnable playbackRunnable;
    private boolean isPlaying = false;
    // Master playback clock: record the system time when playback starts.
    private long playbackStartTime = 0;
    // Indices to track the next event to process in each sensor stream.
    private int currentGnssIndex = 0;
    private int currentPressureIndex = 0;
    // You can add an index for PDR if needed:
    // private int currentPdrIndex = 0;

    // This constant defines how frequently (in ms) we update the playback.
    private static final long PLAYBACK_INTERVAL_MS = 200; // use a shorter interval for smoother sync

    // Building-related managers:
    // For checking if a point is inside a building (and for manual floor switching)
    private NucleusBuildingManager nucleusBuildingManager;
    // For auto-floor switching (used in both auto and manual modes)
    private IndoorMapManager autoFloorMapManager;

    // Passed trajectoryId (if applicable)
    private String trajectoryId;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playback, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // 1) Get arguments (if any)
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

        // 3) Initialize the playback handler.
        replayHandler = new Handler(Looper.getMainLooper());

        // 4) Get the Map Fragment and set callback.
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.replayMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        // 5) Set up control buttons and map spinner.
        setupControlButtons();
        setupMapSpinner();
    }

    /**
     * Set up the control buttons: Play/Pause, Restart, Go-To-End, and Exit.
     */
    private void setupControlButtons() {
        playPauseBtn.setOnClickListener(v -> {
            if (!isPlaying) {
                // Starting playback: reset indices and record the start time.
                isPlaying = true;
                playPauseBtn.setText("Pause");
                playbackStartTime = System.currentTimeMillis();
                // Reset event indices and polyline marker if needed.
                currentGnssIndex = 0;
                currentPressureIndex = 0;
                // Optionally clear the polyline so that playback starts from scratch.
                if (replayPolyline != null) {
                    replayPolyline.setPoints(new ArrayList<>());
                }
                // Start the playback runnable.
                replayHandler.post(playbackRunnable);
            } else {
                // Pause playback.
                isPlaying = false;
                playPauseBtn.setText("Play");
                replayHandler.removeCallbacks(playbackRunnable);
            }
        });

        restartBtn.setOnClickListener(v -> {
            // Stop playback, reset indices, clear polyline, and update UI.
            isPlaying = false;
            replayHandler.removeCallbacks(playbackRunnable);
            playPauseBtn.setText("Play");
            currentGnssIndex = 0;
            currentPressureIndex = 0;
            if (replayPolyline != null) {
                replayPolyline.setPoints(new ArrayList<>());
            }
            // Reset marker to the first GNSS point (if available).
            if (!recordedTrajectory.isEmpty()) {
                replayMarker.setPosition(recordedTrajectory.get(0));
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(recordedTrajectory.get(0), 19f));
            }
            // Reset progress bar.
            playbackProgressBar.setProgress(0);
        });

        goToEndBtn.setOnClickListener(v -> {
            // Skip playback to the end.
            isPlaying = false;
            replayHandler.removeCallbacks(playbackRunnable);
            // Process all remaining GNSS events.
            while (currentGnssIndex < recordedGnssData.size()) {
                GnssData event = recordedGnssData.get(currentGnssIndex);
                LatLng point = new LatLng(event.getLatitude(), event.getLongitude());
                List<LatLng> points = replayPolyline.getPoints();
                points.add(point);
                replayPolyline.setPoints(points);
                replayMarker.setPosition(point);
                currentGnssIndex++;
            }
            // Update progress bar to max.
            playbackProgressBar.setProgress((int)getPlaybackDuration());
            playPauseBtn.setText("Play");
        });

        exitBtn.setOnClickListener(v -> NavHostFragment.findNavController(this).popBackStack());
    }

    /**
     * Set up the map spinner to choose map type (Hybrid, Normal, Satellite).
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
     * <p>
     * Sets up the map, initializes building managers, the auto floor manager, loads trajectory data,
     * and defines the playback runnable.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        this.mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        mMap.getUiSettings().setCompassEnabled(true);

        // Initialize the NucleusBuildingManager for building boundary checks and manual floor switching.
        nucleusBuildingManager = new NucleusBuildingManager(googleMap);

        // Initialize the auto floor manager (from com.openpositioning.PositionMe) for auto-floor switching.
        autoFloorMapManager = new com.openpositioning.PositionMe.IndoorMapManager(googleMap);

        // Set up the floor buttons to allow manual floor switching.
        setupFloorButtons();

        // Load trajectory data (both GNSS and Pressure) from file.
        loadRecordedTrajectory(trajectoryId);

        // Initialize polyline and marker if there is trajectory data.
        if (!recordedTrajectory.isEmpty()) {
            PolylineOptions polylineOptions = new PolylineOptions()
                    .color(getResources().getColor(R.color.pastelBlue))
                    .add(recordedTrajectory.get(0));
            replayPolyline = mMap.addPolyline(polylineOptions);
            replayMarker = mMap.addMarker(new MarkerOptions()
                    .position(recordedTrajectory.get(0))
                    .title("Playback Marker"));
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(recordedTrajectory.get(0), 19f));
        }
        // Set the progress bar max value based on the overall playback duration.
        playbackProgressBar.setMax((int)getPlaybackDuration());

        // Define the playback runnable with the master clock and event triggering.
        playbackRunnable = new Runnable() {
            @Override
            public void run() {
                // Calculate elapsed playback time (in ms) since playbackStartTime.
                long elapsedTime = System.currentTimeMillis() - playbackStartTime;

                // --- Process GNSS Events ---
                // Trigger all GNSS events whose relative timestamp is <= elapsedTime.
                while (currentGnssIndex < recordedGnssData.size() &&
                        recordedGnssData.get(currentGnssIndex).getRelativeTimestamp() <= elapsedTime) {
                    GnssData gnssEvent = recordedGnssData.get(currentGnssIndex);
                    LatLng point = new LatLng(gnssEvent.getLatitude(), gnssEvent.getLongitude());
                    // Update the polyline: add this point.
                    List<LatLng> points = replayPolyline.getPoints();
                    points.add(point);
                    replayPolyline.setPoints(points);
                    // Update marker position and animate camera.
                    replayMarker.setPosition(point);
                    mMap.animateCamera(CameraUpdateFactory.newLatLng(point));
                    currentGnssIndex++;
                }

                // --- Process Pressure Events for Elevation / Auto-Floor ---
                while (currentPressureIndex < recordedPressureData.size() &&
                        recordedPressureData.get(currentPressureIndex).getRelativeTimestamp() <= elapsedTime) {
                    PressureData pressureEvent = recordedPressureData.get(currentPressureIndex);
                    int calculatedFloor = calculateFloorFromPressure(pressureEvent);
                    // If the current playback point is within the building, update the indoor overlay.
                    if (nucleusBuildingManager.isPointInBuilding(currentPlaybackPoint())) {
                        if (autoFloorSwitch.isChecked()) {
                            autoFloorMapManager.setCurrentFloor(calculatedFloor, true);
                        }
                    }
                    // (Optional) Update elevation display here if desired.
                    currentPressureIndex++;
                }

                // --- (Placeholder) Process PDR Events ---
                // If you add PDR data later, process events similarly using their relative timestamps.

                // Update the progress bar based on elapsed time.
                playbackProgressBar.setProgress((int) elapsedTime);

                // Check if we have reached the end of playback.
                if (elapsedTime < getPlaybackDuration()) {
                    replayHandler.postDelayed(this, PLAYBACK_INTERVAL_MS);
                } else {
                    // Playback finished.
                    isPlaying = false;
                    playPauseBtn.setText("Play");
                }
            }
        };
    }

    /**
     * Load the recorded trajectory data from file.
     * <p>
     * This method parses the trajectory file and populates:
     * - recordedTrajectory (List&lt;LatLng&gt;) for polyline drawing (from GNSS samples)
     * - recordedGnssData (List&lt;GnssData&gt;) for event synchronization
     * - recordedPressureData (List&lt;PressureData&gt;) for elevation/auto-floor switching.
     *
     * @param trajectoryId (not used here, data is loaded from file)
     */
    private void loadRecordedTrajectory(String trajectoryId) {
        recordedTrajectory.clear();
        recordedGnssData.clear();
        recordedPressureData.clear();

        // Parse the trajectory file.
        TrajectoryData trajectoryData = TrajectoryParser.parseTrajectoryFile(getContext());

        // Load GNSS samples.
        List<GnssData> gnssSamples = trajectoryData.getGnssData();
        if (gnssSamples != null) {
            for (GnssData sample : gnssSamples) {
                LatLng point = new LatLng(sample.getLatitude(), sample.getLongitude());
                recordedTrajectory.add(point);
                recordedGnssData.add(sample);
            }
        }

        // Load pressure data.
        if (trajectoryData.getPressureData() != null) {
            recordedPressureData.addAll(trajectoryData.getPressureData());
        }
    }

    /**
     * Set up floor up/down buttons for manual switching.
     * <p>
     * Manual switching calls the autoFloorMapManager’s increaseFloor() or decreaseFloor()
     * methods and disables auto-floor (by unchecking the switch).
     */
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
    public void onPause() {
        super.onPause();
        isPlaying = false;
        replayHandler.removeCallbacks(playbackRunnable);
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    /**
     * Helper method to return the current playback point.
     * Here we return the last point added to the polyline (or the first if none added).
     */
    private LatLng currentPlaybackPoint() {
        if (replayPolyline != null && !replayPolyline.getPoints().isEmpty()) {
            List<LatLng> points = replayPolyline.getPoints();
            return points.get(points.size() - 1);
        } else if (!recordedTrajectory.isEmpty()) {
            return recordedTrajectory.get(0);
        }
        return null;
    }

    /**
     * Calculate the current floor based on a PressureData sample.
     * <p>
     * This method divides the relative altitude by the floor height (from autoFloorMapManager)
     * and returns an integer floor value.
     *
     * @param pressureSample a PressureData sample
     * @return the calculated floor (e.g., 0 for baseline floor)
     */
    private int calculateFloorFromPressure(PressureData pressureSample) {
        if (pressureSample == null) {
            return 0;
        }
        float relativeAltitude = pressureSample.getRelativeAltitude();
        // Use the floor height from autoFloorMapManager if available, or default to 4 meters.
        int floorHeight = autoFloorMapManager.getFloorHeight() > 0 ? (int) autoFloorMapManager.getFloorHeight() : 4;
        return (int) (relativeAltitude / floorHeight);
    }

    /**
     * Returns the overall playback duration in milliseconds.
     * In this example, we assume the last pressure data entry marks the end of the recording.
     */
    private long getPlaybackDuration() {
        if (!recordedPressureData.isEmpty()) {
            return recordedPressureData.get(recordedPressureData.size() - 1).getRelativeTimestamp();
        }
        // Fallback: if no pressure data, use last GNSS event timestamp.
        if (!recordedGnssData.isEmpty()) {
            return recordedGnssData.get(recordedGnssData.size() - 1).getRelativeTimestamp();
        }
        return 0;
    }
}
