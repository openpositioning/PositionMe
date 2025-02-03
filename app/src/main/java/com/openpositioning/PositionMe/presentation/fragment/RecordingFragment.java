package com.openpositioning.PositionMe.presentation.fragment;

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
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;

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
    // GNSS marker
    private Marker gnssMarker;
    // Button used to switch colour
    private Button switchColor;
    // Current color of polyline
    private boolean isRed=true;
    // Switch used to set auto floor
    private Switch autoFloor;

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
                orientationMarker=map.addMarker(new MarkerOptions().position(start).title("Current Position")
                        .flat(true)
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(getContext(),R.drawable.ic_baseline_navigation_24))));
                //Center the camera
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, (float) 19f));
                // Adding polyline to map to plot real-time trajectory
                PolylineOptions polylineOptions=new PolylineOptions()
                        .color(Color.RED)
                        .add(currentLocation);
                polyline = gMap.addPolyline(polylineOptions);
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
                    LatLng gnssLocation = new LatLng(location[0],location[1]);
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
        if (gnss.isChecked() && gnssMarker!=null){
            float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
            LatLng gnssLocation = new LatLng(location[0],location[1]);
            gnssError.setVisibility(View.VISIBLE);
            gnssError.setText(String.format(getString(R.string.gnss_error)+"%.2fm",
                    UtilFunctions.distanceBetweenPoints(currentLocation,gnssLocation)));
            gnssMarker.setPosition(gnssLocation);
        }
        //  Updates current location of user to show the indoor floor map (if applicable)
        indoorMapManager.setCurrentLocation(currentLocation);
        float elevationVal = sensorFusion.getElevation();
        // Display buttons to allow user to change floors if indoor map is visible
        if(indoorMapManager.getIsIndoorMapSet()){
            setFloorButtonVisibility(View.VISIBLE);
            // Auto-floor logic
            if(autoFloor.isChecked()){
                indoorMapManager.setCurrentFloor((int)(elevationVal/indoorMapManager.getFloorHeight())
                ,true);
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
                    // Change current location to new location and zoom there
                    orientationMarker.setPosition(nextLocation);
                    gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(nextLocation, (float) 19f));
                }
                catch (Exception ex){
                    Log.e("PlottingPDR","Exception: "+ex);
                }
                currentLocation=nextLocation;
        }
        else{
            //Initialise the starting location
            float[] location = sensorFusion.getGNSSLatitude(true);
            currentLocation=new LatLng(location[0],location[1]);
            nextLocation=currentLocation;
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