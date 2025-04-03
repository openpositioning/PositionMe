package com.openpositioning.PositionMe.fragments;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.openpositioning.PositionMe.FusionFilter.ParticleFilter;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.PdrProcessing;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;

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
import android.widget.Spinner;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.util.Pair;

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
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Gson;
import com.google.protobuf.ByteString;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import org.json.JSONException;
import org.json.JSONObject;

public class ReplayFragment extends Fragment implements OnMapReadyCallback {

    private boolean isPlaying = false;
    private int progress = 0;
    private static final int SEEK_TIME = 10000; // Fast forward/rewind step (milliseconds)
    private Handler refreshDataHandler;
    private Traj.Trajectory receTraj;
    private int pdrNum;
    private int gnssNum;
    private int PressureNum;
    private int wifiNum;
    private GoogleMap gMap;
    private Polyline polyline;
    public IndoorMapManager indoorMapManager;
    private Marker positionMarker;
    private Marker gnssMarker;
    private Marker wifiMarker;
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
    private int pressureIndex = 0;  // current progress pressure index
    private int wifiIndex = 0;      // current progress wifi index
    private boolean GnssOn = false;
    private boolean WifiOn = false;
    private PdrProcessing pdrProcessing;
    private WiFiPositioning wiFiPositioning;
    private LatLng wifiLocation;    // wifi positioning result
    // Algorithm switch dropdown
    private Spinner algorithmSwitchSpinner;

    // --- Particle Filter Integration Variables ---
    private ParticleFilter particleFilter;
    private Polyline pdrPolyline; // Renamed from polyline for clarity
    private Polyline fusedPolyline; // Polyline for the fused path
    private List<Pair<Long, LatLng>> fusedPositionsList; // Stores fused positions with timestamps
    private boolean isIndoor = false; // Flag indicating if trajectory is indoor (based on WiFi data)
    private boolean isParticleFilterActive = false; // Flag indicating if particle filter is selected
    private LatLng currentFusedPosition; // Holds the latest position from the particle filter
    private LatLng initialWifiLocation = null; // Stores the first WiFi location if available
    private boolean isMapReady = false; // Flag to check if onMapReady has been called
    // --- End Particle Filter Integration Variables ---

    // --- EKF Filter Integration Variables ---
    private boolean isEkfFilterActive = false; // Flag indicating if EKF filter is selected
    private List<Pair<Long, LatLng>> ekfPositionsList; // Stores all EKF positions with timestamps
    // --- End EKF Filter Integration Variables ---

    // --- New variables for trajectory optimization ---
    private List<Pair<Long, LatLng>> pdrPositionsList; // Stores all pre-calculated PDR positions with timestamps
    private List<Pair<Long, LatLng>> wifiPositionsList; // Stores all pre-calculated WiFi positions with timestamps
    private boolean isDataPrepared = false; // Flag indicating if data preparation is complete
    private AlertDialog loadingDialog; // Dialog shown while preparing data
    // --- End new variables ---

    // --- Smooth trajectory variables ---
    private List<Pair<Long, LatLng>> smoothedParticleFilterPositionsList; // Stores smoothed particle filter positions
    private List<Pair<Long, LatLng>> smoothedEkfPositionsList; // Stores smoothed EKF positions
    private boolean isSmoothingActive = false; // Flag indicating if trajectory smoothing is enabled
    private static final int WMA_WINDOW_SIZE = 5; // Window size for WMA smoothing
    // --- End smooth trajectory variables ---

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getActivity();

        refreshDataHandler = new Handler();
        pdrProcessing = new PdrProcessing(context);
        wiFiPositioning = new WiFiPositioning(context);
        particleFilter = null; // Initialize particle filter as null
        
        // Initialize trajectory lists
        pdrPositionsList = new ArrayList<>();
        wifiPositionsList = new ArrayList<>();
        fusedPositionsList = new ArrayList<>();
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
        Switch wifiSwitch = view.findViewById(R.id.wifiSwitch);
        Switch smoothSwitch = view.findViewById(R.id.smoothSwitch); // New smooth switch
        tvProgressTime = view.findViewById(R.id.tv_progress_time); // bound to time display
        algorithmSwitchSpinner = view.findViewById(R.id.algorithmSwitchSpinner);

        // Create algorithm dropdown options
        setupAlgorithmDropdown();

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
            if (isPlaying) {
                startPlayback();
            }
            // Toast.makeText(getContext(), "Rewind 10 seconds", Toast.LENGTH_SHORT).show();
        });

        // forward 10 seconds
        forwardButton.setOnClickListener(v -> {
            pausePlayback();
            progress = Math.min(progress + SEEK_TIME, seekBar.getMax());
            seekBar.setProgress(progress);
            redrawPolyline(progress);
            if (isPlaying) {
                startPlayback();
            }
            // Toast.makeText(getContext(), "Forward 10 seconds", Toast.LENGTH_SHORT).show();
        });

        // restart button
        restartButton.setOnClickListener(v -> {
            pausePlayback();
            progress = 0;
            seekBar.setProgress(progress);
            redrawPolyline(progress);
            if (isPlaying) {
                startPlayback();
            }
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

        // WiFi switch
        wifiSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                WifiOn = true;
                if (wifiLocation != null) {
                    // Set WiFi marker
                    wifiMarker = gMap.addMarker(
                            new MarkerOptions().title("WiFi position")
                                    .position(wifiLocation)
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
                    Toast.makeText(getContext(), "WiFi Positioning Enabled", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "Waiting for WiFi position...", Toast.LENGTH_SHORT).show();
                    // Process first WiFi data
                    if (wifiNum > 0) {
                        processWifiData(0);
                    }
                }
            } else {
                WifiOn = false;
                if (wifiMarker != null) {
                    wifiMarker.remove();
                }
                Toast.makeText(getContext(), "WiFi Positioning Disabled", Toast.LENGTH_SHORT).show();
            }
        });
        
        // Smooth switch
        smoothSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isSmoothingActive = isChecked;
            if (isChecked) {
                Toast.makeText(getContext(), "Trajectory Smoothing Enabled", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "Trajectory Smoothing Disabled", Toast.LENGTH_SHORT).show();
            }
            // Redraw trajectory with/without smoothing
            if (isDataPrepared) {
                redrawPolyline(progress);
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
                    if (isPlaying) {
                        startPlayback();
                    }
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
        } else {
            // Show loading dialog while preparing trajectory data
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle("Preparing Data");
            builder.setMessage("Computing trajectories, please wait...");
            builder.setCancelable(false);
            loadingDialog = builder.create();
            loadingDialog.show();
            
            // Start data preparation in a background thread
            new Thread(() -> {
                prepareTrajectoryData();
                
                // Update UI on main thread when complete
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (loadingDialog != null && loadingDialog.isShowing()) {
                            loadingDialog.dismiss();
                        }
                        isDataPrepared = true;
                        // Initialize map with pre-calculated data if map is already ready
                        if (isMapReady && gMap != null) {
                            initializeMapWithTrajectory();
                        }
                    });
                }
            }).start();
        }
    }

    // Map initialization
    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        Log.d("ReplayFragment", "onMapReady");
        gMap = googleMap;
        isMapReady = true; // Map is ready now
        indoorMapManager = new IndoorMapManager(googleMap);

        gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        gMap.getUiSettings().setCompassEnabled(true);
        gMap.getUiSettings().setTiltGesturesEnabled(true);
        gMap.getUiSettings().setRotateGesturesEnabled(true);
        gMap.getUiSettings().setScrollGesturesEnabled(true);
        gMap.getUiSettings().setZoomControlsEnabled(false);

        // If data is already prepared, initialize map with trajectories
        // Otherwise, map will be initialized once data is ready
        if (isDataPrepared) {
            initializeMapWithTrajectory();
        }
    }

    /**
     * Initializes map with pre-calculated trajectory data
     * Sets up polylines, markers, and camera position
     */
    private void initializeMapWithTrajectory() {
        Log.d("ReplayFragment", "Initializing map with trajectory data");
        
        if (pdrNum == 0 || pdrPositionsList.isEmpty()) {
            Log.e("ReplayFragment", "Cannot initialize map: No PDR data available");
            return;
        }
        
        // Get initial position - use the first point of the PDR positions list
        // which was already initialized with the correct starting point (WiFi if indoor, GNSS if outdoor)
        LatLng startLocation = pdrPositionsList.get(0).second;
        Log.d("ReplayFragment", "Map initialization using start position: " + startLocation);
        
        // Set camera position
        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLocation, 19f));
        
        // Initialize position marker
        positionMarker = gMap.addMarker(new MarkerOptions()
                .position(startLocation)
                .title("Current Position")
                .flat(true)
                .icon(BitmapDescriptorFactory.fromBitmap(
                        UtilFunctions.getBitmapFromVector(getContext(), R.drawable.ic_baseline_navigation_24))));
        
        // Initialize PDR polyline (Red)
        PolylineOptions pdrOptions = new PolylineOptions()
                .color(Color.RED)
                .width(8f)
                .add(startLocation)
                .zIndex(1);
        pdrPolyline = gMap.addPolyline(pdrOptions);
        
        // Initialize Fused polyline (Blue)
        PolylineOptions fusedOptions = new PolylineOptions()
                .color(Color.BLUE)
                .width(8f)
                .add(startLocation)
                .zIndex(2)
                .visible(isParticleFilterActive); // Visible if particle filter is active
        fusedPolyline = gMap.addPolyline(fusedOptions);
        
        // Setup indoor map if needed
        indoorMapManager.setCurrentLocation(startLocation);
        indoorMapManager.setIndicationOfIndoorMap();
        
        // Set initial location
        currentLocation = startLocation;
        currentFusedPosition = startLocation;
        
        // Display position at progress zero
        redrawPolyline(0);
        
        Log.d("ReplayFragment", "Map initialized with trajectory data");
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
            wifiNum = receTraj.getWifiDataCount();

            // Log debug information
            Log.d("ReplayFragment", "Trajectory parsed successfully. GNSS points: " + gnssNum);
            Log.d("ReplayFragment", "Trajectory parsed successfully. PDR points: " + pdrNum);
            Log.d("ReplayFragment", "Trajectory parsed successfully. Pressure points: " + PressureNum);
            Log.d("ReplayFragment", "Trajectory parsed successfully. WiFi points: " + wifiNum);
            Log.d("ReplayFragment", "Start Timestamp: " + receTraj.getStartTimestamp());

            // if no PDR record, return
            if (pdrNum == 0) {
                Log.w("ReplayFragment", "No PDR data to replay");
                return;
            }

            // Determine if indoor based on WiFi data presence
            isIndoor = (wifiNum > 0);
            Log.d("ReplayFragment", isIndoor ? "Indoor trajectory detected." : "Outdoor trajectory detected.");

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
            
            // Process first WiFi data to get initial position if indoor
            if (isIndoor) {
                processWifiData(0); // Fetch the first WiFi location early
            } else {
                // For outdoor, initial location is based on GNSS
                if (gnssNum > 0) {
                    currentFusedPosition = new LatLng(gnssLati, gnssLong);
                } else {
                    // Handle case with no GNSS for outdoor (use 0,0 or show error?)
                    currentFusedPosition = new LatLng(0, 0);
                    Log.e("ReplayFragment", "Outdoor mode but no GNSS data for initial position!");
                }
            }

            // Initialize fused positions list
            fusedPositionsList = new ArrayList<>();

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
                    progress = Math.min(progress + 200, MaxProgress);
                    Log.d("ReplayFragment", "current Progress = " + progress);
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
        // Make sure map, data, and UI elements are ready
        if (!isDataPrepared || gMap == null) {
            Log.w("ReplayFragment", "updateUIandPosition skipped: Data or map not ready");
            return;
        }
        
        // Simply redraw the polyline for the current progress
        // This leverages our pre-calculated trajectory data
        redrawPolyline(progress);
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
     * Process WiFi data from trajectory to get positioning
     * @param index The WiFi data index in trajectory
     */
    private void processWifiData(int index) {
        if (wifiNum <= 0 || index >= wifiNum) {
            return;
        }
        
        try {
            // Get WiFi data at specific index
            Traj.WiFi_Sample wifiSample = receTraj.getWifiData(index);
            
            // Create JSON WiFi fingerprint
            JSONObject wifiAccessPoints = new JSONObject();
            for (int i = 0; i < wifiSample.getMacScansCount(); i++) {
                Traj.Mac_Scan macScan = wifiSample.getMacScans(i);
                wifiAccessPoints.put(String.valueOf(macScan.getMac()), macScan.getRssi());
            }
            
            // Create WiFi fingerprint JSON request
            JSONObject wifiFingerprint = new JSONObject();
            wifiFingerprint.put("wf", wifiAccessPoints);
            
            // Send request to WiFi positioning server
            wiFiPositioning.request(wifiFingerprint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng location, int floor) {
                    wifiLocation = location;
                    Log.d("ReplayFragment", "WiFi position updated: " + location.latitude + ", " + location.longitude + ", floor: " + floor);

                    // If this is the first WiFi data point for an indoor track, handle initial positioning
                    if (index == 0 && isIndoor) {
                        initialWifiLocation = location;
                        Log.d("ReplayFragment", "Initial WiFi location received: " + location);
                        // If map is already ready, update the initial setup
                        if (isMapReady && gMap != null && positionMarker != null && pdrPolyline != null && fusedPolyline != null) {
                            Log.d("ReplayFragment", "Map is ready, updating initial position to WiFi location.");
                            currentLocation = initialWifiLocation;
                            currentFusedPosition = initialWifiLocation;
                            positionMarker.setPosition(initialWifiLocation);
                            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialWifiLocation, 19f));

                            // Reset polylines to the new start
                            List<LatLng> startPointList = new ArrayList<>();
                            startPointList.add(initialWifiLocation);
                            pdrPolyline.setPoints(startPointList);
                            fusedPolyline.setPoints(startPointList);

                            // Re-initialize particle filter with the correct start location
                            particleFilter = new ParticleFilter(initialWifiLocation);
                            Log.d("ReplayFragment", "ParticleFilter re-initialized with WiFi start location.");
                        }
                    }

                    // Update WiFi marker immediately on UI thread if WiFi is enabled
                    if (WifiOn && getActivity() != null) {
                        requireActivity().runOnUiThread(() -> {
                            if (wifiMarker != null) {
                                wifiMarker.setPosition(wifiLocation);
                            } else {
                                // Create marker if enabled but doesn't exist
                                wifiMarker = gMap.addMarker(
                                        new MarkerOptions().title("WiFi position")
                                                .position(wifiLocation)
                                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
                            }
                        });
                    }
                }

                @Override
                public void onError(String message) {
                    Log.e("ReplayFragment", "WiFi positioning error: " + message);
                }
            });
            
        } catch (JSONException e) {
            Log.e("ReplayFragment", "Error creating WiFi fingerprint JSON: " + e.getMessage());
        }
    }
    
    /**
     * Find closest WiFi data index
     * @param timestamp Current timestamp
     * @param wifiIndex Current WiFi index
     * @return Closest WiFi data index
     */
    private int findClosestWifiIndex(int timestamp, int wifiIndex) {
        if (wifiNum == 0) {
            return 0; // No WiFi data
        }

        // make sure index is within bounds
        int index = Math.min(Math.max(wifiIndex, 0), wifiNum - 1);

        while ((index < wifiNum - 1) &&
                (receTraj.getWifiData(index + 1).getRelativeTimestamp() <= timestamp)) {
            index++;
        }

        return index;
    }

    /**
     * Prepares all trajectory data in advance to optimize performance
     * - Calculates all PDR positions
     * - Fetches all WiFi positions
     * - Computes all fused positions using the particle filter
     * - Computes all positions using the EKF filter
     */
    private void prepareTrajectoryData() {
        if (pdrNum == 0) {
            Log.e("ReplayFragment", "No PDR data to prepare");
            return;
        }
        
        Log.d("ReplayFragment", "Started preparing trajectory data...");
        
        // Determine if we need to use WiFi data for indoor positioning
        boolean needWifiData = isIndoor && wifiNum > 0;
        
        // If indoor, get WiFi positions first to determine the starting point
        if (needWifiData) {
            fetchAllWifiPositions();
            
            // Check if WiFi data was successfully fetched
            if (!wifiPositionsList.isEmpty()) {
                // Use first WiFi position as starting point
                initialWifiLocation = wifiPositionsList.get(0).second;
                Log.d("ReplayFragment", "Using first WiFi position as starting point: " + initialWifiLocation);
            } else {
                // Fallback to GNSS if WiFi data failed
                if (gnssNum > 0) {
                    initialWifiLocation = new LatLng(receTraj.getGnssData(0).getLatitude(), receTraj.getGnssData(0).getLongitude());
                    Log.d("ReplayFragment", "No WiFi positions available, falling back to GNSS: " + initialWifiLocation);
                } else {
                    initialWifiLocation = new LatLng(0, 0);
                    Log.w("ReplayFragment", "No valid start position available, using (0,0)");
                }
            }
        }
        
        // Determine initial position
        LatLng startLocation;
        if (isIndoor && initialWifiLocation != null) {
            startLocation = initialWifiLocation;
            Log.d("ReplayFragment", "Using WiFi location as start location");
        } else if (gnssNum > 0) {
            startLocation = new LatLng(receTraj.getGnssData(0).getLatitude(), receTraj.getGnssData(0).getLongitude());
            Log.d("ReplayFragment", "Using GNSS location as start location");
        } else {
            startLocation = new LatLng(0, 0);
            Log.w("ReplayFragment", "No valid start location available");
        }
        
        // Initialize Particle Filter with determined start location
        particleFilter = new ParticleFilter(startLocation);
        
        // Initialize EKF positions list
        ekfPositionsList = new ArrayList<>();
        
        // Initialize smoothed position lists
        smoothedParticleFilterPositionsList = new ArrayList<>();
        smoothedEkfPositionsList = new ArrayList<>();
        
        // Pre-calculate all PDR positions
        calculateAllPdrPositions(startLocation);
        
        // If not already fetched, get WiFi positions 
        if (!needWifiData && isIndoor && wifiNum > 0) {
            fetchAllWifiPositions();
        }
        
        // Pre-calculate all fused positions (once WiFi and PDR data are ready)
        calculateAllFusedPositions(startLocation);
        
        // Pre-calculate all EKF positions
        calculateAllEkfPositions(startLocation);
        
        // Apply smoothing to the calculated trajectories
        calculateSmoothedTrajectories();
        
        Log.d("ReplayFragment", "Finished preparing trajectory data");
    }

    /**
     * Calculates all PDR positions and stores them with timestamps
     */
    private void calculateAllPdrPositions(LatLng startLocation) {
        Log.d("ReplayFragment", "Calculating all PDR positions...");
        
        // Add start position
        pdrPositionsList.add(new Pair<>(0L, startLocation));
        
        LatLng currentPdrLocation = startLocation;
        float previousPdrX = receTraj.getPdrData(0).getX();
        float previousPdrY = receTraj.getPdrData(0).getY();
        
        // Calculate all PDR positions
        for (int i = 1; i < pdrNum; i++) {
            float currentPdrX = receTraj.getPdrData(i).getX();
            float currentPdrY = receTraj.getPdrData(i).getY();
            
            // Calculate PDR displacement
            float[] pdrMoved = {currentPdrX - previousPdrX, currentPdrY - previousPdrY};
            
            // Calculate new PDR position
            LatLng newPdrLocation = UtilFunctions.calculateNewPos(currentPdrLocation, pdrMoved);
            
            // Store new position with timestamp
            long timestamp = receTraj.getPdrData(i).getRelativeTimestamp();
            pdrPositionsList.add(new Pair<>(timestamp, newPdrLocation));
            
            // Update for next iteration
            currentPdrLocation = newPdrLocation;
            previousPdrX = currentPdrX;
            previousPdrY = currentPdrY;
        }
        
        Log.d("ReplayFragment", "Calculated " + pdrPositionsList.size() + " PDR positions");
    }

    /**
     * Fetches all WiFi positions from server and stores them with timestamps
     */
    private void fetchAllWifiPositions() {
        Log.d("ReplayFragment", "Fetching all WiFi positions...");
        
        // Clear any existing WiFi positions before fetching new ones
        wifiPositionsList.clear();
        
        // We'll use a CountDownLatch to make sure all WiFi requests are complete
        final CountDownLatch latch = new CountDownLatch(wifiNum);
        
        for (int i = 0; i < wifiNum; i++) {
            final int index = i;
            final long timestamp = receTraj.getWifiData(i).getRelativeTimestamp();
            
            try {
                // Create JSON WiFi fingerprint
                Traj.WiFi_Sample wifiSample = receTraj.getWifiData(index);
                JSONObject wifiAccessPoints = new JSONObject();
                
                for (int j = 0; j < wifiSample.getMacScansCount(); j++) {
                    Traj.Mac_Scan macScan = wifiSample.getMacScans(j);
                    wifiAccessPoints.put(String.valueOf(macScan.getMac()), macScan.getRssi());
                }
                
                // Create WiFi fingerprint JSON request
                JSONObject wifiFingerprint = new JSONObject();
                wifiFingerprint.put("wf", wifiAccessPoints);
                
                // Send request to WiFi positioning server
                wiFiPositioning.request(wifiFingerprint, new WiFiPositioning.VolleyCallback() {
                    @Override
                    public void onSuccess(LatLng location, int floor) {
                        synchronized (wifiPositionsList) {
                            wifiPositionsList.add(new Pair<>(timestamp, location));
                            
                            // Store initial WiFi location if this is the first one
                            if (index == 0) {
                                initialWifiLocation = location;
                                Log.d("ReplayFragment", "Initial WiFi location set: " + location);
                            }
                        }
                        latch.countDown();
                    }
                    
                    @Override
                    public void onError(String message) {
                        Log.e("ReplayFragment", "WiFi positioning error: " + message);
                        latch.countDown();
                    }
                });
                
            } catch (JSONException e) {
                Log.e("ReplayFragment", "Error creating WiFi fingerprint JSON: " + e.getMessage());
                latch.countDown();
            }
        }
        
        try {
            // Wait for all WiFi requests to complete (with timeout)
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            if (completed) {
                Log.d("ReplayFragment", "Fetched " + wifiPositionsList.size() + " WiFi positions");
            } else {
                Log.w("ReplayFragment", "Timeout waiting for WiFi positions, got " + wifiPositionsList.size());
            }
            
            // Sort WiFi positions by timestamp
            Collections.sort(wifiPositionsList, (a, b) -> Long.compare(a.first, b.first));
            
        } catch (InterruptedException e) {
            Log.e("ReplayFragment", "Interrupted while waiting for WiFi positions", e);
        }
    }

    /**
     * Calculates all fused positions using the particle filter
     */
    private void calculateAllFusedPositions(LatLng startLocation) {
        Log.d("ReplayFragment", "Calculating all fused positions...");
        
        // Add start position
        fusedPositionsList.add(new Pair<>(0L, startLocation));
        
        // Reset particle filter
        particleFilter = new ParticleFilter(startLocation);
        
        // Use PDR data timestamps to calculate fusion positions, instead of fixed intervals
        // This ensures the fusion trajectory is aligned with the PDR trajectory in time
        if (pdrPositionsList.isEmpty()) {
            Log.e("ReplayFragment", "No PDR positions available for fusion calculation");
            return;
        }
        
        // Use PDR data timestamps as the basis for fusion calculations
        // Skip the first point (already added as start point)
        for (int i = 1; i < pdrPositionsList.size(); i++) {
            // Get current PDR timestamp and position
            long timestamp = pdrPositionsList.get(i).first;
            LatLng pdrPosition = pdrPositionsList.get(i).second;
            
            // Find the WiFi position at corresponding timestamp
            LatLng wifiPosition = isIndoor ? findClosestPositionByTime(wifiPositionsList, timestamp) : null;
            
            // Find the GNSS position at corresponding timestamp
            LatLng gnssPosition = null;
            if (!isIndoor && gnssNum > 0) {
                int gnssIdx = findClosestGnssIndex((int)timestamp, 0);
                if (gnssIdx < gnssNum) {
                    gnssPosition = new LatLng(
                            receTraj.getGnssData(gnssIdx).getLatitude(),
                            receTraj.getGnssData(gnssIdx).getLongitude()
                    );
                }
            }
            
            // Apply particle filter
            LatLng fusedPosition = particleFilter.particleFilter(wifiPosition, gnssPosition, pdrPosition);
            
            // Store fusion position with the same timestamp as PDR
            fusedPositionsList.add(new Pair<>(timestamp, fusedPosition));
            
            // Log every 100 points for debugging
            if (i % 100 == 0) {
                Log.d("ReplayFragment", "Calculated fusion position " + i + "/" + pdrPositionsList.size() + 
                        " at time " + timestamp + "ms");
            }
        }
        
        Log.d("ReplayFragment", "Calculated " + fusedPositionsList.size() + " fused positions");
        
        // Verify timestamp alignment
        verifyTimestampAlignment();
    }

    /**
     * Verifies that PDR and fusion position timestamps are properly aligned
     * Logs warnings if misalignments are detected
     */
    private void verifyTimestampAlignment() {
        if (pdrPositionsList.isEmpty() || fusedPositionsList.isEmpty()) {
            Log.w("ReplayFragment", "Cannot verify timestamp alignment: Empty position lists");
            return;
        }
        
        // Check that PDR and fusion lists have the same number of entries
        if (pdrPositionsList.size() != fusedPositionsList.size()) {
            Log.w("ReplayFragment", "Timestamp alignment issue: PDR list size (" + 
                    pdrPositionsList.size() + ") differs from fusion list size (" + 
                    fusedPositionsList.size() + ")");
        }
        
        // Check a sample of timestamps to verify alignment
        int sampleSize = Math.min(10, pdrPositionsList.size() - 1);
        for (int i = 1; i <= sampleSize; i++) {
            // Get index at regular intervals
            int index = i * (pdrPositionsList.size() - 1) / sampleSize;
            
            if (index < pdrPositionsList.size() && index < fusedPositionsList.size()) {
                long pdrTimestamp = pdrPositionsList.get(index).first;
                long fusedTimestamp = fusedPositionsList.get(index).first;
                
                if (pdrTimestamp != fusedTimestamp) {
                    Log.w("ReplayFragment", "Timestamp mismatch at index " + index + 
                            ": PDR=" + pdrTimestamp + "ms, Fused=" + fusedTimestamp + "ms");
                }
            }
        }
        
        // Check WiFi timestamp distribution (if available)
        if (!wifiPositionsList.isEmpty()) {
            long firstWifiTimestamp = wifiPositionsList.get(0).first;
            long lastWifiTimestamp = wifiPositionsList.get(wifiPositionsList.size() - 1).first;
            
            Log.d("ReplayFragment", "WiFi timestamps range: " + firstWifiTimestamp + 
                    "ms to " + lastWifiTimestamp + "ms (" + 
                    wifiPositionsList.size() + " points)");
        }
        
        // Log summary
        Log.d("ReplayFragment", "Timestamp alignment check complete. PDR positions: " + 
                pdrPositionsList.size() + ", Fusion positions: " + fusedPositionsList.size());
    }

    /**
     * Finds the closest position to the given timestamp from a list of position-timestamp pairs
     * Improved with additional logging and error handling
     */
    private LatLng findClosestPositionByTime(List<Pair<Long, LatLng>> positionsList, long timestamp) {
        if (positionsList.isEmpty()) {
            return null;
        }
        
        // If before first position, return first
        if (timestamp <= positionsList.get(0).first) {
            return positionsList.get(0).second;
        }
        
        // If after last position, return last
        if (timestamp >= positionsList.get(positionsList.size() - 1).first) {
            return positionsList.get(positionsList.size() - 1).second;
        }
        
        // Binary search for closest position
        int low = 0;
        int high = positionsList.size() - 1;
        
        while (low <= high) {
            int mid = (low + high) / 2;
            long midTime = positionsList.get(mid).first;
            
            if (midTime == timestamp) {
                return positionsList.get(mid).second;
            } else if (midTime < timestamp) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        
        // At this point, high < low and timestamp is between positionsList[high] and positionsList[low]
        // Return the closer one
        if (low >= positionsList.size()) {
            return positionsList.get(high).second;
        } else if (high < 0) {
            return positionsList.get(low).second;
        } else {
            long diffHigh = Math.abs(timestamp - positionsList.get(high).first);
            long diffLow = Math.abs(positionsList.get(low).first - timestamp);
            
            // Log significant time gaps (more than 5 seconds)
            long closerDiff = Math.min(diffHigh, diffLow);
            if (closerDiff > 5000) {
                Log.w("ReplayFragment", "Large time gap (" + closerDiff + 
                        "ms) when finding position for timestamp " + timestamp + "ms");
            }
            
            return diffHigh < diffLow ? positionsList.get(high).second : positionsList.get(low).second;
        }
    }

    private void redrawPolyline(int targetProgress) {
        Log.d("ReplayFragment", "redrawPolyline called for progress: " + targetProgress);
        
        // Ensure map, data, and polylines are ready
        if (gMap == null || !isDataPrepared || pdrPolyline == null || fusedPolyline == null) {
            Log.w("ReplayFragment", "redrawPolyline skipped: Map or data not ready");
            return;
        }
        
        long startTime = System.currentTimeMillis();
        
        // Draw PDR trajectory up to targetProgress
        drawTrajectoryUpToProgress(pdrPositionsList, pdrPolyline, targetProgress);
        
        // Draw fused trajectory if fusion algorithm is active
        if (isParticleFilterActive || isEkfFilterActive) {
            fusedPolyline.setVisible(true);
            
            // Choose which positions list to use based on selected algorithm and smoothing setting
            List<Pair<Long, LatLng>> selectedPositionsList;
            
            if (isParticleFilterActive) {
                selectedPositionsList = isSmoothingActive ? smoothedParticleFilterPositionsList : fusedPositionsList;
            } else { // isEkfFilterActive
                selectedPositionsList = isSmoothingActive ? smoothedEkfPositionsList : ekfPositionsList;
            }
            
            drawTrajectoryUpToProgress(selectedPositionsList, fusedPolyline, targetProgress);
        } else {
            fusedPolyline.setVisible(false);
        }
        
        // Update current position marker
        LatLng currentPosition;
        if ((isParticleFilterActive || isEkfFilterActive)) {
            List<Pair<Long, LatLng>> selectedPositionsList;
            
            if (isParticleFilterActive) {
                selectedPositionsList = isSmoothingActive ? smoothedParticleFilterPositionsList : fusedPositionsList;
            } else { // isEkfFilterActive
                selectedPositionsList = isSmoothingActive ? smoothedEkfPositionsList : ekfPositionsList;
            }
            
            if (!selectedPositionsList.isEmpty()) {
                currentPosition = findClosestPositionByTime(selectedPositionsList, targetProgress);
                currentFusedPosition = currentPosition;
            } else if (!pdrPositionsList.isEmpty()) {
                currentPosition = findClosestPositionByTime(pdrPositionsList, targetProgress);
            } else {
                Log.e("ReplayFragment", "No position data available for marker update");
                return;
            }
        } else if (!pdrPositionsList.isEmpty()) {
            currentPosition = findClosestPositionByTime(pdrPositionsList, targetProgress);
        } else {
            Log.e("ReplayFragment", "No position data available for marker update");
            return;
        }
        currentLocation = currentPosition;
        
        if (positionMarker != null && currentPosition != null) {
            positionMarker.setPosition(currentPosition);
            
            // Calculate orientation based on movement direction
            calculateAndSetOrientation(targetProgress, positionMarker, isParticleFilterActive || isEkfFilterActive);
            
            // Move camera to current position
            gMap.moveCamera(CameraUpdateFactory.newLatLng(currentPosition));
        }
        
        // Update indoor map if needed
        if (currentPosition != null) {
            indoorMapManager.setCurrentLocation(currentPosition);
            
            // Update elevation/floor if pressure data is available
            if (PressureNum > 0 && indoorMapManager.getIsIndoorMapSet()) {
                pressureIndex = findClosestPressureIndex(targetProgress, 0);
                if (pressureIndex < PressureNum) {
                    elevation = pdrProcessing.updateElevation(receTraj.getPressureData(pressureIndex).getPressure());
                    indoorMapManager.setCurrentFloor((int) (elevation / indoorMapManager.getFloorHeight()), true);
                }
            }
        }
        
        // Update GNSS marker if active
        if (GnssOn && gnssNum > 0) {
            gnssIndex = findClosestGnssIndex(targetProgress, 0);
            if (gnssIndex < gnssNum) {
                gnssLocation = new LatLng(
                    receTraj.getGnssData(gnssIndex).getLatitude(),
                    receTraj.getGnssData(gnssIndex).getLongitude()
                );
                
                if (gnssMarker != null) {
                    gnssMarker.setPosition(gnssLocation);
                } else {
                    gnssMarker = gMap.addMarker(
                        new MarkerOptions().title("GNSS position")
                            .position(gnssLocation)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    );
                }
            }
        }
        
        // Update WiFi marker if active
        if (WifiOn && !wifiPositionsList.isEmpty()) {
            LatLng wifiPos = findClosestPositionByTime(wifiPositionsList, targetProgress);
            if (wifiPos != null) {
                wifiLocation = wifiPos;
                
                if (wifiMarker != null) {
                    wifiMarker.setPosition(wifiLocation);
                } else {
                    wifiMarker = gMap.addMarker(
                        new MarkerOptions().title("WiFi position")
                            .position(wifiLocation)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                    );
                }
            }
        }
        
        // Log performance metrics for redrawing
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        if (duration > 100) { // Only log if it takes more than 100ms
            Log.d("ReplayFragment", "redrawPolyline took " + duration + "ms for progress " + targetProgress + "ms");
        }
    }

    /**
     * Draws a trajectory on a polyline up to the specified progress time
     */
    private void drawTrajectoryUpToProgress(List<Pair<Long, LatLng>> positionsList, Polyline polyline, int targetProgress) {
        if (positionsList.isEmpty()) {
            Log.w("ReplayFragment", "Cannot draw trajectory: Empty position list");
            return;
        }
        
        // Create new points list starting with the first position
        List<LatLng> points = new ArrayList<>();
        points.add(positionsList.get(0).second);
        
        // Count of points added to track performance
        int pointsAdded = 1;
        
        // Add all points with timestamp <= targetProgress
        for (int i = 1; i < positionsList.size(); i++) {
            Pair<Long, LatLng> position = positionsList.get(i);
            if (position.first <= targetProgress) {
                points.add(position.second);
                pointsAdded++;
            } else {
                break; // Stop once we reach positions beyond target progress
            }
        }
        
        // Update the polyline
        polyline.setPoints(points);
        
        // Log trajectory drawing information occasionally (every 5 seconds of playback)
        if (targetProgress % 5000 == 0 || targetProgress == MaxProgress) {
            Log.d("ReplayFragment", "Drew trajectory with " + pointsAdded + 
                    " points up to " + targetProgress + "ms (" + 
                    (int)(targetProgress/1000) + "s)");
        }
    }

    /**
     * Calculates and sets the orientation for the position marker based on movement direction
     */
    private void calculateAndSetOrientation(int targetProgress, Marker marker, boolean useFusedPath) {
        List<Pair<Long, LatLng>> positionsList;
        
        // Select the appropriate list based on active algorithm and smoothing setting
        if (useFusedPath) {
            if (isParticleFilterActive) {
                positionsList = isSmoothingActive ? smoothedParticleFilterPositionsList : fusedPositionsList;
            } else { // isEkfFilterActive
                positionsList = isSmoothingActive ? smoothedEkfPositionsList : ekfPositionsList;
            }
        } else {
            positionsList = pdrPositionsList;
        }
        
        // Find current position and previous position
        LatLng currentPos = findClosestPositionByTime(positionsList, targetProgress);
        LatLng prevPos = findClosestPositionByTime(positionsList, Math.max(0, targetProgress - 500)); // 500ms earlier
        
        if (currentPos != null && prevPos != null) {
            // Only calculate orientation if there's actual movement
            if (!currentPos.equals(prevPos)) {
                double deltaLat = currentPos.latitude - prevPos.latitude;
                double deltaLng = currentPos.longitude - prevPos.longitude;
                
                if (Math.abs(deltaLat) > 1e-9 || Math.abs(deltaLng) > 1e-9) {
                    // Calculate bearing (orientation)
                    float bearing = (float) Math.toDegrees(Math.atan2(deltaLng, deltaLat));
                    marker.setRotation(bearing);
                }
            }
        }
    }

    /**
     * Calculates all EKF positions and stores them with timestamps
     */
    private void calculateAllEkfPositions(LatLng startLocation) {
        Log.d("ReplayFragment", "Calculating all EKF positions...");
        
        // Add start position
        ekfPositionsList.add(new Pair<>(0L, startLocation));
        
        // Initialize variables to track PDR increments
        float previousPdrX = receTraj.getPdrData(0).getX();
        float previousPdrY = receTraj.getPdrData(0).getY();
        LatLng lastEkfPosition = startLocation;
        
        // Use PDR data timestamps as the basis for EKF calculations
        // Skip the first point (already added as start point)
        for (int i = 1; i < pdrNum; i++) {
            // Get current PDR data
            float currentPdrX = receTraj.getPdrData(i).getX();
            float currentPdrY = receTraj.getPdrData(i).getY();
            
            // Calculate PDR displacement (increments)
            float dx = currentPdrX - previousPdrX;
            float dy = currentPdrY - previousPdrY;
            
            // Get current timestamp
            long timestamp = receTraj.getPdrData(i).getRelativeTimestamp();
            
            // Find the WiFi position at corresponding timestamp
            LatLng wifiPosition = isIndoor ? findClosestPositionByTime(wifiPositionsList, timestamp) : null;
            
            // Find the GNSS position at corresponding timestamp
            LatLng gnssPosition = null;
            if (!isIndoor && gnssNum > 0) {
                int gnssIdx = findClosestGnssIndex((int)timestamp, 0);
                if (gnssIdx < gnssNum) {
                    gnssPosition = new LatLng(
                            receTraj.getGnssData(gnssIdx).getLatitude(),
                            receTraj.getGnssData(gnssIdx).getLongitude()
                    );
                }
            }
            
            // Apply EKF filter with the new interface (using dx, dy increments)
            LatLng ekfPosition = com.openpositioning.PositionMe.FusionFilter.EKFFilter.ekfFusion(
                    lastEkfPosition, wifiPosition, gnssPosition, dx, dy);
            
            // Store EKF position with the same timestamp as PDR
            ekfPositionsList.add(new Pair<>(timestamp, ekfPosition));
            
            // Update for next iteration
            lastEkfPosition = ekfPosition;
            previousPdrX = currentPdrX;
            previousPdrY = currentPdrY;
            
            // Log every 100 points for debugging
            if (i % 100 == 0) {
                Log.d("ReplayFragment", "Calculated EKF position " + i + "/" + pdrNum + 
                        " at time " + timestamp + "ms with dx=" + dx + ", dy=" + dy);
            }
        }
        
        Log.d("ReplayFragment", "Calculated " + ekfPositionsList.size() + " EKF positions");
    }

    /**
     * Calculates smoothed trajectories using WMA algorithm
     */
    private void calculateSmoothedTrajectories() {
        Log.d("ReplayFragment", "Calculating smoothed trajectories using WMA...");
        
        // Create temporary lists for trajectory points without timestamps
        List<LatLng> particleFilterPoints = new ArrayList<>();
        List<LatLng> ekfPoints = new ArrayList<>();
        
        // Extract position data from the paired lists
        for (Pair<Long, LatLng> position : fusedPositionsList) {
            particleFilterPoints.add(position.second);
        }
        
        for (Pair<Long, LatLng> position : ekfPositionsList) {
            ekfPoints.add(position.second);
        }
        
        // Apply WMA smoothing for Particle Filter points
        smoothedParticleFilterPositionsList.clear();
        for (int i = 0; i < fusedPositionsList.size(); i++) {
            long timestamp = fusedPositionsList.get(i).first;
            LatLng smoothedPoint;
            
            if (i < WMA_WINDOW_SIZE - 1) {
                // Not enough previous points for full window size, use original point
                smoothedPoint = particleFilterPoints.get(i);
            } else {
                // Apply WMA smoothing with specified window size
                smoothedPoint = com.openpositioning.PositionMe.TrajOptim.applyWMAAtIndex(particleFilterPoints, WMA_WINDOW_SIZE, i);
            }
            
            // Add smoothed point with original timestamp
            smoothedParticleFilterPositionsList.add(new Pair<>(timestamp, smoothedPoint));
        }
        
        // Apply WMA smoothing for EKF points
        smoothedEkfPositionsList.clear();
        for (int i = 0; i < ekfPositionsList.size(); i++) {
            long timestamp = ekfPositionsList.get(i).first;
            LatLng smoothedPoint;
            
            if (i < WMA_WINDOW_SIZE - 1) {
                // Not enough previous points for full window size, use original point
                smoothedPoint = ekfPoints.get(i);
            } else {
                // Apply WMA smoothing with specified window size
                smoothedPoint = com.openpositioning.PositionMe.TrajOptim.applyWMAAtIndex(ekfPoints, WMA_WINDOW_SIZE, i);
            }
            
            // Add smoothed point with original timestamp
            smoothedEkfPositionsList.add(new Pair<>(timestamp, smoothedPoint));
        }
        
        Log.d("ReplayFragment", "Calculated " + smoothedParticleFilterPositionsList.size() + " smoothed particle filter positions");
        Log.d("ReplayFragment", "Calculated " + smoothedEkfPositionsList.size() + " smoothed EKF positions");
    }

    /**
     * Creates a dropdown for switching positioning algorithms
     */
    private void setupAlgorithmDropdown() {
        // Different Algorithm Types
        String[] algorithms = new String[]{"No Fusion", "EKF", "Batch optimisation", "Particle filter"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, algorithms);
        // Set the Dropdowns menu adapter
        algorithmSwitchSpinner.setAdapter(adapter);
        
        // Set the default algorithm to Particle filter
        algorithmSwitchSpinner.setSelection(3);
        isParticleFilterActive = true; // Default to particle filter active
        isEkfFilterActive = false;     // Default EKF to inactive

        // Set listener for algorithm selection
        algorithmSwitchSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean prevParticleFilterActive = isParticleFilterActive;
                boolean prevEkfFilterActive = isEkfFilterActive;
                
                // Reset all algorithm flags first
                isParticleFilterActive = false;
                isEkfFilterActive = false;
                
                switch (position) {
                    case 0:
                        // No Fusion selected
                        Toast.makeText(getContext(), "No Fusion algorithm selected", Toast.LENGTH_SHORT).show();
                        break;
                    case 1:
                        // EKF selected
                        isEkfFilterActive = true;
                        Toast.makeText(getContext(), "EKF algorithm selected", Toast.LENGTH_SHORT).show();
                        break;
                    case 2:
                        // Batch optimisation selected
                        Toast.makeText(getContext(), "Batch optimisation algorithm selected", Toast.LENGTH_SHORT).show();
                        break;
                    case 3:
                        // Particle filter selected
                        isParticleFilterActive = true;
                        Toast.makeText(getContext(), "Particle filter algorithm selected", Toast.LENGTH_SHORT).show();
                        break;
                    default:
                        break;
                }
                
                // If filter status changed and data is ready, redraw the current progress
                if ((prevParticleFilterActive != isParticleFilterActive || 
                     prevEkfFilterActive != isEkfFilterActive) && isDataPrepared) {
                    redrawPolyline(progress);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Default to No Fusion when nothing selected
                isParticleFilterActive = false;
                isEkfFilterActive = false;
                if (isDataPrepared) {
                    redrawPolyline(progress);
                }
            }
        });
    }
}