package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.hardware.SensorManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.Toast;
import android.graphics.Color;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.PdrProcessing;
import com.openpositioning.PositionMe.BuildingPolygon;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;
import com.openpositioning.PositionMe.FusionAlgorithms.ExtendedKalmanFilter;
import com.openpositioning.PositionMe.FusionAlgorithms.ParticleFilter;
import com.openpositioning.PositionMe.Method.TurnDetector;
import com.openpositioning.PositionMe.Method.CoordinateTransform;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * ReplayFragment: Implements playback of GNSS, PDR, WiFi, and Fusion (EKF) tracks.
 *Using a relative_timestamp in Traj as a time interval in Replay mode,
 *And feed the data to EKF for state fusion. The fusion result is returned to ReplayFragment,
 *ReplayFragment determines whether it is indoors based on the current location and decides whether to use WiFi or GNSS data correction at the next moment.
 *Added: On the basis of not changing the original code, add the integration and display of PF (ParticleFilter) tracks.
 * * PF fusion strategy: PDR and WiFi data are integrated indoors, and PDR and GNSS data are integrated outdoors.
 */
public class ReplayFragment extends Fragment implements OnMapReadyCallback {

    private static class FusedEvent {
        long relativeTime;
        LatLng fusedPosition;
        public FusedEvent(long relativeTime, LatLng fusedPosition) {
            this.relativeTime = relativeTime;
            this.fusedPosition = fusedPosition;
        }
    }
    private List<FusedEvent> fusedEvents = new ArrayList<>();

    // In the ReplayFragment class, add two member variables to PDR to record the x and y of the previous Pdr_Sample
    private float lastPdrX = 0f;
    private float lastPdrY = 0f;
    private boolean firstPdrArrived = false;

    // PF-related member variables
    private ParticleFilter pf;
    private Polyline pfPolyline;
    private Marker pfMarker;
    private float lastPfPdrX = 0f;
    private float lastPfPdrY = 0f;
    private boolean firstPfPdrArrived = false;

    private MapView mapView;
    private GoogleMap mMap;
    private Button btnPlayPause, btnRestart, btnGoToEnd, btnExit;
    private SeekBar progressBar;

    private boolean isPlaying = false;
    private Handler playbackHandler = new Handler(Looper.getMainLooper());

    // Trajectory data
    private Traj.Trajectory trajectory;
    private List<Traj.GNSS_Sample> gnssPositions;
    private List<Traj.Pdr_Sample> pdrPositions;
    private List<Traj.Pressure_Sample> pressureinfos;
    private List<Traj.WiFi_Sample> wifiSamples;

    private List<Float> altitudeList;
    private List<Float> relativeAltitudeList;

    private Polyline gnssPolyline;
    private Polyline pdrPolyline;
    private Polyline wifiPolyline;
    private Polyline fusedPolyline;
    private Marker gnssMarker;
    private Marker pdrMarker;
    private Marker wifiMarker;
    private Marker fusedMarker;
    private PdrProcessing pdrProcessing;

    private TurnDetector turnDetector;

    private IndoorMapManager indoorMapManager;
    private LatLng currentLocation;

    // Floor control parameters
    private int currentMeasuredFloor = 0;
    private final float FLOOR_HEIGHT = 4.2f;
    private final float TOLERANCE = 0.5f;
    private final int CONSECUTIVE_THRESHOLD = 1;
    private int upCounter = 0;
    private int downCounter = 0;

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton;
    private com.google.android.material.floatingactionbutton.FloatingActionButton floorDownButton;
    private Switch autoFloor;

    private String filePath;

    // Merge event
    private List<Event> mergedEvents = new ArrayList<>();
    private int currentEventIndex = 0;

    private WiFiPositioning wiFiPositioning;
    private Map<Long, LatLng> wifiPositionCache = new HashMap<>();
    private boolean wifiPositionRequestsComplete = false;

    private static final String WIFI_FINGERPRINT = "wf";

    // EKF 实例
    private ExtendedKalmanFilter ekf;

    // Record the initial GNSS reference location (lat, lon, alt) in order to transfer PDR, GNSS, WiFi to ENU
    private double[] refPosition = null;
    private double[] refEcef = null;
    private boolean hasInitRef = false;

    // It is used to manage different types of trajectory events in a unified manner
    private static class Event {
        long relativeTime;  // relative_timestamp in Traj
        int eventType;      // 0=GNSS, 1=PDR, 2=Pressure, 3=WiFi
        Traj.GNSS_Sample gnss;
        Traj.Pdr_Sample pdr;
        Traj.Pressure_Sample pressure;
        Traj.WiFi_Sample wifi;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_replay, container, false);
        mapView = view.findViewById(R.id.mapView);
        btnPlayPause = view.findViewById(R.id.btnPlayPause);
        btnRestart = view.findViewById(R.id.btnRestart);
        btnGoToEnd = view.findViewById(R.id.btnGoToEnd);
        btnExit = view.findViewById(R.id.btnExit);
        progressBar = view.findViewById(R.id.progressBar);
        floorUpButton = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        autoFloor = view.findViewById(R.id.autoFloor);

        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        pdrProcessing = new PdrProcessing(getContext());
        wiFiPositioning = new WiFiPositioning(getContext());

        turnDetector = new TurnDetector();
        turnDetector.startMonitoring(); // Make sure to start monitoring changes in direction

        // Receive the incoming file path
        if (getArguments() != null) {
            filePath = getArguments().getString("trajectory_file_path");
        }
        if (filePath == null) {
            Toast.makeText(getContext(), "No trajectory file provided", Toast.LENGTH_SHORT).show();
            return;
        }

        // Read the Traj data
        try {
            File file = new File(filePath);
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();
            trajectory = Traj.Trajectory.parseFrom(data);
            gnssPositions = trajectory.getGnssDataList();
            pdrPositions = trajectory.getPdrDataList();
            pressureinfos = trajectory.getPressureDataList();
            wifiSamples = trajectory.getWifiDataList();

            altitudeList = new ArrayList<>();
            relativeAltitudeList = new ArrayList<>();
            if (pressureinfos != null && !pressureinfos.isEmpty()) {
                for (Traj.Pressure_Sample ps : pressureinfos) {
                    float alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, ps.getPressure());
                    altitudeList.add(alt);
                }
            }
            // Generate a list of relative elevations
            for (Float absoluteAlt : altitudeList) {
                float relativeAlt = pdrProcessing.updateElevation(absoluteAlt);
                relativeAltitudeList.add(relativeAlt);
            }
            // Preprocessing WiFi data: asynchronous query, caching the results to the wifiPositionCache
            if (wifiSamples != null && !wifiSamples.isEmpty()) {
                processWifiSamplesForPositioning();
            }

            buildMergedEvents();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Failed to load trajectory data", Toast.LENGTH_SHORT).show();
        }

        // Initialize EKF
        ekf = new ExtendedKalmanFilter();

        // If GNSS data is not empty, the first GNSS is set as the reference point
        if (gnssPositions != null && !gnssPositions.isEmpty()) {
            Traj.GNSS_Sample firstGnss = gnssPositions.get(0);
            refPosition = new double[]{firstGnss.getLatitude(), firstGnss.getLongitude(), firstGnss.getAltitude()};
            refEcef = CoordinateTransform.geodeticToEcef(
                    refPosition[0], refPosition[1], refPosition[2]);
            ekf.setInitialReference(refPosition, refEcef);
            // Also set the start time of the EKF to a relativeTime for the first event (if present)
            if (!mergedEvents.isEmpty()) {
                ekf.setInitialTime(mergedEvents.get(0).relativeTime);
            }
            hasInitRef = true;
        }

        // Initialize PF when there is a reference point
        if (refPosition != null) {
            pf = new ParticleFilter(refPosition);
        }

        // Gets the newly added button
        Button btnShowEKF = view.findViewById(R.id.btnShowEKF);
        Button btnShowPF = view.findViewById(R.id.btnShowPF);

        // Initial status can be set to not display (if required)
        if (fusedPolyline != null) fusedPolyline.setVisible(false);
        if (fusedMarker != null) fusedMarker.setVisible(false);
        if (pfPolyline != null) pfPolyline.setVisible(false);
        if (pfMarker != null) pfMarker.setVisible(false);

        // Set button click event in onViewCreated (modified)
        btnShowEKF.setOnClickListener(v -> {
            if (fusedPolyline != null) {
                boolean currentVisible = fusedPolyline.isVisible();
                // Toggle fusion trajectory visibility
                fusedPolyline.setVisible(!currentVisible);
                // Update marker synchronously (if present)
                if (fusedMarker != null) {
                    fusedMarker.setVisible(!currentVisible);
                }
                // Update button text
                btnShowEKF.setText(!currentVisible ? "Hide EKF" : "Show EKF");
            }
        });

        btnShowPF.setOnClickListener(v -> {
            if (pfPolyline != null) {
                boolean currentVisible = pfPolyline.isVisible();
                // Switching PF fusion trajectory visibility
                pfPolyline.setVisible(!currentVisible);
                // Update marker synchronously (if present)
                if (pfMarker != null) {
                    pfMarker.setVisible(!currentVisible);
                }
                // Update button text
                btnShowPF.setText(!currentVisible ? "Hide PF" : "Show PF");
            }
        });

        // Button and progress bar initialization
        btnPlayPause.setOnClickListener(v -> {
            if (isPlaying) {
                pauseReplay();
            } else {
                // If it has started but not finished, continue playing. Otherwise, start from scratch.
                if (currentEventIndex > 0 && currentEventIndex < mergedEvents.size()) {
                    resumeReplay();
                } else {
                    startReplay();
                }
            }
        });
        btnRestart.setOnClickListener(v -> restartReplay());
        btnGoToEnd.setOnClickListener(v -> goToEndReplay());
        btnExit.setOnClickListener(v -> exitReplay());

        if (!mergedEvents.isEmpty()) {
            progressBar.setMax(mergedEvents.size() - 1);
        }
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentEventIndex = progress;
                    updateMarkersForEventIndex(currentEventIndex);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                pauseReplay();
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        floorUpButton.setOnClickListener(v -> {
            autoFloor.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
            }
        });
        floorDownButton.setOnClickListener(v -> {
            autoFloor.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
            }
        });
        setFloorButtonVisibility(View.VISIBLE);


    }

    /**
     * Asynchronously process WiFi samples to fetch (LatLng) and store them in the wifiPositionCache.
     */
    private void processWifiSamplesForPositioning() {
        Toast.makeText(getContext(), "Processing WiFi data...", Toast.LENGTH_SHORT).show();
        final int[] completedRequests = {0};
        final int totalRequests = wifiSamples.size();

        for (Traj.WiFi_Sample sample : wifiSamples) {
            final long timestamp = sample.getRelativeTimestamp();
            try {
                JSONObject wifiAccessPoints = new JSONObject();
                for (Traj.Mac_Scan scan : sample.getMacScansList()) {
                    wifiAccessPoints.put(String.valueOf(scan.getMac()), scan.getRssi());
                }
                JSONObject wifiFingerPrint = new JSONObject();
                wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);

                wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                    @Override
                    public void onSuccess(LatLng location, int floor) {
                        wifiPositionCache.put(timestamp, location);
                        completedRequests[0]++;
                        if (completedRequests[0] >= totalRequests) {
                            wifiPositionRequestsComplete = true;
                            new Handler(Looper.getMainLooper()).post(() -> {
                                Toast.makeText(getContext(),
                                        "WiFi positioning complete", Toast.LENGTH_SHORT).show();
                                if (mMap != null) {
                                    drawFullWifiTrack();
                                }
                            });
                        }
                    }

                    @Override
                    public void onError(String message) {
                        completedRequests[0]++;
                        if (completedRequests[0] >= totalRequests) {
                            wifiPositionRequestsComplete = true;
                            new Handler(Looper.getMainLooper()).post(() -> {
                                Toast.makeText(getContext(),
                                        "WiFi positioning completed with some errors",
                                        Toast.LENGTH_SHORT).show();
                            });
                        }
                    }
                });
            } catch (JSONException e) {
                completedRequests[0]++;
            }
        }
    }

    /**
     * Draw the overall WiFi track on the map (optional, easy to view).
     */
    private void drawFullWifiTrack() {
        if (mMap == null || !wifiPositionRequestsComplete) return;

        if (wifiPolyline != null) {
            wifiPolyline.remove();
        }
        PolylineOptions wifiOptions = new PolylineOptions().width(10).color(Color.GREEN).geodesic(true);
        List<Traj.WiFi_Sample> sortedWifiSamples = new ArrayList<>(wifiSamples);
        Collections.sort(sortedWifiSamples, (o1, o2) -> Long.compare(o1.getRelativeTimestamp(), o2.getRelativeTimestamp()));
        for (Traj.WiFi_Sample sample : sortedWifiSamples) {
            long ts = sample.getRelativeTimestamp();
            LatLng wifiLatLng = wifiPositionCache.get(ts);
            if (wifiLatLng != null) {
                wifiOptions.add(wifiLatLng);
            }
        }
        wifiPolyline = mMap.addPolyline(wifiOptions);
        if (wifiMarker != null) {
            wifiMarker.remove();
            wifiMarker = null;
        }
        if (!sortedWifiSamples.isEmpty()) {
            LatLng firstPos = wifiPositionCache.get(sortedWifiSamples.get(0).getRelativeTimestamp());
            if (firstPos != null) {
                wifiMarker = mMap.addMarker(new MarkerOptions()
                        .position(firstPos)
                        .title("WiFi Position")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
            }
        }
    }

    /**
     * Combine GNSS, PDR, Pressure, WiFi events into one List and sort them by relative time.
     */
    private void buildMergedEvents() {
        mergedEvents.clear();
        if (gnssPositions != null) {
            for (Traj.GNSS_Sample g : gnssPositions) {
                Event e = new Event();
                e.relativeTime = g.getRelativeTimestamp();
                e.eventType = 0;
                e.gnss = g;
                mergedEvents.add(e);
            }
        }
        if (pdrPositions != null) {
            for (Traj.Pdr_Sample p : pdrPositions) {
                Event e = new Event();
                e.relativeTime = p.getRelativeTimestamp();
                e.eventType = 1;
                e.pdr = p;
                mergedEvents.add(e);
            }
        }
        if (pressureinfos != null) {
            for (Traj.Pressure_Sample pr : pressureinfos) {
                Event e = new Event();
                e.relativeTime = pr.getRelativeTimestamp();
                e.eventType = 2;
                e.pressure = pr;
                mergedEvents.add(e);
            }
        }
        if (wifiSamples != null) {
            for (Traj.WiFi_Sample w : wifiSamples) {
                Event e = new Event();
                e.relativeTime = w.getRelativeTimestamp();
                e.eventType = 3;
                e.wifi = w;
                mergedEvents.add(e);
            }
        }
        Collections.sort(mergedEvents, (o1, o2) -> Long.compare(o1.relativeTime, o2.relativeTime));
    }

    private void setFloorButtonVisibility(int visibility) {
        if (floorUpButton != null) floorUpButton.setVisibility(visibility);
        if (floorDownButton != null) floorDownButton.setVisibility(visibility);
        if (autoFloor != null) autoFloor.setVisibility(visibility);
    }

    /**
     * Process progress bar Drag: Jump to the eventIndex event and replay.
     */
    private void updateMarkersForEventIndex(int eventIndex) {
        if (eventIndex < 0 || eventIndex >= mergedEvents.size()) return;
        if (mMap == null || indoorMapManager == null) return;
        if (!hasInitRef) return;

        // Reset some visual markers
        if (gnssMarker != null) { gnssMarker.remove(); gnssMarker = null; }
        if (pdrMarker != null) { pdrMarker.remove(); pdrMarker = null; }
        if (wifiMarker != null) { wifiMarker.remove(); wifiMarker = null; }
        if (fusedMarker != null) { fusedMarker.remove(); fusedMarker = null; }
        if (pfMarker != null) { pfMarker.remove(); pfMarker = null; }

        currentLocation = null;
        currentMeasuredFloor = 0;
        upCounter = 0;
        downCounter = 0;

        // Reset EKF
        ekf.reset();

        // Set the EKF reference again
        ekf.setInitialReference(refPosition, refEcef);
        if (!mergedEvents.isEmpty()) {
            ekf.setInitialTime(mergedEvents.get(0).relativeTime);
        }
        // Reset the cumulative value of PDR
        lastPdrX = 0f;
        lastPdrY = 0f;
        firstPdrArrived = false;

        // Reset the accumulated PDR status of PF
        lastPfPdrX = 0f;
        lastPfPdrY = 0f;
        firstPfPdrArrived = false;

        // The events of [0... eventIndex] are processed sequentially, with the last state being the one we want
        for (int i = 0; i <= eventIndex; i++) {
            processEvent(mergedEvents.get(i), false);
            //  PF events are processed synchronously
            processPFEvent(mergedEvents.get(i), false);
        }

        progressBar.setProgress(eventIndex);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        mMap.getUiSettings().setCompassEnabled(true);
        mMap.getUiSettings().setTiltGesturesEnabled(true);
        mMap.getUiSettings().setRotateGesturesEnabled(true);
        mMap.getUiSettings().setScrollGesturesEnabled(true);

        // Show GNSS trajectory (blue)
        if (gnssPositions != null && !gnssPositions.isEmpty()) {
            PolylineOptions gnssOptions = new PolylineOptions().color(Color.BLUE).width(10);
            for (Traj.GNSS_Sample sample : gnssPositions) {
                LatLng latLng = new LatLng(sample.getLatitude(), sample.getLongitude());
                gnssOptions.add(latLng);
            }
            gnssPolyline = mMap.addPolyline(gnssOptions);
            LatLng gnssStart = new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude());
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(gnssStart, 18f));
            gnssMarker = mMap.addMarker(new MarkerOptions().position(gnssStart)
                    .title("GNSS Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
            currentLocation = gnssStart;
        }

        // Show the PDR trace (red)
        if (pdrPositions != null && !pdrPositions.isEmpty()) {
            // If GNSS data is available, use GNSS point 1 as the starting point for PDR
            LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty())
                    ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude())
                    : new LatLng(0, 0);
            PolylineOptions pdrOptions = new PolylineOptions().color(Color.RED).width(10);
            float sumX = 0f;
            float sumY = 0f;
            for (Traj.Pdr_Sample sample : pdrPositions) {
                sumX  = sample.getX();
                sumY = sample.getY();
                LatLng pdrLatLng = CoordinateTransform.enuToGeodetic(sumX , sumY, 0,
                        refPosition[0], refPosition[1], refEcef);
                pdrOptions.add(pdrLatLng);
            }
            pdrPolyline = mMap.addPolyline(pdrOptions);
            if (!pdrOptions.getPoints().isEmpty()) {
                pdrMarker = mMap.addMarker(new MarkerOptions()
                        .position(pdrOptions.getPoints().get(0))
                        .title("PDR Position")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
            }
        }

        indoorMapManager = new IndoorMapManager(mMap);
        fusedPolyline = mMap.addPolyline(new PolylineOptions().color(Color.MAGENTA).width(10));

        //  Initialize the PF track display in orange
        pfPolyline = mMap.addPolyline(new PolylineOptions().color(Color.rgb(255, 165, 0)).width(10));
    }

    private void resetAltitudeData() {
        altitudeList = new ArrayList<>();
        relativeAltitudeList = new ArrayList<>();
        if (pressureinfos != null && !pressureinfos.isEmpty()) {
            for (Traj.Pressure_Sample ps : pressureinfos) {
                float alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, ps.getPressure());
                altitudeList.add(alt);
            }
            // Relative elevation is calculated from each absolute elevation
            for (Float absoluteAlt : altitudeList) {
                float relativeAlt = pdrProcessing.updateElevation(absoluteAlt);
                relativeAltitudeList.add(relativeAlt);
            }
        }
    }


    // Play control
    private void startReplay() {
        if (mergedEvents.isEmpty()) return;
        isPlaying = true;
        btnPlayPause.setText("Pause");
        currentEventIndex = 0;
        lastPdrX = 0f;
        lastPdrY = 0f;
        firstPdrArrived = false;
        // Reset the accumulated PF status
        lastPfPdrX = 0f;
        lastPfPdrY = 0f;
        firstPfPdrArrived = false;
        updateMarkersForEventIndex(0);
        scheduleNext();
    }

    private void pauseReplay() {
        isPlaying = false;
        btnPlayPause.setText("Play");
        playbackHandler.removeCallbacksAndMessages(null);
    }

    private void resumeReplay() {
        if (mergedEvents.isEmpty()) return;
        isPlaying = true;
        btnPlayPause.setText("Pause");
        scheduleNext();
    }

    private void restartReplay() {
        pauseReplay();
        currentEventIndex = 0;

        resetAltitudeData();

        // Reset EKF and PDR status (clear track display)
        updateMarkersForEventIndex(0);
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentFloor(0, true);
        }

        // Clear the EKF fusion trace: Clear the points of the fusedPolyline and remove Fusedmarkers
        if (fusedPolyline != null) {
            fusedPolyline.setPoints(new ArrayList<>());
            //fusedPolyline.setVisible(false);
        }
        if (fusedMarker != null) {
            fusedMarker.remove();
            fusedMarker = null;
        }
        // reset the EKF object (call reset()
        ekf.reset();

        // Reset the PF status: Rebuild the PF and clear the PF trace display
        if (refPosition != null) {
            pf = new ParticleFilter(refPosition);
        }
        if (pfPolyline != null) {
            pfPolyline.setPoints(new ArrayList<>());
            //pfPolyline.setVisible(false);
        }
        if (pfMarker != null) {
            pfMarker.remove();
            pfMarker = null;
        }

        // Reset the accumulated PDR status
        lastPdrX = 0f;
        lastPdrY = 0f;
        firstPdrArrived = false;
        lastPfPdrX = 0f;
        lastPfPdrY = 0f;
        firstPfPdrArrived = false;

        startReplay();
    }

    private void goToEndReplay() {
        pauseReplay();
        if (!mergedEvents.isEmpty()) {
            currentEventIndex = mergedEvents.size() - 1;
            updateMarkersForEventIndex(currentEventIndex);
        }
    }

    private void exitReplay() {
        pauseReplay();
        requireActivity().onBackPressed();
    }

    /**
     * Schedule the next event, calculating the latency based on the relative_timestamp difference between adjacent events.
     *  relativeTime is used to simulate the original timing in Replay mode.
     */
    private void scheduleNext() {
        if (!isPlaying || currentEventIndex >= mergedEvents.size()) {
            pauseReplay();
            return;
        }
        final Event current = mergedEvents.get(currentEventIndex);
        final int nextIndex = currentEventIndex + 1;
        long delayMs = 500;
        if (nextIndex < mergedEvents.size()) {
            long dt = mergedEvents.get(nextIndex).relativeTime - current.relativeTime;
            delayMs = dt < 0 ? 0 : dt;
        }
        processEvent(current, true);
        // PF trajectory is updated synchronously.
        processPFEvent(current, true);
        currentEventIndex++;
        progressBar.setProgress(currentEventIndex);
        playbackHandler.postDelayed(() -> scheduleNext(), delayMs);
    }

    /**
     * Core: Processes a single Event and feeds it to the EKF.
     * @param e Corresponding event
     * @param immediate Whether to update the map camera immediately
     */
    private void processEvent(Event e, boolean immediate) {
        if (!hasInitRef) return; // ENU transformations cannot be performed without a GNSS starting reference point

        double currentAltitude = 0;
        //double[] pdrEnu = ekf.getEnuPosition(); // Take out (east, north) of the current EKF estimation state.

        switch (e.eventType) {
            case 0: // GNSS
                if (e.gnss != null) {
                    Traj.GNSS_Sample sample = e.gnss;
                    double lat = sample.getLatitude();
                    double lon = sample.getLongitude();
                    double alt = sample.getAltitude();
                    currentAltitude = alt;

                    // Update GNSS markers on the map
                    LatLng gnssLatLng = new LatLng(lat, lon);
                    if (gnssMarker != null) {
                        gnssMarker.setPosition(gnssLatLng);
                    } else {
                        gnssMarker = mMap.addMarker(new MarkerOptions()
                                .position(gnssLatLng)
                                .title("GNSS Position")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
                    }
                    if (immediate) {
                        mMap.animateCamera(CameraUpdateFactory.newLatLng(gnssLatLng));
                    }

                    // Convert GNSS (lat,lon,alt) to ENU
                    double[] enuGnss = CoordinateTransform.geodeticToEnu(
                            lat, lon, alt,
                            refPosition[0], // lat of the GNSS reference point
                            refPosition[1], // lon of the GNSS reference point
                            refPosition[2]  // alt of the GNSS reference point
                    );

                    // Get the current forecast PDR (east, north) from EKF, and merge together later
                    double[] pdrEnu = ekf.getEnuPosition();

                    // Call EKF's onObservationUpdate() method and use GNSS to fix it
                    //    这里的 observeEast, observeNorth = enuGnss
                    //    pdrEast, pdrNorth = pdrEnu
                    //    penaltyFactor = 1.0
                    ekf.onObservationUpdate(
                            enuGnss[0], enuGnss[1],   // GNSS East, North
                            pdrEnu[0], pdrEnu[1],    // PDR East, North
                            alt, 1.0);
                }
                break;

            case 1: // PDR
                if (e.pdr != null) {
                    float currX = e.pdr.getX();
                    float currY = e.pdr.getY();

                    float stepX, stepY;
                    if (!firstPdrArrived) {
                        // This is the first PDR sample, so we can't do anything different from the last one,
                        // so we just increment it by 0
                        stepX = 0f;
                        stepY = 0f;
                        firstPdrArrived = true;
                    } else {
                        // Make the difference with the previous one
                        stepX = currX - lastPdrX;
                        stepY = currY - lastPdrY;
                    }

                    // Record the current cumulative value for next use
                    lastPdrX = currX;
                    lastPdrY = currY;

                    // Regard differential stepX and stepY as "ENU displacement of this small step"
                    double stepLength = Math.hypot(stepX, stepY);
                    double heading = Math.atan2(stepY, stepX);
                    // heading: True north is π/2, and due east is 0 or 2π, which is used here only for EKF
                    // and will be re-wrapped/corrected internally

                    // EKF predict
                    ekf.predict(heading, stepLength, stepLength, e.relativeTime,
                            turnDetector.onStepDetected(heading));

                    // Visualization: The absolute cumulative position of the current PDR (currX, currY) is converted into a LatLng display
                    if (hasInitRef) {
                        double[] ecefRef = ekf.getEcefRefCoords();
                        LatLng pdrLatLng = CoordinateTransform.enuToGeodetic(
                                currX, currY, 0,
                                refPosition[0], refPosition[1],
                                refEcef
                        );
                        if (pdrMarker == null) {
                            pdrMarker = mMap.addMarker(new MarkerOptions()
                                    .position(pdrLatLng)
                                    .title("PDR Position")
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
                        } else {
                            pdrMarker.setPosition(pdrLatLng);
                        }
                        if (immediate) {
                            mMap.animateCamera(CameraUpdateFactory.newLatLng(pdrLatLng));
                        }
                    }
                }
                break;

            case 2: // PRESSURE
                if (e.pressure != null && pressureinfos != null && !pressureinfos.isEmpty()) {
                    // Find the index of the current pressure sample in pressureinfos
                    int idx = pressureinfos.indexOf(e.pressure);
                    if (idx >= 0 && idx < relativeAltitudeList.size()) {
                        float currentRelAlt = relativeAltitudeList.get(idx);
                        // Updated the captions of PDR Marker to show altitude
                        if (pdrMarker != null) {
                            pdrMarker.setSnippet("Altitude: " + String.format("%.1f", currentRelAlt) + " m");
                        }
                        // Automatic floor detection: processed only when autoFloor is enabled
                        if (autoFloor != null && autoFloor.isChecked()) {
                            float buildingFloorHeight = FLOOR_HEIGHT;
                            if (currentLocation != null) {
                                if (BuildingPolygon.inNucleus(currentLocation)) {
                                    buildingFloorHeight = IndoorMapManager.NUCLEUS_FLOOR_HEIGHT;
                                } else if (BuildingPolygon.inLibrary(currentLocation)) {
                                    buildingFloorHeight = IndoorMapManager.LIBRARY_FLOOR_HEIGHT;
                                } else {
                                    buildingFloorHeight = 0;
                                }
                            }
                            if (buildingFloorHeight > 0 && indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
                                float expectedUp = (currentMeasuredFloor + 1) * buildingFloorHeight;
                                float expectedDown = (currentMeasuredFloor - 1) * buildingFloorHeight;
                                if (currentRelAlt >= (expectedUp - TOLERANCE) && currentRelAlt <= (expectedUp + TOLERANCE)) {
                                    upCounter++;
                                    downCounter = 0;
                                } else if (currentRelAlt >= (expectedDown - TOLERANCE) && currentRelAlt <= (expectedDown + TOLERANCE)) {
                                    downCounter++;
                                    upCounter = 0;
                                } else {
                                    upCounter = 0;
                                    downCounter = 0;
                                }
                                if (upCounter >= CONSECUTIVE_THRESHOLD) {
                                    currentMeasuredFloor++;
                                    indoorMapManager.setCurrentFloor(currentMeasuredFloor, true);
                                    if (currentLocation != null) {
                                        mMap.animateCamera(CameraUpdateFactory.newLatLng(currentLocation));
                                    }
                                    upCounter = 0;
                                }
                                if (downCounter >= CONSECUTIVE_THRESHOLD) {
                                    currentMeasuredFloor--;
                                    indoorMapManager.setCurrentFloor(currentMeasuredFloor, true);
                                    if (currentLocation != null) {
                                        mMap.animateCamera(CameraUpdateFactory.newLatLng(currentLocation));
                                    }
                                    downCounter = 0;
                                }
                            }
                        }
                        // Here you can update currentAltitude to currentRelAlt (for use if fusion is required)
                        currentAltitude = currentRelAlt;
                    }
                }
                break;

            case 3: // WIFI
                if (e.wifi != null) {
                    Traj.WiFi_Sample wifiSample = e.wifi;
                    long ts = wifiSample.getRelativeTimestamp();
                    LatLng wifiPos = wifiPositionCache.get(ts);
                    if (wifiPos != null) {
                        if (wifiMarker == null) {
                            wifiMarker = mMap.addMarker(new MarkerOptions()
                                    .position(wifiPos)
                                    .title("WiFi Position")
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
                        } else {
                            wifiMarker.setPosition(wifiPos);
                        }
                        if (immediate) {
                            mMap.animateCamera(CameraUpdateFactory.newLatLng(wifiPos));
                        }
                        // Convert WiFi location (lat, lon) to ENU
                        double[] enuWiFi = CoordinateTransform.geodeticToEnu(
                                wifiPos.latitude,
                                wifiPos.longitude,
                                0,  // WiFi is generally not with height, temporary transmission 0
                                refPosition[0],
                                refPosition[1],
                                refPosition[2]
                        );

                        // Observations are updated with the current PDR status
                        double[] pdrEnu = ekf.getEnuPosition();
                        ekf.onObservationUpdate(
                                enuWiFi[0], enuWiFi[1],   // WiFi East, North
                                pdrEnu[0], pdrEnu[1],     // PDR East, North
                                0, 1.0);                 // altitude=0, penaltyFactor=1.0
                    }
                }
                break;
        }

        // Determine indoor/outdoor, using WiFi/GNSS respectively
        if (indoorMapManager != null && currentLocation != null) {
            if (BuildingPolygon.inLibrary(currentLocation) || BuildingPolygon.inNucleus(currentLocation)) {
                ekf.setUsingWifi(true);
            } else {
                ekf.setUsingWifi(false);
            }
        }

        // Update the coordinates after EKF fusion: turn them back to latitude and longitude
        double fusedAlt = currentAltitude; //
        LatLng fusedLatLng = ekf.getCurrentLatLng(fusedAlt);
        if (fusedLatLng != null) {
            // update fusedPolyline
            List<LatLng> points = fusedPolyline.getPoints();
            points.add(fusedLatLng);
            fusedPolyline.setPoints(points);

            // update fusedMarker
            if (fusedMarker == null) {
                fusedMarker = mMap.addMarker(new MarkerOptions()
                        .position(fusedLatLng)
                        .title("Fused Position")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_MAGENTA)));
            } else {
                fusedMarker.setPosition(fusedLatLng);
            }

            currentLocation = fusedLatLng; // As a "current location" on the map
            if (immediate) {
                mMap.animateCamera(CameraUpdateFactory.newLatLng(fusedLatLng));
            }
            // Notify IndoorMapManager to update the indoor layer
            indoorMapManager.setCurrentLocation(fusedLatLng);
        }
    }

    /**
     * Process a single Event and update the status of PF.
     * * Rules:
     * * - For PDR events: Calculate the step size and orientation, and do motion updates with predictMotion()
     * * - For GNSS events (outdoor) : Update PF (measurementUpdate) with GNSS measurements
     * * - For WiFi events (indoors) : Update PF (measurementUpdate) with WiFi measurements
     */
    private void processPFEvent(Event e, boolean immediate) {
        if (pf == null || !hasInitRef) return;

        switch (e.eventType) {
            case 1: // PDR event
                if (e.pdr != null) {
                    float currX = e.pdr.getX();
                    float currY = e.pdr.getY();
                    float stepX, stepY;
                    if (!firstPfPdrArrived) {
                        stepX = 0f;
                        stepY = 0f;
                        firstPfPdrArrived = true;
                    } else {
                        stepX = currX - lastPfPdrX;
                        stepY = currY - lastPfPdrY;
                    }
                    lastPfPdrX = currX;
                    lastPfPdrY = currY;
                    double stepLength = Math.hypot(stepX, stepY);
                    double heading = Math.atan2(stepY, stepX);
                    pf.predictMotion(stepLength, heading);
                }
                break;
            case 0: // GNSS event (outdoor) : Measurement updates are performed
                if (e.gnss != null) {
                    if (currentLocation != null &&
                            !(BuildingPolygon.inLibrary(currentLocation) || BuildingPolygon.inNucleus(currentLocation))) {
                        double lat = e.gnss.getLatitude();
                        double lon = e.gnss.getLongitude();
                        pf.measurementUpdate(lat, lon);
                    }
                }
                break;
            case 3: // WiFi event (indoor) : Measurement update
                if (e.wifi != null) {
                    long ts = e.wifi.getRelativeTimestamp();
                    LatLng wifiPos = wifiPositionCache.get(ts);
                    if (wifiPos != null) {
                        if (currentLocation != null &&
                                (BuildingPolygon.inLibrary(currentLocation) || BuildingPolygon.inNucleus(currentLocation))) {
                            pf.measurementUpdate(wifiPos.latitude, wifiPos.longitude);
                        }
                    }
                }
                break;
            // Other event types are not processed
        }

        // Updated the position display after PF fusion
        LatLng pfFusedPos = pf.getFusedPosition();
        if (pfFusedPos != null) {
            List<LatLng> points = pfPolyline.getPoints();
            points.add(pfFusedPos);
            pfPolyline.setPoints(points);
            if (pfMarker == null) {
                pfMarker = mMap.addMarker(new MarkerOptions()
                        .position(pfFusedPos)
                        .title("PF Position")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)));
            } else {
                pfMarker.setPosition(pfFusedPos);
            }
            if (immediate) {
                mMap.animateCamera(CameraUpdateFactory.newLatLng(pfFusedPos));
            }
        }
    }
}
