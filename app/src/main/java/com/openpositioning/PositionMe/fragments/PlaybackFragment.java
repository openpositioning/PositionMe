package com.openpositioning.PositionMe.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
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
import java.util.ArrayList;
import java.util.List;

/**
 * A Fragment that not only shows the live recording but also integrates playback controls
 * (play bar, seek bar, pause/resume, go to beginning/end) to replay the recorded trajectory.
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

    // Changed to Image button so that it can show drawable.
    private ImageButton pauseResumeButton;
    private Button goToBeginningButton;
    private Button goToEndButton;

    private SharedPreferences settings;
    private CountDownTimer autoStop;

    // Variables for trajectory/live updates
    private double distance;

    // Map-related variables
    private static TimedLatLng start;
    private GoogleMap gMap;
    private Spinner switchMapSpinner;
    private Marker orientationMarker;
    private TimedLatLng currentLocation;
    private Polyline polyline;
    public IndoorMapManager indoorMapManager;
    public FloatingActionButton floorUpButton;
    public FloatingActionButton floorDownButton;
    private Switch gnss;
    private Button switchColor;
    private boolean isRed = true;
    private Switch autoFloor;
    private TrajectoryViewModel trajectoryViewModel;

    private Traj.Trajectory trajectory;
    private List<Traj.Pdr_Sample> pdrSamples;
    private List<Traj.GNSS_Sample> gnssSamples;

    private List<Marker> gnssMarkers = new ArrayList<>();


    // ----------------------------
    // Fields for playback functionality
    private Handler playbackHandler = new Handler();
    private int playbackCurrentIndex = 0;
    private int currentPressureIdx = 0;
    private int gnssIdx = 0;
    private long playbackCurrentTimestamp = 0;
    private boolean playbackIsPaused = false;
    // Instead of a List<LatLng>, we use a List<TimedLatLng> to store timestamps as well.
    private List<TimedLatLng> trajectoryPointsTimed;
    private List<TimedLatLng> gnssPointsTimed;
    private final int SEEKBAR_SIZE = 2000;
    private List<TimedLatLng> timedAltitude;
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
        Context context = getActivity();
        settings = PreferenceManager.getDefaultSharedPreferences(context);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        trajectoryViewModel = new ViewModelProvider(requireActivity()).get(TrajectoryViewModel.class);
        trajectory = trajectoryViewModel.getTrajectory().getValue();

        // Inflate the layout for this fragment (ensure it contains the playback UI elements)
        View rootView = inflater.inflate(R.layout.fragment_playback, container, false);
        ((AppCompatActivity)getActivity()).getSupportActionBar().hide();

        // Obtain start position from the first GNSS sample of the trajectory.
        // (This assumes that trajectory.getGnssData(0) returns an object with getLatitude() and getLongitude())
        Traj.GNSS_Sample startingSample = trajectory.getGnssData(0);
        start = new TimedLatLng(new LatLng(startingSample.getLatitude(), startingSample.getLongitude()),
                                startingSample.getRelativeTimestamp());


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
                orientationMarker = map.addMarker(new MarkerOptions().position(start.latLng)
                        .title("Current Position")
                        .flat(true)
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(getContext(), R.drawable.ic_baseline_navigation_24))));
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(start.latLng, 19f));

                // Index is set so that it is above the floor map.
                PolylineOptions polylineOptions = new PolylineOptions()
                        .color(Color.RED)
                        .zIndex(10f)
                        .add(currentLocation.latLng);
                polyline = gMap.addPolyline(polylineOptions);
                indoorMapManager.setCurrentLocation(currentLocation.latLng);
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

        backButton = getView().findViewById(R.id.backButton);
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(autoStop != null) autoStop.cancel();
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
            LatLng point = convertRelativeXYToLatLng(start.latLng, sample.getX(), sample.getY());
            // Create a TimedLatLng instance with the point and its timestamp.
            trajectoryPointsTimed.add(new TimedLatLng(point, sample.getRelativeTimestamp()));
        }
        playbackCurrentTimestamp = trajectoryPointsTimed.get(0).timestamp;

        gnssSamples = trajectory.getGnssDataList();

        gnssPointsTimed = new ArrayList<>();
        for (Traj.GNSS_Sample sample : gnssSamples) {
            // Create a LatLng from the sample's latitude and longitude
            LatLng point = new LatLng(sample.getLatitude(), sample.getLongitude());
            // Create a TimedLatLng with the point and its relative timestamp, then add it to the list
            gnssPointsTimed.add(new TimedLatLng(point, sample.getRelativeTimestamp()));
        }

        timedAltitude = new ArrayList<>();
        double initialElevation = 0;
        List<Traj.Pressure_Sample> pressureSamples = trajectory.getPressureDataList();
        for (int i = 0; i < trajectory.getPressureDataCount(); i++){
            double elevation = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                    pressureSamples.get(i).getPressure());
            if(i == 0) initialElevation = elevation;

            LatLng altitude = new LatLng(-1, elevation - initialElevation);
            // TODO: HACK - reuse functions by using longitude of LatLng to store elevation
            timedAltitude.add(new TimedLatLng(altitude,
                    pressureSamples.get(i).getRelativeTimestamp()));


        }

        // Initialize playback UI elements.
        //playbackProgressBar = getView().findViewById(R.id.playbackProgressBar);
        playbackSeekBar = getView().findViewById(R.id.playbackSeekBar);
        pauseResumeButton = getView().findViewById(R.id.pauseResumeButton);
        goToBeginningButton = getView().findViewById(R.id.goToBeginningButton);
        goToEndButton = getView().findViewById(R.id.goToEndButton);

        playbackSeekBar.setMax(SEEKBAR_SIZE);

        // Start playback (gradually drawing the trajectory on the map).
        drawTrajectoryGradually(trajectoryPointsTimed);

        // Set up pause/resume button.
        pauseResumeButton.setOnClickListener(v -> {
            if (playbackIsPaused) {
                resumePlayback(trajectoryPointsTimed);
            } else {
                pausePlayback();
            }
        });

        // Set up "Go to Beginning" and "Go to End" buttons.
        goToBeginningButton.setOnClickListener(v -> goToBeginning(trajectoryPointsTimed));
        goToEndButton.setOnClickListener(v -> goToEnd(trajectoryPointsTimed));

        // SeekBar listener to allow user-controlled playback navigation.
        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    long targetTimestamp = getTargetTimestamp(
                            progress,
                            trajectoryPointsTimed.get(0).timestamp,
                            trajectoryPointsTimed.get(trajectoryPointsTimed.size() - 1).timestamp,
                            SEEKBAR_SIZE);
                    playbackCurrentIndex = getPlaybackCurrentIdx(trajectoryPointsTimed, targetTimestamp);
                    playbackCurrentTimestamp = targetTimestamp;
                    updatePlaybackPolyline(trajectoryPointsTimed, targetTimestamp);
                    currentLocation = trajectoryPointsTimed.get(playbackCurrentIndex);
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
        // Necessary to prevent the code from trying to continue the replay!
        playbackHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
    }


    // When pausing, show the play drawable.
    private void pausePlayback() {
        playbackIsPaused = true;
        pauseResumeButton.setImageResource(R.drawable.ic_play);
        playbackHandler.removeCallbacksAndMessages(null);
    }

    // When resuming, show the resume drawable.
    private void resumePlayback(List<TimedLatLng> trajectoryPoints) {
        playbackIsPaused = false;
        pauseResumeButton.setImageResource(R.drawable.ic_pause);
        drawTrajectoryGradually(trajectoryPoints);
    }
    private void goToBeginning(List<TimedLatLng> trajectoryPoints) {
        pausePlayback();
        playbackCurrentIndex = 0;
        playbackCurrentTimestamp = trajectoryPoints.get(0).timestamp;
        updatePlaybackPolyline(trajectoryPoints, playbackCurrentTimestamp);
    }

    private void goToEnd(List<TimedLatLng> trajectoryPoints) {
        pausePlayback();
        playbackCurrentIndex = trajectoryPoints.size() - 1;
        playbackCurrentTimestamp = trajectoryPoints.get(trajectoryPoints.size() - 1).timestamp;
        updatePlaybackPolyline(trajectoryPoints, playbackCurrentTimestamp);
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
                if (playbackCurrentTimestamp
                        < trajectoryPoints.get(trajectoryPoints.size() - 1).timestamp) {
                    if (!playbackIsPaused) {
                        playbackCurrentIndex = getPlaybackCurrentIdx(trajectoryPointsTimed, playbackCurrentTimestamp);
                        updatePlaybackPolyline(trajectoryPoints, playbackCurrentTimestamp);
                        long startTimestamp = trajectoryPoints.get(0).timestamp;
                        long lastTimestamp = trajectoryPoints.get(trajectoryPoints.size() - 1)
                                .timestamp;

                        // time until the next seekbar step
                        long deltaT = (lastTimestamp - startTimestamp) / SEEKBAR_SIZE;

                        if (playbackCurrentIndex < trajectoryPoints.size() - 1) {
                            long currentTimestamp = trajectoryPoints.get(playbackCurrentIndex).timestamp;
                            long nextTimestamp = trajectoryPoints.get(playbackCurrentIndex + 1).timestamp;

                            // If the next sample appears sooner than the seekbar's deltaT
                            if (nextTimestamp - currentTimestamp <= deltaT) {
                                deltaT = nextTimestamp - currentTimestamp;
                                playbackCurrentIndex++;
                            }
                        currentLocation = trajectoryPointsTimed.get(playbackCurrentIndex);
                        }
                        playbackCurrentTimestamp += deltaT;
                        playbackHandler.postDelayed(this, deltaT);
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

    private int getProgressByTime(long timestamp, long minTimestamp, long maxTimestamp, final int barSize) {
        // Between 0 and 1
        double timeProgress = ((double) timestamp - minTimestamp) / (maxTimestamp - minTimestamp);

        return (int) (timeProgress * barSize);
    }

    /**
     * Calculates the bearing between two LatLng points.
     * Y and X are derived from spherical laws of sines and cosines.
     * Bearing is normalized.
     */
    private double calculateBearing(LatLng from, LatLng to) {
        double lat1 = Math.toRadians(from.latitude);
        double lon1 = Math.toRadians(from.longitude);
        double lat2 = Math.toRadians(to.latitude);
        double lon2 = Math.toRadians(to.longitude);
        double dLon = lon2 - lon1;
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        double bearing = Math.atan2(y, x);
        bearing = Math.toDegrees(bearing);
        return (bearing + 360) % 360;
    }
    /**
     * Updates the polyline and UI controls based on the current playback index.
     * This method extracts the LatLng coordinates from each TimedLatLng.
     * The orientation is also updated.
     */
    private void updatePlaybackPolyline(List<TimedLatLng> trajectoryPoints, long currentTimestamp) {
        List<LatLng> currentPoints = new ArrayList<>();
        for (int i = 0;
             i < trajectoryPoints.size() && trajectoryPoints.get(i).timestamp <= currentTimestamp;
             i++) {
            currentPoints.add(trajectoryPoints.get(i).latLng);
        }
        // Update UI, position and GNSS data
        updateUIandPosition();
        gnssIdx = getTrajectoryPointIndex(gnssPointsTimed, currentTimestamp);
        plotGnssSamples(gnssIdx);
        currentPressureIdx = getTrajectoryPointIndex(timedAltitude, currentTimestamp);


        polyline.setPoints(currentPoints);
        int seekbarProgress = getProgressByTime(
                currentTimestamp,
                trajectoryPoints.get(0).timestamp,
                trajectoryPoints.get(trajectoryPoints.size() - 1).timestamp,
                SEEKBAR_SIZE);
        playbackSeekBar.setProgress(seekbarProgress);
        if (!currentPoints.isEmpty()) {
            // Get the current point (the last one in the list)
            LatLng currentPoint = currentPoints.get(currentPoints.size() - 1);

            // Update the marker’s position if it has changed.
            if (!currentPoint.equals(orientationMarker.getPosition())) {
                orientationMarker.setPosition(currentPoint);
                gMap.moveCamera(CameraUpdateFactory.newLatLng(currentPoint));
            }

            // Bearing is computed if there is a next point available.
            if (currentPoints.size() < trajectoryPoints.size()) {
                LatLng nextPoint = trajectoryPoints.get(currentPoints.size()).latLng;
                float newBearing = (float) calculateBearing(currentPoint, nextPoint);

                // Compare the current rotation with the new bearing by rounding to the nearest integer.
                // Rounding happens to change only when significant difference in bearing has been made.
                int currentRotationInt = Math.round(orientationMarker.getRotation());
                int newBearingInt = Math.round(newBearing);

                // Only update the rotation if there is a significant difference. Can avoid flicker.
                if (currentRotationInt != newBearingInt) {
                    orientationMarker.setRotation(newBearing);
                }
            }
        }
    }
    private void updateUIandPosition(){
        distance = 0;
        for (int i = 1; i <= playbackCurrentIndex; i++) {
            distance += Math.hypot(
                    pdrSamples.get(i).getX() - pdrSamples.get(i - 1).getX(),
                    pdrSamples.get(i).getY() - pdrSamples.get(i - 1).getY()
            );
        }
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));
        if (indoorMapManager == null) {
            indoorMapManager = new IndoorMapManager(gMap);
        }
        if (gnss.isChecked()) {
            gnssError.setVisibility(View.VISIBLE);
            gnssError.setText(String.format(getString(R.string.gnss_error) + "%.2fm",
                    UtilFunctions.distanceBetweenPoints(trajectoryPointsTimed.get(playbackCurrentIndex).latLng,
                            gnssPointsTimed.get(gnssIdx).latLng)));
        }
        indoorMapManager.setCurrentLocation(currentLocation.latLng);
        double elevationVal = timedAltitude.get(currentPressureIdx).latLng.longitude;
        this.elevation.setText(String.format("Elevation: %.2f",elevationVal));

        if (indoorMapManager.getIsIndoorMapSet()){
            setFloorButtonVisibility(View.VISIBLE);
            if (autoFloor.isChecked()){
                indoorMapManager.setCurrentFloor((int)(elevationVal/indoorMapManager.getFloorHeight()), true);
            }
        } else {
            setFloorButtonVisibility(View.GONE);
        }
    }
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
            if (i <= idx && gnss.isChecked()) {
                gnssMarkers.get(i).setVisible(true);
            } else {
                gnssMarkers.get(i).setVisible(false);
            }
        }
    }

    private long getTargetTimestamp(int progress, long firstTimestamp, long lastTimestamp,
                                    final int seekbarSize){
        double progressRatio = ((double) progress) / seekbarSize;
        return (long) (progressRatio * (lastTimestamp - firstTimestamp)) + firstTimestamp;
    }

    /**
     * @param trajectoryPoints
     * @param timestamp
     * @return integer i such that all timestamps from 0 to i inclusive are less or equal
     * `timestamp`
     */
    private int getPlaybackCurrentIdx(List<TimedLatLng> trajectoryPoints, long timestamp) {
        int i = 0;
        while (i < trajectoryPoints.size() && trajectoryPoints.get(i).timestamp <= timestamp) i++;
        if (i > 0) {
            i--;
        }
        return i;
    }
}
