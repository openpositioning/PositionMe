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
    private TextView ElevationPres;

    private static final DecimalFormat df = new DecimalFormat("#.####");

    //    public ReplayDataProcessor ReplayDataProcessor;
    private LatLng startLoc;
    private Polyline pdrPolyline;
    private Polyline gnssPolyline;
    private List<Circle> circleList = new ArrayList<>();
    private Marker gnssMarker;
    private boolean gnssEnabled = true;
    private LatLng currentLocation;

    private LatLng currentGnssLoc;

    private float currentOrientation;
    private Marker orientationMarker;

    private Timer readPdrTimer;
    private TimerTask currTask;
    private static final long TimeInterval = 10;

    private Traj.Trajectory trajectory;
    private int trajSize;

    private ReplayDataProcessor.TrajRecorder trajProcessor;

    private List<LatLng> pdrLocList;
    private List<Traj.Motion_Sample> imuDataList;
    private List<Traj.Pressure_Sample> pressureSampleList;
    private List<Traj.GNSS_Sample> gnssDataList;

    private int currStepCount = 0;

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

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.trajProcessor = ReplayDataProcessor.TrajRecorder.getInstance();
        this.trajectory = trajProcessor.getReplayTraj();
        readPdrTimer = new Timer();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 获取主界面的 BottomNavigationView，并隐藏它
        BottomNavigationView bottomNav = getActivity().findViewById(R.id.bottom_navigation);
        if (bottomNav != null) {
            bottomNav.setVisibility(View.GONE);
        }
    }

    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_replay, container, false);
        pdrLocList = ReplayDataProcessor.translatePdrPath(this.trajectory);
//        imuDataList = this.trajectory.getImuDataList();
        imuDataList = ReplayDataProcessor.getMotionDataList(this.trajectory);
//        pressureSampleList = this.trajectory.getPressureDataList();
//        if (!ReplayDataProcessor.hasEstimatedAltitude(this.trajectory)) {
//            pressureSampleList = ReplayDataProcessor.pressureSampleAdapter(pressureSampleList);
//        }
        pressureSampleList = ReplayDataProcessor.getPressureDataList(this.trajectory);

        gnssDataList = this.trajectory.getGnssDataList();

        trajSize = imuDataList.size();
//        float[] startLoc = ReplayDataProcessor.getStartLocation(trajectory);
//        currentLocation = new LatLng(startLoc[0], startLoc[1]);

        // Initialize map/ indoor map
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.ReplayMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(@NonNull GoogleMap map) {
                    replayMap = map;
                    //Initialising the indoor map manager object
                    indoorMapManager = new IndoorMapManager(replayMap);
                    // Setting map attributes
                    replayMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                    replayMap.getUiSettings().setCompassEnabled(true);
                    replayMap.getUiSettings().setTiltGesturesEnabled(true);
                    replayMap.getUiSettings().setRotateGesturesEnabled(true);
                    replayMap.getUiSettings().setScrollGesturesEnabled(true);

                    // Add a marker at the start position and move the camera
                    PositionInitialization();

                    //Center the camera
                    replayMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLoc, (float) 19f));
                    indoorMapManager.setCurrentLocation(startLoc);
                    //Showing an indication of available indoor maps using PolyLines
                    indoorMapManager.setIndicationOfIndoorMap();
                }
            });
        }
        seekBar = rootView.findViewById(R.id.seekBar);
        ElevationPres = rootView.findViewById(R.id.ElevationView);
        seekBar.setMax(100);
        return rootView;
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ReplayBackButton();
        setupReplayButton();
        setupGoToEndButton();
        setupPlayPauseButton();
        ProgressView();
        GNSSSwitch();

        // Configuring dropdown for switching map types
        mapDropdown();
        // Setting listener for the switching map types dropdown
        switchMap();

        currTask = createTimerTask();
        this.readPdrTimer.schedule(currTask, 0, TimeInterval);
    }

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

    public void PositionInitialization(){
        counterYaw = 0;
        counterPressure = 0;
        currProgress = 0;
        currStepCount = 0;
        counterGnss = 0;
        isPlaying = true;
        playPauseButton.setImageResource(android.R.drawable.ic_media_pause);

        if(pdrPolyline != null) { pdrPolyline.remove(); }
        if(orientationMarker != null) { orientationMarker.remove(); }
        if(gnssMarker != null) { gnssMarker.remove(); }
        if(circleList != null) {
            for (Circle circle : circleList) {
                circle.remove();
            }
            circleList.clear();
        }
        if(gnssPolyline != null) { gnssPolyline.remove(); }

        startLoc = !pdrLocList.isEmpty() ? pdrLocList.get(0) : new LatLng(0,0);
        currElevation = trajectory.getPressureData(counterPressure).getEstimatedElevation();
        String formatElevation = df.format(currElevation);
        ElevationPres.setText("Elevation:"+formatElevation+"m");
        currentOrientation = imuDataList.get(counterYaw).getAzimuth();
        System.out.println("init Orientation: " + currentOrientation);

        if (!gnssDataList.isEmpty() ){
            Traj.GNSS_Sample gnssStartData = gnssDataList.get(counterGnss);
            currentGnssLoc = new LatLng(gnssStartData.getLatitude(), gnssStartData.getLongitude());
            float radius = gnssStartData.getAccuracy();
            CircleOptions circleOptions = new CircleOptions()
                    .strokeWidth(2)
                    .strokeColor(Color.BLUE)
                    .fillColor(0x0277A88D)
                    .radius(radius)
                    .center(currentGnssLoc)
                    .zIndex(0)
                    .visible(gnssEnabled);
            circleList.add(replayMap.addCircle(circleOptions));

            PolylineOptions gnssPolylineOptions=new PolylineOptions()
                    .color(Color.BLUE)
                    .add(currentGnssLoc)
                    .zIndex(6)
                    .visible(gnssEnabled);
            gnssPolyline = replayMap.addPolyline(gnssPolylineOptions);

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

        if (startLoc != null) {
            orientationMarker = replayMap.addMarker(new MarkerOptions()
                    .position(startLoc)
                    .title("Current Position")
                    .flat(true)
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(getContext(), R.drawable.ic_baseline_navigation_24)))
                    .zIndex(6));
            PolylineOptions polylineOptions=new PolylineOptions()
                    .color(Color.RED)
                    .add(startLoc)
                    .zIndex(6);
            if(indoorMapManager != null){
                indoorMapManager.setCurrentLocation(startLoc);
                if(indoorMapManager.getIsIndoorMapSet()){
                    indoorMapManager.setCurrentFloor((int)(currElevation/indoorMapManager.getFloorHeight())
                            ,true);
                }
            }

            pdrPolyline = replayMap.addPolyline(polylineOptions);

        }
    }

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
        } else {
            // Ensure the last pressure sample is used when counterPressure reaches the last index
            currElevation = pressureSampleList.get(counterPressure).getEstimatedElevation();
        }
        String formatElevation = df.format(currElevation);
        ElevationPres.setText("Elevation:"+formatElevation+"m");
        // ===== GNSS value update logic ===== //
        if (!gnssDataList.isEmpty() && counterGnss < gnssDataList.size() - 1) {
            // always take the next gnss sample
            Traj.GNSS_Sample nextGnssSample = gnssDataList.get(counterGnss + 1);
            long nextTGnss = nextGnssSample.getRelativeTimestamp();
            if (relativeTBase >= nextTGnss) {
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
                circleList.add(replayMap.addCircle(circleOptions));

                List<LatLng> pointsMoved = gnssPolyline.getPoints();
                pointsMoved.add(currentGnssLoc);
                gnssPolyline.setPoints(pointsMoved);
                gnssPolyline.setVisible(gnssEnabled);

                gnssMarker.setPosition(currentGnssLoc);
                gnssMarker.setVisible(gnssEnabled);

                float altitude = nextGnssSample.getAltitude();
                gnssMarker.setTitle("GNSS position");
                gnssMarker.setSnippet("Acc: " + radius + "m" + " Alt: " + altitude + "m");

                counterGnss++;
            }

        }

        // ===== pdr value update logic ===== //
        int nextStepCount = imuDataList.get(counterYaw).getStepCount();
        if (currStepCount != nextStepCount) {
            currStepCount = nextStepCount;
            currentLocation = pdrLocList.get(currStepCount);

            // move the marker
            if (orientationMarker!=null) {
                orientationMarker.setPosition(currentLocation);
            }

            if (pdrPolyline!=null) {
                // get existing points
                List<LatLng> pointsMoved = pdrPolyline.getPoints();
                // add new point
                pointsMoved.add(currentLocation);
                pdrPolyline.setPoints(pointsMoved);
            }

            if(indoorMapManager != null){
                indoorMapManager.setCurrentLocation(currentLocation);
                if(indoorMapManager.getIsIndoorMapSet()){
                    indoorMapManager.setCurrentFloor((int)(currElevation/indoorMapManager.getFloorHeight())
                            ,true);
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

    public void ProgressView() {
        if (seekBar == null) return;
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
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

    //return back to the last page/////////////////////////////////////////////////////
    private void ReplayBackButton() {
        replayBackButton = requireView().findViewById(R.id.ReplayBackButton);
        replayBackButton.setOnClickListener(view -> {
            if (requireActivity().getSupportFragmentManager().getBackStackEntryCount() > 0) {
                requireActivity().getSupportFragmentManager().popBackStack();
            }
        });
    }//////////////////////////////////////////////////////////////////////////////////


    // play / pause button control
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

    private void setupReplayButton() {
        replayButton = requireView().findViewById(R.id.ReplayButton);
        replayButton.setOnClickListener(v -> {
            if (currTask != null) {
                currTask.cancel();
            }
            PositionInitialization();
            currTask = createTimerTask();
            readPdrTimer.schedule(currTask, 0, TimeInterval);
            isPlaying = true;
            playPauseButton.setImageResource(android.R.drawable.ic_media_pause);
        });
    }
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