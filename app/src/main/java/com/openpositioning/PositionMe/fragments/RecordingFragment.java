package com.openpositioning.PositionMe.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
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
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.LatLng;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;


import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.openpositioning.PositionMe.BuildingPolygon;

import android.graphics.Rect;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * This fragment handles the active data recording session by capturing sensor inputs,
 * displaying a real-time map with markers for the current pedestrian dead reckoning (PDR)
 * position and orientation, and drawing the evolving user trajectory on the map.
 * It also overlays indoor floor plans when the user is within supported buildings,
 * thereby enhancing navigation accuracy in indoor environments.
 *
 * In this updated version, additional functionalities have been introduced:
 * <ul>
 *   <li>Enhanced fusion support for integrating WiFi, GNSS, and PDR data.</li>
 *   <li>New tagging functionality to mark specific positions during recording.</li>
 *   <li>Improved UI controls for switching map types and adjusting floor overlays.</li>
 * </ul>
 *
 * These improvements provide a more robust framework for post-processing and correcting
 * the recorded trajectory.
 *
 * @see HomeFragment for navigation from the home screen.
 * @see CorrectionFragment for reviewing and adjusting recorded data.
 * @see SensorFusion for the sensor integration and fusion algorithms.
 * @see IndoorMapManager for managing indoor map overlays.
 *  addtage for the new location tagging functionality.
 *
 */
public class RecordingFragment extends Fragment implements SensorFusion.SensorFusionUpdates {
    private Marker gnssMarker;
    private List<Marker> gnssMarkers = new ArrayList<>();
    private Marker wifiMarker;
    private List<Marker> wifiMarkers = new ArrayList<>();
    //Button to end PDR recording
    private Button stopButton;
    private Button addtag;
    private Button cancelButton;
    //Recording icon to show user recording is in progress
    private ImageView recIcon;
    //Loading bar to show time remaining before recording automatically ends
    private ProgressBar timeRemaining;
    //Text views to display distance travelled and elevation since beginning of recording

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
    private Switch wifi;
    private Switch fusionSwitch;
    private Switch pdrSwitch;
    private Polyline fusionPolyline;
    private Polyline pdrPolyline;

    // Button used to switch colour
    private Button switchColor;
    // Current color of polyline
    private boolean isRed=true;
    // Switch used to set auto floor
    private Switch autoFloor;

    private Marker fusedOrientationMarker;  // new arrow marker for fusion


    private LatLng currentFusedLocation = null;
    private Marker tempFusedArrowMarker = null;


    // List to store detected lift positions from OCR (as LatLng)
    private List<LatLng> detectedLifts = new ArrayList<>();
    // Marker used to show the lift icon when the user is near a detected lift
    private Marker liftMarker;
    // Proximity threshold in meters to consider the user “near” a lift
    private static final float LIFT_PROXIMITY_THRESHOLD_METERS = 1.5f;

    private Button liftIconButton;
    private List<Marker> liftMarkers = new ArrayList<>();

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
     *
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.sensorFusion = SensorFusion.getInstance();
        Context context = getActivity();
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.refreshDataHandler = new Handler();

    }
    //Wifi update from sensor fusion

    @Override
    public void onWifiUpdate(LatLng latlngFromWifiServer){
        if (!isAdded() || getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (latlngFromWifiServer == null) {
                // Hide WiFi Marker
                if (wifiMarker != null) {
                    wifiMarker.remove();
                    wifiMarker = null;
                }
                return;
            }
            // WiFi Marker
            if (gMap != null) {
                if (wifiMarker != null) wifiMarker.remove();
                if (wifi.isChecked()) {
                    wifiMarker = gMap.addMarker(new MarkerOptions()
                            .position(latlngFromWifiServer)
                            .title("WiFi Location")
                            .icon(BitmapDescriptorFactory.fromBitmap(
                                    UtilFunctions.getBitmapFromVector(getContext(), R.drawable.blue_hollow_circle))));
                }

            }
        });
    }


    @Override
    public void onFusedUpdate(LatLng fusedCoordinate) {
        if (!isAdded() || getActivity() == null) return;
        requireActivity().runOnUiThread(() -> {
            if (fusedCoordinate == null || gMap == null) return;

            currentFusedLocation = fusedCoordinate;

            if (fusionPolyline == null) {
                PolylineOptions fusionOptions = new PolylineOptions()
                        .color(Color.BLUE)
                        .width(8f)
                        .add(fusedCoordinate);
                fusionPolyline = gMap.addPolyline(fusionOptions);
            } else {
                List<LatLng> points = fusionPolyline.getPoints();
                points.add(fusedCoordinate);
                fusionPolyline.setPoints(points);
            }

            // Update fused orientation marker
            if (fusedOrientationMarker == null) {
                fusedOrientationMarker = gMap.addMarker(new MarkerOptions()
                        .position(fusedCoordinate)
                        .title("Fused Position")
                        .flat(true)
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(getContext(), R.drawable.ic_baseline_navigation_blue_24)))); // You can use a different icon if desired
            } else {
                fusedOrientationMarker.setPosition(fusedCoordinate);
                fusedOrientationMarker.setRotation((float) Math.toDegrees(sensorFusion.passOrientation()));

            }

            // Remove temp marker once the real one is added
            if (tempFusedArrowMarker != null) {
                tempFusedArrowMarker.remove();
                tempFusedArrowMarker = null;
            }

            // Set visibility based on the fusion switch
            if (fusionSwitch != null) {
                boolean visible = fusionSwitch.isChecked();
                fusionPolyline.setVisible(visible);
                fusedOrientationMarker.setVisible(visible);
            }

            // Check if the fused coordinate is near any detected lift from OCR
            boolean nearLift = false;
            for (LatLng liftLatLng : detectedLifts) {
                float distance = (float) UtilFunctions.distanceBetweenPoints(fusedCoordinate, liftLatLng);

                if (distance < LIFT_PROXIMITY_THRESHOLD_METERS) {
                    nearLift = true;
                    break;
                }
            }
// Toggle the visibility of the Lift icon based on proximity
            if (nearLift) {
                liftIconButton.setVisibility(View.VISIBLE);
            } else {
                liftIconButton.setVisibility(View.GONE);
            }


        });
        Log.d("FUSION_PATH", "Fused location: " + fusedCoordinate);

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
        SensorFusion.getInstance().registerForSensorUpdates(this);
        // Inflate the layout for this fragment
        ((AppCompatActivity)getActivity()).getSupportActionBar().hide();
        getActivity().setTitle("Recording...");
        //Obtain start position set in the startLocation fragment
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
                tempFusedArrowMarker=map.addMarker(new MarkerOptions().position(start).title("Current Position")
                        .position(start)
                        .title("Current Position")
                        .flat(true)
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(getContext(),R.drawable.ic_baseline_navigation_blue_24))));
                //Center the camera
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, (float) 19f));


                //Center the camera
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, (float) 19f));

                // Setting current location to set Ground Overlay for indoor map (if in building)
                indoorMapManager.setCurrentLocation(currentLocation);
                //Showing an indication of available indoor maps using PolyLines
                indoorMapManager.setIndicationOfIndoorMap();

// Load the floor plan bitmap (adjust resource ID as needed)
                Bitmap floorPlanBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.nucleusg);
                runOCROnFloorPlan(floorPlanBitmap);

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
        sensorFusion.registerForSensorUpdates(this);
        sensorFusion.initialiseFusionAlgorithm();

        this.autoStop = null;
        this.timeRemaining = getView().findViewById(R.id.timeRemainingBar);
        //Initialise UI components
        this.elevation = getView().findViewById(R.id.currentElevation);
        this.distanceTravelled = getView().findViewById(R.id.currentDistanceTraveled);
        this.gnssError =getView().findViewById(R.id.gnssError);

        // Get reference to the Lift icon (predefined in XML)
        liftIconButton = view.findViewById(R.id.Lift);
        // Initially hide it (or set to GONE as default)
        liftIconButton.setVisibility(View.GONE);

        //Set default text of TextViews to 0
        this.gnssError.setVisibility(View.GONE);
        this.elevation.setText(getString(R.string.elevation, "0"));
        this.distanceTravelled.setText(getString(R.string.meter, "0"));

        //Reset variables to 0
        this.distance = 0f;
        this.previousPosX = 0f;
        this.previousPosY = 0f;
        // Add Tag
        addtag = getView().findViewById(R.id.position_tag_button);
        addtag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (currentFusedLocation  != null) {
                    SensorFusion.getInstance().addTagFusionTrajectory(currentFusedLocation );
                    // Add visual tag to the map
                    gMap.addMarker(new MarkerOptions()
                            .position(currentFusedLocation )
                            .title("Tag")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
                    Toast.makeText(getContext(), "Tag Successfully added.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "Tag Unsuccessfully added.", Toast.LENGTH_SHORT).show();
                }
            }
        });
        // Stop button to save trajectory and move to corrections
        this.stopButton = getView().findViewById(R.id.stopButton);
        this.stopButton.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * OnClick listener for button to go to next fragment.
             * When button clicked the PDR recording is stopped and the {@link CorrectionFragment} is loaded.
             */
            @Override
            public void onClick(View view) {
                if(autoStop != null) autoStop.cancel();
                sensorFusion.stopRecording();
                NavDirections action = RecordingFragmentDirections.actionRecordingFragmentToCorrectionFragment();
                Navigation.findNavController(view).navigate(action);
            }
        });

        // Cancel button to discard trajectory and return to Home
        this.cancelButton = getView().findViewById(R.id.cancelButton);
        this.cancelButton.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * OnClick listener for button to go to home fragment.
             * When button clicked the PDR recording is stopped and the {@link HomeFragment} is loaded.
             * The trajectory is not saved.
             */
            @Override
            public void onClick(View view) {
                sensorFusion.stopRecording();
                NavDirections action = RecordingFragmentDirections.actionRecordingFragmentToHomeFragment();
                Navigation.findNavController(view).navigate(action);
                if(autoStop != null) autoStop.cancel();
            }
        });
        // Configuring dropdown for switching map types
        mapDropdown();
        // Setting listener for the switching map types dropdown
        switchMap();
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

        this.fusionSwitch = getView().findViewById(R.id.fusionSwitch);
        this.pdrSwitch = getView().findViewById(R.id.pdrSwitch);
        fusionSwitch.setChecked(true); //enable fusion by default
        pdrSwitch.setChecked(false);  //enable PDR by default

        fusionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (fusionPolyline != null) fusionPolyline.setVisible(isChecked);
            if (fusedOrientationMarker != null) fusedOrientationMarker.setVisible(isChecked);
        });

        pdrSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (pdrPolyline != null) pdrPolyline.setVisible(isChecked);
            if (orientationMarker != null) orientationMarker.setVisible(isChecked);
        });

        //Obtain the GNSS toggle switch
        this.gnss= getView().findViewById(R.id.gnssSwitch);

        this.wifi = getView().findViewById(R.id.Wifiswitch);

        wifi.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!isChecked) {
                    for (Marker marker : wifiMarkers) {
                        marker.setVisible(false);
                    }

                }
            }
        });

        // Display the progress of the recording when a max record length is set
        this.timeRemaining = getView().findViewById(R.id.timeRemainingBar);

        // Display a blinking red dot to show recording is in progress
        blinkingRecording();

        // Check if there is manually set time limit:
        if(this.settings.getBoolean("split_trajectory", false)) {
            // If that time limit has been reached:
            long limit = this.settings.getInt("split_duration", 30) * 60000L;
            // Set progress bar
            this.timeRemaining.setMax((int) (limit/1000));
            this.timeRemaining.setScaleY(3f);

            // Create a CountDownTimer object to adhere to the time limit
            this.autoStop = new CountDownTimer(limit, 1000) {
                /**
                 * {@inheritDoc}
                 * Increment the progress bar to display progress and remaining time. Update the
                 * observed PDR values, and animate icons based on the data.
                 */
                @Override
                public void onTick(long l) {
                    // increment progress bar
                    timeRemaining.incrementProgressBy(1);
                    // Get new position and update UI
                    updateUIandPosition();
                }

                /**
                 * {@inheritDoc}
                 * Finish recording and move to the correction fragment.
                 * @see CorrectionFragment
                 */
                @Override
                public void onFinish() {
                    // Timer done, move to next fragment automatically - will stop recording
                    sensorFusion.stopRecording();
                    NavDirections action = RecordingFragmentDirections.actionRecordingFragmentToCorrectionFragment();
                    Navigation.findNavController(view).navigate(action);
                }
            }.start();
        }
        else {
            // No time limit - use a repeating task to refresh UI.
            this.refreshDataHandler.post(refreshDataTask);
        }
        Button infobutton = view.findViewById(R.id.infobutton);
        LinearLayout switchContainer = view.findViewById(R.id.switchContainer);

        infobutton.setOnClickListener(v -> {
            if (switchContainer.getVisibility() == View.GONE) {
                // Show with slide-up animation
                switchContainer.setVisibility(View.VISIBLE);
                switchContainer.setTranslationY(switchContainer.getHeight());
                switchContainer.animate()
                        .translationY(0)
                        .setDuration(300)
                        .start();
                infobutton.setText("Hide Options");
            } else {
                // Slide down and hide
                switchContainer.animate()
                        .translationY(switchContainer.getHeight())
                        .setDuration(300)
                        .withEndAction(() -> {
                            switchContainer.setVisibility(View.GONE);
                            infobutton.setText("Show Options");
                        })
                        .start();
            }
        });

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
        if (gnss.isChecked()) {
            float[] gnssData = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
            // Bring back all GNSS markers from the map
            for (Marker marker : gnssMarkers) {
                marker.setVisible(true);
            }
            if (gnssData != null && gnssData.length >= 2) {
                LatLng gnssLocation = new LatLng(gnssData[0], gnssData[1]);
                gnssError.setVisibility(View.VISIBLE);
                gnssError.setText(String.format(getString(R.string.gnss_error) + "%.2fm",
                        UtilFunctions.distanceBetweenPoints(currentLocation, gnssLocation)));
                Marker newGnssMarker = gMap.addMarker(new MarkerOptions()
                        .title("GNSS")
                        .position(gnssLocation)
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(getContext(), R.drawable.green_hollow_circle)))
                        .anchor(0.5f, 0.5f));
                gnssMarkers.add(newGnssMarker);
            }
        } else {
            // Remove all GNSS markers from the map
            for (Marker marker : gnssMarkers) {
                marker.setVisible(false);
            }
            gnssError.setVisibility(View.GONE);
        }
        //  Updates current location of user to show the indoor floor map (if applicable)
        indoorMapManager.setCurrentLocation(currentLocation);
        float elevationVal;

        if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
            int currentFloor = sensorFusion.getWifiFloor();
            float floorHeight = indoorMapManager.getFloorHeight();
            elevationVal = currentFloor * floorHeight;
        } else {
            // fallback to SensorFusion elevation (e.g. barometer) when outdoors
            elevationVal = sensorFusion.getElevation();
        }

        // Display buttons to allow user to change floors if indoor map is visible
        if(indoorMapManager.getIsIndoorMapSet()){
            setFloorButtonVisibility(View.VISIBLE);
            // Auto-floor logic
            if(autoFloor.isChecked()){
                int wifiFloor = sensorFusion.getWifiFloor();
                if (wifiFloor != -2) {  // valid floor
                    indoorMapManager.setCurrentFloor(wifiFloor, true);
                } else {
                    // Fallback to elevation if WiFi floor not available
                    indoorMapManager.setCurrentFloor(
                            (int) (elevationVal / indoorMapManager.getFloorHeight()), true);
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
    private void plotLines(float[] pdrMoved) {
        if (currentLocation != null) {
            // Calculate new position based on net PDR movement
            nextLocation = UtilFunctions.calculateNewPos(currentLocation, pdrMoved);

            try {
                // Lazy-initialize the PDR polyline
                if (pdrPolyline == null && gMap != null) {
                    pdrPolyline = gMap.addPolyline(new PolylineOptions()
                            .color(Color.argb(150, 255, 0, 0)) // semi-transparent red
                            .width(5f)
                            .zIndex(1) // render under fusion path
                            .add(currentLocation));
                }

                // Lazy-initialize the orientation marker
                if (orientationMarker == null && gMap != null) {
                    orientationMarker = gMap.addMarker(new MarkerOptions()
                            .position(currentLocation)
                            .title("PDR Position")
                            .flat(true)
                            .zIndex(1)
                            .icon(BitmapDescriptorFactory.fromBitmap(
                                    UtilFunctions.getBitmapFromVector(getContext(), R.drawable.ic_baseline_navigation_24))));
                }

                // Add to polyline and update
                if (pdrPolyline != null) {
                    List<LatLng> pointsMoved = pdrPolyline.getPoints();
                    pointsMoved.add(nextLocation);
                    pdrPolyline.setPoints(pointsMoved);
                    if (pdrSwitch != null) {
                        pdrPolyline.setVisible(pdrSwitch.isChecked());
                    }
                }

                // Update orientation marker
                if (orientationMarker != null) {
                    orientationMarker.setPosition(nextLocation);
                    orientationMarker.setRotation((float) Math.toDegrees(sensorFusion.passOrientation()));
                    if (pdrSwitch != null) {
                        orientationMarker.setVisible(pdrSwitch.isChecked());
                    }
                }

                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(nextLocation, 19f));

            } catch (Exception ex) {
                Log.e("PlottingPDR", "Exception: " + ex);
            }

            currentLocation = nextLocation;

        } else {
            // Fallback if currentLocation is not initialized
            float[] location = sensorFusion.getGNSSLatitude(true);
            currentLocation = new LatLng(location[0], location[1]);
            nextLocation = currentLocation;
        }
    }

    /**
     * Converts a pixel coordinate (x, y) from the floor plan image to a LatLng coordinate.
     *
     * @param x           The x-coordinate (in pixels) from OCR.
     * @param y           The y-coordinate (in pixels) from OCR.
     * @param imageWidth  The width of the floor plan image in pixels.
     * @param imageHeight The height of the floor plan image in pixels.
     * @param bounds      The LatLngBounds of the overlay (e.g. for Nucleus or Library).
     * @return            The corresponding LatLng coordinate.
     */
    private LatLng convertPixelToLatLng(int x, int y, int imageWidth, int imageHeight, LatLngBounds bounds) {
        // Compute the top-left and bottom-right coordinates from the bounds.
        LatLng topLeft = new LatLng(bounds.northeast.latitude, bounds.southwest.longitude);
        LatLng bottomRight = new LatLng(bounds.southwest.latitude, bounds.northeast.longitude);

        double latRange = topLeft.latitude - bottomRight.latitude; // decreases downward
        double lngRange = bottomRight.longitude - topLeft.longitude; // increases to the right

        double lat = topLeft.latitude - ((double) y / imageHeight) * latRange;
        double lng = topLeft.longitude + ((double) x / imageWidth) * lngRange;
        return new LatLng(lat, lng);
    }


    private void runOCROnFloorPlan(Bitmap floorPlanBitmap) {
        // Determine which building overlay is active.
        LatLngBounds overlayBounds;
        if (BuildingPolygon.inNucleus(currentLocation)) {
            overlayBounds = new LatLngBounds(BuildingPolygon.NUCLEUS_SW, BuildingPolygon.NUCLEUS_NE);
        } else if (BuildingPolygon.inLibrary(currentLocation)) {
            overlayBounds = new LatLngBounds(BuildingPolygon.LIBRARY_SW, BuildingPolygon.LIBRARY_NE);
        } else {
            // Not in a building with an indoor overlay; do nothing.
            return;
        }

        InputImage image = InputImage.fromBitmap(floorPlanBitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);


        recognizer.process(image)
                .addOnSuccessListener(result -> {
                    for (Text.TextBlock block : result.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            String text = line.getText().toLowerCase();
                            if (text.contains("lift") || text.contains("elevator")) {
                                Rect boundingBox = line.getBoundingBox();
                                if (boundingBox != null) {
                                    int centerX = boundingBox.centerX();
                                    int centerY = boundingBox.centerY();
                                    // Convert the OCR pixel coordinate to a LatLng
                                    LatLng liftLatLng = convertPixelToLatLng(centerX, centerY,
                                            floorPlanBitmap.getWidth(), floorPlanBitmap.getHeight(), overlayBounds);
                                    detectedLifts.add(liftLatLng);
                                    Log.d("OCR", "Detected lift at: " + liftLatLng);

                                    if (gMap != null) {
                                        Marker liftMarker = gMap.addMarker(new MarkerOptions()
                                                .position(liftLatLng)
                                                .title("Lift")
                                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))); // or custom icon
                                        liftMarker.setVisible(false);
                                        liftMarkers.add(liftMarker);  // Optional: maintain list if you want to remove/hide later
                                    }
                                    Log.d("OCR", "Total lifts detected: " + detectedLifts.size());

                                }

                            }
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e("OCR", "OCR processing failed", e);
                });

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