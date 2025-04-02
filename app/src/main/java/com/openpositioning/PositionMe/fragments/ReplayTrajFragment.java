package com.openpositioning.PositionMe.fragments;

import android.graphics.Color;
import android.os.Bundle;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.openpositioning.PositionMe.ReplayDataProcessor;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.UtilFunctions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;


public class ReplayTrajFragment extends Fragment {
    // For initialize map to replay with indoor map
    private GoogleMap replayMap;
    public IndoorMapManager indoorMapManager;
    private Spinner switchMapSpinner;
    private Switch GNSSSwitchControl;

    private Switch pdrPathSwitch;
    private Switch wifiSwitch;
    private TextView ElevationPres;

    private static final DecimalFormat df = new DecimalFormat("#.####");

    //    public ReplayDataProcessor ReplayDataProcessor;
    private LatLng startLoc;
    private Polyline pdrPolyline;
    private Polyline gnssPolyline;

    private Polyline wifiPolyline;
    private Polyline fusedPolyline;
    private List<Circle> circleList = new ArrayList<>();
    private List<Marker> tagMarkers = new ArrayList<>();
    private Marker gnssMarker;
    private Marker tagMarker;
    private Marker wifiMarker;
    private boolean gnssEnabled = true;
    private boolean wifiEnabled = true;
    private boolean pdrEnabled = true;
    private LatLng currentLocation;

    private LatLng currentGnssLoc;
    private LatLng currentWifiLoc;
    private LatLng tagLocation;
    private LatLng fusedCurrLocation;

    private int currentFloor;

    private int startFloor;
    private int startEleFloor;
    private float currentOrientation;
    private Marker orientationMarker;

    private Timer readPdrTimer;
    private TimerTask currTask;
    private static final long TimeInterval = 10;

    private Traj.Trajectory trajectory;
    private int trajSize;

    private ReplayDataProcessor.TrajRecorder trajProcessor;

    private List<LatLng> pdrLocList;

    private List<LatLng> fusedDataList;
    private List<Traj.Motion_Sample> imuDataList;
    private List<Traj.Pressure_Sample> pressureSampleList;
    private List<Traj.GNSS_Sample> gnssDataList;

    private int currStepCount = 0;
    private int currFusedStepCount = 0;

    private float currElevation;

    private int currProgress = 0;
    private int counterYaw = 0;
    private int counterPressure = 0;
    private int counterGnss = 0;
    private int counterYawLimit = 0;

//    private int nextProgress;

    private SeekBar seekBar;
    private ImageButton replayButton;
    private ImageButton replayBackButton;
    private ImageButton playPauseButton;
    private ImageButton goToEndButton;

    private boolean isPlaying = true;

    /**
     * Called when the activity is first created.
     * Initializes the trajectory processor, prepares trajectory data, and sets up the timer for PDR readings.
     *
     * @param savedInstanceState A Bundle containing the activity's previously saved state.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Initialize the trajectory processor and obtain the replay trajectory
        this.trajProcessor = ReplayDataProcessor.TrajRecorder.getInstance();
        this.trajectory = trajProcessor.getReplayTraj();
        // Initialize the timer for processing PDR data
        readPdrTimer = new Timer();
    }

    /**
     * Called when the activity is resumed.
     * Hides the bottom navigation bar if it is present.
     */
    @Override
    public void onResume() {
        super.onResume();
        BottomNavigationView bottomNav = getActivity().findViewById(R.id.bottom_navigation);
        if (bottomNav != null) {
            bottomNav.setVisibility(View.GONE);
        }
    }

    /**
     * Inflates the fragment's layout and sets up the map and data processing.
     * Translates the trajectory data (PDR, IMU, pressure, and GNSS) into usable lists.
     * Initializes the map and sets up the initial map view and camera position.
     *
     * @param inflater           The LayoutInflater object to inflate the layout.
     * @param container          The container view that the fragment will be attached to.
     * @param savedInstanceState A Bundle containing the saved state of the fragment.
     * @return The root view of the fragment.
     */
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the fragment layout
        View rootView = inflater.inflate(R.layout.fragment_replay, container, false);

        // Translate trajectory data into usable lists (PDR, IMU, pressure, GNSS)
        pdrLocList = ReplayDataProcessor.translatePdrPath(this.trajectory, false);

        // Check if fusion data is available, and translate the path accordingly
        if (!this.trajectory.getFusionDataList().isEmpty()) {
            fusedDataList = ReplayDataProcessor.translatePdrPath(this.trajectory, true);
        } else {
            fusedDataList = ReplayDataProcessor.translatePdrPath(this.trajectory, false);
        }

        // Retrieve IMU, pressure, and GNSS data lists
        imuDataList = ReplayDataProcessor.getMotionDataList(this.trajectory);
        pressureSampleList = ReplayDataProcessor.getPressureDataList(this.trajectory);
        gnssDataList = this.trajectory.getGnssDataList();

        // Get the size of the IMU data list (trajectory size)
        trajSize = imuDataList.size();

        // Initialize and configure the map fragment for displaying the indoor map
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.ReplayMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(@NonNull GoogleMap map) {
                    replayMap = map;

                    // Initialize the indoor map manager to handle the indoor map features
                    indoorMapManager = new IndoorMapManager(replayMap);

                    // Set map attributes (type, gestures, etc.)
                    replayMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                    replayMap.getUiSettings().setCompassEnabled(true);
                    replayMap.getUiSettings().setTiltGesturesEnabled(true);
                    replayMap.getUiSettings().setRotateGesturesEnabled(true);
                    replayMap.getUiSettings().setScrollGesturesEnabled(true);

                    // Initialize the start position marker and move the camera to it
                    PositionInitialization();

                    // Center the camera on the start location with a zoom level of 19
                    replayMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLoc, (float) 19f));

                    // Set the current location and indoor map indication
                    indoorMapManager.setCurrentLocation(startLoc);
                    indoorMapManager.setIndicationOfIndoorMap();
                }
            });
        }

        // Set up the SeekBar and Elevation view
        seekBar = rootView.findViewById(R.id.seekBar);
        ElevationPres = rootView.findViewById(R.id.ElevationView);
        seekBar.setMax(100);

        return rootView;
    }

    /**
     * Called when the fragment's view has been created.
     * Sets up various UI elements and starts necessary tasks such as replay controls and timer.
     *
     * @param view The root view of the fragment.
     * @param savedInstanceState A Bundle containing the saved state of the fragment.
     */
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ReplayBackButton();         // Initialize replay back button functionality
        setupReplayButton();        // Set up replay button functionality
        setupGoToEndButton();       // Set up go to end button functionality
        setupPlayPauseButton();     // Set up play/pause button functionality
        ProgressView();             // Initialize progress view UI elements

        GNSSSwitch();
        wifiSwitch();
        pdrPathSwitch();

        // Configuring dropdown for switching map types
        mapDropdown();
        // Setting listener for the switching map types dropdown
        switchMap();

        currTask = createTimerTask();
        this.readPdrTimer.schedule(currTask, 0, TimeInterval);
    }

    /**
     * Creates a TimerTask to periodically update the path view.
     *
     * @return TimerTask that will update the path view on the main thread.
     */
    private TimerTask createTimerTask() {
        return new TimerTask() {
            @Override
            public void run() {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            drawPathView();
                        }
                    });
                }
            }
        };
    }

    /**
     * Initializes the starting location by setting up pdr and fusion path markers, polylines, and location data.
     * Configures the indoor map manager and sets the current location and floor if available.
     */
    private void setupStartLocation() {
        if (startLoc != null) {
            // Add a marker for the current position on the map
            orientationMarker = replayMap.addMarker(new MarkerOptions()
                    .position(startLoc)
                    .title("Current Position")
                    .flat(true)
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(getContext(), R.drawable.ic_baseline_navigation_24)))
                    .zIndex(6));

            // Create and add a polyline for the PDR path
            PolylineOptions pdrpolylineOptions = new PolylineOptions()
                    .color(Color.parseColor("#FFA500"))
                    .add(startLoc)
                    .zIndex(6);
            pdrPolyline = replayMap.addPolyline(pdrpolylineOptions);

            // Create and add a polyline for the fused path
            PolylineOptions polylineOptions = new PolylineOptions()
                    .color(Color.RED)
                    .add(startLoc)
                    .zIndex(7);
            fusedPolyline = replayMap.addPolyline(polylineOptions);

            // If the indoor map manager is available, update the current location and floor
            if (indoorMapManager != null) {
                indoorMapManager.setCurrentLocation(startLoc);
                if (indoorMapManager.getIsIndoorMapSet()) {

                    startFloor = currentFloor;
                    startEleFloor = (int)(currElevation / indoorMapManager.getFloorHeight());
                    if(currentWifiLoc != null) {
                        indoorMapManager.setCurrentFloor(startFloor, true);
                    }else {
                        indoorMapManager.setCurrentFloor(startEleFloor,true);
                    }
                    // The start floor value is saved for reapply

                }
            }
        }
    }


    /**
     * Resets various counters and the play/pause button status.
     */
    private void resetCounters() {
        counterYaw = 0;
        counterPressure = 0;
        currProgress = 0;
        currStepCount = 0;
        currFusedStepCount = 0;
        currentWifiLoc = null;
        counterGnss = 0;
        isPlaying = true;
        playPauseButton.setImageResource(android.R.drawable.ic_media_pause);
    }

    /**
     * Resets the UI elements, removing markers, polylines, and circles.
     */
    private void resetUI() {
        // Remove PDR polyline and markers
        if (pdrPolyline != null) { pdrPolyline.remove(); }
        if (tagMarkers != null) {
            for (Marker marker : tagMarkers) {
                marker.remove();
            }
            tagMarkers.clear();
        }

        // Remove fused polyline and orientation marker
        if (fusedPolyline != null) { fusedPolyline.remove(); }
        if (orientationMarker != null) { orientationMarker.remove(); }

        // Remove WiFi polyline and marker if they exist
        if (wifiPolyline != null) {
            wifiPolyline.remove();
            wifiPolyline.setVisible(wifiSwitch.isChecked());
            wifiPolyline = null;
        }
        if (wifiMarker != null) {
            wifiMarker.remove();
            wifiMarker.setVisible(wifiSwitch.isChecked());
            wifiMarker = null;
        }

        // Remove GNSS polyline and marker if they exist
        if (gnssPolyline != null) { gnssPolyline.remove(); }
        if (gnssMarker != null) { gnssMarker.remove(); }

        // Remove circles if they exist
        if (circleList != null) {
            for (Circle circle : circleList) {
                circle.remove();
            }
            circleList.clear();
        }
    }

    /**
     * Initializes the position by resetting counters and UI, setting the start location,
     * and handling GNSS, WiFi, and PDR path data.
     *
     * This method distinguishes the provider (WiFi, GNSS, or tag) and sets the corresponding
     * markers and polylines for visualization on the map.
     */
    public void PositionInitialization() {
        resetCounters();
        resetUI();

        // Set the starting location from the PDR data list or default to (0, 0)
        startLoc = !pdrLocList.isEmpty() ? pdrLocList.get(0) : new LatLng(0, 0);
        currElevation = pressureSampleList.get(counterPressure).getEstimatedElevation();

        // Format and display the current elevation
        String formatElevation = df.format(currElevation);
        ElevationPres.setText("Elevation:" + formatElevation + "m");

        // Get the current orientation (azimuth) from the IMU data
        currentOrientation = imuDataList.get(counterYaw).getAzimuth();

        /**
         * GNSS data is acquired for initialization and distinguishing the provider: WiFi, Tag, or GNSS.
         * Each provider supports the location recorded for plotting on the map.
         */
        if (!gnssDataList.isEmpty()) {
            Traj.GNSS_Sample gnssStartData = gnssDataList.get(counterGnss);
            String provider = gnssStartData.getProvider();

            // Initialize WiFi location
            if (provider.equals("wifi_fine")) {
                currentWifiLoc = new LatLng(gnssStartData.getLatitude(), gnssStartData.getLongitude());
                currentFloor =  (int)((gnssStartData.getAltitude())/4.2 + 1);
                // If WiFi polyline doesn't exist, create it
                if (wifiPolyline == null) {
                    wifiPolyline = replayMap.addPolyline(new PolylineOptions()
                            .color(Color.GREEN)
                            .zIndex(6));
                }
                // Add the current WiFi location to the polyline
                List<LatLng> wifipointsMoved = new ArrayList<>(wifiPolyline.getPoints());
                wifipointsMoved.add(currentWifiLoc);
                wifiPolyline.setPoints(wifipointsMoved);

                // Add a WiFi marker if it doesn't exist
                if (wifiMarker == null) {
                    wifiMarker = replayMap.addMarker(new MarkerOptions()
                            .title("WiFi position")
                            .position(currentWifiLoc)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                            .visible(wifiEnabled));
                } else {
                    wifiMarker.setPosition(currentWifiLoc);
                }

                // Initialize GNSS location
            } else {
                currentGnssLoc = new LatLng(gnssStartData.getLatitude(), gnssStartData.getLongitude());
                float radius = gnssStartData.getAccuracy();

                // Create a circle to represent GNSS accuracy
                CircleOptions circleOptions = new CircleOptions()
                        .strokeWidth(2)
                        .strokeColor(Color.BLUE)
                        .fillColor(0x0277A88D)
                        .radius(radius)
                        .center(currentGnssLoc)
                        .zIndex(0)
                        .visible(gnssEnabled);
                circleList.add(replayMap.addCircle(circleOptions));

                // Create and add a polyline for GNSS path
                PolylineOptions gnssPolylineOptions = new PolylineOptions()
                        .color(Color.BLUE)
                        .add(currentGnssLoc)
                        .zIndex(6)
                        .visible(gnssEnabled);
                gnssPolyline = replayMap.addPolyline(gnssPolylineOptions);

                // Create and add a GNSS marker with additional information
                String formattedLat = df.format(currentGnssLoc.latitude);
                String formattedLng = df.format(currentGnssLoc.longitude);
                float altitude = gnssStartData.getAltitude();
                gnssMarker = replayMap.addMarker(new MarkerOptions()
                        .title("GNSS position")
                        .snippet("Acc: " + radius + " Alt: " + altitude)
                        .position(currentGnssLoc)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                        .visible(gnssEnabled));
            }
        }

        // Setup the start location marker and polylines
        setupStartLocation();
    }

    /**
     * Updates the path view on the map by processing the latest sensor data (IMU, GNSS, WiFi, etc.)
     * and drawing corresponding markers, polylines, and progress on the map.
     * It handles the following types of data:
     * <ul>
     *     <li>IMU data for orientation updates</li>
     *     <li>Pressure data for elevation updates</li>
     *     <li>GNSS data for location updates (WiFi, Tag, and GNSS)</li>
     *     <li>PDR data for step tracking</li>
     *     <li>Fused data for combined location updates</li>
     * </ul>
     *
     * The method also updates the progress bar based on the current position in the trajectory.
     *
     * @return A {@link TimerTask} that is executed periodically to update the map view.
     */
    public TimerTask drawPathView() {
        // ===== break logic ===== //
        if (counterYaw >= imuDataList.size() - 1) {
            if (currTask != null) {
                currTask.cancel();
            }
            isPlaying = false;
            playPauseButton.setImageResource(R.drawable.ic_baseline_play_arrow_24);
            return null;
        }

        // ===== orientation value update logic ===== //
        // get base tick
        long relativeTBase = imuDataList.get(counterYaw).getRelativeTimestamp();

        float nextOrientation = imuDataList.get(counterYaw).getAzimuth();
        if (orientationMarker!=null && currentOrientation!= nextOrientation) {
            currentOrientation = nextOrientation;
//            System.out.println("Current Orientation: " + currentOrientation);
            orientationMarker.setRotation((float) Math.toDegrees(currentOrientation));
        }

        // ===== pressure value update logic ===== //
        if (counterPressure < pressureSampleList.size() - 1) {
            // always take the next pressure sample
            Traj.Pressure_Sample nextPressureSample = pressureSampleList.get(counterPressure + 1);
            long nextTPressure = nextPressureSample.getRelativeTimestamp();
            float nextElevation = nextPressureSample.getEstimatedElevation();
            if (relativeTBase >= nextTPressure) {
                currElevation = nextElevation;
                counterPressure++;
            }
        }
//        else {
//            // Ensure the last pressure sample is used when counterPressure reaches the last index
//            currElevation = pressureSampleList.get(counterPressure).getEstimatedElevation();
//        }
        String formatElevation = df.format(currElevation);
        ElevationPres.setText("Elevation:"+formatElevation+"m");

        // ===== GNSS value update logic ===== //
        if ((!gnssDataList.isEmpty()) && counterGnss < gnssDataList.size() - 1) {
            // always take the next gnss sample

            Traj.GNSS_Sample nextGnssSample = gnssDataList.get(counterGnss + 1);
            long nextTGnss = nextGnssSample.getRelativeTimestamp();
            String provider = nextGnssSample.getProvider();

            if (relativeTBase >= nextTGnss) {
            // WIFI point //
                if(provider.equals("wifi_fine")){
                    currentWifiLoc = new LatLng(nextGnssSample.getLatitude(), nextGnssSample.getLongitude());  // current wifi location
                    currentFloor =  (int)((nextGnssSample.getAltitude()) /4.2 + 1);
                    if (wifiPolyline == null && replayMap!=null) {
                        wifiPolyline = replayMap.addPolyline(new PolylineOptions()
                                .color(Color.GREEN)
                                .zIndex(6));
//                                .visible(wifiEnabled));
                    }
                    List<LatLng> wifipointsMoved = new ArrayList<>(wifiPolyline.getPoints());
                    wifipointsMoved.add(currentWifiLoc);
                    wifiPolyline.setPoints(wifipointsMoved);
//                    wifiPolyline.setVisible(wifiEnabled);
                    if (wifiMarker == null && replayMap!=null) {
                        wifiMarker = replayMap.addMarker(new MarkerOptions()
                                .title("WiFi position")
                                .position(currentWifiLoc)
//                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                                .visible(wifiEnabled));
                    } else {
                        wifiMarker.setPosition(currentWifiLoc);
//                        wifiMarker.setVisible(wifiEnabled);
                    }
                }
            // Tag Point //
                else if(provider.equals("fusion")){
                    tagLocation = new LatLng(nextGnssSample.getLatitude(), nextGnssSample.getLongitude());    // location of tag added
                    tagMarker = replayMap.addMarker(new MarkerOptions()
                            .title("Tag Added")
                            .position(tagLocation)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)));
                    tagMarkers.add(tagMarker);
                }
            // GNSS Point //
                else {
                    currentGnssLoc = new LatLng(nextGnssSample.getLatitude(), nextGnssSample.getLongitude());
                    float radius = nextGnssSample.getAccuracy();
                    CircleOptions circleOptions = new CircleOptions()
                            .strokeWidth(2)
                            .strokeColor(Color.BLUE)
                            .fillColor(0x0277A88D)
                            .radius(radius)
                            .center(currentGnssLoc)
                            .zIndex(0)
                            .visible(gnssEnabled);
                    if (circleList != null && replayMap != null) {
                        circleList.add(replayMap.addCircle(circleOptions));
                    }
                    if (gnssPolyline != null) {
                        List<LatLng> pointsMoved = gnssPolyline.getPoints();
                        pointsMoved.add(currentGnssLoc);
                        gnssPolyline.setPoints(pointsMoved);
                        gnssPolyline.setVisible(gnssEnabled);
                    }
                    if (gnssMarker != null) {
                        gnssMarker.setPosition(currentGnssLoc);
                        gnssMarker.setVisible(gnssEnabled);
                        float altitude = nextGnssSample.getAltitude();
                        gnssMarker.setTitle("GNSS position");
                        gnssMarker.setSnippet("Acc: " + radius + "m" + " Alt: " + altitude + "m");
                    }
                }
                counterGnss++;
            }
        }

        // ===== pdr value update logic ===== //
        int nextStepCount = imuDataList.get(counterYaw).getStepCount();
        if (currStepCount != nextStepCount) {
            currStepCount = nextStepCount;
            currentLocation = pdrLocList.get(currStepCount);
            if (pdrPolyline!=null) {
                // get existing points
                List<LatLng> pdrpointsMoved = pdrPolyline.getPoints();
                // add new point
                pdrpointsMoved.add(currentLocation);
                pdrPolyline.setPoints(pdrpointsMoved    );
                pdrPolyline.setColor(Color.parseColor("#FFA500"));
                pdrPolyline.setVisible(pdrEnabled);
            }
        }
        // === fused value update logic == //
        int nextFusedStepCount = imuDataList.get(counterYaw).getStepCount();
        if (currFusedStepCount != nextFusedStepCount) {
            currFusedStepCount = nextFusedStepCount;
            Log.e("pdr", "test" + currFusedStepCount);
            fusedCurrLocation = fusedDataList.get(currFusedStepCount);

            // move the marker
            if (orientationMarker!=null) {
                orientationMarker.setPosition(fusedCurrLocation);
            }

            if (fusedPolyline!=null) {
                // get existing points
                List<LatLng> fusedPointsMoved = fusedPolyline.getPoints();
                // add new point
                fusedPointsMoved.add(fusedCurrLocation);
                fusedPolyline.setPoints(fusedPointsMoved);
                fusedPolyline.setColor(Color.RED);
                fusedPolyline.setZIndex(7);
            }

            if (indoorMapManager != null) {
                indoorMapManager.setCurrentLocation(fusedCurrLocation);
                if (indoorMapManager.getIsIndoorMapSet()) {
                    if(currentWifiLoc != null) {
                        indoorMapManager.setCurrentFloor(currentFloor, true);
                    }else {
                        indoorMapManager.setCurrentFloor((int)(currElevation / indoorMapManager.getFloorHeight()), true);
                    }
                }
            }
        }

        // ===== progress bar update logic ===== //
        currProgress = (int) ((counterYaw * 100.0f) / trajSize);
        seekBar.setProgress(currProgress);

        // ===== counter update logic ===== //
        counterYaw++;
        return null;
    }

    /**
     * Sets up the progress bar (seek bar) listener to handle changes in the playback progress.
     * This method allows for controlling the playback through the seek bar.
     * <p>
     * When the user changes the progress, the corresponding marker and polyline visibility are updated.
     * Also, cancels the current task when the user starts tracking, and initializes the path view when tracking stops.
     */
    public void ProgressView() {
        if (seekBar == null) return;
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {

                if(!wifiSwitch.isChecked()){
                    if(wifiPolyline != null && wifiMarker != null) {
                        wifiPolyline.setVisible(wifiEnabled);
                        wifiMarker.setVisible(wifiEnabled);
                        if(currentWifiLoc != null)
                       indoorMapManager.setCurrentFloor(startFloor,true);
                    }else{
                    indoorMapManager.setCurrentFloor(startEleFloor,true);
                    }
                }
                // No actions required
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (currTask != null) {
                    currTask.cancel();
                    isPlaying = false;
                    playPauseButton.setImageResource(R.drawable.ic_baseline_play_arrow_24);
                }
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int nextProgress = seekBar.getProgress();
                counterYawLimit = (int) ((nextProgress / 100.0f) * trajSize - 1);
                isPlaying = true;
                playPauseButton.setImageResource(android.R.drawable.ic_media_pause);
//                if (currTask != null) {
//                    currTask.cancel();
//                    PositionInitialization();
//                }
                PositionInitialization();
                for (counterYaw = 0; counterYaw <= counterYawLimit; counterYaw++) {
                    drawPathView();
                }
                currTask = createTimerTask();
                readPdrTimer.schedule(currTask, 0, TimeInterval);
            }
        });
    }

    /**
     * Sets up the "Replay Back" button to navigate back to the previous page in the fragment stack.
     * <p>
     * When the button is clicked, it checks if there are entries in the fragment back stack and pops the top fragment if available.
     * Return back to the last page
     */
    private void ReplayBackButton() {
        replayBackButton = requireView().findViewById(R.id.ReplayBackButton);
        replayBackButton.setOnClickListener(view -> {
            if (requireActivity().getSupportFragmentManager().getBackStackEntryCount() > 0) {
                requireActivity().getSupportFragmentManager().popBackStack();
            }

        });
    }


    /**
     * Sets up the "Play / Pause" button to control the playback of the path.
     * <p>
     * If the current task is running, it will either pause or resume based on the current playback state.
     * If the playback is paused, it shows the play icon. If it's playing, it shows the pause icon.
     */
    private void setupPlayPauseButton() {
        playPauseButton = requireView().findViewById(R.id.PlayPauseButton);
        playPauseButton.setOnClickListener(v -> {
            if (currTask != null) {
                currTask.cancel();
                if (isPlaying) {
                    isPlaying = false;
                    playPauseButton.setImageResource(R.drawable.ic_baseline_play_arrow_24);
                } else {
                    if (currProgress == 100) {
                        PositionInitialization();
                        readPdrTimer = new Timer();
                    }
                    currTask = createTimerTask();
                    readPdrTimer.schedule(currTask, 0, TimeInterval);
                    isPlaying = true;
                    playPauseButton.setImageResource(android.R.drawable.ic_media_pause);
                }
            }
        });
    }

    /**
     * Sets up the "Replay" button to restart the playback from the beginning.
     * <p>
     * This method cancels the current task, initializes the position, and restarts the playback. It also ensures the WiFi polyline visibility is handled based on user settings.
     */
    private void setupReplayButton() {
        replayButton = requireView().findViewById(R.id.ReplayButton);
        replayButton.setOnClickListener(v -> {
            if (currTask != null) {
                currTask.cancel();
            }
            PositionInitialization();
            if(!wifiSwitch.isChecked()){
                if(wifiPolyline != null && wifiMarker != null) {
                    wifiPolyline.setVisible(wifiEnabled);
                    wifiMarker.setVisible(wifiEnabled);
                    indoorMapManager.setCurrentFloor(startFloor,true);
                }else{
                    indoorMapManager.setCurrentFloor(startEleFloor,true);
                }
            }
            currTask = createTimerTask();
            readPdrTimer.schedule(currTask, 0, TimeInterval);
            isPlaying = true;
            playPauseButton.setImageResource(android.R.drawable.ic_media_pause);
        });
    }

    /**
     * Sets up the "Go to End" button to fast forward to the end of the path.
     * <p>
     * When clicked, this method cancels the current task, updates the seek bar to 100% progress, and stops the playback.
     * It then draws the entire path up to the last point and pauses the playback.
     */
    private void setupGoToEndButton() {
        goToEndButton = requireView().findViewById(R.id.GoToEndButton);
        goToEndButton.setOnClickListener(v -> {
            try{
                if (currTask != null) {
                    currTask.cancel();
                }}
            catch (Exception e) {
                Log.e("GoToEnd", "Fail to cancel currTask",e);
            }
            if(seekBar.getProgress() != 100){
                counterYawLimit = trajSize - 1;
                try{
                    for (counterYaw = 0; counterYaw < counterYawLimit; counterYaw++) { drawPathView(); }}
                catch (Exception e){
                    Log.e("DrawLogic","Error Draw",e);
                }
                seekBar.setProgress(100);
                isPlaying = false;
                playPauseButton.setImageResource(R.drawable.ic_baseline_play_arrow_24);}
        });
    }

    /**
     * Control the visibility of the WIFI
     */
    private void wifiSwitch(){
        wifiSwitch = requireView().findViewById(R.id.switchWifi);
        wifiSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    wifiEnabled = true;
                    if(wifiPolyline != null) wifiPolyline.setVisible(true);
                    if(wifiMarker != null) wifiMarker.setVisible(true);
                } else {
                    wifiEnabled = false;
                    if(wifiPolyline != null) wifiPolyline.setVisible(false);
                    if(wifiMarker != null) wifiMarker.setVisible(false);
                }
            }
        });
    }

    /**
     * Control the visibility of the GNSS
     */
    private void GNSSSwitch(){
        GNSSSwitchControl = requireView().findViewById(R.id.switchGNSS);
        GNSSSwitchControl.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    gnssEnabled = true;
                    if(gnssPolyline != null) gnssPolyline.setVisible(true);
                    if(gnssMarker != null) gnssMarker.setVisible(true);
                    if(circleList != null) {
                        for (Circle circle : circleList) {
                            circle.setVisible(true);
                        }
                    }

                } else {
                    gnssEnabled = false;
                    if(gnssPolyline != null) gnssPolyline.setVisible(false);
                    if(gnssMarker != null) gnssMarker.setVisible(false);
                    if(circleList != null) {
                        for (Circle circle : circleList) {
                            circle.setVisible(false);
                        }
                    }

                }
            }
        });
    }

    /**
     * Control the visibility of the PDR
     */
    private void pdrPathSwitch(){
        pdrPathSwitch = requireView().findViewById(R.id.switchPDR);
        pdrPathSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    pdrEnabled = true;
                    if(pdrPolyline != null) pdrPolyline.setVisible(true);

                } else {
                    pdrEnabled = false;
                    if(pdrPolyline != null) pdrPolyline.setVisible(false);

                }
            }
        });
    }



    private void mapDropdown(){
        // Creating and Initialising options for Map's Dropdown Menu
        switchMapSpinner = (Spinner) getView().findViewById(R.id.ReplayMapSwitchSpinner);
        // Different Map Types
        String[] maps = new String[]{getString(R.string.hybrid), getString(R.string.normal), getString(R.string.satellite)};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, maps);
        // Set the Dropdowns menu adapter
        switchMapSpinner.setAdapter(adapter);
    }
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
                        replayMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                    case 1:
                        replayMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                        break;
                    case 2:
                        replayMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                        break;
                }
            }
            /**
             * {@inheritDoc}
             * When Nothing is selected set to MAP_TYPE_HYBRID (NORMAL and SATELLITE)
             */
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                replayMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
            }
        });
    }
}