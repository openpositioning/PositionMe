package com.openpositioning.PositionMe.fragments;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.AdapterView;

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
import com.openpositioning.PositionMe.Replay;
import com.openpositioning.PositionMe.ServerCommunications;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;

import java.util.List;

/**
 * A simple {@link Fragment} subclass. The ReplayFragment is responsible for managing the
 * playback of recorded trajectories and enhancing the user's navigation experience. It
 * displays the user's current location on a Google Map, integrating GNSS data to show
 * real-time positional updates. The fragment includes UI elements that allow users to
 * control the playback of their recorded paths, with options to play, pause, restart,
 * and jump to the end of the trajectory.
 *
 * Additionally, the fragment provides indoor navigation functionalities by managing
 * floor selection through buttons and an auto-floor switch, ensuring that users can
 * navigate effectively within multi-story buildings. The indoor map manager overlays
 * the indoor layout when applicable, improving the overall user experience in locations
 * such as the Nucleus and Library buildings.
 *
 * @see HomeFragment the previous fragment in the navigation graph.
 * @see Replay the class responsible for handling the logic of trajectory playback.
 * @see IndoorMapManager the class responsible for managing indoor map overlays and floor navigation.
 *
 * @author Yueyan Zhao
 * @author Zizhen Wang
 * @author Chen Zhao
 */

public class ReplayFragment extends Fragment implements Replay.ReplayCallback {

    // ------------------ Replay Functionality Related Variables ------------------
    // Manages communication with the server.
    private ServerCommunications serverCommunications;
    // Handles replay logic for recorded trajectories.
    private Replay replay;
    // Google Map instance for displaying trajectories and user location.
    private GoogleMap gMap;
    // Switch to enable or disable GNSS tracking for user's location.
    private Switch gnss;
    // Switch for automatic floor selection in indoor navigation.
    private Switch autoFloor;
    // Marker for indicating the user's current GNSS position on the map.
    private Marker gnssMarker;
    // Processes sensor data related to position and movement.
    private SensorFusion sensorFusion;

    /**
     * Callback invoked when the Google Map is ready for use.
     * This method sets the default location based on the user's GNSS position,
     * adds a marker at that location, and moves the camera to center on it.
     * It enables zoom gesture controls and initializes the indoor map manager.
     * If a replay object is initialized, it calls its onMapReady method.
     *
     * @param googleMap                 The GoogleMap instance that is ready.
     */
    private OnMapReadyCallback mapReadyCallback = new OnMapReadyCallback() {
        @Override
        public void onMapReady(GoogleMap googleMap) {
            gMap = googleMap; // Assign the ready GoogleMap instance to the class variable.

            // Retrieve the user's current location from the sensor fusion and set it as the default location.
            float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
            LatLng defaultLocation = new LatLng(location[0], location[1]);

            // Add a marker at the default location, titled "Default Location".
            googleMap.addMarker(new MarkerOptions().position(defaultLocation).title("Default Location"));

            // Move the camera to the default location with an initial zoom level of 20.
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 20)); // 初始缩放级别 22

            // Enable user gesture controls for zooming in and out on the map.
            gMap.getUiSettings().setZoomGesturesEnabled(true);

            // If the replay object is initialized, call its onMapReady method to handle trajectory rendering.
            if (replay != null) {
                replay.onMapReady(googleMap);
            }

            // Initialize the indoor map manager to manage indoor map overlays.
            if (gMap != null) {
                indoorMapManager = new IndoorMapManager(gMap);
            }
        }
    };

    // ------------------ Playback Control Buttons (Bottom Area) ------------------
    private Button playButton;
    private Button pauseButton;
    private Button restartButton;
    private Button goToEndButton;
    private Button exitButton;

    // ------------------ Indoor Control Related Controls ------------------
    private Switch gnssSwitch;
    private FloatingActionButton floorUpButton;
    private FloatingActionButton floorDownButton;
    private Button lineColorButton;
    // Dropdown menu for selecting map types.
    private Spinner switchMapSpinner;

    // Manager for indoor map overlays.
    private IndoorMapManager indoorMapManager;

    // ------------------ Progress Bar Control ------------------
    // ProgressBar for displaying playback progress.
    private ProgressBar progressBar;
    // Handler for updating the progress bar at intervals.
    private Handler handler = new Handler();
    // Interval for progress bar updates (in milliseconds).
    private static final int UPDATE_INTERVAL = 100;

    /**
     * Runnable task to update the playback progress at regular intervals.
     * This task retrieves the total duration and current progress of the replay,
     * calculates the progress percentage, and updates the progress bar accordingly.
     * The task continues to run at defined intervals until the replay is no longer active.
     */
    private Runnable updateProgressRunnable = new Runnable() {
        @Override
        public void run() {
            // Check if the replay object is initialized.
            if (replay != null) {
                int totalDuration = replay.getTotalDuration(); // Get the total duration of the replay.
                int currentProgress = replay.getCurrentProgress(); // Get the current progress of the replay.

                // If total duration is greater than zero, calculate and update the progress bar.
                if (totalDuration > 0) {
                    int progress = (int) ((float) currentProgress / totalDuration * 100); // Calculate progress percentage.
                    progressBar.setProgress(progress); // Update the progress bar.
                }

                // Continue to update the progress at specified intervals.
                handler.postDelayed(this, UPDATE_INTERVAL); // Schedule the next update.
            }
        }
    };

    /**
     * Default constructor for the ReplayFragment class.
     * This constructor is required for fragment instantiation
     * and initializes the fragment with no arguments or settings.
     */
    public ReplayFragment() {
        // Required empty public constructor
    }

    /**
     * Called when the fragment is being created.
     * This method initializes the fragment's components, including
     * setting up server communications and acquiring the instance
     * of SensorFusion for processing sensor data.
     *
     * @param savedInstanceState A Bundle containing the fragment's
     *                           previously saved state, if available.
     *                           This can be used to restore the fragment's state.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); // Call the superclass's onCreate method.

        // Initialize server communications using the context of the current activity.
        serverCommunications = new ServerCommunications(requireContext());

        // Get an instance of SensorFusion for processing sensor data.
        this.sensorFusion = SensorFusion.getInstance();
    }

    /**
     * Called to create the view hierarchy associated with the fragment.
     * This method inflates the fragment_replay layout and initializes
     * the UI components, such as the progress bar, before returning
     * the root view.
     *
     * @param inflater                 The LayoutInflater used to inflate views in the fragment.
     * @param container                The parent view that this fragment's UI should be attached to,
     *                                  if it is not null. It is used for generating the layout parameters.
     * @param savedInstanceState       If non-null, this fragment is being re-constructed from a previous saved state.
     * @return                        Returns the View for the fragment's UI.
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate the fragment_replay layout file to create the fragment's UI.
        View view = inflater.inflate(R.layout.fragment_replay, container, false);


        // Find and initialize the progress bar UI component.
        progressBar = view.findViewById(R.id.progress_bar);

        return view; // Return the root view of the fragment.
    }

    /**
     * Called when the view associated with the fragment is created.
     * This method inflates the fragment_replay layout, initializes
     * the UI components such as playback control buttons and the
     * progress bar, and sets up the map fragment. It also
     * retrieves the trajectory ID and manages callbacks for data
     * loading and playback control.
     *
     * @param view                     The View returned by onCreateView(LayoutInflater, ViewGroup, Bundle).
     * @param savedInstanceState       A Bundle containing previously saved state information, if available.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // ------------------ Playback Trajectory Section ------------------
        // Retrieve the trajectory ID passed to the fragment.
        String trajectoryId = (getArguments() != null) ? getArguments().getString("trajectoryId") : null;
        if (trajectoryId != null) {
            Log.d("ReplayFragment", "Received trajectory ID: " + trajectoryId);
        } else {
            Log.w("ReplayFragment", "No trajectory ID received");
        }

        // Convert the trajectory ID to an integer.
        int trajId = trajectoryId != null ? Integer.parseInt(trajectoryId) : -1;

        // Callback for handling the result of trajectory data download.
        ServerCommunications.DownloadStringResultCallback callback = new ServerCommunications.DownloadStringResultCallback() {
            @Override
            public void onResult(String trajectoryJson) {
                if (isAdded()) {
                    if (trajectoryJson != null) {
                        Log.d("ReplayFragment", "Parsed JSON data: " + trajectoryJson);
                        replay = new Replay(requireContext(), trajectoryJson, ReplayFragment.this); // Initialize replay object.
                        Log.d("ReplayFragment", "Trajectory data successfully loaded and ready to play");

                        requireActivity().runOnUiThread(() -> {
                            // Find the map fragment and set up the map asynchronously.
                            SupportMapFragment mapFragment = (SupportMapFragment)
                                    getChildFragmentManager().findFragmentById(R.id.fragment_container);
                            if (mapFragment != null) {
                                mapFragment.getMapAsync(mapReadyCallback);
                            }
                        });
                    } else {
                        Log.e("ReplayFragment", "Failed to download trajectory JSON");
                    }
                } else {
                    Log.w("ReplayFragment", "Fragment is not attached");
                }
            }
        };

        // If trajectory ID is valid, request the trajectory data from the server.
        if (trajId != -1) {
            serverCommunications.replayTrajectory(trajId, callback);
        }

        // Initialize the map.
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.fragment_container);
        if (mapFragment == null) {
            mapFragment = new SupportMapFragment(); // Create a new map fragment if one does not exist.
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, mapFragment) // Replace the container with the new map fragment.
                    .commit();
        }
        mapFragment.getMapAsync(mapReadyCallback); // Asynchronously get the map.

        // Initialize playback control buttons.
        playButton = view.findViewById(R.id.play_button);
        pauseButton = view.findViewById(R.id.pause_button);
        restartButton = view.findViewById(R.id.restart_button);
        goToEndButton = view.findViewById(R.id.go_to_end_button);
        exitButton = view.findViewById(R.id.exit_button);

        // Set onClick listeners for each control button to manage playback.
        playButton.setOnClickListener(v -> {
            if (replay != null) {
                replay.play(); // Start playback of the trajectory.
                handler.postDelayed(updateProgressRunnable, UPDATE_INTERVAL); // Begin updating the progress bar.
            } else {
                Log.w("ReplayFragment", "Replay 对象未初始化");
            }
        });

        pauseButton.setOnClickListener(v -> {
            if (replay != null) {
                replay.pause(); // Pause playback.
                handler.removeCallbacks(updateProgressRunnable); // Stop updating the progress bar.
            } else {
                Log.w("ReplayFragment", "Replay 对象未初始化");
            }
        });

        restartButton.setOnClickListener(v -> {
            if (replay != null) {
                replay.replay();  // Restart playback.
                progressBar.setProgress(0); // Reset progress bar to 0%.
                handler.postDelayed(updateProgressRunnable, UPDATE_INTERVAL); // Resume updating the progress.
            } else {
                Log.w("ReplayFragment", "Replay object uninitialized");
            }
        });

        goToEndButton.setOnClickListener(v -> {
            if (replay != null) {
                replay.displayFullTrajectory(); // Display the full trajectory.
                progressBar.setProgress(100); // Set progress bar to 100%.
                handler.removeCallbacks(updateProgressRunnable);
            } else {
                Log.w("ReplayFragment", "Replay object uninitialized");
            }
        });

        exitButton.setOnClickListener(v -> {
            handler.removeCallbacks(updateProgressRunnable); // Stop updating the progress bar.
            getActivity().onBackPressed(); // Navigate back to the previous screen.
        });

        // ------------------ Indoor Control Section ------------------
        // Initialize indoor control UI components.
        gnssSwitch = view.findViewById(R.id.gnssSwitch); // GNSS switch for enabling/disabling tracking.
        floorUpButton = view.findViewById(R.id.floorUpButton); // Button to move up a floor.
        floorDownButton = view.findViewById(R.id.floorDownButton); // Button to move down a floor.
        lineColorButton = view.findViewById(R.id.lineColorButton); // Button to change line color in the map.

        // Initialize the map type dropdown menu.
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        mapDropdown(); // Set up dropdown contents and listeners.
        switchMap(); // Initialize listener for map type changes.

        // GNSS switch listener to toggle location tracking.
        gnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Log.d("ReplayFragment", "GNSS switched: " + (isChecked ? "ON" : "OFF"));
            // Functionality to show or hide GNSS marker can be added here.
        });

        this.gnss = view.findViewById(R.id.gnssSwitch);
        // Handle GNSS switch state changes.
        this.gnss.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                if (isChecked){
                    // Get the current GNSS location to display on the map.
                    float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
                    LatLng gnssLocation = new LatLng(location[0], location[1]);
                    // Set the GNSS marker on the map at the current location.
                    gnssMarker = gMap.addMarker(
                            new MarkerOptions().title("GNSS position")
                                    .position(gnssLocation)
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                } else {
                    // Remove the GNSS marker from the map if the switch is turned off.
                    if (gnssMarker != null) {
                        gnssMarker.remove();
                    }
                }
            }
        });

        // Initialize buttons for floor control.
        this.floorUpButton=getView().findViewById(R.id.floorUpButton);
        this.floorDownButton=getView().findViewById(R.id.floorDownButton);
        // Auto-floor switch initialization
        this.autoFloor=getView().findViewById(R.id.autoFloor);
        autoFloor.setChecked(true); // Set auto-floor switch to on by default.

        // Hide floor navigation buttons when auto-floor is active.
        setFloorButtonVisibility(View.GONE); // Implement visibility function as needed.

        // Button listener for increasing the floor level in the indoor map.
        this.floorUpButton.setOnClickListener(new View.OnClickListener() {
            /**
             *{@inheritDoc}
             * Listener for increasing the floor for the indoor map
             */
            @Override
            public void onClick(View view) {
                autoFloor.setChecked(false); // Disable auto-floor when manually adjusted.
                indoorMapManager.increaseFloor(); // Move up one floor.
            }
        });

        // Button listener for decreasing the floor level in the indoor map.
        this.floorDownButton.setOnClickListener(new View.OnClickListener() {
            /**
             *{@inheritDoc}
             * Listener for decreasing the floor for the indoor map
             */
            @Override
            public void onClick(View view) {
                autoFloor.setChecked(false); // Disable auto-floor when manually adjusted.
                indoorMapManager.decreaseFloor(); // Move down one floor.
            }
        });
    }

    /**
     * Initializes the contents of the dropdown menu for selecting different map types.
     * This method creates an array of map type options and sets it as the adapter for
     * the dropdown spinner, allowing users to choose between hybrid, normal, and satellite views.
     */
    private void mapDropdown() {
        // Create an array of map type options using string resources.
        String[] maps = new String[]{getString(R.string.hybrid), getString(R.string.normal), getString(R.string.satellite)};

        // Create an ArrayAdapter using the context and the array of map types.
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, maps);

        // Set the adapter to the switchMapSpinner to populate it with map type options.
        switchMapSpinner.setAdapter(adapter);
    }

    /**
     * Sets up the listener for the map type dropdown menu.
     * This method listens for item selections in the dropdown and
     * changes the Google Map type accordingly based on the selected item.
     */
    private void switchMap() {
        // Set an item selected listener on the switchMapSpinner for map type selection.
        switchMapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Switch the map type based on the selected position in the dropdown.
                switch (position) {
                    case 0: // Hybrid map type
                        gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                    case 1: // Normal map type
                        gMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                        break;
                    case 2: // Satellite map type
                        gMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                        break;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // If no selection is made, default to the hybrid map type.
                gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
            }
        });
    }

    /**
     * Called when the fragment's view is being destroyed.
     * This method is used to perform cleanup operations,
     * such as stopping any ongoing updates to the progress bar.
     */
    @Override
    public void onDestroyView() {
        super.onDestroyView(); // Call the superclass's onDestroyView method.

        // Stop updating the progress bar by removing any pending callbacks for the updateProgressRunnable.
        handler.removeCallbacks(updateProgressRunnable);
    }

    /**
     * Sets the visibility of the floor control buttons and the auto-floor switch.
     * This method allows for dynamic control of UI elements related to floor navigation
     * by setting their visibility based on the given parameter.
     *
     * @param visibility               The visibility state to be set for the buttons and switch,
     *                                which can be View.VISIBLE, View.INVISIBLE, or View.GONE.
     */
    private void setFloorButtonVisibility(int visibility){
        // Set the visibility of the floor up button.
        floorUpButton.setVisibility(visibility);
        // Set the visibility of the floor down button.
        floorDownButton.setVisibility(visibility);
        // Set the visibility of the auto-floor switch.
        autoFloor.setVisibility(visibility);
    }

    /**
     * Updates the user interface and the user's current position on the map based on the provided latitude and longitude.
     * This method checks if the indoor map manager is initialized, updates the user's current location,
     * and manages the visibility of floor navigation buttons based on whether the indoor map is visible.
     *
     * @param latLng   The current location of the user represented as a LatLng object.
     */
    public void updateUIandPosition(LatLng latLng){
        // If the indoor map manager is not initialized, create a new instance.
        if (indoorMapManager == null) {
            indoorMapManager =new IndoorMapManager(gMap);
        }

        // Update the current user location in the indoor map manager.
        indoorMapManager.setCurrentLocation(latLng);

        // Get the current elevation value from the sensor fusion instance.
        float elevationVal = sensorFusion.getElevation();

        // Display floor change buttons if the indoor map is currently set.
        if(indoorMapManager.getIsIndoorMapSet()){
            setFloorButtonVisibility(View.VISIBLE);
        }else{
            // Hide the buttons and switch if the indoor map is not visible.
            setFloorButtonVisibility(View.GONE);
        }

        // Log the current position for debugging purposes.
        Log.d("updateUIandPosition called", "Current position: " + latLng);
    }
}