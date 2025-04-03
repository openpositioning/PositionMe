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
 * ReplayFragment: Implements replay of GNSS, PDR, WiFi, and fused (EKF) trajectories.
 * In Replay mode, uses relative_timestamp from Traj as time intervals and feeds data
 * to EKF for state fusion. Fusion results are returned to ReplayFragment, which
 * determines whether to use WiFi or GNSS data for correction based on current location.
 *
 * ★Added: Without modifying existing code, adds PF (ParticleFilter) trajectory fusion and display.
 *     PF fusion strategy: Fuses PDR and WiFi data indoors, PDR and GNSS data outdoors.
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

    // In ReplayFragment class, add two member variables for PDR to record last Pdr_Sample's x, y
    private float lastPdrX = 0f;
    private float lastPdrY = 0f;
    private boolean firstPdrArrived = false;

    // ★【Added】PF related member variables
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

    // Floor control parameters (keep original logic)
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

    // Merged event queue
    private List<Event> mergedEvents = new ArrayList<>();
    private int currentEventIndex = 0;

    private WiFiPositioning wiFiPositioning;
    private Map<Long, LatLng> wifiPositionCache = new HashMap<>();
    private boolean wifiPositionRequestsComplete = false;

    private static final String WIFI_FINGERPRINT = "wf";

    // EKF instance
    private ExtendedKalmanFilter ekf;

    // ★Modified: Record initial GNSS reference position (lat, lon, alt) to convert PDR, GNSS, WiFi to ENU
    private double[] refPosition = null;
    private double[] refEcef = null;
    private boolean hasInitRef = false;

    // For managing different types of trajectory events
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
        turnDetector.startMonitoring(); // Ensure monitoring direction changes

        // Receive passed file path
        if (getArguments() != null) {
            filePath = getArguments().getString("trajectory_file_path");
        }
        if (filePath == null) {
            Toast.makeText(getContext(), "No trajectory file provided", Toast.LENGTH_SHORT).show();
            return;
        }

        // Read Traj data
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

            // Preprocess WiFi data: Asynchronous queries, cache results to wifiPositionCache
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

        // ★Modified: If GNSS data exists, set first GNSS point as reference
        if (gnssPositions != null && !gnssPositions.isEmpty()) {
            Traj.GNSS_Sample firstGnss = gnssPositions.get(0);
            refPosition = new double[]{firstGnss.getLatitude(), firstGnss.getLongitude(), firstGnss.getAltitude()};
            refEcef = CoordinateTransform.geodeticToEcef(
                    refPosition[0], refPosition[1], refPosition[2]);
            ekf.setInitialReference(refPosition, refEcef);
            // Also set EKF's start time to first event's relativeTime (if exists)
            if (!mergedEvents.isEmpty()) {
                ekf.setInitialTime(mergedEvents.get(0).relativeTime);
            }
            hasInitRef = true;
        }

        // ★【Added】Initialize PF when reference point exists
        if (refPosition != null) {
            pf = new ParticleFilter(refPosition);
        }

        // Button and progress bar initialization
        btnPlayPause.setOnClickListener(v -> {
            if (isPlaying) {
                pauseReplay();
            } else {
                // If already started but not finished, continue; otherwise start from beginning
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
     * Asynchronously process WiFi samples to get (LatLng) and store in wifiPositionCache.
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
     * Draw full WiFi track on map (optional, for easy viewing).
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
     * Merge GNSS, PDR, Pressure, WiFi events into one List, sorted by relative time.
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
     * Handle progress bar drag: Jump to eventIndex-th event, then replay.
     */
    private void updateMarkersForEventIndex(int eventIndex) {
        if (eventIndex < 0 || eventIndex >= mergedEvents.size()) return;
        if (mMap == null || indoorMapManager == null) return;
        if (!hasInitRef) return; // Without GNSS reference point, cannot perform ENU conversion

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

        // Set EKF reference again
        ekf.setInitialReference(refPosition, refEcef);
        if (!mergedEvents.isEmpty()) {
            ekf.setInitialTime(mergedEvents.get(0).relativeTime);
        }
        // Reset PDR accumulated values
        lastPdrX = 0f;
        lastPdrY = 0f;
        firstPdrArrived = false;

        // ★【Added】Reset PF accumulated PDR state
        lastPfPdrX = 0f;
        lastPfPdrY = 0f;
        firstPfPdrArrived = false;

        // Sequentially process events [0 ... eventIndex], last state is what we want
        for (int i = 0; i <= eventIndex; i++) {
            processEvent(mergedEvents.get(i), false);
            // ★【Added】Synchronously process PF events
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

        // Display GNSS trajectory (blue)
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

        // Display PDR trajectory (red) - Note: Just approximate drawing here
        if (pdrPositions != null && !pdrPositions.isEmpty()) {
            // If GNSS data exists, use first GNSS point as PDR start point
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

        // ★【Added】Initialize PF trajectory display, using orange
        pfPolyline = mMap.addPolyline(new PolylineOptions().color(Color.rgb(255, 165, 0)).width(10));
    }

    // Playback controls
    private void startReplay() {
        if (mergedEvents.isEmpty()) return;
        isPlaying = true;
        btnPlayPause.setText("Pause");
        currentEventIndex = 0;
        lastPdrX = 0f;
        lastPdrY = 0f;
        firstPdrArrived = false;
        // ★【Added】Reset PF accumulated state
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

        // Reset EKF and PDR state (clear trajectory display)
        updateMarkersForEventIndex(0);
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentFloor(0, true);
        }

        // Clear EKF fused trajectory: Clear fusedPolyline points and remove fusedMarker
        if (fusedPolyline != null) {
            fusedPolyline.setPoints(new ArrayList<>());
        }
        if (fusedMarker != null) {
            fusedMarker.remove();
            fusedMarker = null;
        }
        // Reset EKF object (call reset())
        ekf.reset();

        // Reset PF state: Reconstruct PF and clear PF trajectory display
        if (refPosition != null) {
            pf = new ParticleFilter(refPosition);
        }
        if (pfPolyline != null) {
            pfPolyline.setPoints(new ArrayList<>());
        }
        if (pfMarker != null) {
            pfMarker.remove();
            pfMarker = null;
        }

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
     * Schedule next event, calculate delay based on relative_timestamp difference between adjacent events.
     * In Replay mode, uses relativeTime to simulate original time rhythm.
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
        // ★【Added】Synchronously update PF trajectory
        processPFEvent(current, true);
        currentEventIndex++;
        progressBar.setProgress(currentEventIndex);
        playbackHandler.postDelayed(() -> scheduleNext(), delayMs);
    }

    /**
     * Core: Process single Event and feed to EKF.
     * @param e Corresponding event
     * @param immediate Whether to immediately update map camera
     */
    private void processEvent(Event e, boolean immediate) {
        if (!hasInitRef) return; // Without GNSS reference point, cannot perform ENU conversion

        double currentAltitude = 0;
        //double[] pdrEnu = ekf.getEnuPosition(); // Get current EKF estimated state (east, north)

        switch (e.eventType) {
            case 0: // GNSS
                if (e.gnss != null) {
                    Traj.GNSS_Sample sample = e.gnss;
                    double lat = sample.getLatitude();
                    double lon = sample.getLongitude();
                    double alt = sample.getAltitude();
                    currentAltitude = alt;

                    // Update GNSS Marker on map
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

                    // ★Modified: Convert GNSS (lat,lon,alt) to ENU
                    double[] enuGnss = CoordinateTransform.geodeticToEnu(
                            lat, lon, alt,
                            refPosition[0], // GNSS reference point lat
                            refPosition[1], // GNSS reference point lon
                            refPosition[2]  // GNSS reference point alt
                    );

                    // ④ Get current predicted PDR (east, north) from EKF for fusion
                    double[] pdrEnu = ekf.getEnuPosition();

                    // ⑤ ★ Added: Call EKF's onObservationUpdate() method, use GNSS to correct
                    //    Here observeEast, observeNorth = enuGnss
                    //    pdrEast, pdrNorth = pdrEnu
                    //    penaltyFactor initially set to 1.0
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
                        // First PDR sample, cannot difference with previous, set increment to 0
                        stepX = 0f;
                        stepY = 0f;
                        firstPdrArrived = true;
                    } else {
                        // Difference with previous
                        stepX = currX - lastPdrX;
                        stepY = currY - lastPdrY;
                    }

                    // Record current accumulated values for next use
                    lastPdrX = currX;
                    lastPdrY = currY;

                    // Treat differential stepX, stepY as "this small step's ENU displacement"
                    double stepLength = Math.hypot(stepX, stepY);
                    double heading = Math.atan2(stepY, stepX);
                    // heading: North as π/2, East as 0 or 2π, used by EKF internally for wrap/correction

                    // EKF predict
                    ekf.predict(heading, stepLength, stepLength, e.relativeTime,
                            turnDetector.onStepDetected(heading));

                    // Visualization: Convert current PDR's absolute accumulated position (currX, currY) to LatLng
                    if (hasInitRef) {
                        double[] ecefRef = ekf.getEcefRefCoords();
                        LatLng pdrLatLng = CoordinateTransform.enuToGeodetic(
                                currX, currY, 0,
                                refPosition[0], refPosition[1],
                                ecefRef
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
                    // Just example, not much operation here
                    double pressureVal = e.pressure.getPressure();
                    currentAltitude = pressureVal; // Or convert to altitude with formula
                    // Can additionally do floor judgment
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
                        // ★Modified: Convert WiFi position (lat, lon) to ENU
                        double[] enuWiFi = CoordinateTransform.geodeticToEnu(
                                wifiPos.latitude,
                                wifiPos.longitude,
                                0,  // WiFi usually doesn't have height, temporarily pass 0
                                refPosition[0],
                                refPosition[1],
                                refPosition[2]
                        );

                        // ④ Do observation update with current PDR state
                        double[] pdrEnu = ekf.getEnuPosition();
                        ekf.onObservationUpdate(
                                enuWiFi[0], enuWiFi[1],   // WiFi East, North
                                pdrEnu[0], pdrEnu[1],     // PDR East, North
                                0, 1.0);                 // altitude=0, penaltyFactor=1.0
                    }
                }
                break;
        }

        // Determine indoor/outdoor, use WiFi/GNSS respectively
        if (indoorMapManager != null && currentLocation != null) {
            if (BuildingPolygon.inLibrary(currentLocation) || BuildingPolygon.inNucleus(currentLocation)) {
                ekf.setUsingWifi(true);
            } else {
                ekf.setUsingWifi(false);
            }
        }

        // Update EKF fused coordinates: Convert back to lat/lon
        double fusedAlt = currentAltitude; // Can simplify to GNSS or pressure's altitude
        LatLng fusedLatLng = ekf.getCurrentLatLng(fusedAlt);
        if (fusedLatLng != null) {
            // Update fusedPolyline
            List<LatLng> points = fusedPolyline.getPoints();
            points.add(fusedLatLng);
            fusedPolyline.setPoints(points);

            // Update fusedMarker
            if (fusedMarker == null) {
                fusedMarker = mMap.addMarker(new MarkerOptions()
                        .position(fusedLatLng)
                        .title("Fused Position")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_MAGENTA)));
            } else {
                fusedMarker.setPosition(fusedLatLng);
            }

            currentLocation = fusedLatLng; // As "current latest position" on map
            if (immediate) {
                mMap.animateCamera(CameraUpdateFactory.newLatLng(fusedLatLng));
            }
            // Notify IndoorMapManager to update indoor layer
            indoorMapManager.setCurrentLocation(fusedLatLng);
        }
    }

    /**
     * ★【Added method】Process single Event, update PF state.
     * Rules:
     * - For PDR events: Calculate step length and heading, use predictMotion() for motion update
     * - For GNSS events (outdoor): Use GNSS measurement to update PF (measurementUpdate)
     * - For WiFi events (indoor): Use WiFi measurement to update PF (measurementUpdate)
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
            case 0: // GNSS event (outdoor): Do measurement update
                if (e.gnss != null) {
                    if (currentLocation != null &&
                            !(BuildingPolygon.inLibrary(currentLocation) || BuildingPolygon.inNucleus(currentLocation))) {
                        double lat = e.gnss.getLatitude();
                        double lon = e.gnss.getLongitude();
                        pf.measurementUpdate(lat, lon);
                    }
                }
                break;
            case 3: // WiFi event (indoor): Do measurement update
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
            // Other event types not processed
        }

        // Update PF fused position display
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