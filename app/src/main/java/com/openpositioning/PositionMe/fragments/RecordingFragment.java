package com.openpositioning.PositionMe.fragments;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
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
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.TrajOptim;

import java.util.ArrayList;
import java.util.List;

/**
 * A simple {@link Fragment} subclass. The recording fragment is displayed while the app is actively
 * saving data, with UI elements and a map with a marker indicating current PDR location and
 * direction of movement status. The user's PDR trajectory/path being recorded
 * is drawn on the map as well.
 * An overlay of indoor maps for the building is achieved when the user is in the Nucleus
 * and Library buildings to allow for a better user experience.
 *
 * @see HomeFragment the previous fragment in the nav graph.
 * @see CorrectionFragment the next fragment in the nav graph.
 * @see SensorFusion the class containing sensors and recording.
 * @see IndoorMapManager responsible for overlaying the indoor floor maps
 *
 * @author Mate Stodulka
 * @author Arun Gopalakrishnan
 */
public class RecordingFragment extends Fragment {

    //Button to end PDR recording
    private Button stopButton;
    private Button cancelButton;
    //Recording icon to show user recording is in progress
    private ImageView recIcon;
    //Loading bar to show time remaining before recording automatically ends
    private ProgressBar timeRemaining;
    // Text views to display distance travelled and elevation since beginning of recording

    private TextView elevation;
    private TextView distanceTravelled;
    // Text view to show the error between current PDR and current GNSS
    private TextView gnssError;

    //App settings
    private SharedPreferences settings;
    //Singleton class to collect all sensor data
    private SensorFusion sensorFusion;
    //Timer to end recording
    private CountDownTimer autoStop;
    // Responsible for updating UI in Loop
    private Handler refreshDataHandler;

    //variables to store data of the trajectory
    private float distance;
    private float previousPosX;
    private float previousPosY;

    // Starting point coordinates
    private static LatLng start;
    // Storing the google map object
    private GoogleMap gMap;
    //Switch Map Dropdown
    private Spinner switchMapSpinner;
    //Map Marker
    private Marker orientationMarker;
    // Current Location coordinates
    private LatLng currentLocation;
    // Next Location coordinates
    private LatLng nextLocation;
    // Stores the polyline object for plotting path
    private Polyline polyline;
    // Manages overlaying of the indoor maps
    public IndoorMapManager indoorMapManager;
    // Floor Up button
    public FloatingActionButton floorUpButton;
    // Floor Down button
    public FloatingActionButton floorDownButton;
    // GNSS Switch
    private Switch gnss;
    // GNSS marker
    private Marker gnssMarker;
    // WiFi marker
    private Marker wifiMarker;
    // WiFi Switch
    private Switch wifiSwitch;
    // Button used to switch colour
    private Button switchColor;
    // Current color of polyline
    private boolean isRed=true;
    // Switch used to set auto floor
    private Switch autoFloor;
    // Algorithm switch dropdown
    private Spinner algorithmSwitchSpinner;
    // Flag to track if recording is in progress
    private boolean isRecording = false;
    // Flag to track if signal is available for recording
    private boolean isSignalAvailable = false;
    // TextView to show waiting for signal message
    private TextView waitingForSignalText;
    
    // --- Particle Filter Integration Variables ---
    // Particle filter instance for sensor fusion
    private com.openpositioning.PositionMe.FusionFilter.ParticleFilter particleFilter;
    // Extended Kalman filter instance for sensor fusion
    private com.openpositioning.PositionMe.FusionFilter.EKFFilter ekfFilter;
    // Polyline for the fused path (blue)
    private Polyline fusedPolyline;
    // Store the fused position 
    private LatLng currentFusedPosition;
    // Next fused location
    private LatLng nextFusedLocation;
    // Flag indicating if we're in indoor environment
    private boolean isIndoor = false;
    // Flag indicating if particle filter is active
    private boolean isParticleFilterActive = false;
    // Flag indicating if EKF filter is active
    private boolean isEKFActive = false;
    // Flag indicating if batch optimization is active 
    private boolean isBatchOptimizationActive = false;
    // List to store fused trajectory points
    private List<LatLng> fusedTrajectoryPoints = new ArrayList<>();
    // --- End Particle Filter Integration Variables ---

    // Switch for trajectory smoothing
    private Switch smoothSwitch;
    // Flag to track if smoothing is active
    private boolean isSmoothing = false;
    // Store the last smoothed position for low-pass filter
    private LatLng lastSmoothedPosition = null;
    // Alpha value for low-pass filter (0-1)
    private final float SMOOTHING_ALPHA = 0.3f;

    /**
     * Public Constructor for the class.
     * Left empty as not required
     */
    public RecordingFragment() {
        // Required empty public constructor
    }

    /**
     * {@inheritDoc}
     * Gets an instance of the {@link SensorFusion} class, and initialises the context and settings.
     * Creates a handler for periodically updating the displayed data.
     * Starts recording and initializes the start position since navigation now comes directly from HomeFragment.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.sensorFusion = SensorFusion.getInstance();
        Context context = getActivity();
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.refreshDataHandler = new Handler();
        
        // Initialize fusion variables
        this.isParticleFilterActive = false;
        this.isEKFActive = false;
        this.isBatchOptimizationActive = false;
        this.isIndoor = false;
        this.fusedTrajectoryPoints = new ArrayList<>();
        
        // Do not initialize recording here - wait for WiFi position if indoors
        // Will be started in the start button handler after valid position is confirmed
    }

    /**
     * {@inheritDoc}
     * Set title in action bar to "Recording"
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_recording, container, false);
        // Inflate the layout for this fragment
        ((AppCompatActivity)getActivity()).getSupportActionBar().hide();
        getActivity().setTitle("Recording...");
        //Obtain start position that was set in onCreate
        float[] startPosition = sensorFusion.getGNSSLatitude(true);

        // Initialize map fragment
        SupportMapFragment supportMapFragment=(SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.RecordingMap);
        // Asynchronous map which can be configured
        supportMapFragment.getMapAsync(new OnMapReadyCallback() {
            /**
             * {@inheritDoc}
             * Controls to allow scrolling, tilting, rotating and a compass view of the
             * map are enabled. A marker is added to the map with the start position and
             * the compass indicating user direction. A polyline object is initialised
             * to plot user direction.
             * Initialises the manager to control indoor floor map overlays.
             *
             * @param map      Google map to be configured
             */
            @Override
            public void onMapReady(GoogleMap map) {
                gMap=map;
                //Initialising the indoor map manager object
                indoorMapManager =new IndoorMapManager(map);
                // Setting map attributes
                map.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                map.getUiSettings().setCompassEnabled(true);
                map.getUiSettings().setTiltGesturesEnabled(true);
                map.getUiSettings().setRotateGesturesEnabled(true);
                map.getUiSettings().setScrollGesturesEnabled(true);

                // Add a marker at the start position and move the camera
                start = new LatLng(startPosition[0], startPosition[1]);
                currentLocation=start;
                orientationMarker=map.addMarker(new MarkerOptions().position(start).title("Current Position")
                        .flat(true)
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(getContext(),R.drawable.ic_baseline_navigation_24))));
                //Center the camera
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, (float) 19f));
                // Adding polyline to map to plot real-time trajectory
                PolylineOptions polylineOptions=new PolylineOptions()
                        .color(Color.RED)
                        .add(currentLocation)
                        .width(10f)
                        .zIndex(10f);
                polyline = gMap.addPolyline(polylineOptions);
                
                // Add polyline for fused trajectory (blue)
                PolylineOptions fusedPolylineOptions = new PolylineOptions()
                        .color(Color.BLUE)
                        .width(8f)
                        .add(currentLocation)
                        .visible(isParticleFilterActive || isEKFActive)
                        .zIndex(20f);
                fusedPolyline = gMap.addPolyline(fusedPolylineOptions);
                
                // Initialize fused position with current location
                currentFusedPosition = currentLocation;
                
                // Setting current location to set Ground Overlay for indoor map (if in building)
                indoorMapManager.setCurrentLocation(currentLocation);
                //Showing an indication of available indoor maps using PolyLines
                indoorMapManager.setIndicationOfIndoorMap();
            }
        });

        return rootView;
    }

    /**
     * {@inheritDoc}
     * Text Views and Icons initialised to display the current PDR to the user. A Button onClick
     * listener is enabled to detect when to go to next fragment and allow the user to correct PDR.
     * Other onClick, onCheckedChange and onSelectedItem Listeners for buttons, switch and spinner
     * are defined to allow user to change UI and functionality of the recording page as wanted
     * by the user.
     * A runnable thread is called to update the UI every 0.2 seconds.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Set autoStop to null for repeat recordings
        this.autoStop = null;

        //Initialise UI components
        this.elevation = getView().findViewById(R.id.currentElevation);
        this.distanceTravelled = getView().findViewById(R.id.currentDistanceTraveled);
        this.gnssError =getView().findViewById(R.id.gnssError);


        //Set default text of TextViews to 0
        this.gnssError.setVisibility(View.GONE);
        this.elevation.setText(getString(R.string.elevation, "0"));
        this.distanceTravelled.setText(getString(R.string.meter, "0"));

        //Reset variables to 0
        this.distance = 0f;
        this.previousPosX = 0f;
        this.previousPosY = 0f;

        // Initialize waiting for signal text
        this.waitingForSignalText = getView().findViewById(R.id.waitingForSignalText);
        
        // Initialize recording icon (red dot) as invisible - only shown when recording
        this.recIcon = getView().findViewById(R.id.redDot);
        this.recIcon.setVisibility(View.GONE);
        
        // Initialize particle filter as active by default (set in algorithDropdown)
        isParticleFilterActive = true;
        
        // Start/Stop button to control recording and save trajectory
        this.stopButton = getView().findViewById(R.id.stopButton);
        // Initially set as "Start" and disabled
        this.stopButton.setText(getString(R.string.start));
        this.stopButton.setEnabled(false);
        this.stopButton.setBackgroundColor(Color.GRAY);
        // Show waiting for signal message
        this.waitingForSignalText.setVisibility(View.VISIBLE);
        this.waitingForSignalText.setText(getString(R.string.waiting_for_signal));
        
        this.stopButton.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * OnClick listener for Start/Stop button.
             * When clicked in "Start" mode, it begins recording and changes to "Stop".
             * When clicked in "Stop" mode, it stops recording and navigates to CorrectionFragment.
             */
            @Override
            public void onClick(View view) {
                if (!isRecording) {
                    // Start recording
                    isRecording = true;
                    stopButton.setText(getString(R.string.stop));
                    
                    // Set the start location based on environment (indoor/outdoor)
                    if (isIndoor) {
                        // For indoor, use WiFi position if available
                        LatLng wifiPosition = sensorFusion.getLatLngWifiPositioning();
                        if (wifiPosition != null) {
                            // Convert to float array for SensorFusion
                            float[] wifiArray = new float[] {(float)wifiPosition.latitude, (float)wifiPosition.longitude};
                            sensorFusion.setStartGNSSLatitude(wifiArray);
                        } else {
                            // Fallback to GNSS if WiFi not available
                            float[] gnssPosition = sensorFusion.getGNSSLatitude(false);
                            sensorFusion.setStartGNSSLatitude(gnssPosition);
                        }
                    } else {
                        // For outdoor, use GNSS position
                        float[] gnssPosition = sensorFusion.getGNSSLatitude(false);
                        sensorFusion.setStartGNSSLatitude(gnssPosition);
                    }
                    
                    // Start the actual recording
                    sensorFusion.startRecording();
                    // Display a blinking red dot to show recording is in progress
                    blinkingRecording();
                    // Start the refreshing tasks
                    if (!settings.getBoolean("split_trajectory", false)) {
                        refreshDataHandler.post(refreshDataTask);
                    } else {
                        // If that time limit has been reached:
                        long limit = settings.getInt("split_duration", 30) * 60000L;
                        // Set progress bar
                        timeRemaining.setMax((int) (limit/1000));
                        timeRemaining.setScaleY(3f);
                        
                        // Create a CountDownTimer object to adhere to the time limit
                        autoStop = new CountDownTimer(limit, 1000) {
                            @Override
                            public void onTick(long l) {
                                // increment progress bar
                                timeRemaining.incrementProgressBy(1);
                                // Get new position and update UI
                                updateUIandPosition();
                            }

                            @Override
                            public void onFinish() {
                                // Timer done, move to next fragment automatically - will stop recording
                                sensorFusion.stopRecording();
                                NavDirections action = RecordingFragmentDirections.actionRecordingFragmentToCorrectionFragment();
                                Navigation.findNavController(view).navigate(action);
                            }
                        }.start();
                    }
                } else {
                    // Stop recording
                    isRecording = false;
                    if(autoStop != null) autoStop.cancel();
                    sensorFusion.stopRecording();
                    NavDirections action = RecordingFragmentDirections.actionRecordingFragmentToCorrectionFragment();
                    Navigation.findNavController(view).navigate(action);
                }
            }
        });

        // Cancel button to discard trajectory and return to Home
        this.cancelButton = getView().findViewById(R.id.cancelButton);
        this.cancelButton.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * OnClick listener for button to go to home fragment.
             * When button clicked the PDR recording is stopped (if recording) and the {@link HomeFragment} is loaded.
             * The trajectory is not saved.
             */
            @Override
            public void onClick(View view) {
                // Only stop recording if we're actually recording
                if (isRecording) {
                    sensorFusion.stopRecording();
                    if(autoStop != null) autoStop.cancel();
                }
                NavDirections action = RecordingFragmentDirections.actionRecordingFragmentToHomeFragment();
                Navigation.findNavController(view).navigate(action);
            }
        });
        // Configuring dropdown for switching map types
        mapDropdown();
        // Setting listener for the switching map types dropdown
        switchMap();
        // Configuring dropdown for switching positioning algorithms
        algorithmDropdown();
        // Setting listener for the switching positioning algorithms dropdown
        switchAlgorithm();
        // Floor changer Buttons
        this.floorUpButton=getView().findViewById(R.id.floorUpButton);
        this.floorDownButton=getView().findViewById(R.id.floorDownButton);
        // Auto-floor switch
        this.autoFloor=getView().findViewById(R.id.autoFloor);
        autoFloor.setChecked(true);
        // Hiding floor changing buttons and auto-floor switch
        setFloorButtonVisibility(View.GONE);
        this.floorUpButton.setOnClickListener(new View.OnClickListener() {
            /**
             *{@inheritDoc}
             * Listener for increasing the floor for the indoor map
             */
            @Override
            public void onClick(View view) {
                // Setting off auto-floor as manually changed
                autoFloor.setChecked(false);
                indoorMapManager.increaseFloor();
            }
        });
        this.floorDownButton.setOnClickListener(new View.OnClickListener() {
            /**
             *{@inheritDoc}
             * Listener for decreasing the floor for the indoor map
             */
            @Override
            public void onClick(View view) {
                // Setting off auto-floor as manually changed
                autoFloor.setChecked(false);
                indoorMapManager.decreaseFloor();
            }
        });
        //Obtain the GNSS toggle switch
        this.gnss= getView().findViewById(R.id.gnssSwitch);

        this.gnss.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            /**
             * {@inheritDoc}
             * Listener to set GNSS marker and show GNSS vs PDR error.
             */
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                if (isChecked){
                    // Show GNSS eror
                    float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
                    LatLng gnssLocation = null;
                    
                    // Validate location data before creating LatLng
                    if (location != null && location.length >= 2 && 
                        !Float.isNaN(location[0]) && !Float.isNaN(location[1]) &&
                        !Float.isInfinite(location[0]) && !Float.isInfinite(location[1])) {
                        gnssLocation = new LatLng(location[0], location[1]);
                    }
                    
                    gnssError.setVisibility(View.VISIBLE);
                    gnssError.setText(String.format(getString(R.string.gnss_error)+"%.2fm",
                            UtilFunctions.distanceBetweenPoints(currentLocation,gnssLocation)));
                    // Set GNSS marker
                    gnssMarker=gMap.addMarker(
                            new MarkerOptions().title("GNSS position")
                                    .position(gnssLocation)
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                }else {
                    gnssMarker.remove();
                    gnssError.setVisibility(View.GONE);
                }
            }
        });

        //Obtain the WiFi toggle switch
        this.wifiSwitch = getView().findViewById(R.id.wifiSwitch);
        this.wifiSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            /**
             * {@inheritDoc}
             * Listener to set WiFi positioning marker.
             */
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                if (isChecked) {
                    // Get WiFi position
                    LatLng wifiLocation = sensorFusion.getLatLngWifiPositioning();
                    // Add WiFi marker with blue color if location is available
                    if (wifiLocation != null) {
                        wifiMarker = gMap.addMarker(
                                new MarkerOptions().title("WiFi position")
                                        .position(wifiLocation)
                                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
                    } else {
                        // Inform user that WiFi positioning isn't available
                        wifiSwitch.setChecked(false);
                        Toast.makeText(getContext(), "WiFi positioning not available", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    if (wifiMarker != null) {
                        wifiMarker.remove();
                    }
                }
            }
        });
        
        // Switch colour button
        this.switchColor=getView().findViewById(R.id.lineColorButton);
        this.switchColor.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * Listener to button to switch the colour of the polyline
             * to red/black
             */
            @Override
            public void onClick(View view) {
                if (isRed){
                    switchColor.setBackgroundColor(Color.BLACK);
                    polyline.setColor(Color.BLACK);
                    isRed=false;
                }
                else {
                    switchColor.setBackgroundColor(Color.RED);
                    polyline.setColor(Color.RED);
                    isRed=true;
                }
            }
        });

        // Display the progress of the recording when a max record length is set
        this.timeRemaining = getView().findViewById(R.id.timeRemainingBar);

        // Check for signal availability periodically
        checkSignalAvailability();
        
        // Obtain the Smooth toggle switch
        this.smoothSwitch = getView().findViewById(R.id.smoothSwitch);
        this.smoothSwitch.setChecked(false); // Default off
        this.smoothSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            /**
             * {@inheritDoc}
             * Listener to enable/disable trajectory smoothing.
             */
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                isSmoothing = isChecked;
                if (isChecked) {
                    // When smoothing is enabled, set the last smoothed position to current position
                    if ((isParticleFilterActive || isEKFActive) && currentFusedPosition != null) {
                        lastSmoothedPosition = currentFusedPosition;
                    }
                    Toast.makeText(getContext(), "Trajectory smoothing enabled", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "Trajectory smoothing disabled", Toast.LENGTH_SHORT).show();
                }
            }
        });
        
        // Remove existing recording start logic and let the Start button handle it
    }

    /**
     * Check for GNSS or WiFi signal availability and update the Start button accordingly
     */
    private void checkSignalAvailability() {
        Handler signalCheckHandler = new Handler();
        signalCheckHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Check if GNSS or WiFi signals are available
                boolean hasGnssSignal = false;
                boolean hasWifiSignal = false;
                
                // Check GNSS availability
                if (sensorFusion.getSensorValueMap().containsKey(SensorTypes.GNSSLATLONG)) {
                    float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
                    if (location != null && location.length >= 2) {
                        hasGnssSignal = true;
                    }
                }
                
                // Check if WiFi position not available, try GNSS
                LatLng wifiLocation = sensorFusion.getLatLngWifiPositioning();
                if (wifiLocation != null) {
                    hasWifiSignal = true;
                    // If this is our first WiFi position, determine we're indoors
                    if (!isSignalAvailable && !isRecording) {
                        isIndoor = true;
                        // Update currentLocation to WiFi position for indoor environment
                        currentLocation = wifiLocation;
                        currentFusedPosition = wifiLocation;
                        
                        // Update marker position if marker exists
                        if (orientationMarker != null) {
                            orientationMarker.setPosition(wifiLocation);
                            // Move camera to WiFi position
                            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(wifiLocation, 19f));
                            
                            // Reset polylines with new start position
                            List<LatLng> points = new ArrayList<>();
                            points.add(wifiLocation);
                            polyline.setPoints(points);
                            fusedPolyline.setPoints(points);
                        }
                        
                        // Validate WiFi position before initializing particle filter
                        if (wifiLocation != null && 
                            !Double.isNaN(wifiLocation.latitude) && !Double.isNaN(wifiLocation.longitude) &&
                            !Double.isInfinite(wifiLocation.latitude) && !Double.isInfinite(wifiLocation.longitude) &&
                            Math.abs(wifiLocation.latitude) <= 90 && Math.abs(wifiLocation.longitude) <= 180 &&
                            !(wifiLocation.latitude == 0 && wifiLocation.longitude == 0)) {
                            try {
                                particleFilter = new com.openpositioning.PositionMe.FusionFilter.ParticleFilter(wifiLocation);
                                Log.d("RecordingFragment", "Indoor environment detected, using WiFi position as start");
                            } catch (IllegalArgumentException e) {
                                Log.e("RecordingFragment", "Failed to initialize particle filter with WiFi position: " + e.getMessage());
                                particleFilter = null;
                            }
                        } else {
                            Log.e("RecordingFragment", "Invalid WiFi position for particle filter initialization");
                        }
                    }
                } else if (hasGnssSignal && !isSignalAvailable && !isRecording) {
                    // If no WiFi but GNSS available, we're outdoors
                    isIndoor = false;
                    // Initialize particle filter with GNSS position
                    float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
                    // Validate GNSS location before initializing particle filter
                    if (location != null && location.length >= 2 && 
                        !Float.isNaN(location[0]) && !Float.isNaN(location[1]) &&
                        !Float.isInfinite(location[0]) && !Float.isInfinite(location[1]) &&
                        Math.abs(location[0]) <= 90 && Math.abs(location[1]) <= 180 &&
                        !(location[0] == 0 && location[1] == 0)) {
                        try {
                            LatLng gnssLocation = new LatLng(location[0], location[1]);
                            particleFilter = new com.openpositioning.PositionMe.FusionFilter.ParticleFilter(gnssLocation);
                            Log.d("RecordingFragment", "Outdoor environment detected, using GNSS position as start");
                        } catch (IllegalArgumentException e) {
                            Log.e("RecordingFragment", "Failed to initialize particle filter with GNSS position: " + e.getMessage());
                            particleFilter = null;
                        }
                    } else {
                        Log.e("RecordingFragment", "Invalid GNSS position for particle filter initialization");
                    }
                }
                
                // Update button state based on signal availability
                isSignalAvailable = hasGnssSignal || hasWifiSignal;
                if (isSignalAvailable && !isRecording) {
                    // Enable Start button if signal is available and not recording
                    stopButton.setEnabled(true);
                    stopButton.setBackgroundColor(Color.BLUE);
                    waitingForSignalText.setVisibility(View.GONE);
                } else if (!isSignalAvailable && !isRecording) {
                    // Keep button disabled if no signal and update message
                    stopButton.setEnabled(false);
                    stopButton.setBackgroundColor(Color.GRAY);
                    waitingForSignalText.setVisibility(View.VISIBLE);
                }
                
                // Check again after a delay (500ms)
                signalCheckHandler.postDelayed(this, 500);
            }
        }, 500); // Initial delay of 500ms
        
        // Start periodic position refresh before recording starts
        startPreRecordingPositionRefresh();
    }

    /**
     * Periodically refreshes the marker and camera position before recording starts
     */
    private void startPreRecordingPositionRefresh() {
        Handler preRecordingHandler = new Handler();
        preRecordingHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Only update if not recording yet
                if (!isRecording) {
                    // Try to get WiFi position first (for indoor)
                    LatLng newPosition = sensorFusion.getLatLngWifiPositioning();
                    
                    // If WiFi position not available, try GNSS
                    if (newPosition == null && sensorFusion.getSensorValueMap().containsKey(SensorTypes.GNSSLATLONG)) {
                        float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
                        if (location != null && location.length >= 2 && 
                            !Float.isNaN(location[0]) && !Float.isNaN(location[1]) &&
                            !Float.isInfinite(location[0]) && !Float.isInfinite(location[1])) {
                            newPosition = new LatLng(location[0], location[1]);
                        }
                    }
                    
                    // Update marker and camera if we have a valid position
                    if (newPosition != null && gMap != null && orientationMarker != null) {
                        // Additional validation to prevent invalid coordinates
                        if (!Double.isNaN(newPosition.latitude) && !Double.isNaN(newPosition.longitude) &&
                            !Double.isInfinite(newPosition.latitude) && !Double.isInfinite(newPosition.longitude) &&
                            Math.abs(newPosition.latitude) <= 90 && Math.abs(newPosition.longitude) <= 180 &&
                            !(newPosition.latitude == 0 && newPosition.longitude == 0)) {
                            
                            currentLocation = newPosition;
                            currentFusedPosition = newPosition;
                            orientationMarker.setPosition(newPosition);
                            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newPosition, 19f));
                            
                            // Reset polylines with new position
                            List<LatLng> points = new ArrayList<>();
                            points.add(newPosition);
                            if (polyline != null) {
                                polyline.setPoints(points);
                            }
                            if (fusedPolyline != null) {
                                fusedPolyline.setPoints(points);
                            }
                            
                            // If particle filter is active but not initialized, try to initialize it
                            if (isParticleFilterActive && particleFilter == null) {
                                try {
                                    particleFilter = new com.openpositioning.PositionMe.FusionFilter.ParticleFilter(newPosition);
                                    Log.d("RecordingFragment", "Initialized particle filter with position: " + 
                                          newPosition.latitude + ", " + newPosition.longitude);
                                } catch (IllegalArgumentException e) {
                                    Log.e("RecordingFragment", "Failed to initialize particle filter: " + e.getMessage());
                                    // Don't disable particle filter, we'll try again next time
                                }
                            }
                            // If EKF filter is active but not initialized, try to initialize it
                            else if (isEKFActive) {
                                try {
                                    ekfFilter = new com.openpositioning.PositionMe.FusionFilter.EKFFilter();
                                    // Initialize EKF with zero motion
                                    com.openpositioning.PositionMe.FusionFilter.EKFFilter.ekfFusion(
                                        newPosition, null, null, 0, 0);
                                    Log.d("RecordingFragment", "Initialized EKF filter with position: " + 
                                          newPosition.latitude + ", " + newPosition.longitude);
                                } catch (Exception e) {
                                    Log.e("RecordingFragment", "Failed to initialize EKF filter: " + e.getMessage());
                                    // Don't disable EKF filter, we'll try again next time
                                }
                            }
                        } else {
                            Log.w("RecordingFragment", "Invalid position received: " + 
                                  newPosition.latitude + ", " + newPosition.longitude);
                        }
                    }
                    
                    // Schedule next update in 5 seconds
                    preRecordingHandler.postDelayed(this, 5000);
                }
            }
        }, 5000); // Initial delay of 5 seconds
    }

    /**
     * Creates a dropdown for Changing maps
     */
    private void mapDropdown(){
        // Creating and Initialising options for Map's Dropdown Menu
        switchMapSpinner = (Spinner) getView().findViewById(R.id.mapSwitchSpinner);
        // Different Map Types
        String[] maps = new String[]{getString(R.string.hybrid), getString(R.string.normal), getString(R.string.satellite)};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, maps);
        // Set the Dropdowns menu adapter
        switchMapSpinner.setAdapter(adapter);
    }

    /**
     * Spinner listener to change map bap based on user input
     */
    private void switchMap(){
        // Switch between map type based on user input
        this.switchMapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            /**
             * {@inheritDoc}
             * OnItemSelected listener to switch maps.
             * The map switches between MAP_TYPE_NORMAL, MAP_TYPE_SATELLITE
             * and MAP_TYPE_HYBRID based on user selection.
             */
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position){
                    case 0:
                        gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                    case 1:
                        gMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                        break;
                    case 2:
                        gMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                        break;
                }
            }
            /**
             * {@inheritDoc}
             * When Nothing is selected set to MAP_TYPE_HYBRID (NORMAL and SATELLITE)
             */
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
            }
        });
    }

    /**
     * Creates a dropdown for switching positioning algorithms
     */
    private void algorithmDropdown() {
        // Creating and Initialising options for Algorithm's Dropdown Menu
        algorithmSwitchSpinner = (Spinner) getView().findViewById(R.id.algorithmSwitchSpinner);
        // Different Algorithm Types
        String[] algorithms = new String[]{"No Fusion", "EKF", "Batch optimisation", "Particle filter"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, algorithms);
        // Set the Dropdowns menu adapter
        algorithmSwitchSpinner.setAdapter(adapter);
        // Set Particle filter as default
        algorithmSwitchSpinner.setSelection(3);
        isParticleFilterActive = true;
    }

    /**
     * Spinner listener to change positioning algorithm based on user input
     */
    private void switchAlgorithm() {
        this.algorithmSwitchSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Reset all algorithm flags
                boolean wasFiltering = isParticleFilterActive || isEKFActive || isBatchOptimizationActive;
                isParticleFilterActive = false;
                isEKFActive = false;
                isBatchOptimizationActive = false;
                
                switch (position) {
                    case 0:
                        // No Fusion selected
                        if (fusedPolyline != null) {
                            fusedPolyline.setVisible(false);
                        }
                        Toast.makeText(getContext(), "No Fusion algorithm selected", Toast.LENGTH_SHORT).show();
                        break;
                    case 1:
                        // EKF selected
                        if (fusedPolyline != null) {
                            fusedPolyline.setVisible(true);
                        }
                        // Initialize EKF with current position
                        if (currentLocation != null) {
                            // Validate currentLocation before initializing
                            if (!Double.isNaN(currentLocation.latitude) && !Double.isNaN(currentLocation.longitude) &&
                                !Double.isInfinite(currentLocation.latitude) && !Double.isInfinite(currentLocation.longitude) &&
                                Math.abs(currentLocation.latitude) <= 90 && Math.abs(currentLocation.longitude) <= 180) {
                                
                                try {
                                    // Initialize EKF with current position
                                    ekfFilter = new com.openpositioning.PositionMe.FusionFilter.EKFFilter();
                                    // If we were previously using particle filter, continuity is maintained by using
                                    // the last fused position as the initial position for EKF
                                    LatLng initialPos = wasFiltering && currentFusedPosition != null ? 
                                                       currentFusedPosition : currentLocation;
                                    
                                    // Reset EKF initial state with current position
                                    com.openpositioning.PositionMe.FusionFilter.EKFFilter.ekfFusion(
                                        initialPos, null, null, 0, 0);
                                    
                                    Log.d("RecordingFragment", "Initialized EKF filter with position: " + 
                                          initialPos.latitude + ", " + initialPos.longitude);
                                    isEKFActive = true;
                                } catch (Exception e) {
                                    Log.e("RecordingFragment", "Failed to initialize EKF filter: " + e.getMessage());
                                    isEKFActive = false;
                                }
                            } else {
                                Log.e("RecordingFragment", "Invalid currentLocation for EKF initialization");
                                isEKFActive = false;
                            }
                        }
                        Toast.makeText(getContext(), "EKF algorithm selected", Toast.LENGTH_SHORT).show();
                        break;
                    case 2:
                        // Batch optimisation selected
                        if (fusedPolyline != null) {
                            fusedPolyline.setVisible(false);
                        }
                        isBatchOptimizationActive = false; // Not implemented
                        Toast.makeText(getContext(), "Batch optimisation algorithm selected", Toast.LENGTH_SHORT).show();
                        break;
                    case 3:
                        // Particle filter selected
                        if (fusedPolyline != null) {
                            fusedPolyline.setVisible(true);
                        }
                        // Initialize particle filter if not already done
                        if (currentLocation != null) {
                            // Validate currentLocation before initializing
                            if (!Double.isNaN(currentLocation.latitude) && !Double.isNaN(currentLocation.longitude) &&
                                !Double.isInfinite(currentLocation.latitude) && !Double.isInfinite(currentLocation.longitude) &&
                                Math.abs(currentLocation.latitude) <= 90 && Math.abs(currentLocation.longitude) <= 180) {
                                
                                try {
                                    // If we were previously using EKF, continuity is maintained by using
                                    // the last fused position as the initial position for particle filter
                                    LatLng initialPos = wasFiltering && currentFusedPosition != null ? 
                                                       currentFusedPosition : currentLocation;
                                    
                                    particleFilter = new com.openpositioning.PositionMe.FusionFilter.ParticleFilter(initialPos);
                                    Log.d("RecordingFragment", "Initialized particle filter with position: " + 
                                          initialPos.latitude + ", " + initialPos.longitude);
                                    isParticleFilterActive = true;
                                } catch (IllegalArgumentException e) {
                                    Log.e("RecordingFragment", "Failed to initialize particle filter: " + e.getMessage());
                                    isParticleFilterActive = false;
                                }
                            } else {
                                Log.e("RecordingFragment", "Invalid currentLocation for particle filter initialization");
                                isParticleFilterActive = false;
                            }
                        } else {
                            // If we can't initialize now, still mark it active but we'll try again later
                            isParticleFilterActive = true;
                        }
                        Toast.makeText(getContext(), "Particle filter algorithm selected", Toast.LENGTH_SHORT).show();
                        break;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Default to No Fusion when nothing selected
                isParticleFilterActive = false;
                isEKFActive = false;
                isBatchOptimizationActive = false;
                if (fusedPolyline != null) {
                    fusedPolyline.setVisible(false);
                }
            }
        });
    }
    /**
     * Runnable task used to refresh UI elements with live data.
     * Has to be run through a Handler object to be able to alter UI elements
     */
    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            // Get new position and update UI
            updateUIandPosition();
            // Loop the task again to keep refreshing the data
            refreshDataHandler.postDelayed(refreshDataTask, 200);
        }
    };

    /**
     * Updates the UI, traces PDR Position on the map
     * and also updates marker representing the current location and direction on the map
     */
    private void updateUIandPosition(){
        // Get new position
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        // Calculate distance travelled
        distance += Math.sqrt(Math.pow(pdrValues[0] - previousPosX, 2) + Math.pow(pdrValues[1] - previousPosY, 2));
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));
        // Net pdr movement
        float[] pdrMoved={pdrValues[0]-previousPosX,pdrValues[1]-previousPosY};
        // if PDR has changed plot new line to indicate user movement
        if (pdrMoved[0]!=0 ||pdrMoved[1]!=0) {
            plotLines(pdrMoved);
        }
        // If not initialized, initialize
        if (indoorMapManager == null) {
            indoorMapManager =new IndoorMapManager(gMap);
        }
        //Show GNSS marker and error if user enables it
        if (gnss.isChecked() && gnssMarker!=null){
            float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
            LatLng gnssLocation = null;
            
            // Validate location data before creating LatLng
            if (location != null && location.length >= 2 && 
                !Float.isNaN(location[0]) && !Float.isNaN(location[1]) &&
                !Float.isInfinite(location[0]) && !Float.isInfinite(location[1])) {
                gnssLocation = new LatLng(location[0], location[1]);
            }
            
            gnssError.setVisibility(View.VISIBLE);
            
            // Show error based on current location (PDR or fused)
            if ((isParticleFilterActive || isEKFActive) && currentFusedPosition != null) {
                if (gnssLocation != null && currentFusedPosition != null) {
                    gnssError.setText(String.format(getString(R.string.gnss_error)+"%.2fm",
                        UtilFunctions.distanceBetweenPoints(currentFusedPosition, gnssLocation)));
                } else {
                    gnssError.setText(getString(R.string.gnss_error)+"N/A");
                }
            } else {
                if (gnssLocation != null && currentLocation != null) {
                    gnssError.setText(String.format(getString(R.string.gnss_error)+"%.2fm",
                        UtilFunctions.distanceBetweenPoints(currentLocation, gnssLocation)));
                } else {
                    gnssError.setText(getString(R.string.gnss_error)+"N/A");
                }
            }
            
            // Update the marker position only if we have a valid gnssLocation
            if (gnssLocation != null) {
                gnssMarker.setPosition(gnssLocation);
            }
        }
        
        //Show WiFi positioning marker if enabled
        if (wifiSwitch.isChecked() && wifiMarker != null) {
            LatLng wifiLocation = sensorFusion.getLatLngWifiPositioning();
            if (wifiLocation != null) {
                wifiMarker.setPosition(wifiLocation);
            }
        }

        //  Updates current location of user to show the indoor floor map (if applicable)
        if ((isParticleFilterActive || isEKFActive) && currentFusedPosition != null) {
            // Use fused position for indoor map when any fusion filter is active
            indoorMapManager.setCurrentLocation(currentFusedPosition);
        } else {
            indoorMapManager.setCurrentLocation(currentLocation);
        }
        
        float elevationVal = sensorFusion.getElevation();
        // Display buttons to allow user to change floors if indoor map is visible
        if(indoorMapManager.getIsIndoorMapSet()){
            setFloorButtonVisibility(View.VISIBLE);
            // Auto-floor logic
            if(autoFloor.isChecked()){
                // Get floor from WiFi positioning when available, default to calculated floor
                int wifiFloor = 0;
                if (sensorFusion.getLatLngWifiPositioning() != null) {
                    wifiFloor = sensorFusion.getWifiFloor();
                    indoorMapManager.setCurrentFloor(wifiFloor, true);
                } else {
                    // Fallback to elevation calculation if WiFi positioning not available
                    indoorMapManager.setCurrentFloor((int)(elevationVal/indoorMapManager.getFloorHeight()), true);
                }
            }
        }else{
            // Hide the buttons and switch used to change floor if indoor map is not visible
            setFloorButtonVisibility(View.GONE);
        }
        // Store previous PDR values for next call
        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];
        // Display elevation
        elevation.setText(getString(R.string.elevation, String.format("%.1f", elevationVal)));
        //Rotate compass Marker according to direction of movement
        if (orientationMarker!=null) {
            orientationMarker.setRotation((float) Math.toDegrees(sensorFusion.passOrientation()));
        }
    }
    /**
     * Plots the users location based on movement in Real-time
     * @param pdrMoved Contains the change in PDR in X and Y directions
     */
    private void plotLines(float[] pdrMoved){
        if (currentLocation!=null){
            // Calculate new position based on net PDR movement
            nextLocation=UtilFunctions.calculateNewPos(currentLocation,pdrMoved);
                //Try catch to prevent exceptions from crashing the app
                try{
                    // Adds new location to polyline to plot the PDR path of user
                    List<LatLng> pointsMoved = polyline.getPoints();
                    pointsMoved.add(nextLocation);
                    polyline.setPoints(pointsMoved);
                    
                    // Apply sensor fusion based on selected algorithm
                    if (isParticleFilterActive || isEKFActive) {
                        // Get current WiFi and GNSS positions
                        LatLng wifiPosition = null;
                        LatLng gnssPosition = null;
                        
                        // Get WiFi position if available and indoors
                        if (isIndoor) {
                            wifiPosition = sensorFusion.getLatLngWifiPositioning();
                        }
                        
                        // Get GNSS position if outdoors or WiFi not available
                        if (!isIndoor || wifiPosition == null) {
                            if (sensorFusion.getSensorValueMap().containsKey(SensorTypes.GNSSLATLONG)) {
                                float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
                                if (location != null && location.length >= 2) {
                                    gnssPosition = new LatLng(location[0], location[1]);
                                }
                            }
                        }
                        
                        try {
                            // Apply filter based on selected algorithm
                            if (isParticleFilterActive) {
                                // Initialize particle filter if needed
                                if (particleFilter == null) {
                                    try {
                                        // Validate current location before initializing
                                        if (!Double.isNaN(currentLocation.latitude) && !Double.isNaN(currentLocation.longitude) &&
                                            !Double.isInfinite(currentLocation.latitude) && !Double.isInfinite(currentLocation.longitude) &&
                                            Math.abs(currentLocation.latitude) <= 90 && Math.abs(currentLocation.longitude) <= 180 &&
                                            !(currentLocation.latitude == 0 && currentLocation.longitude == 0)) {
                                            particleFilter = new com.openpositioning.PositionMe.FusionFilter.ParticleFilter(currentLocation);
                                            Log.d("RecordingFragment", "Initialized particle filter in plotLines");
                                        } else {
                                            // Skip particle filter processing this iteration
                                            throw new IllegalArgumentException("Invalid position for filter initialization");
                                        }
                                    } catch (IllegalArgumentException e) {
                                        Log.e("RecordingFragment", "Could not initialize particle filter: " + e.getMessage());
                                        // Update marker with PDR position and continue
                                        orientationMarker.setPosition(nextLocation);
                                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(nextLocation, (float) 19f));
                                        currentLocation = nextLocation;
                                        return;
                                    }
                                }
                                
                                // Apply particle filter
                                nextFusedLocation = particleFilter.particleFilter(wifiPosition, gnssPosition, nextLocation);
                            } else if (isEKFActive) {
                                // Apply EKF filter
                                // Note: EKF requires PDR increments rather than absolute position
                                nextFusedLocation = com.openpositioning.PositionMe.FusionFilter.EKFFilter.ekfFusion(
                                    currentFusedPosition != null ? currentFusedPosition : currentLocation,
                                    wifiPosition, 
                                    gnssPosition,
                                    pdrMoved[0], // dx
                                    pdrMoved[1]  // dy
                                );
                            } else {
                                // This should not happen - fallback to PDR position
                                nextFusedLocation = nextLocation;
                            }
                            
                            // Apply smoothing if enabled
                            if (isSmoothing && nextFusedLocation != null) {
                                // Use TrajOptim's low-pass filter for smoothing
                                nextFusedLocation = TrajOptim.applyLowPassFilter(
                                    lastSmoothedPosition, nextFusedLocation, SMOOTHING_ALPHA);
                                // Update last smoothed position for next iteration
                                lastSmoothedPosition = nextFusedLocation;
                            }
                            
                            // Update fused trajectory if we have a valid fusion result
                            if (nextFusedLocation != null && 
                                !Double.isNaN(nextFusedLocation.latitude) && !Double.isNaN(nextFusedLocation.longitude) &&
                                !Double.isInfinite(nextFusedLocation.latitude) && !Double.isInfinite(nextFusedLocation.longitude)) {
                                
                                // Update fused trajectory
                                List<LatLng> fusedPoints = fusedPolyline.getPoints();
                                fusedPoints.add(nextFusedLocation);
                                fusedPolyline.setPoints(fusedPoints);
                                
                                // Store for later use
                                fusedTrajectoryPoints.add(nextFusedLocation);
                                
                                // Update marker position with fused position
                                orientationMarker.setPosition(nextFusedLocation);
                                // Move camera to fused position
                                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(nextFusedLocation, (float) 19f));
                                
                                // Update current fused position
                                currentFusedPosition = nextFusedLocation;
                            } else {
                                // Invalid fusion result, fall back to PDR
                                Log.w("RecordingFragment", "Invalid fusion result, falling back to PDR");
                                orientationMarker.setPosition(nextLocation);
                                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(nextLocation, (float) 19f));
                            }
                        } catch (Exception e) {
                            // If filter fails, fall back to PDR position
                            Log.e("RecordingFragment", "Filter error: " + e.getMessage());
                            orientationMarker.setPosition(nextLocation);
                            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(nextLocation, (float) 19f));
                        }
                    } else {
                        // No fusion active, use PDR position directly
                        orientationMarker.setPosition(nextLocation);
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(nextLocation, (float) 19f));
                    }
                }
                catch (Exception ex){
                    Log.e("PlottingPDR","Exception: "+ex);
                }
                currentLocation=nextLocation;
        }
        else{
            //Initialise the starting location
            float[] location = sensorFusion.getGNSSLatitude(true);
            // Validate location data before creating LatLng
            if (location != null && location.length >= 2 && 
                !Float.isNaN(location[0]) && !Float.isNaN(location[1]) &&
                !Float.isInfinite(location[0]) && !Float.isInfinite(location[1]) &&
                Math.abs(location[0]) <= 90 && Math.abs(location[1]) <= 180) {
                
                currentLocation = new LatLng(location[0], location[1]);
                nextLocation = currentLocation;
                currentFusedPosition = currentLocation;
                nextFusedLocation = currentLocation;
                
                // Initialize filters based on currently selected algorithm
                if (isParticleFilterActive) {
                    // Initialize particle filter
                    if (particleFilter == null) {
                        try {
                            // Additional check for 0,0 coordinates which are invalid
                            if (!(currentLocation.latitude == 0 && currentLocation.longitude == 0)) {
                                particleFilter = new com.openpositioning.PositionMe.FusionFilter.ParticleFilter(currentLocation);
                                Log.d("RecordingFragment", "Initialized particle filter with starting location");
                            }
                        } catch (IllegalArgumentException e) {
                            Log.e("RecordingFragment", "Failed to initialize particle filter: " + e.getMessage());
                        }
                    }
                } else if (isEKFActive) {
                    // Initialize EKF filter
                    try {
                        if (!(currentLocation.latitude == 0 && currentLocation.longitude == 0)) {
                            ekfFilter = new com.openpositioning.PositionMe.FusionFilter.EKFFilter();
                            // Initialize EKF with zero motion (dx=0, dy=0)
                            com.openpositioning.PositionMe.FusionFilter.EKFFilter.ekfFusion(
                                currentLocation, null, null, 0, 0);
                            Log.d("RecordingFragment", "Initialized EKF filter with starting location");
                        }
                    } catch (Exception e) {
                        Log.e("RecordingFragment", "Failed to initialize EKF filter: " + e.getMessage());
                    }
                }
            } else {
                Log.e("RecordingFragment", "Invalid GNSS data for starting position");
            }
        }
    }

    /**
     * Function to set change visibility of the floor up and down buttons
     * @param visibility the visibility of floor buttons should be set to
     */
    private void setFloorButtonVisibility(int visibility){
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        autoFloor.setVisibility(visibility);
    }
    /**
     * Displays a blinking red dot to signify an ongoing recording.
     *
     * @see Animation for makin the red dot blink.
     */
    private void blinkingRecording() {
        //Initialise Image View
        this.recIcon = getView().findViewById(R.id.redDot);
        
        // Make the red dot visible
        this.recIcon.setVisibility(View.VISIBLE);
        
        //Configure blinking animation
        Animation blinking_rec = new AlphaAnimation(1, 0);
        blinking_rec.setDuration(800);
        blinking_rec.setInterpolator(new LinearInterpolator());
        blinking_rec.setRepeatCount(Animation.INFINITE);
        blinking_rec.setRepeatMode(Animation.REVERSE);
        recIcon.startAnimation(blinking_rec);
    }

    /**
     * {@inheritDoc}
     * Stops ongoing refresh task, but not the countdown timer which stops automatically
     */
    @Override
    public void onPause() {
        refreshDataHandler.removeCallbacks(refreshDataTask);
        super.onPause();
    }

    /**
     * {@inheritDoc}
     * Restarts UI refreshing task when no countdown task is in progress
     */
    @Override
    public void onResume() {
        if(!this.settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
        super.onResume();
    }
}