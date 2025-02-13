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


        if (getArguments() != null) {
            String trajectoryJson = getArguments().getString("trajectory");
            trajectoryJSON = trajectoryJson;
            Log.d("ReplayFragment", "Received trajectory JSON: " + trajectoryJson);

            Traj.Trajectory.Builder trajectoryBuilder = Traj.Trajectory.newBuilder();
            try {
                com.google.protobuf.util.JsonFormat.parser().merge(trajectoryJson, trajectoryBuilder);
                receivedTrajectory = trajectoryBuilder.build();
            } catch (InvalidProtocolBufferException e) {
                Log.e("ReplayFragment", "Error parsing trajectory JSON", e);
                return rootView;
            }
            Log.d("TrajectoryData", "PDR Points: " + receivedTrajectory.getPdrDataCount());
            Log.d("TrajectoryData" , "GNSS Points: " + receivedTrajectory.getGnssDataCount());
            Log.d("TrajectoryData", "Pressure Points: " + receivedTrajectory.getPressureDataCount());
            if (receivedTrajectory.getGnssDataCount() > 0) {
                Traj.GNSS_Sample firstGnss = receivedTrajectory.getGnssData(0);
                initialPosition = new LatLng(firstGnss.getLatitude(), firstGnss.getLongitude());
            } else {
                initialPosition = new LatLng(55.9229346, -3.17451653); // if GNSS data not found, set location to nucleus building
            }

            if (receivedTrajectory.getPdrDataCount() > 1) {
                long startTime = receivedTrajectory.getPdrData(0).getRelativeTimestamp();
                long endTime = receivedTrajectory.getPdrData(receivedTrajectory.getPdrDataCount() - 1).getRelativeTimestamp();
                totalDuration = (int) ((endTime - startTime) / 1000);
            }
        }

        SupportMapFragment supportMapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.fragmentContainerView);

        supportMapFragment.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(GoogleMap map) {
                gMap = map;
                gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                gMap.getUiSettings().setCompassEnabled(true);
                gMap.getUiSettings().setTiltGesturesEnabled(true);
                gMap.getUiSettings().setRotateGesturesEnabled(true);
                gMap.getUiSettings().setScrollGesturesEnabled(true);

                indoorMapManager = new IndoorMapManager(gMap);

                List<float[]> pdrDataList = new ArrayList<>();
                for (Traj.Pdr_Sample pdr : receivedTrajectory.getPdrDataList()) {
                    pdrDataList.add(new float[]{pdr.getX(), pdr.getY()});
                    Log.d("ReplayFragment", "PDR data XY: " + pdr.getX() + " " + pdr.getY());
                }
                List<Traj.GNSS_Sample> gnssDataList = new ArrayList<>();
                for (Traj.GNSS_Sample gnss : receivedTrajectory.getGnssDataList()){
                    gnssDataList.add(gnss);
                    Log.d("ReplayFragment", "Gnss Data: Latitude: " + gnss.getLatitude() + " Longitude: " + gnss.getLongitude());
                }

                Log.d("ReplayFragment", "PDR data: " + pdrDataList);
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

    private void loadTrajectory(List<float[]> recordedPDRData, List<Traj.GNSS_Sample> recordedGnssData, LatLng initialPosition, GoogleMap gMap) {
        if (gMap == null || recordedPDRData.isEmpty()) return;

        gMap.clear();
        Bitmap largeMarker = UtilFunctions.getBitmapFromVector(getContext(), R.drawable.ic_baseline_navigation_24, 2f);
        Bitmap gnssMarker = UtilFunctions.getBitmapFromVector(getContext(),R.drawable.ic_launcher_icon, 2f);

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


        trajectoryPoints.clear();
        revealedTrajectoryPoints.clear();
        trajectoryPoints.add(initialPosition);
        revealedTrajectoryPoints.add(initialPosition);
        gnssTrajectoryPoints.clear();
        gnssRevealedTrajectoryPoints.clear();
        gnssTrajectoryPoints.add(initialPosition);
        gnssRevealedTrajectoryPoints.add(initialPosition);

        LatLng lastPosition = initialPosition;
        float previousPosX = recordedPDRData.get(0)[0];
        float previousPosY = recordedPDRData.get(0)[1];

        pressureData = receivedTrajectory.getPressureDataList();
        pdrProcessing = new PdrProcessing(getContext());
        elevationVal = pdrProcessing.updateElevation(SensorManager.getAltitude(
                SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureData.get(0).getPressure()));

        indoorMapManager.setCurrentLocation(initialPosition);
        indoorMapManager.setIndicationOfIndoorMap();
        indoorMapManager.setCurrentFloor((int)(elevationVal/indoorMapManager.getFloorHeight())
                ,true);

        float pdrToPressureListRatio = (float)recordedPDRData.size()/pressureData.size(); // pressure and pdr lists not always same length
        int j = 0;

        for (int i = 1; i < recordedPDRData.size(); i++) {
            float[] pdrData = recordedPDRData.get(i);
            float[] pdrMoved = {pdrData[0] - previousPosX, pdrData[1] - previousPosY};
            LatLng newPosition = UtilFunctions.calculateNewPos(lastPosition, pdrMoved);
            trajectoryPoints.add(newPosition);
            if (i > recordedGnssData.size()-1){
                gnssTrajectoryPoints.add(new LatLng(recordedGnssData.get(recordedGnssData.size()-1).getLatitude(),recordedGnssData.get(recordedGnssData.size()-1).getLongitude()));
            } else{
                gnssTrajectoryPoints.add(new LatLng(recordedGnssData.get(i).getLatitude(),recordedGnssData.get(i).getLongitude()));
            }

            lastPosition = newPosition;

            previousPosX = pdrData[0];
            previousPosY = pdrData[1];

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

        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialPosition, 19f));
        seekBar.setMax(trajectoryPoints.size() - 1);

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

    private void updateMarkerPosition(int index) {
        if (index < 0 || index >= trajectoryPoints.size()) return;

        LatLng newPosition = trajectoryPoints.get(index);
        LatLng gnssNewPosition = gnssTrajectoryPoints.get(index);
        movingMarker.setPosition(newPosition);
        movingGnssMarker.setPosition(gnssNewPosition);
        gMap.animateCamera(CameraUpdateFactory.newLatLng(newPosition));

        if (index < trajectoryPoints.size() - 1) {
            LatLng nextPosition = trajectoryPoints.get(index + 1);
            movingMarker.setRotation(calculateBearing(newPosition, nextPosition));
        }

        revealedTrajectoryPoints.clear();
        gnssRevealedTrajectoryPoints.clear();
        for (int i = 0; i <= index-1; i++) {
            revealedTrajectoryPoints.add(trajectoryPoints.get(i));
            gnssRevealedTrajectoryPoints.add(gnssTrajectoryPoints.get(i));
        }
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
        indoorMapManager.setCurrentLocation(newPosition);
    }

    private float calculateBearing(LatLng start, LatLng end) {
        return (float) Math.toDegrees(Math.atan2(
                end.longitude - start.longitude, end.latitude - start.latitude));
    }


    private String formatTime(int seconds) {
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }



    private void setPlaybackSpeed(int speed) {
        playbackSpeed = speed;
        if (isPlaying) {
            stopPlaying();
            startPlaying();
        }
    }

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

    private void stopPlaying() {
        isPlaying = false;
        handler.removeCallbacksAndMessages(null);
    }
}