package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.BuildingPolygon;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.KFLinear2D;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * FusionFragment that fuses positioning and indoor floor map switching functionality:
 * 1. Uses a Kalman filter to fuse WiFi and GNSS data, and updates the position using incremental PDR.
 * 2. Applies exponential smoothing to the fused optimal position to ensure smooth display.
 * 3. Records the most recent MAX_OBSERVATIONS absolute positioning data from GNSS, WiFi, and PDR,
 *    displaying them using markers in different colors.
 * 4. Displays the complete trajectory of the fused position (red polyline) and calls IndoorMapManager to update floor information,
 *    ensuring that the floor map remains intact (without calling mMap.clear() to remove floor overlays).
 * 5. Adds an "Add tag" button to allow users to tag the current position into the Trajectory.
 * 6. Calculates and displays positioning accuracy (based on the KF covariance matrix).
 * 7. Determines and displays indoor/outdoor status based on whether the fused position falls within a building.
 */
public class FusionFragment extends Fragment implements OnMapReadyCallback {

    private static final String TAG = "FusionFragment";

    private GoogleMap mMap;
    private Marker fusionMarker;
    private Polyline fusionPolyline;
    private List<LatLng> fusionPath = new ArrayList<>();

    // Stores the most recent N observations
    private List<LatLng> gnssObservations = new ArrayList<>();
    private List<LatLng> wifiObservations = new ArrayList<>();
    private List<LatLng> pdrObservations = new ArrayList<>();
    private static final int MAX_OBSERVATIONS = 10;

    // Used to store references to each sensor's Marker for easy removal
    private List<Marker> gnssMarkers = new ArrayList<>();
    private List<Marker> wifiMarkers = new ArrayList<>();
    private List<Marker> pdrMarkers = new ArrayList<>();

    private Handler updateHandler = new Handler(Looper.getMainLooper());
    private Runnable fusionUpdateRunnable;
    private static final long UPDATE_INTERVAL_MS = 1000; // Update every second

    private SensorFusion sensorFusion;
    private IndoorMapManager indoorMapManager;
    private Spinner mapSpinner;

    // New TextViews for displaying positioning accuracy and indoor/outdoor status
    private TextView accuracyTextView;
    private TextView indoorOutdoorTextView;

    // Kalman filter KF
    private KFLinear2D kf;
    private long lastUpdateTime = 0;

    // Local coordinate conversion origin (lat/lon), used to convert lat/lon to local (x,y) coordinates in meters
    private double lat0Deg = 0.0;
    private double lon0Deg = 0.0;
    private boolean originSet = false;

    // Stores the last PDR data (used to calculate increments)
    private float[] lastPdr = null;

    // Noise parameters
    private final double[][] R_wifi = { {20.0, 0.0}, {0.0, 20.0} };
    private final double[][] R_gnss = { {100.0, 0.0}, {0.0, 100.0} };
    private final double[][] Q = {
            {0.1, 0,   0,   0},
            {0,   0.1, 0,   0},
            {0,   0,   0.1, 0},
            {0,   0,   0,   0.1}
    };

    // Exponential smoothing parameter (range [0,1], lower values yield stronger smoothing)
    private static final double SMOOTHING_ALPHA = 0.2;
    private LatLng smoothFusedPosition = null;

    // Used to record the start time of recording
    private long startTimestamp;

    public FusionFragment() {
        // Required empty constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startTimestamp = System.currentTimeMillis();
        // Initialize sensorFusion so we can get the initial sensor readings.
        sensorFusion = SensorFusion.getInstance();
        pdrObservations.clear();

        // Retrieve WiFi and GNSS positions.
        LatLng wifiPos = sensorFusion.getLatLngWifiPositioning();
        float[] gnssArr = sensorFusion.getGNSSLatitude(false);

        if (wifiPos != null && gnssArr != null && gnssArr.length >= 2 && !(gnssArr[0] == 0 && gnssArr[1] == 0)) {
            // Calculate the average latitude and longitude.
            double avgLat = (wifiPos.latitude + gnssArr[0]) / 2.0;
            double avgLon = (wifiPos.longitude + gnssArr[1]) / 2.0;

            // Use wifiPos as the local origin for converting to local coordinates.
            double metersPerLat = 111320.0;
            double cosLat = Math.cos(Math.toRadians(wifiPos.latitude));
            double metersPerLon = 111320.0 * cosLat;
            double localX = (avgLon - wifiPos.longitude) * metersPerLon;
            double localY = (avgLat - wifiPos.latitude) * metersPerLat;

            // Set the PDR starting position (local coordinates) to the computed average.
            lastPdr = new float[]{(float) localX, (float) localY};
        } else {
            lastPdr = null;
        }
        sensorFusion.resetPdrStartingPosition();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Ensure that fragment_fusion.xml contains controls with IDs: addTagButton_fusion, accuracyTextView_fusion, and indoorOutdoorTextView_fusion
        return inflater.inflate(R.layout.fragment_fusion, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        sensorFusion = SensorFusion.getInstance();

        view.findViewById(R.id.exitButton_fusion).setOnClickListener(v ->
                Navigation.findNavController(v).popBackStack(R.id.homeFragment, false));

        // Floor button click events
        view.findViewById(R.id.floorUpButton_fusion).setOnClickListener(v -> {
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
                if (smoothFusedPosition != null) {
                    updateFloor(smoothFusedPosition);
                }
            }
        });
        view.findViewById(R.id.floorDownButton_fusion).setOnClickListener(v -> {
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
                if (smoothFusedPosition != null) {
                    updateFloor(smoothFusedPosition);
                }
            }
        });

        mapSpinner = view.findViewById(R.id.mapSwitchSpinner_fusion);
        setupMapSpinner();

        // Initialize the new controls: the Add Tag button and two TextViews
        Button addTagButton = view.findViewById(R.id.addTagButton_fusion);
        accuracyTextView = view.findViewById(R.id.accuracyTextView_fusion);
        indoorOutdoorTextView = view.findViewById(R.id.indoorOutdoorTextView_fusion);

        addTagButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long relativeTimestamp = System.currentTimeMillis() - startTimestamp;
                if (smoothFusedPosition != null) {
                    double lat = smoothFusedPosition.latitude;
                    double lon = smoothFusedPosition.longitude;
                    float altitude = sensorFusion.getElevation();
                    // Call the new addFusionTag() method in SensorFusion to write to the Trajectory
                    sensorFusion.addFusionTag(relativeTimestamp, lat, lon, altitude, "fusion");
                    Log.d(TAG, "Add tag: timestamp=" + relativeTimestamp + ", lat=" + lat + ", lon=" + lon + ", alt=" + altitude);
                }
            }
        });

        fusionUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateFusionUI();
                updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
            }
        };

        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.fusionMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        } else {
            Log.e(TAG, "Map fragment is null");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        lastUpdateTime = System.currentTimeMillis();
        // Reset the PDR starting position each time the fragment resumes
        resetPdrStartingPosition();
        updateHandler.postDelayed(fusionUpdateRunnable, UPDATE_INTERVAL_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        updateHandler.removeCallbacks(fusionUpdateRunnable);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        this.mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        mMap.getUiSettings().setCompassEnabled(true);
        indoorMapManager = new IndoorMapManager(mMap);
        setupMapSpinner();
        // Once the map is ready, IndoorMapManager will automatically add floor overlays based on the current position.
    }

    private void updateFusionUI() {
        if (!originSet) {
            if (initLocalOriginIfPossible()) {
                initKalmanFilter();
            } else {
                return;
            }
        }
        if (kf == null) return;

        long now = System.currentTimeMillis();
        double dt = (now - lastUpdateTime) / 1000.0;
        lastUpdateTime = now;
        if (dt <= 0) dt = 0.001;

        // 1) Apply PDR increment
        applyPdrIncrement();

        // 2) Kalman Filter prediction
        kf.predict(dt);

        // 3) Fuse WiFi data (with larger noise)
        LatLng wifiLatLon = sensorFusion.getLatLngWifiPositioning();
        if (wifiLatLon != null) {
            double[] localWifi = latLonToLocal(wifiLatLon.latitude, wifiLatLon.longitude);
            kf.setMeasurementNoise(R_wifi);
            kf.update(localWifi);
            Log.d(TAG, "KF updated with WiFi: " + wifiLatLon);
            addObservation(wifiObservations, wifiLatLon);
        }

        // 4) Fuse GNSS data (with smaller noise)
        float[] gnssArr = sensorFusion.getGNSSLatitude(false);
        if (gnssArr != null && gnssArr.length >= 2) {
            float latGNSS = gnssArr[0];
            float lonGNSS = gnssArr[1];
            if (!(latGNSS == 0 && lonGNSS == 0)) {
                double[] localGnss = latLonToLocal(latGNSS, lonGNSS);
                kf.setMeasurementNoise(R_gnss);
                kf.update(localGnss);
                LatLng gnssLatLng = new LatLng(latGNSS, lonGNSS);
                Log.d(TAG, "KF updated with GNSS: " + gnssLatLng);
                addObservation(gnssObservations, gnssLatLng);
            }
        }

        // 5) Get KF fused output and convert it to latitude/longitude
        double[] xy = kf.getXY();
        double[] latlon = localToLatLon(xy[0], xy[1]);
        LatLng fusedLatLng = new LatLng(latlon[0], latlon[1]);

        // 6) Apply exponential smoothing to the fused result
        if (smoothFusedPosition == null) {
            smoothFusedPosition = fusedLatLng;
        } else {
            double smoothLat = SMOOTHING_ALPHA * fusedLatLng.latitude + (1 - SMOOTHING_ALPHA) * smoothFusedPosition.latitude;
            double smoothLon = SMOOTHING_ALPHA * fusedLatLng.longitude + (1 - SMOOTHING_ALPHA) * smoothFusedPosition.longitude;
            smoothFusedPosition = new LatLng(smoothLat, smoothLon);
        }
        fusionPath.add(smoothFusedPosition);

        // 7) Process PDR data (convert to absolute coordinates)
        float[] pdrData = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrData != null && pdrData.length >= 2) {
            LatLng pdrLatLng = convertLocalToLatLon(pdrData[0], pdrData[1]);
            addObservation(pdrObservations, pdrLatLng);
        }

        // 8) Clear existing Markers and Polyline (without calling mMap.clear() to preserve floor overlays)
        if (fusionMarker != null) {
            fusionMarker.remove();
            fusionMarker = null;
        }
        if (fusionPolyline != null) {
            fusionPolyline.remove();
            fusionPolyline = null;
        }
        // Also clear markers for other sensors (to avoid accumulation)
        for (Marker m : gnssMarkers) { m.remove(); }
        gnssMarkers.clear();
        for (Marker m : wifiMarkers) { m.remove(); }
        wifiMarkers.clear();
        for (Marker m : pdrMarkers) { m.remove(); }
        pdrMarkers.clear();

        // 9) Draw fused position Marker and complete trajectory (red)
        if (smoothFusedPosition != null) {
            fusionMarker = mMap.addMarker(new MarkerOptions()
                    .position(smoothFusedPosition)
                    .title("Fused Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    // Set zIndex to 2000 so that it is above other markers
                    .zIndex(2000));
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(smoothFusedPosition, 19f));
            if (fusionPath.size() > 1) {
                fusionPolyline = mMap.addPolyline(new PolylineOptions()
                        .addAll(fusionPath)
                        .color(android.graphics.Color.RED)
                        .width(10)
                        .zIndex(1000));
            }
        }

        // 10) Draw GNSS markers (blue)
        for (LatLng pos : gnssObservations) {
            Marker marker = mMap.addMarker(new MarkerOptions()
                    .position(pos)
                    .title("GNSS")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    .zIndex(1000));
            gnssMarkers.add(marker);
        }
        // 11) Draw WiFi markers (green)
        for (LatLng pos : wifiObservations) {
            Marker marker = mMap.addMarker(new MarkerOptions()
                    .position(pos)
                    .title("WiFi")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    .zIndex(1000));
            wifiMarkers.add(marker);
        }
        // 12) Draw PDR markers (orange)
        for (LatLng pos : pdrObservations) {
            Marker marker = mMap.addMarker(new MarkerOptions()
                    .position(pos)
                    .title("PDR")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                    .zIndex(1000));
            pdrMarkers.add(marker);
        }

        // 13) Calculate positioning accuracy and update display (using the KF covariance matrix)
        double[][] P = kf.getErrorCovariance(); // New method that returns the covariance matrix P
        double stdX = Math.sqrt(P[0][0]);
        double stdY = Math.sqrt(P[1][1]);
        double accuracy = (stdX + stdY) / 2.0;
        if (accuracyTextView != null) {
            accuracyTextView.setText(String.format("Accuracy: %.1f m", accuracy));
        }

        // 14) Determine indoor/outdoor status (based on whether the fused position falls within a building)
        if (indoorOutdoorTextView != null) {
            if (BuildingPolygon.inNucleus(smoothFusedPosition) || BuildingPolygon.inLibrary(smoothFusedPosition)) {
                indoorOutdoorTextView.setText("Indoor");
            } else {
                indoorOutdoorTextView.setText("Outdoor");
            }
        }

        // 15) Update floor display (update floor information in IndoorMapManager; floor map is controlled by IndoorMapManager without calling mMap.clear())
        updateFloor(smoothFusedPosition);
    }

    private boolean initLocalOriginIfPossible() {
        if (originSet) return true;
        LatLng wifi = sensorFusion.getLatLngWifiPositioning();
        if (wifi != null) {
            lat0Deg = wifi.latitude;
            lon0Deg = wifi.longitude;
            originSet = true;
            Log.d(TAG, "Local origin set from WiFi: " + wifi);
            return true;
        }
        float[] gnssArr = sensorFusion.getGNSSLatitude(false);
        if (gnssArr != null && gnssArr.length >= 2 && !(gnssArr[0] == 0 && gnssArr[1] == 0)) {
            lat0Deg = gnssArr[0];
            lon0Deg = gnssArr[1];
            originSet = true;
            Log.d(TAG, "Local origin set from GNSS: " + lat0Deg + ", " + lon0Deg);
            return true;
        }
        return false;
    }

    private void initKalmanFilter() {
        if (kf != null) return;
        double[] initialState = {0, 0, 0, 0};
        double[][] initialCov = {
                {10, 0, 0, 0},
                {0, 10, 0, 0},
                {0, 0, 10, 0},
                {0, 0, 0, 10}
        };
        kf = new KFLinear2D(initialState, initialCov, Q, R_wifi);
        Log.d(TAG, "KF built with local origin lat0=" + lat0Deg + ", lon0=" + lon0Deg);
    }

    private double[] latLonToLocal(double latDeg, double lonDeg) {
        double metersPerLat = 111320.0;
        double y = (latDeg - lat0Deg) * metersPerLat;
        double cosLat = Math.cos(Math.toRadians(lat0Deg));
        double metersPerLon = 111320.0 * cosLat;
        double x = (lonDeg - lon0Deg) * metersPerLon;
        return new double[]{x, y};
    }

    private double[] localToLatLon(double x, double y) {
        double metersPerLat = 111320.0;
        double latDiff = y / metersPerLat;
        double latDeg = lat0Deg + latDiff;
        double cosLat = Math.cos(Math.toRadians(lat0Deg));
        double metersPerLon = 111320.0 * cosLat;
        double lonDiff = x / metersPerLon;
        double lonDeg = lon0Deg + lonDiff;
        return new double[]{latDeg, lonDeg};
    }

    private void applyPdrIncrement() {
        float[] pdrNow = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrNow == null) return;
        if (!originSet) return;  // or if KF is not set

        if (lastPdr == null) {
            lastPdr = pdrNow.clone();
            return;
        }

        float rawDx = pdrNow[0] - lastPdr[0]; // "forward/back" from phone perspective
        float rawDy = pdrNow[1] - lastPdr[1]; // "side-to-side"
        lastPdr[0] = pdrNow[0];
        lastPdr[1] = pdrNow[1];

        // Try negating rawDy:
        // If "rawDx" is forward => +Y, then "rawDy" is left/right => -X (or +X).
        // We'll do dx = -rawDy, dy = rawDx, so forward translates to upward (north).
        float dx = -rawDy;
        float dy = rawDx;

        kf.applyPdrDelta(dx, dy);
    }

    private LatLng convertLocalToLatLon(float x, float y) {
        double[] latlon = localToLatLon(x, y);
        return new LatLng(latlon[0], latlon[1]);
    }

    private void addObservation(List<LatLng> list, LatLng pos) {
        list.add(pos);
        if (list.size() > MAX_OBSERVATIONS) {
            list.remove(0);
        }
    }

    private void updateFloor(LatLng fusedLatLng) {
        if (indoorMapManager == null) return;
        indoorMapManager.setCurrentLocation(fusedLatLng);
        float elevationVal = sensorFusion.getElevation();
        int wifiFloor = sensorFusion.getWifiFloor();
        int elevFloor = (int) Math.round(elevationVal / indoorMapManager.getFloorHeight());
        int avgFloor = Math.round((wifiFloor + elevFloor) / 2.0f);
        indoorMapManager.setCurrentFloor(avgFloor, true);
        indoorMapManager.setIndicationOfIndoorMap();
    }

    private void setupMapSpinner() {
        String[] maps = new String[]{"Hybrid", "Normal", "Satellite"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, maps);
        mapSpinner.setAdapter(adapter);
        mapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mMap == null) return;
                switch (position) {
                    case 0:
                        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                    case 1:
                        mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                        break;
                    case 2:
                        mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                        break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    /**
     * Resets the PDR starting position based on the average of the current GNSS and WiFi positions.
     * This method is called each time the fragment resumes.
     */
    private void resetPdrStartingPosition() {
        // Retrieve WiFi and GNSS positions
        LatLng wifiPos = sensorFusion.getLatLngWifiPositioning();
        float[] gnssArr = sensorFusion.getGNSSLatitude(false);

        if (wifiPos != null && gnssArr != null && gnssArr.length >= 2 && !(gnssArr[0] == 0 && gnssArr[1] == 0)) {
            // Compute the average of the two positions
            double avgLat = (wifiPos.latitude + gnssArr[0]) / 2.0;
            double avgLon = (wifiPos.longitude + gnssArr[1]) / 2.0;

            // Use wifiPos as the reference for local coordinate conversion.
            double metersPerLat = 111320.0;
            double cosLat = Math.cos(Math.toRadians(wifiPos.latitude));
            double metersPerLon = 111320.0 * cosLat;
            double localX = (avgLon - wifiPos.longitude) * metersPerLon;
            double localY = (avgLat - wifiPos.latitude) * metersPerLat;

            // Set the PDR starting position (in local coordinates)
            lastPdr = new float[]{(float) localX, (float) localY};
        } else {
            lastPdr = null;
        }
        pdrObservations.clear();
        sensorFusion.resetPdrStartingPosition();
    }
}
