package com.openpositioning.PositionMe.fragments;

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
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
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
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * A Fragment that not only shows the live recording but also integrates playback controls
 * (play bar, seek bar, pause/resume, go to beginning/end) to replay the recorded trajectory.
 *
 * In this version, we embed the timestamp from each PDR sample into the trajectory points list
 * by using the TimedLatLng helper class.
 */
public class PlaybackFragment extends Fragment {

    // UI components for recording/live view
    private Button backButton;
    private ImageView recIcon;
    private ProgressBar timeRemaining;
    private TextView elevation;
    private TextView distanceTravelled;
    private TextView gnssError;

    // Playback control UI components
   // private ProgressBar playbackProgressBar;
    private SeekBar playbackSeekBar;
    private Button pauseResumeButton;
    private Button goToBeginningButton;
    private Button goToEndButton;

    // App settings and sensor fusion
    private SharedPreferences settings;
    private SensorFusion sensorFusion;
    private CountDownTimer autoStop;
    private Handler refreshDataHandler;

    // Variables for trajectory/live updates
    private float distance;
    private float previousPosX;
    private float previousPosY;

    // Map-related variables
    private static LatLng start;
    private GoogleMap gMap;
    private Spinner switchMapSpinner;
    private Marker orientationMarker;
    private LatLng currentLocation;
    private LatLng nextLocation;
    private Polyline polyline;
    public IndoorMapManager indoorMapManager;
    public FloatingActionButton floorUpButton;
    public FloatingActionButton floorDownButton;
    private Switch gnss;
    private Marker gnssMarker;
    private Button switchColor;
    private boolean isRed = true;
    private Switch autoFloor;
    private TrajectoryViewModel trajectoryViewModel;

    private Traj.Trajectory trajectory;
    private List<Traj.Pdr_Sample> pdrSamples;
    private List<Traj.GNSS_Sample> gnssSamples;

    // ----------------------------
    // Fields for playback functionality
    private Handler playbackHandler = new Handler();
    private int playbackCurrentIndex = 0;
    private boolean playbackIsPaused = false;
    // Instead of a List<LatLng>, we use a List<TimedLatLng> to store timestamps as well.
    private List<TimedLatLng> trajectoryPointsTimed;
    private List<TimedLatLng> gnssPointsTimed;
    // ----------------------------

    // Helper class to hold both a LatLng and its timestamp.
    private static class TimedLatLng {
        public final LatLng latLng;
        public final long timestamp;

        public TimedLatLng(LatLng latLng, long timestamp) {
            this.latLng = latLng;
            this.timestamp = timestamp;
        }
    }

    public PlaybackFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sensorFusion = SensorFusion.getInstance();
        Context context = getActivity();
        settings = PreferenceManager.getDefaultSharedPreferences(context);
        refreshDataHandler = new Handler();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        trajectoryViewModel = new ViewModelProvider(requireActivity()).get(TrajectoryViewModel.class);
        trajectory = trajectoryViewModel.getTrajectory().getValue();

        // Inflate the layout for this fragment (ensure it contains the playback UI elements)
        View rootView = inflater.inflate(R.layout.fragment_playback, container, false);
        ((AppCompatActivity)getActivity()).getSupportActionBar().hide();
        getActivity().setTitle("Recording...");

        // Obtain start position from the first GNSS sample of the trajectory.
        // (This assumes that trajectory.getGnssData(0) returns an object with getLatitude() and getLongitude())
        Traj.GNSS_Sample startingSample = trajectory.getGnssData(0);
        start = new LatLng(startingSample.getLatitude(), startingSample.getLongitude());


        // Initialize map fragment
        SupportMapFragment supportMapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.PlaybackMap);
        supportMapFragment.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(GoogleMap map) {
                gMap = map;
                indoorMapManager = new IndoorMapManager(map);
                map.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                map.getUiSettings().setCompassEnabled(true);
                map.getUiSettings().setTiltGesturesEnabled(true);
                map.getUiSettings().setRotateGesturesEnabled(true);
                map.getUiSettings().setScrollGesturesEnabled(true);

                // Set start and current location
                currentLocation = start;
                orientationMarker = map.addMarker(new MarkerOptions().position(start)
                        .title("Current Position")
                        .flat(true)
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(getContext(), R.drawable.ic_baseline_navigation_24))));
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 19f));

                PolylineOptions polylineOptions = new PolylineOptions()
                        .color(Color.RED)
                        .add(currentLocation);
                polyline = gMap.addPolyline(polylineOptions);
                indoorMapManager.setCurrentLocation(currentLocation);
                indoorMapManager.setIndicationOfIndoorMap();
            }
        });

        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Live recording UI initialization
        timeRemaining = getView().findViewById(R.id.timeRemainingBar);
        elevation = getView().findViewById(R.id.currentElevation);
        distanceTravelled = getView().findViewById(R.id.currentDistanceTraveled);
        gnssError = getView().findViewById(R.id.gnssError);
        gnssError.setVisibility(View.GONE);
        elevation.setText(getString(R.string.elevation, "0"));
        distanceTravelled.setText(getString(R.string.meter, "0"));
        distance = 0f;
        previousPosX = 0f;
        previousPosY = 0f;

        backButton = getView().findViewById(R.id.backButton);
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(autoStop != null) autoStop.cancel();
                sensorFusion.stopRecording();
                NavDirections action = PlaybackFragmentDirections.actionPlaybackFragmentToFilesFragment();
                Navigation.findNavController(view).navigate(action);
            }
        });

        mapDropdown();
        switchMap();

        floorUpButton = getView().findViewById(R.id.floorUpButton);
        floorDownButton = getView().findViewById(R.id.floorDownButton);
        autoFloor = getView().findViewById(R.id.autoFloor);
        autoFloor.setChecked(true);
        setFloorButtonVisibility(View.GONE);
        floorUpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                autoFloor.setChecked(false);
                indoorMapManager.increaseFloor();
            }
        });
        floorDownButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                autoFloor.setChecked(false);
                indoorMapManager.decreaseFloor();
            }
        });

        gnss = getView().findViewById(R.id.gnssSwitch);
        gnss.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                if (isChecked) {
                    float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
                    LatLng gnssLocation = new LatLng(location[0], location[1]);
                    gnssError.setVisibility(View.VISIBLE);
                    gnssError.setText(String.format(getString(R.string.gnss_error) + "%.2fm",
                            UtilFunctions.distanceBetweenPoints(currentLocation, gnssLocation)));
                    gnssMarker = gMap.addMarker(
                            new MarkerOptions().title("GNSS position")
                                    .position(gnssLocation)
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                } else {
                    if (gnssMarker != null) {
                        gnssMarker.remove();
                    }
                    gnssError.setVisibility(View.GONE);
                }
            }
        });

        switchColor = getView().findViewById(R.id.lineColorButton);
        switchColor.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isRed) {
                    switchColor.setBackgroundColor(Color.BLACK);
                    polyline.setColor(Color.BLACK);
                    isRed = false;
                } else {
                    switchColor.setBackgroundColor(Color.RED);
                    polyline.setColor(Color.RED);
                    isRed = true;
                }
            }
        });

        // Blinking recording indicator
        blinkingRecording();

        // Now set up the playback functionality:
        // Convert the list of PDR samples (relative x,y values) to TimedLatLng points relative to the starting point.
        pdrSamples = trajectory.getPdrDataList();
        trajectoryPointsTimed = new ArrayList<>();
        for (Traj.Pdr_Sample sample : pdrSamples) {
            LatLng point = convertRelativeXYToLatLng(start, sample.getX(), sample.getY());
            // Create a TimedLatLng instance with the point and its timestamp.
            trajectoryPointsTimed.add(new TimedLatLng(point, sample.getRelativeTimestamp()));
        }

        gnssSamples = trajectory.getGnssDataList();

        gnssPointsTimed = new ArrayList<>();
        for (Traj.GNSS_Sample sample : gnssSamples) {
            // Create a LatLng from the sample's latitude and longitude
            LatLng point = new LatLng(sample.getLatitude(), sample.getLongitude());
            // Create a TimedLatLng with the point and its relative timestamp, then add it to the list
            gnssPointsTimed.add(new TimedLatLng(point, sample.getRelativeTimestamp()));
        }



        // Initialize playback UI elements.
        //playbackProgressBar = getView().findViewById(R.id.playbackProgressBar);
        playbackSeekBar = getView().findViewById(R.id.playbackSeekBar);
        pauseResumeButton = getView().findViewById(R.id.pauseResumeButton);
        goToBeginningButton = getView().findViewById(R.id.goToBeginningButton);
        goToEndButton = getView().findViewById(R.id.goToEndButton);

        // Set max values based on trajectoryPointsTimed size.
       // playbackProgressBar.setMax(trajectoryPointsTimed.size());
        playbackSeekBar.setMax(trajectoryPointsTimed.size() - 1);

        // Start playback (gradually drawing the trajectory on the map).
        drawTrajectoryGradually(trajectoryPointsTimed);

        // Set up pause/resume button.
        pauseResumeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (playbackIsPaused) {
                    resumePlayback(trajectoryPointsTimed);
                } else {
                    pausePlayback();
                }
            }
        });

        // Set up "Go to Beginning" and "Go to End" buttons.
        goToBeginningButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goToBeginning(trajectoryPointsTimed);
            }
        });
        goToEndButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goToEnd(trajectoryPointsTimed);
            }
        });

        // SeekBar listener to allow user-controlled playback navigation.
        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {


            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    long targetTimestamp = getTargetTimestamp(progress);
                    int gnssIdx = getTrajectoryPointIndex(gnssPointsTimed, targetTimestamp);
                    int playbackIdx = getTrajectoryPointIndex(trajectoryPointsTimed,targetTimestamp);
                    if (playbackIdx >= trajectoryPointsTimed.size()) {
                        return;
                    }

                    plotGnssSamples(gnssIdx);
                    playbackCurrentIndex = playbackIdx;
                    updatePlaybackPolyline(trajectoryPointsTimed, playbackCurrentIndex);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                pausePlayback();
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                resumePlayback(trajectoryPointsTimed);
            }
        });
//        // Schedule plotting of GNSS samples once the map is ready, after a short delay.
//        new Handler().postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                plotGnssSamples();
//            }
//        }, 5000); // Delay in milliseconds (2 seconds)

        // ---------------
    }

    /**
     * Converts relative x,y offsets (in meters) into a LatLng coordinate based on a given start.
     */
    private LatLng convertRelativeXYToLatLng(LatLng start, float offsetX, float offsetY) {
        double deltaLat = offsetY / 111320.0;
        double cosLat = Math.cos(Math.toRadians(start.latitude));
        if (Math.abs(cosLat) < 1e-6) {
            cosLat = 1e-6; // safeguard against division by zero
        }
        double metersPerDegreeLongitude = 111320.0 * cosLat;
        double deltaLng = offsetX / metersPerDegreeLongitude;
        return new LatLng(start.latitude + deltaLat, start.longitude + deltaLng);
    }

    private void mapDropdown(){
        switchMapSpinner = getView().findViewById(R.id.mapSwitchSpinner);
        String[] maps = new String[]{getString(R.string.hybrid), getString(R.string.normal), getString(R.string.satellite)};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, maps);
        switchMapSpinner.setAdapter(adapter);
    }

    private void switchMap(){
        switchMapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
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
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
            }
        });
    }

    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            updateUIandPosition();
            refreshDataHandler.postDelayed(refreshDataTask, 200);
        }
    };

    private void updateUIandPosition(){
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        distance += Math.sqrt(Math.pow(pdrValues[0] - previousPosX, 2) + Math.pow(pdrValues[1] - previousPosY, 2));
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));
        float[] pdrMoved = {pdrValues[0]-previousPosX, pdrValues[1]-previousPosY};
        if (pdrMoved[0]!=0 || pdrMoved[1]!=0) {
            plotLines(pdrMoved);
        }
        if (indoorMapManager == null) {
            indoorMapManager = new IndoorMapManager(gMap);
        }
        if (gnss.isChecked() && gnssMarker != null) {
            float[] location = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
            LatLng gnssLocation = new LatLng(location[0], location[1]);
            gnssError.setVisibility(View.VISIBLE);
            gnssError.setText(String.format(getString(R.string.gnss_error) + "%.2fm",
                    UtilFunctions.distanceBetweenPoints(currentLocation, gnssLocation)));
            gnssMarker.setPosition(gnssLocation);
        }
        indoorMapManager.setCurrentLocation(currentLocation);
        float elevationVal = sensorFusion.getElevation();
        if (indoorMapManager.getIsIndoorMapSet()){
            setFloorButtonVisibility(View.VISIBLE);
            if (autoFloor.isChecked()){
                indoorMapManager.setCurrentFloor((int)(elevationVal/indoorMapManager.getFloorHeight()), true);
            }
        } else {
            setFloorButtonVisibility(View.GONE);
        }
        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];
        elevation.setText("PLAYBACK FRAGMENT");
        if (orientationMarker != null) {
            orientationMarker.setRotation((float) Math.toDegrees(sensorFusion.passOrientation()));
        }
    }

    private void plotLines(float[] pdrMoved){
        if (currentLocation != null) {
            nextLocation = UtilFunctions.calculateNewPos(currentLocation, pdrMoved);
            try{
                List<LatLng> pointsMoved = polyline.getPoints();
                pointsMoved.add(nextLocation);
                polyline.setPoints(pointsMoved);
                orientationMarker.setPosition(nextLocation);
                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(nextLocation, 19f));
            } catch (Exception ex){
                Log.e("PlottingPDR","Exception: " + ex);
            }
            currentLocation = nextLocation;
        } else {
            float[] location = sensorFusion.getGNSSLatitude(true);
            currentLocation = new LatLng(location[0], location[1]);
            nextLocation = currentLocation;
        }
    }

    private void setFloorButtonVisibility(int visibility){
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        autoFloor.setVisibility(visibility);
    }

    private void blinkingRecording() {
        recIcon = getView().findViewById(R.id.redDot);
        Animation blinking_rec = new AlphaAnimation(1, 0);
        blinking_rec.setDuration(800);
        blinking_rec.setInterpolator(new LinearInterpolator());
        blinking_rec.setRepeatCount(Animation.INFINITE);
        blinking_rec.setRepeatMode(Animation.REVERSE);
        recIcon.startAnimation(blinking_rec);
    }

    @Override
    public void onPause() {
        refreshDataHandler.removeCallbacks(refreshDataTask);
        playbackHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    public void onResume() {
        if (!settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
        super.onResume();
    }

    // ---------------
    // Playback functionality methods:

    /**
     * Gradually draws the playback trajectory by updating the polyline one point at a time.
     * @param trajectoryPoints The full list of TimedLatLng points to be drawn.
     */
    private void drawTrajectoryGradually(final List<TimedLatLng> trajectoryPoints) {
        playbackHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (playbackCurrentIndex < trajectoryPoints.size()) {
                    if (!playbackIsPaused) {
                        // Update the polyline with the current point
                        updatePlaybackPolyline(trajectoryPoints, playbackCurrentIndex);

                        // Default delay in case there is no previous timestamp
                        long delayTime = 1000;

                        // Calculate the time difference between the current and previous point if possible
                        if (playbackCurrentIndex > 0) {
                            long currentTimestamp = trajectoryPoints.get(playbackCurrentIndex).timestamp;
                            long previousTimestamp = trajectoryPoints.get(playbackCurrentIndex - 1).timestamp;
                            long timeDifference = currentTimestamp - previousTimestamp;
                            delayTime = timeDifference;
                        }

                        long targetTimestamp = getTargetTimestamp(playbackCurrentIndex);
                        int gnssIdx = getTrajectoryPointIndex(gnssPointsTimed, targetTimestamp);
                        plotGnssSamples(gnssIdx);


                        playbackCurrentIndex++;
                        // Use the computed delayTime for the next update
                        playbackHandler.postDelayed(this, delayTime);
                    }
                }
            }
        }, 1000);
    }

    /**
     * Returns the trajectory point recorded the closes to the timestamp
     * @param trajectoryPoints
     * @param timestampMillis
     * @return index in trajectoryPoints
     */
    private int getTrajectoryPointIndex(final List<TimedLatLng> trajectoryPoints,
                                        long timestampMillis) {
        int idx = 0;
        while (idx < trajectoryPoints.size() &&
                trajectoryPoints.get(idx).timestamp < timestampMillis) { idx++; }

        if (idx == trajectoryPoints.size()) {
            return trajectoryPoints.size() - 1;
        }

        // idx points to the first element with timestamp greater than timestampMillis
        if (idx > 0) {
            if (timestampMillis - trajectoryPoints.get(idx - 1).timestamp <
                    trajectoryPoints.get(idx).timestamp - timestampMillis) {
                idx--;
            }
        }

        return idx;
    }

    private int getProgressByTime(long timestamp, long minTimestamp, long maxTimestamp, int barSize) {
        // Between 0 and 1
        double timeProgress = ((double) timestamp - minTimestamp) / (maxTimestamp - minTimestamp);

        return (int) (timeProgress * barSize);
    }

    /**
     * Updates the polyline and UI controls based on the current playback index.
     * This method extracts the LatLng coordinates from each TimedLatLng.
     */
    private void updatePlaybackPolyline(List<TimedLatLng> trajectoryPoints, int pointCount) {
        List<LatLng> currentPoints = new ArrayList<>();
        for (int i = 0; i <= pointCount && i < trajectoryPoints.size(); i++) {
            currentPoints.add(trajectoryPoints.get(i).latLng);
        }
        polyline.setPoints(currentPoints);

        int progress = getProgressByTime(trajectoryPoints.get(pointCount).timestamp,
                trajectoryPoints.get(0).timestamp,
                trajectoryPoints.get(trajectoryPoints.size() - 1).timestamp,
                trajectoryPoints.size());

        playbackSeekBar.setProgress(progress);
        if (!currentPoints.isEmpty()) {
            gMap.moveCamera(CameraUpdateFactory.newLatLng(currentPoints.get(currentPoints.size() - 1)));
        }
    }

    private void pausePlayback() {
        playbackIsPaused = true;
        pauseResumeButton.setText("Resume");
        playbackHandler.removeCallbacksAndMessages(null);
    }

    private void resumePlayback(List<TimedLatLng> trajectoryPoints) {
        playbackIsPaused = false;
        pauseResumeButton.setText("Pause");
        drawTrajectoryGradually(trajectoryPoints);
    }

    private void goToBeginning(List<TimedLatLng> trajectoryPoints) {
        pausePlayback();
        playbackCurrentIndex = 0;
        updatePlaybackPolyline(trajectoryPoints, playbackCurrentIndex);
    }

    private void goToEnd(List<TimedLatLng> trajectoryPoints) {
        pausePlayback();
        playbackCurrentIndex = trajectoryPoints.size() - 1;
        updatePlaybackPolyline(trajectoryPoints, playbackCurrentIndex);
    }

    // Add this helper method to plot GNSS samples as dots on the map.
//    private void plotGnssSamples(int idx) {
//        // Check that the map and GNSS samples are available.
//        if (gMap == null || gnssSamples == null || gnssSamples.isEmpty()) {
//            return;
//        }
//        for (int i =0; i <= idx; i++){
//            // Assume each sample has getLatitude(), getLongitude(), and getRelativeTimestamp() methods.
//            LatLng pos = new LatLng(gnssSamples.get(i).getLatitude(), gnssSamples.get(i).getLongitude());
//
//            // Option 1: Plot a marker with a dot-like icon.
//            gMap.addMarker(new MarkerOptions()
//                    .position(pos)
//                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
//                    .anchor(0.5f, 0.5f)
//                    .title("GNSS Sample @" + gnssSamples.get(i).getRelativeTimestamp())
//                    .visible(true));
//        }
//    }
    private List<Marker> gnssMarkers = new ArrayList<>();

    private void plotGnssSamples(int idx) {
        // Check that the map and GNSS samples are available.
        if (gMap == null || gnssSamples == null || gnssSamples.isEmpty()) {
            return;
        }

        // Initialize the GNSS markers if they haven't been created already.
        if (gnssMarkers.isEmpty()) {
            for (Traj.GNSS_Sample sample : gnssSamples) {
                // Create the LatLng position from the sample.
                LatLng pos = new LatLng(sample.getLatitude(), sample.getLongitude());
                // Create a marker with the desired properties, setting it as not visible initially.
                Marker marker = gMap.addMarker(new MarkerOptions()
                        .position(pos)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW))
                        .anchor(0.5f, 0.5f)
                        .title("GNSS Sample @" + sample.getRelativeTimestamp())
                        .visible(false));
                gnssMarkers.add(marker);
            }
        }

        // Loop through the marker list: show markers for indices <= idx, hide the rest.
        for (int i = 1; i < gnssMarkers.size(); i++) {
            if (i <= idx) {
                gnssMarkers.get(i).setVisible(true);
            } else {
                gnssMarkers.get(i).setVisible(false);
            }
        }
    }

    private long getTargetTimestamp(int progress){
        long lastTimestamp = trajectoryPointsTimed.get(trajectoryPointsTimed.size() - 1)
                .timestamp;
        long firstTimestamp = trajectoryPointsTimed.get(0).timestamp;
        double progressRatio = ((double) progress) / trajectoryPointsTimed.size();
        long targetTimestamp = (long) (progressRatio * (lastTimestamp - firstTimestamp))
                + firstTimestamp;
        return targetTimestamp;
    }




    // ---------------
}