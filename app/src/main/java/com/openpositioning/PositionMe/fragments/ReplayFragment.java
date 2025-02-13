package com.openpositioning.PositionMe.fragments;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.PdrProcessing;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Gson;
import com.google.protobuf.ByteString;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.sensors.SensorTypes;

public class ReplayFragment extends Fragment implements OnMapReadyCallback {

    private boolean isPlaying = false;
    private int progress = 0;
    private static final int SEEK_TIME = 10000; // Fast forward/rewind step (milliseconds)
    private Handler refreshDataHandler;
    private Traj.Trajectory receTraj;
    private int pdrNum;
    private int gnssNum;
    private int PressureNum;
    private GoogleMap gMap;
    private Polyline polyline;
    public IndoorMapManager indoorMapManager;
    private Marker positionMarker;
    private Marker gnssMarker;
    private SeekBar seekBar;
    // private List<LatLng> pdrCoordinates = new ArrayList<>();
    // private List<LatLng> gnssCoordinates = new ArrayList<>();
    private int MaxProgress;
    private TextView tvProgressTime; // time display

    private float pdrX, pdrY;   // current progress PDR data
    private float orientation;
    private float previousPdrX = 0f;
    private float previousPdrY = 0f;
    private LatLng currentLocation; // current progress location
    private LatLng nextLocation;    // next progress location
    private LatLng gnssLocation;
    private float gnssLati, gnssLong; // current progress GNSS data
    private float elevation;    // current progress elevation
    private int pdrIndex = 0;       // current progress PDR index
    private int gnssIndex = 0;      // current progress GNSS index
    private int pressureIndex = 0;      // current progress pressure index
    private boolean GnssOn = false;
    private PdrProcessing pdrProcessing;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getActivity();

        refreshDataHandler = new Handler();
        pdrProcessing = new PdrProcessing(context);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_replay, container, false);
        requireActivity().setTitle("Replay");

        // Maps initialization
        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager()
                .findFragmentById(R.id.replayMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        // UI elements initialization
        ImageButton playPauseButton = view.findViewById(R.id.btn_play_pause);
        ImageButton rewindButton = view.findViewById(R.id.btn_rewind);
        ImageButton forwardButton = view.findViewById(R.id.btn_forward);
        Button restartButton = view.findViewById(R.id.btn_restart);
        Button goToEndButton = view.findViewById(R.id.btn_go_to_end);
        Button exitButton = view.findViewById(R.id.btn_exit);
        seekBar = view.findViewById(R.id.seek_bar);
        Switch gnssSwitch = view.findViewById(R.id.gnssSwitch);
        tvProgressTime = view.findViewById(R.id.tv_progress_time); // bound to time display

        // play/pause button
        playPauseButton.setOnClickListener(v -> {
            isPlaying = !isPlaying;
            if (isPlaying) {
                playPauseButton.setImageResource(R.drawable.ic_baseline_pause_circle_filled_24);
                startPlayback();
            } else {
                playPauseButton.setImageResource(R.drawable.ic_baseline_play_circle_filled_24_b);
                pausePlayback();
            }
        });

        // back 10 seconds
        rewindButton.setOnClickListener(v -> {
            pausePlayback();
            progress = Math.max(progress - SEEK_TIME, 0);
            seekBar.setProgress(progress);
            redrawPolyline(progress);
            startPlayback();
            // Toast.makeText(getContext(), "Rewind 10 seconds", Toast.LENGTH_SHORT).show();
        });

        // forward 10 seconds
        forwardButton.setOnClickListener(v -> {
            pausePlayback();
            progress = Math.min(progress + SEEK_TIME, seekBar.getMax());
            seekBar.setProgress(progress);
            redrawPolyline(progress);
            startPlayback();
            // Toast.makeText(getContext(), "Forward 10 seconds", Toast.LENGTH_SHORT).show();
        });

        // restart button
        restartButton.setOnClickListener(v -> {
            pausePlayback();
            progress = 0;
            seekBar.setProgress(progress);
            redrawPolyline(progress);
            startPlayback();
            // Toast.makeText(getContext(), "Restart button clicked", Toast.LENGTH_SHORT).show();
        });

        // go to end button
        goToEndButton.setOnClickListener(v -> {
            progress = seekBar.getMax();
            seekBar.setProgress(progress);
            redrawPolyline(progress);
            pausePlayback();
            // Toast.makeText(getContext(), "Go to End button clicked", Toast.LENGTH_SHORT).show();
        });

        // exit button
        exitButton.setOnClickListener(v -> {
            requireActivity().onBackPressed();
            // Toast.makeText(getContext(), "Exit button clicked", Toast.LENGTH_SHORT).show();
        });

        // GNSS switch
        gnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                GnssOn = true;
                gnssLocation = new LatLng(gnssLati, gnssLong);
                // Set GNSS marker
                gnssMarker=gMap.addMarker(
                        new MarkerOptions().title("GNSS position")
                                .position(gnssLocation)
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                Toast.makeText(getContext(), "GNSS Enabled", Toast.LENGTH_SHORT).show();
            } else {
                GnssOn = false;
                gnssMarker.remove();
                Toast.makeText(getContext(), "GNSS Disabled", Toast.LENGTH_SHORT).show();
            }
        });

        // SeekBar Listener
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    pausePlayback();
                    ReplayFragment.this.progress = progress;
                    seekBar.setProgress(progress);
                    redrawPolyline(progress);
                    updateTimeDisplay(progress);
                    startPlayback();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Toast.makeText(getContext(), "SeekBar dragged", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Toast.makeText(getContext(), "SeekBar released", Toast.LENGTH_SHORT).show();
            }
        });
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        readTrajectoryData();
        if (pdrNum == 0) {
            new AlertDialog.Builder(getContext())
                    .setTitle("PDR data invalid")
                    .setMessage("No PDR data to replay")
                    .setNegativeButton(R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            requireActivity().onBackPressed();
                        }
                    })
                    .setIcon(R.drawable.ic_baseline_download_24)
                    .show();
        }
    }

    // Map initialization
    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        Log.d("ReplayFragment", "onMapReady");
        gMap = googleMap;
        indoorMapManager = new IndoorMapManager(googleMap);

        gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        gMap.getUiSettings().setCompassEnabled(true);
        gMap.getUiSettings().setTiltGesturesEnabled(true);
        gMap.getUiSettings().setRotateGesturesEnabled(true);
        gMap.getUiSettings().setScrollGesturesEnabled(true);
        gMap.getUiSettings().setZoomControlsEnabled(false);

        if ((pdrX != 0) || (pdrY != 0)) {
            LatLng start = new LatLng(gnssLati, gnssLong);
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 19f));
            positionMarker=gMap.addMarker(new MarkerOptions().position(start).title("Current Position")
                    .flat(true)
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(getContext(),R.drawable.ic_baseline_navigation_24))));
            PolylineOptions options = new PolylineOptions().color(Color.RED).width(8f).add(start).zIndex(1);
            polyline = gMap.addPolyline(options);
            indoorMapManager.setCurrentLocation(start);
            //Showing an indication of available indoor maps using PolyLines
            indoorMapManager.setIndicationOfIndoorMap();
        }
        else {
            Log.e("ReplayFragment", "No PDR data to replay");
            Toast.makeText(getContext(), "No PDR data to replay", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Stop playback when fragment is destroyed
        pausePlayback();
    }

    private void readTrajectoryData() {
        try {
            // Get file path and read the file
            File file = new File(requireContext().getFilesDir(), "received_trajectory.traj");
            FileInputStream fileStream = new FileInputStream(file);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

            // Read the file into a byte array
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = fileStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }

            // Convert the byte array to a protobuf object
            byte[] byteArray = byteArrayOutputStream.toByteArray();
            System.out.println("ReplayFragment File load, size is: " + byteArray.length + " bytes");
            receTraj = Traj.Trajectory.parseFrom(byteArray);

            // Extract trajectory details
            pdrNum = receTraj.getPdrDataCount();
            gnssNum = receTraj.getGnssDataCount();
            PressureNum = receTraj.getPressureDataCount();

            // Log debug information
            Log.d("ReplayFragment", "Trajectory parsed successfully. GNSS points: " + gnssNum);
            Log.d("ReplayFragment", "Trajectory parsed successfully. PDR points: " + pdrNum);
            Log.d("ReplayFragment", "Trajectory parsed successfully. Pressure points: " + PressureNum);
            Log.d("ReplayFragment", "Start Timestamp: " + receTraj.getStartTimestamp());

            // if no PDR record, return
            if (pdrNum == 0) {
                Log.w("ReplayFragment", "No PDR data to replay");
                return;
            }

            // Get max progress
            if (receTraj.getPdrData(pdrNum-1).getRelativeTimestamp() > Integer.MAX_VALUE) {
                MaxProgress = Integer.MAX_VALUE;
                Log.w("ReplayFragment", "Trajectory too long, playback limited to 2^31-1 milliseconds");
            }
            else {
                MaxProgress = (int)receTraj.getPdrData(pdrNum-1).getRelativeTimestamp();
                Log.d("ReplayFragment", "MaxProgress = "+MaxProgress);
            }
            seekBar.setMax(MaxProgress);

            // initial current progress data
            pdrX = receTraj.getPdrData(0).getX();
            pdrY = receTraj.getPdrData(0).getY();
            if (gnssNum > 0) {
                gnssLati = receTraj.getGnssData(0).getLatitude();
                gnssLong = receTraj.getGnssData(0).getLongitude();
            }
            else {
                gnssLati = 0;
                gnssLong = 0;
                Log.e("ReplayFragment", "No GNSS data!");
            }
            elevation = 0;

        } catch (IOException | JsonSyntaxException e) {
            Log.e("ReplayFragment", "Failed to read trajectory", e);
            Toast.makeText(getContext(), "Error: Invalid trajectory file", Toast.LENGTH_LONG).show();
        }
    }

    // start playback
    private void startPlayback() {
        refreshDataHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isPlaying && progress < MaxProgress) {
                    progress = (int)Math.min(progress+200, MaxProgress);
                    Log.d("ReplayFragment", "current Progress = "+progress);
                    seekBar.setProgress(progress);
                    updateUIandPosition(progress);
                    updateTimeDisplay(progress);
                    refreshDataHandler.postDelayed(this, 200);
                }
            }
        }, 100);
        // Toast.makeText(getContext(), "Playback started", Toast.LENGTH_SHORT).show();
    }

    // pause playback
    private void pausePlayback() {
        refreshDataHandler.removeCallbacksAndMessages(null);
        // Toast.makeText(getContext(), "Playback paused", Toast.LENGTH_SHORT).show();
    }

    static List<LatLng> points = new ArrayList<>();

    // update UI and position
    private void updateUIandPosition(int progress) {
        // Get new position
        pdrIndex = findClosestPdrIndex(progress, pdrIndex);
        pdrX = receTraj.getPdrData(pdrIndex).getX();
        pdrY = receTraj.getPdrData(pdrIndex).getY();

        // Net pdr movement
        float[] pdrMoved={pdrX-previousPdrX,pdrY-previousPdrY};
        // if PDR has changed plot new line to indicate user movement
        if (pdrMoved[0]!=0 ||pdrMoved[1]!=0) {
            plotLines(pdrMoved);
        }
        // If not initialized, initialize
        if (indoorMapManager == null) {
            indoorMapManager =new IndoorMapManager(gMap);
        }
        //Show GNSS marker and error if user enables it
        if (GnssOn){
            gnssIndex = findClosestGnssIndex(progress, gnssIndex);
            gnssLati = receTraj.getGnssData(gnssIndex).getLatitude();
            gnssLong = receTraj.getGnssData(gnssIndex).getLongitude();
            gnssLocation = new LatLng(gnssLati, gnssLong);
            gnssMarker.setPosition(gnssLocation);
        }

        //  Updates current location of user to show the indoor floor map (if applicable)
        indoorMapManager.setCurrentLocation(currentLocation);

        // calculate elevation and display current floor
        if(indoorMapManager.getIsIndoorMapSet()){
            if (PressureNum>0) {
                findClosestPressureIndex(progress, pressureIndex);
                Log.d("ReplayFragment", "Pressure = " + receTraj.
                        getPressureData(pressureIndex).getPressure());
                Log.d("ReplayFragment", "Light = " + receTraj.
                        getLightData(pressureIndex).getLight());
                elevation = pdrProcessing.updateElevation(receTraj.getPressureData(pressureIndex)
                        .getPressure());
            }
            else {
                elevation = 0;
            }
            Log.d("ReplayFragment", "Elevation = "+elevation);
            indoorMapManager.setCurrentFloor((int)(elevation/indoorMapManager.getFloorHeight())
                         ,true);
        }

        // Store previous PDR values for next call
        previousPdrX = pdrX;
        previousPdrY = pdrY;

        if (positionMarker != null) {
            positionMarker.setPosition(currentLocation);
            gMap.moveCamera(CameraUpdateFactory.newLatLng(currentLocation));
        }
    }

    // time display formatting
    private void updateTimeDisplay(int progress) {
        int seconds = (progress / 1000) % 60;
        int minutes = (progress / 1000) / 60;
        String time = String.format("%d:%02d", minutes, seconds);
        tvProgressTime.setText(time);
    }

    // find closest PDR index
    private int findClosestPdrIndex(int timestamp, int pdrIndex) {
        // make sure index is within bounds
        int index = Math.min(Math.max(pdrIndex, 0), pdrNum - 1);

        while ((index < pdrNum - 1) &&
                (receTraj.getPdrData(index + 1).getRelativeTimestamp() <= timestamp)) {
            index++;
        }

        // Log.d("ReplayFragment", "Closest PDR index: " + index);
        return index;
    }

    private int findClosestGnssIndex(int timestamp, int gnssIndex) {
        // make sure index is within bounds
        int index = Math.min(Math.max(gnssIndex, 0), gnssNum - 1);

        while ((index < gnssNum - 1) &&
                (receTraj.getGnssData(index + 1).getRelativeTimestamp() <= timestamp)) {
            index++;
        }

        // Log.d("ReplayFragment", "Closest Gnss index: " + index);
        return index;
    }

    private int findClosestPressureIndex(int timestamp, int pressureIndex) {
        if (PressureNum == 0) {
            return 0; // No pressure data
        }

        // make sure index is within bounds
        int index = Math.min(Math.max(pressureIndex, 0), PressureNum - 1);

        while ((index < PressureNum - 1) &&
                (receTraj.getPressureData(index + 1).getRelativeTimestamp() <= timestamp)) {
            index++;
        }

        Log.d("ReplayFragment", "Closest Gnss index: " + index);
        return index;
    }

    /**
     * Plots the users location based on movement in Real-time
     * @param pdrMoved Contains the change in PDR in X and Y directions
     */
    private void plotLines(float[] pdrMoved){
        if (currentLocation!=null){
            // Calculate new position based on net PDR movement
            nextLocation=UtilFunctions.calculateNewPos(currentLocation,pdrMoved);

            // Adds new location to polyline to plot the PDR path of user
            List<LatLng> pointsMoved = polyline.getPoints();
            pointsMoved.add(nextLocation);
            polyline.setPoints(pointsMoved);

            // calculate orientation
            orientation = (float) Math.toDegrees(Math.atan2(pdrMoved[1], pdrMoved[0]));

            // Change current location to new location and zoom there
            positionMarker.setPosition(nextLocation);
            // positionMarker.setRotation(orientation);
            positionMarker.setRotation((float) Math.toDegrees(orientation));
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(nextLocation, (float) 19f));

            currentLocation=nextLocation;
        }
        else{
            //Initialise the starting location
            currentLocation=new LatLng(gnssLati,gnssLong);
            nextLocation=currentLocation;
        }
    }

    private void redrawPolyline(int progress) {
        // reset index
        pdrIndex = 0;
        gnssIndex = 0;

        // get GNSS start point
        LatLng start = new LatLng(receTraj.getGnssData(0).getLatitude(),
                receTraj.getGnssData(0).getLongitude());

        // clear previous polyline
        if (polyline != null) {
            polyline.remove();
        }

        // create new PolylineOptions
        PolylineOptions options = new PolylineOptions().color(Color.RED).width(8f).zIndex(1).add(start);
        polyline = gMap.addPolyline(options);

        for (int i = 0; i <= progress; i += 200) {
            updateUIandPosition(i);
        }
    }
}