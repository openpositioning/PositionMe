package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.local.TrajParser;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import com.openpositioning.PositionMe.viewmodels.MapViewModel;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


/**
 * Sub fragment of Replay Activity. Fragment that replays trajectory data on a map.
 * <p>
 * The ReplayFragment is responsible for visualizing and replaying trajectory data captured during
 * previous recordings. It loads trajectory data from a JSON file, updates the map with user movement,
 * and provides UI controls for playback, pause, and seek functionalities.
 * <p>
 * Features:
 * - Loads trajectory data from a file and displays it on a map.
 * - Provides playback controls including play, pause, restart, and go to end.
 * - Updates the trajectory dynamically as playback progresses.
 * - Allows users to manually seek through the recorded trajectory.
 * - Integrates with {@link TrajectoryMapFragment} for map visualization.
 *
 * @see TrajectoryMapFragment The map fragment displaying the trajectory.
 * @see ReplayActivity The activity managing the replay workflow.
 * @see TrajParser Utility class for parsing trajectory data.
 *
 * @author Shu Gu
 */
public class ReplayFragment extends Fragment {

    private static final String TAG = "ReplayFragment";

    // GPS start location (received from ReplayActivity)
    private float initialLat = 0f;
    private float initialLon = 0f;
    private String filePath = "";
    private int lastIndex = -1;

    // UI Controls
    private TrajectoryMapFragment trajectoryMapFragment;
    private Button playPauseButton, restartButton, exitButton, goEndButton;
    private SeekBar playbackSeekBar;

    // Playback-related
    private final Handler playbackHandler = new Handler();
    private final long PLAYBACK_INTERVAL_MS = 500; // milliseconds
    private List<TrajParser.ReplayPoint> replayData = new ArrayList<>();
    private int currentIndex = 0;
    private boolean isPlaying = false;

    // NEW FEATURE: Variables for indoor map display functionality.
    private MapViewModel mapViewModel;
    private List<Polygon> venuePolygons = new ArrayList<>();
    private GroundOverlay floorplanOverlay;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Retrieve transferred data from ReplayActivity
        if (getArguments() != null) {
            filePath = getArguments().getString(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, "");
            initialLat = getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LAT, 0f);
            initialLon = getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LON, 0f);
        }

        // Log the received data
        Log.i(TAG, "ReplayFragment received data:");
        Log.i(TAG, "Trajectory file path: " + filePath);
        Log.i(TAG, "Initial latitude: " + initialLat);
        Log.i(TAG, "Initial longitude: " + initialLon);

        // Check if file exists before parsing
        File trajectoryFile = new File(filePath);
        if (!trajectoryFile.exists()) {
            Log.e(TAG, "ERROR: Trajectory file does NOT exist at: " + filePath);
            return;
        }
        if (!trajectoryFile.canRead()) {
            Log.e(TAG, "ERROR: Trajectory file exists but is NOT readable: " + filePath);
            return;
        }

        Log.i(TAG, "Trajectory file confirmed to exist and is readable.");

        // Parse the JSON file and prepare replayData using TrajParser
        replayData = TrajParser.parseTrajectoryData(filePath, requireContext(), initialLat, initialLon);

        // Log the number of parsed points
        if (replayData != null && !replayData.isEmpty()) {
            Log.i(TAG, "Trajectory data loaded successfully. Total points: " + replayData.size());
        } else {
            Log.e(TAG, "Failed to load trajectory data! replayData is empty or null.");
        }
    }


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_replay, container, false);
    }

    // In ReplayFragment.java

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 1. Initialize MapViewModel (as we planned)
        mapViewModel = new ViewModelProvider(this).get(MapViewModel.class);

        // 2. Observe LiveData for API responses (as we planned)
        mapViewModel.getFloorplanResponse().observe(getViewLifecycleOwner(), response -> {
            // This part remains the same. When data comes back, it will be drawn.
            if (response != null && trajectoryMapFragment.getMap() != null) {
                // The logic to draw outlines is now inside TrajectoryMapFragment,
                // so we don't call drawVenueOutlines() from here anymore.
                // The observer in TrajectoryMapFragment will handle it.
                Log.d(TAG, "Floorplan response received and observed by child fragment.");
            } else {
                Log.d(TAG, "Failed to fetch floorplans or no floorplans nearby.");
            }
        });

        // 3. Initialize the map fragment
        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.replayMapFragmentContainer);
        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.replayMapFragmentContainer, trajectoryMapFragment)
                    .commit();
        }

        // 4. CRITICAL STEP: Use getMapAsync to connect everything when the map is ready.
        trajectoryMapFragment.getMapAsync(googleMap -> {
            // This code block is guaranteed to run ONLY when the map is fully loaded.

            // a. Set the polygon click listener on the now-ready map.
            googleMap.setOnPolygonClickListener(polygon -> {
                // The selectVenue() method is now inside TrajectoryMapFragment,
                // so we don't need to do anything here. The child fragment handles its own clicks.
            });

            // b. Run the original logic to determine the starting position.
            boolean gnssExists = hasAnyGnssData(replayData);
            if (gnssExists) {
                showGnssChoiceDialog();
            } else {
                if (initialLat != 0f || initialLon != 0f) {
                    // This call will now trigger the API request inside TrajectoryMapFragment.
                    setupInitialMapPosition(initialLat, initialLon);
                }
            }
        });


        // --- The rest of your original UI setup code remains unchanged ---

        // Initialize UI controls
        playPauseButton = view.findViewById(R.id.playPauseButton);
        restartButton   = view.findViewById(R.id.restartButton);
        exitButton      = view.findViewById(R.id.exitButton);
        goEndButton     = view.findViewById(R.id.goEndButton);
        playbackSeekBar = view.findViewById(R.id.playbackSeekBar);

        // Set SeekBar max value based on replay data
        if (!replayData.isEmpty()) {
            playbackSeekBar.setMax(replayData.size() - 1);
        }

        // Button Listeners (No changes needed here)
        playPauseButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) {
                Log.w(TAG, "Play/Pause button pressed but replayData is empty.");
                return;
            }
            if (isPlaying) {
                isPlaying = false;
                playPauseButton.setText("Play");
                Log.i(TAG, "Playback paused at index: " + currentIndex);
            } else {
                isPlaying = true;
                playPauseButton.setText("Pause");
                Log.i(TAG, "Playback started from index: " + currentIndex);
                if (currentIndex >= replayData.size()) {
                    currentIndex = 0;
                }
                playbackHandler.post(playbackRunnable);
            }
        });

        restartButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            currentIndex = 0;
            playbackSeekBar.setProgress(0);
            Log.i(TAG, "Restart button pressed. Resetting playback to index 0.");
            updateMapForIndex(0);
        });

        goEndButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            currentIndex = replayData.size() - 1;
            playbackSeekBar.setProgress(currentIndex);
            Log.i(TAG, "Go to End button pressed. Moving to last index: " + currentIndex);
            updateMapForIndex(currentIndex);
            isPlaying = false;
            playPauseButton.setText("Play");
        });

        exitButton.setOnClickListener(v -> {
            Log.i(TAG, "Exit button pressed. Exiting replay.");
            if (getActivity() instanceof ReplayActivity) {
                ((ReplayActivity) getActivity()).finishFlow();
            } else {
                // NEW: Use the OnBackPressedDispatcher to handle the back press.
                // This is the modern, recommended way.
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            }
        });

        // SeekBar listener (No changes needed here)
        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    Log.i(TAG, "SeekBar moved by user. New index: " + progress);
                    currentIndex = progress;
                    updateMapForIndex(currentIndex);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        if (!replayData.isEmpty()) {
            updateMapForIndex(0);
        }
    }



    /**
     * Checks if any ReplayPoint contains a non-null gnssLocation.
     */
    private boolean hasAnyGnssData(List<TrajParser.ReplayPoint> data) {
        for (TrajParser.ReplayPoint point : data) {
            if (point.gnssLocation != null) {
                return true;
            }
        }
        return false;
    }


    /**
     * Show a simple dialog asking user to pick:
     * 1) GNSS from file
     * 2) Lat/Lon from ReplayActivity arguments
     */
    private void showGnssChoiceDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Choose Starting Location")
                .setMessage("GNSS data is found in the file. Would you like to use the file's GNSS as the start, or the one you manually picked?")
                .setPositiveButton("Use File's GNSS", (dialog, which) -> {
                    LatLng firstGnss = getFirstGnssLocation(replayData);
                    if (firstGnss != null) {
                        setupInitialMapPosition((float) firstGnss.latitude, (float) firstGnss.longitude);
                    } else {
                        // Fallback if no valid GNSS found
                        setupInitialMapPosition(initialLat, initialLon);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("Use Manual Set", (dialog, which) -> {
                    setupInitialMapPosition(initialLat, initialLon);
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
    }

    // NEW FEATURE: Modified this method to also trigger the API call.
    private void setupInitialMapPosition(float latitude, float longitude) {
        LatLng startPoint = new LatLng(latitude, longitude);
        Log.i(TAG, "Setting initial map position: " + startPoint.toString());
        trajectoryMapFragment.setInitialCameraPosition(startPoint);

        // NEW FEATURE: When the map position is set, trigger a fetch for nearby floorplans.
        Log.d(TAG, "Triggering fetch for nearby floorplans at " + latitude + ", " + longitude);
        mapViewModel.fetchNearbyFloorplans(latitude, longitude);
    }

    /**
     * Retrieve the first available GNSS location from the replay data.
     */
    private LatLng getFirstGnssLocation(List<TrajParser.ReplayPoint> data) {
        for (TrajParser.ReplayPoint point : data) {
            if (point.gnssLocation != null) {
                // BUG FIX: Was using replayData.get(0), should use the current point.
                return new LatLng(point.gnssLocation.latitude, point.gnssLocation.longitude);
            }
        }
        return null; // None found
    }


    /**
     * Runnable for playback of trajectory data.
     * This runnable is called repeatedly to update the map with the next point in the replayData list.
     */
    private final Runnable playbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying || replayData.isEmpty()) return;

            Log.i(TAG, "Playing index: " + currentIndex);
            updateMapForIndex(currentIndex);
            currentIndex++;
            playbackSeekBar.setProgress(currentIndex);

            if (currentIndex < replayData.size()) {
                playbackHandler.postDelayed(this, PLAYBACK_INTERVAL_MS);
            } else {
                Log.i(TAG, "Playback completed. Reached end of data.");
                isPlaying = false;
                playPauseButton.setText("Play");
            }
        }
    };


    /**
     * Update the map with the user location and GNSS location (if available) for the given index.
     * Clears the map and redraws up to the given index.
     *
     * @param newIndex
     */
    private void updateMapForIndex(int newIndex) {
        if (newIndex < 0 || newIndex >= replayData.size()) return;

        // Detect if user is playing sequentially (lastIndex + 1)
        // or is skipping around (backwards, or jump forward)
        boolean isSequentialForward = (newIndex == lastIndex + 1);

        if (!isSequentialForward) {
            // Clear everything and redraw up to newIndex
            trajectoryMapFragment.clearMapAndReset();
            for (int i = 0; i <= newIndex; i++) {
                TrajParser.ReplayPoint p = replayData.get(i);
                trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
                if (p.gnssLocation != null) {
                    trajectoryMapFragment.updateGNSS(p.gnssLocation);
                }
            }
        } else {
            // Normal sequential forward step: add just the new point
            TrajParser.ReplayPoint p = replayData.get(newIndex);
            trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
            if (p.gnssLocation != null) {
                trajectoryMapFragment.updateGNSS(p.gnssLocation);
            }
        }

        lastIndex = newIndex;
    }

    @Override
    public void onPause() {
        super.onPause();
        isPlaying = false;
        playbackHandler.removeCallbacks(playbackRunnable);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        playbackHandler.removeCallbacks(playbackRunnable);
    }

    // --- NEW FEATURE: All methods below are added for the indoor map display functionality. ---

    /**
     * Draws venue outlines on the map based on the API response.
     * @param apiResponse The JSON object received from the floorplan API.
     */
    private void drawVenueOutlines(JsonObject apiResponse) {
        // Clear any previously drawn polygons before adding new ones.
        for (Polygon p : venuePolygons) {
            p.remove();
        }
        venuePolygons.clear();

        JsonArray venues = apiResponse.getAsJsonArray("venues");
        if (venues == null || trajectoryMapFragment.getMap() == null) return;

        for (JsonElement venueElement : venues) {
            JsonObject venue = venueElement.getAsJsonObject();
            // The API returns coordinates in a nested array: [[lng, lat], [lng, lat], ...]
            JsonArray outlineCoords = venue.getAsJsonObject("outline").getAsJsonArray("coordinates").get(0).getAsJsonArray();

            PolygonOptions polygonOptions = new PolygonOptions()
                    .strokeColor(Color.BLUE)
                    .strokeWidth(5)
                    .fillColor(Color.argb(50, 0, 0, 255)) // Semi-transparent blue
                    .clickable(true);

            for (JsonElement coordElement : outlineCoords) {
                JsonArray lngLat = coordElement.getAsJsonArray();
                // Note: API returns [longitude, latitude], Google Maps LatLng is (latitude, longitude).
                polygonOptions.add(new LatLng(lngLat.get(1).getAsDouble(), lngLat.get(0).getAsDouble()));
            }

            Polygon polygon = trajectoryMapFragment.getMap().addPolygon(polygonOptions);
            polygon.setTag(venue); // Attach the full venue data to the polygon for later use on click.
            venuePolygons.add(polygon);
        }
    }

    /**
     * Handles the event when a user clicks on a venue polygon on the map.
     * @param venueData The JSON data of the selected venue, retrieved from the polygon's tag.
     */
    private void selectVenue(JsonObject venueData) {
        String venueId = venueData.get("id").getAsString();
        Log.d(TAG, "Venue selected: " + venueId);

        // Update the ViewModel with the selected venue ID. This makes it available to other parts
        // of the app, like the data recording service.
        mapViewModel.setSelectedVenueId(venueId);

        // TODO: Implement a UI element (e.g., a dropdown menu) to allow the user to select a floor.

        // For now, automatically display the first floorplan available for the selected venue.
        JsonArray floorplans = venueData.getAsJsonArray("floorplans");
        if (floorplans != null && floorplans.size() > 0) {
            JsonObject firstFloor = floorplans.get(0).getAsJsonObject();
            displayFloorplan(firstFloor);
        }
    }

    /**
     * Displays a specific floorplan image as a GroundOverlay on the map.
     * @param floorplan The JSON object for a single floor, containing URL and bounding box.
     */
    private void displayFloorplan(JsonObject floorplan) {
        // Remove the previous floorplan overlay if it exists.
        if (floorplanOverlay != null) {
            floorplanOverlay.remove();
        }

        String imageUrl = floorplan.get("url").getAsString();
        // The bounding box is defined as [minLng, minLat, maxLng, maxLat].
        JsonArray bbox = floorplan.getAsJsonArray("bbox");
        LatLngBounds bounds = new LatLngBounds(
                new LatLng(bbox.get(1).getAsDouble(), bbox.get(0).getAsDouble()), // Southwest corner
                new LatLng(bbox.get(3).getAsDouble(), bbox.get(2).getAsDouble())  // Northeast corner
        );

        // Use Glide to asynchronously load the image from the URL. This prevents blocking the main thread.
        Glide.with(this)
                .asBitmap()
                .load(imageUrl)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                        if (trajectoryMapFragment.getMap() != null) {
                            GroundOverlayOptions options = new GroundOverlayOptions()
                                    .image(BitmapDescriptorFactory.fromBitmap(resource))
                                    .positionFromBounds(bounds);
                            floorplanOverlay = trajectoryMapFragment.getMap().addGroundOverlay(options);
                        }
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                        // This is called when the resource is no longer needed.
                    }
                });
    }
}