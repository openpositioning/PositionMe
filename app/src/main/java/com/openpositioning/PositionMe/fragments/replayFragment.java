package com.openpositioning.PositionMe.fragments;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;

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
import com.google.protobuf.InvalidProtocolBufferException;
import com.openpositioning.PositionMe.PdrProcessing;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.PdrProcessing;

import java.util.ArrayList;
import java.util.List;

public class replayFragment extends Fragment {

    private GoogleMap gMap;
    private SeekBar seekBar;
    private Polyline trajectoryPolyline;
    private Polyline gnssTrajectoryPolyline;
    private Traj.Trajectory receivedTrajectory;
    private LatLng initialPosition;
    private Handler handler = new Handler();
    private boolean isPlaying = false;
    private ImageButton playButton, pauseButton, replayButton, endButton;
    private Button speedHalfButton, speedDoubleButton, viewStatsButton;
    private int playbackSpeed = 100;
    private Marker movingMarker;
    private List<LatLng> trajectoryPoints = new ArrayList<>();
    private List<LatLng> revealedTrajectoryPoints = new ArrayList<>();
    private TextView timeElapsedTextView, timeRemainingTextView;
    private int totalDuration = 0;
    private boolean statsButtonVisible = false; // Track if button should be visible
    public IndoorMapManager indoorMapManager;

    public String trajectoryJSON;
    public float elevationVal;

    public List<Traj.Pressure_Sample> pressureData;
    public PdrProcessing pdrProcessing;

    public Marker movingGnssMarker;
    private List<LatLng> gnssTrajectoryPoints = new ArrayList<>();
    private List<LatLng> gnssRevealedTrajectoryPoints = new ArrayList<>();

    /**
     * onCreateView to initialise the ReplayFragment xml layout.
     * Playback buttons are initialised with listener functions to achieve their functionality.
     * The Trajectory file is received as a JSON and parsed before being built back into a Trajectory type.
     * PDR and GNSS data are extracted from the trajectory when initialising the map and passed as
     * arguments to the loadTrajectory() function.
     * @param inflater The LayoutInflater object that can be used to inflate
     * any views in the fragment,
     * @param container If non-null, this is the parent view that the fragment's
     * UI should be attached to.  The fragment should not add the view itself,
     * but this can be used to generate the LayoutParams of the view.
     * @param savedInstanceState If non-null, this fragment is being re-constructed
     * from a previous saved state as given here.
     *
     * @return rootView
     * @ Author - Jamie Arnott
     * @ Author - Guilherme Barreiros
     */
    @SuppressLint("WrongViewCast")
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.activity_replay_fragment, container, false);

        // Initialize UI elements
        seekBar = rootView.findViewById(R.id.trajectorySeekBar);
        timeElapsedTextView = rootView.findViewById(R.id.timeElapsedTextView);
        timeRemainingTextView = rootView.findViewById(R.id.timeRemainingTextView);
        playButton = rootView.findViewById(R.id.playButton);
        pauseButton = rootView.findViewById(R.id.pauseButton);
        speedHalfButton = rootView.findViewById(R.id.speedHalfButton);
        speedDoubleButton = rootView.findViewById(R.id.speedDoubleButton);
        replayButton = rootView.findViewById(R.id.replayButton);
        endButton = rootView.findViewById(R.id.goToEndButton);
        viewStatsButton = rootView.findViewById(R.id.viewStatsButton);

        // listener functions for playback buttons
        pauseButton.setOnClickListener(v -> stopPlaying());

        speedHalfButton.setOnClickListener(v -> {
            setPlaybackSpeed(200);
            startPlaying();
        });

        speedDoubleButton.setOnClickListener(v -> {
            setPlaybackSpeed(50);
            startPlaying();
        });

        replayButton.setOnClickListener(v -> {
            stopPlaying();
            seekBar.setProgress(0);
            updateMarkerPosition(0);
        });

        endButton.setOnClickListener(v -> {
            stopPlaying();
            int maxProgress = trajectoryPoints.size() > 0 ? trajectoryPoints.size() - 1 : 0;
            seekBar.setProgress(maxProgress);
            updateMarkerPosition(maxProgress);
        });

        // initialise the toolbar to display at the top of the page
        Toolbar toolbar = rootView.findViewById(R.id.toolbar);
        toolbar.setTitle("Replay Trajectory");
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setNavigationOnClickListener(v -> getActivity().onBackPressed());

        ((AppCompatActivity) getActivity()).getSupportActionBar().hide();

        viewStatsButton = rootView.findViewById(R.id.viewStatsButton);

        if (statsButtonVisible) {
            viewStatsButton.setVisibility(View.VISIBLE);
        } else {
            viewStatsButton.setVisibility(View.GONE);
        }

        // check the received arguments
        if (getArguments() != null) {
            // parse the trajectory JSON file
            String trajectoryJson = getArguments().getString("trajectory");
            trajectoryJSON = trajectoryJson;
            Log.d("ReplayFragment", "Received trajectory JSON: " + trajectoryJson);
            // initialise a new trajectory builder
            Traj.Trajectory.Builder trajectoryBuilder = Traj.Trajectory.newBuilder();
            try {
                // merge the JSON and builder trajectories using parsing function
                com.google.protobuf.util.JsonFormat.parser().merge(trajectoryJson, trajectoryBuilder);
                // build trajectory
                receivedTrajectory = trajectoryBuilder.build();
            } catch (InvalidProtocolBufferException e) {
                Log.e("ReplayFragment", "Error parsing trajectory JSON", e);
                return rootView;
            }
            Log.d("TrajectoryData", "PDR Points: " + receivedTrajectory.getPdrDataCount());
            Log.d("TrajectoryData" , "GNSS Points: " + receivedTrajectory.getGnssDataCount());
            Log.d("TrajectoryData", "Pressure Points: " + receivedTrajectory.getPressureDataCount());
            if (receivedTrajectory.getGnssDataCount() > 0) {
                // check if GNSS data exists and initialise the start point as the first lat and lon elements
                Traj.GNSS_Sample firstGnss = receivedTrajectory.getGnssData(0);
                initialPosition = new LatLng(firstGnss.getLatitude(), firstGnss.getLongitude());
            } else {
                // If no GNSS data exists, set start location as Nucleus Building
                initialPosition = new LatLng(55.9229346, -3.17451653);
            }
            // obtain timestamps of PDR data to display the time next to the seek bar
            if (receivedTrajectory.getPdrDataCount() > 1) {
                long startTime = receivedTrajectory.getPdrData(0).getRelativeTimestamp();
                long endTime = receivedTrajectory.getPdrData(receivedTrajectory.getPdrDataCount() - 1).getRelativeTimestamp();
                totalDuration = (int) ((endTime - startTime) / 1000);
            }
        }

        SupportMapFragment supportMapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.fragmentContainerView);
        // initialise the google map fragment
        supportMapFragment.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(GoogleMap map) {
                gMap = map;
                gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                gMap.getUiSettings().setCompassEnabled(true);
                gMap.getUiSettings().setTiltGesturesEnabled(true);
                gMap.getUiSettings().setRotateGesturesEnabled(true);
                gMap.getUiSettings().setScrollGesturesEnabled(true);

                // initialise the indoor map manager for the replay on the google map
                indoorMapManager = new IndoorMapManager(gMap);

                // extract the PDR data and store the X and Y elements in a list
                List<float[]> pdrDataList = new ArrayList<>();
                for (Traj.Pdr_Sample pdr : receivedTrajectory.getPdrDataList()) {
                    pdrDataList.add(new float[]{pdr.getX(), pdr.getY()});
                    Log.d("ReplayFragment", "PDR data XY: " + pdr.getX() + " " + pdr.getY());
                }
                // extract the GNSS data and store the X and Y elements in a list
                List<Traj.GNSS_Sample> gnssDataList = new ArrayList<>();
                for (Traj.GNSS_Sample gnss : receivedTrajectory.getGnssDataList()){
                    gnssDataList.add(gnss);
                    Log.d("ReplayFragment", "Gnss Data: Latitude: " + gnss.getLatitude() + " Longitude: " + gnss.getLongitude());
                }

                Log.d("ReplayFragment", "PDR data: " + pdrDataList);
                // call loadTrajectory() and pass necessary arguments
                loadTrajectory(pdrDataList, gnssDataList, initialPosition, gMap);
            }
        });

        playButton.setOnClickListener(v -> {
            setPlaybackSpeed(100);
            startPlaying();
        });

        pauseButton.setOnClickListener(v -> stopPlaying());
        speedHalfButton.setOnClickListener(v -> setPlaybackSpeed(200));
        speedDoubleButton.setOnClickListener(v -> setPlaybackSpeed(50));
        viewStatsButton.setOnClickListener(v -> navigateToStats());


        return rootView;
    }

    /**
     * Function to navigate from the ReplayFragment to the StatsFragment using
     * the NavDirections
     * @ Author - Guilherme Barreiros
     */
    private void navigateToStats() {
        if (receivedTrajectory != null) {
            try {
                // Convert Trajectory to JSON
                String trajectoryJson = com.google.protobuf.util.JsonFormat.printer().print(receivedTrajectory);

                // Navigate using Safe Args
                NavDirections action = replayFragmentDirections.actionReplayFragmentToStatsFragment(trajectoryJson);
                Navigation.findNavController(requireView()).navigate(action);
            } catch (Exception e) {
                Log.e("ReplayFragment", "Error serializing trajectory", e);
            }
        }
    }

    /**
     * Function to process the trajectory data and plot them on the google map using polyline
     * Trajectory is plotted by looping through PDR and GNSS data and computing the new coords to add to
     * trajectoryPoint and gnssTrajectoryPoint lists.
     * Calls to UpdateUIandMarker() to plot the lines as the markers move
     * @param recordedPDRData
     * @param recordedGnssData
     * @param initialPosition
     * @param gMap
     * @ Author - Jamie Arnott
     * @ Author - Guilherme Barreiros
     */
    private void loadTrajectory(List<float[]> recordedPDRData, List<Traj.GNSS_Sample> recordedGnssData, LatLng initialPosition, GoogleMap gMap) {
        if (gMap == null || recordedPDRData.isEmpty()) return;

        // clear the google map and initialise BitMaps for the PDR and GNSS markers
        gMap.clear();
        Bitmap largeMarker = UtilFunctions.getBitmapFromVector(getContext(), R.drawable.ic_baseline_navigation_24, 2f);
        Bitmap gnssMarker = UtilFunctions.getBitmapFromVector(getContext(),R.drawable.ic_launcher_icon, 2f);

        // initialise markers
        movingMarker = gMap.addMarker(new MarkerOptions()
                .position(initialPosition)
                .title("Current Position")
                .flat(true)
                .anchor(0.5f, 0.5f)
                .icon(BitmapDescriptorFactory.fromBitmap(largeMarker)));

        movingGnssMarker = gMap.addMarker(new MarkerOptions()
                .position(initialPosition)
                .title("Gnss Current Position")
                .flat(true)
                .anchor(0.5f, 0.5f)
                .icon(BitmapDescriptorFactory.fromBitmap(gnssMarker))
        );

        // clear and add initial coords to the trajectory lists
        trajectoryPoints.clear();
        revealedTrajectoryPoints.clear();
        trajectoryPoints.add(initialPosition);
        revealedTrajectoryPoints.add(initialPosition);
        gnssTrajectoryPoints.clear();
        gnssRevealedTrajectoryPoints.clear();
        gnssTrajectoryPoints.add(initialPosition);
        gnssRevealedTrajectoryPoints.add(initialPosition);

        // initialise previousPos variables using first values in PDR set
        LatLng lastPosition = initialPosition;
        float previousPosX = recordedPDRData.get(0)[0];
        float previousPosY = recordedPDRData.get(0)[1];


        // retreieve pressure data to compute the elevationVal
        pressureData = receivedTrajectory.getPressureDataList();
        pdrProcessing = new PdrProcessing(getContext());
        elevationVal = pdrProcessing.updateElevation(SensorManager.getAltitude(
                SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureData.get(0).getPressure()));
        // use IndoorMapManager to determine if the coordinates lie inside one of the indoor spaces
        indoorMapManager.setCurrentLocation(initialPosition);
        // set the green bounding box indicators of available indoor spaces
        indoorMapManager.setIndicationOfIndoorMap();
        // set the current floor of the building using the elevationVal
        indoorMapManager.setCurrentFloor((int)(elevationVal/indoorMapManager.getFloorHeight())
                ,true);

        // pressure list and PDR list not the same length, so compute ratio to index pressure
        // without going higher than available index
        float pdrToPressureListRatio = (float)recordedPDRData.size()/pressureData.size(); // pressure and pdr lists not always same length
        int j = 0;

        for (int i = 1; i < recordedPDRData.size(); i++) {
            float[] pdrData = recordedPDRData.get(i);
            // compute PDR moved by subtracting previous X and Y data from new X and Y data
            float[] pdrMoved = {pdrData[0] - previousPosX, pdrData[1] - previousPosY};
            // use UtilFunctions to calculate new position based on pdrMoved and lastPosition
            LatLng newPosition = UtilFunctions.calculateNewPos(lastPosition, pdrMoved);
            trajectoryPoints.add(newPosition);
            // add GNSS points to gnssTrajectoryPoints
            // check to ensure index never exceeds size() of recordedGnssData
            if (i > recordedGnssData.size()-1){
                gnssTrajectoryPoints.add(new LatLng(recordedGnssData.get(recordedGnssData.size()-1).getLatitude(),recordedGnssData.get(recordedGnssData.size()-1).getLongitude()));
            } else{
                gnssTrajectoryPoints.add(new LatLng(recordedGnssData.get(i).getLatitude(),recordedGnssData.get(i).getLongitude()));
            }
            // update lastPosition
            lastPosition = newPosition;

            previousPosX = pdrData[0];
            previousPosY = pdrData[1];
            // compute the new elevationVal and indoor floor number
            j = (int)Math.max(i/pdrToPressureListRatio-1,0);
            elevationVal = pdrProcessing.updateElevation(SensorManager.getAltitude(
                    SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureData.get(j).getPressure()));
            indoorMapManager.setCurrentFloor((int)(elevationVal/indoorMapManager.getFloorHeight())
                    ,true);
            Log.d("ElevationHeight", "Floor Value: " + (-1)*(int)elevationVal/indoorMapManager.getFloorHeight());
        }

        // Ensure polyline is initialized properly
        trajectoryPolyline = gMap.addPolyline(new PolylineOptions().color(Color.RED).addAll(revealedTrajectoryPoints));
        gnssTrajectoryPolyline = gMap.addPolyline(new PolylineOptions().color(Color.BLUE).addAll(gnssRevealedTrajectoryPoints));
        // move camera to initial position
        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialPosition, 19f));
        seekBar.setMax(trajectoryPoints.size() - 1);
        // listener function for seekBar
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateMarkerPosition(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                stopPlaying();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (isPlaying) {
                    startPlaying();
                }
            }
        });
    }

    /**
     * Function to update the marker positions and plot the lines as the markers move
     * Ensures that the trajectory line is hidden until the marker reaches the given coordinate
     * @param index
     * @ Author - Guilherme Barreiros: Wrote the main functionality for the movingMarker
     * @ Author - Jamie Arnott: Added the functionality for the movingGnssMarker
     */
    private void updateMarkerPosition(int index) {
        if (index < 0 || index >= trajectoryPoints.size()) return;

        LatLng newPosition = trajectoryPoints.get(index);
        LatLng gnssNewPosition = gnssTrajectoryPoints.get(index);
        movingMarker.setPosition(newPosition);
        movingGnssMarker.setPosition(gnssNewPosition);
        gMap.animateCamera(CameraUpdateFactory.newLatLng(newPosition));

        // calculates angle to next point to point marker in correct direction
        if (index < trajectoryPoints.size() - 1) {
            LatLng nextPosition = trajectoryPoints.get(index + 1);
            movingMarker.setRotation(calculateBearing(newPosition, nextPosition));
        }

        // add the trajectoryPoints to the revealedTrajectoryPoints as the index increases
        revealedTrajectoryPoints.clear();
        gnssRevealedTrajectoryPoints.clear();
        for (int i = 0; i <= index-1; i++) {
            revealedTrajectoryPoints.add(trajectoryPoints.get(i));
            gnssRevealedTrajectoryPoints.add(gnssTrajectoryPoints.get(i));
        }
        // add the revealed points to the polylines
        trajectoryPolyline.setPoints(revealedTrajectoryPoints);
        gnssTrajectoryPolyline.setPoints(gnssRevealedTrajectoryPoints);

        // **Fixed Time Elapsed & Remaining Updates**
        int elapsedTime = (int) ((index / (float) seekBar.getMax()) * totalDuration);
        int remainingTime = totalDuration - elapsedTime;

        timeElapsedTextView.setText(formatTime(elapsedTime));
        timeRemainingTextView.setText(formatTime(remainingTime));

        // Show stats button when replay reaches the end
        if (index == trajectoryPoints.size() - 1 && !statsButtonVisible) {
            viewStatsButton.setVisibility(View.VISIBLE);
            statsButtonVisible = true;
        }
        // check the current position on the indoorMapManager again
        indoorMapManager.setCurrentLocation(newPosition);
    }

    /**
     * Function to calculate the angle between 2 points
     * @param start
     * @param end
     * @return float angle
     * @ Author - Guilherme Barreiros
     */
    private float calculateBearing(LatLng start, LatLng end) {
        return (float) Math.toDegrees(Math.atan2(
                end.longitude - start.longitude, end.latitude - start.latitude));
    }

    /**
     * Function to format the time to seconds on the display
     * @param seconds
     * @return String time format
     * @ Author - Guilherme Barreiros
     */
    private String formatTime(int seconds) {
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }


    /**
     * Set the playback speed
     * @param speed
     * @ Author - Guilherme Barreiros
     */
    private void setPlaybackSpeed(int speed) {
        playbackSpeed = speed;
        if (isPlaying) {
            stopPlaying();
            startPlaying();
        }
    }

    /**
     * Start playing when the play button is clicked
     * @ Author - Guilherme Barreiros
     */
    private void startPlaying() {
        if (seekBar == null || seekBar.getMax() == 0) return;

        if (isPlaying) stopPlaying();
        isPlaying = true;

        handler.postDelayed(() -> {
            if (isPlaying && seekBar.getProgress() < seekBar.getMax()) {
                seekBar.setProgress(seekBar.getProgress() + 1);
                handler.postDelayed(this::startPlaying, playbackSpeed);
            }
        }, playbackSpeed);
    }

    /**
     * Stop playing when the video ends or the stop button is pressed
     * @ Author - Guilherme Barreiros
     */
    private void stopPlaying() {
        isPlaying = false;
        handler.removeCallbacksAndMessages(null);
    }
}