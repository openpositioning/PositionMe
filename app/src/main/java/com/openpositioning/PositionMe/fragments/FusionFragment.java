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
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.KFLinear2D;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Demonstrates a local-meters Kalman Filter:
 *  1) We set an origin lat0, lon0 from the first measurement.
 *  2) Each measurement (Wi-Fi or GNSS) is converted to local X,Y in meters,
 *  3) We do kf.update([ xm, ym ]),
 *  4) We apply PDR as part of the predict or a separate "applyPdrDelta".
 *  5) We only convert final (x,y) -> lat/lon for display on the map.
 */
public class FusionFragment extends Fragment implements OnMapReadyCallback {

    private static final String TAG = "FusionFragment";

    private GoogleMap mMap;
    private Marker fusionMarker;
    private Polyline fusionPolyline;
    private List<LatLng> fusionPath = new ArrayList<>();

    private Handler updateHandler = new Handler(Looper.getMainLooper());
    private Runnable fusionUpdateRunnable;
    private static final long UPDATE_INTERVAL_MS = 200; // 5 Hz

    private SensorFusion sensorFusion;
    private IndoorMapManager indoorMapManager;
    private Spinner mapSpinner;

    private KFLinear2D kf; // our local-meters Kalman Filter
    private long lastUpdateTime = 0;

    // local origin lat/lon for the "meter" transform
    private double lat0Deg = 0.0;
    private double lon0Deg = 0.0;
    private boolean originSet = false;

    // We'll store the last PDR offset so we can do incremental displacement
    private float[] lastPdr = null;

    // Some typical noise values: you can tune them
    private final double[][] R_wifi = {
            {20.0, 0.0},
            {0.0, 20.0}
    };
    private final double[][] R_gnss = {
            {100.0, 0.0},
            {0.0, 100.0}
    };
    private final double[][] Q = {
            {0.1, 0,   0,   0},
            {0,   0.1, 0,   0},
            {0,   0,   0.1, 0},
            {0,   0,   0,   0.1}
    };

    public FusionFragment() {
        // required empty
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_fusion, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        sensorFusion = SensorFusion.getInstance();

        view.findViewById(R.id.exitButton_fusion).setOnClickListener(v ->
                Navigation.findNavController(v).popBackStack(R.id.homeFragment, false));

        // Floor up/down
        view.findViewById(R.id.floorUpButton_fusion).setOnClickListener(v -> {
            if (indoorMapManager != null) indoorMapManager.increaseFloor();
        });
        view.findViewById(R.id.floorDownButton_fusion).setOnClickListener(v -> {
            if (indoorMapManager != null) indoorMapManager.decreaseFloor();
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

        mapSpinner = getView().findViewById(R.id.mapSwitchSpinner_fusion);
        setupMapSpinner();
    }

    private void updateFusionUI() {
        if (!originSet) {
            // Attempt to init our local origin from any measurement
            if (initLocalOriginIfPossible()) {
                initKalmanFilter();
            } else {
                return; // still no measurement
            }
        }
        if (kf == null) return; // haven't built the KF yet

        long now = System.currentTimeMillis();
        double dt = (now - lastUpdateTime)/1000.0;
        lastUpdateTime = now;
        if (dt <= 0) dt = 0.001;

        // 1) apply PDR increment
        applyPdrIncrement();

        // 2) predict
        kf.predict(dt);

        // 3) fuse Wi-Fi
        LatLng wifiLatLon = sensorFusion.getLatLngWifiPositioning();
        if (wifiLatLon != null) {
            double[] localWifi = latLonToLocal(wifiLatLon.latitude, wifiLatLon.longitude);
            kf.setMeasurementNoise(R_wifi); // bigger noise
            kf.update(localWifi);
            Log.d(TAG, "KF updated with WiFi: " + wifiLatLon);
        }

        // 4) fuse GNSS
        float[] gnssArr = sensorFusion.getGNSSLatitude(false);
        if (gnssArr != null && gnssArr.length>=2) {
            float latGNSS = gnssArr[0];
            float lonGNSS = gnssArr[1];
            if (!(latGNSS==0 && lonGNSS==0)) {
                double[] localGnss = latLonToLocal(latGNSS, lonGNSS);
                kf.setMeasurementNoise(R_gnss); // smaller noise
                kf.update(localGnss);
                Log.d(TAG, "KF updated with GNSS: " + latGNSS + "," + lonGNSS);
            }
        }

        // 5) get final (x,y) from KF, convert to lat/lon for display
        double[] xy = kf.getXY();
        double[] latlon = localToLatLon(xy[0], xy[1]);
        LatLng fusedLatLng = new LatLng(latlon[0], latlon[1]);

        // update marker + polyline
        if (fusionMarker == null) {
            fusionMarker = mMap.addMarker(new MarkerOptions().position(fusedLatLng)
                    .title("Fused Position"));
            fusionPath.clear();
            fusionPath.add(fusedLatLng);
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(fusedLatLng, 19f));
        } else {
            fusionMarker.setPosition(fusedLatLng);
            fusionPath.add(fusedLatLng);
            if (fusionPolyline == null) {
                fusionPolyline = mMap.addPolyline(new PolylineOptions()
                        .addAll(fusionPath)
                        .color(android.graphics.Color.RED)
                        .width(10)
                        .zIndex(1000f));
            } else {
                fusionPolyline.setPoints(fusionPath);
            }
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(fusedLatLng, 19f));
        }

        // 6) floor
        updateFloor(fusedLatLng);
    }

    /**
     * If we have at least one measurement (WiFi or GNSS),
     * set lat0Deg/lon0Deg from it, so we can define local x=0,y=0
     */
    private boolean initLocalOriginIfPossible() {
        if (originSet) return true;
        // check WiFi
        LatLng wifi = sensorFusion.getLatLngWifiPositioning();
        if (wifi != null) {
            lat0Deg = wifi.latitude;
            lon0Deg = wifi.longitude;
            originSet = true;
            Log.d(TAG, "Local origin set from WiFi: " + wifi);
            return true;
        }
        // check GNSS
        float[] gnssArr = sensorFusion.getGNSSLatitude(false);
        if (gnssArr != null && gnssArr.length>=2) {
            if (!(gnssArr[0] == 0 && gnssArr[1] == 0)) {
                lat0Deg = gnssArr[0];
                lon0Deg = gnssArr[1];
                originSet = true;
                Log.d(TAG, "Local origin set from GNSS: " + lat0Deg + "," + lon0Deg);
                return true;
            }
        }
        return false;
    }

    private void initKalmanFilter() {
        if (kf != null) return;
        // initial state = [0,0,0,0], P=some big
        double[] initialState = {0,0,0,0};
        double[][] initialCov = {
                {10,0, 0, 0},
                {0, 10,0, 0},
                {0, 0, 10,0},
                {0, 0, 0, 10}
        };
        kf = new KFLinear2D(initialState, initialCov, Q, R_wifi);
        Log.d(TAG, "KF built with local origin lat0=" + lat0Deg + " lon0=" + lon0Deg);
    }

    /**
     * Convert lat,lon => local x,y in meters (east,north).
     * For small areas, we can do:
     *    x = (lon - lon0Deg)* metersPerLon
     *    y = (lat - lat0Deg)* metersPerLat
     * where metersPerLat ~111,320, metersPerLon ~111,320*cos(lat0).
     */
    private double[] latLonToLocal(double latDeg, double lonDeg) {
        double metersPerLat = 111320.0; // approximate
        double latDiff = latDeg - this.lat0Deg;
        double y = latDiff * metersPerLat;

        double avgLat = Math.toRadians((latDeg + this.lat0Deg)/2.0);
        // or just use lat0 if distances are small
        double cosLat = Math.cos(Math.toRadians(this.lat0Deg));
        double metersPerLon = 111320.0*cosLat;
        double lonDiff = lonDeg - this.lon0Deg;
        double x = lonDiff * metersPerLon;

        return new double[]{x, y};
    }

    /**
     * The inverse: local (x,y) => lat,lon
     */
    private double[] localToLatLon(double x, double y) {
        double metersPerLat = 111320.0;
        double latDiff = y / metersPerLat;
        double latDeg = this.lat0Deg + latDiff;

        double cosLat = Math.cos(Math.toRadians(this.lat0Deg));
        double metersPerLon = 111320.0*cosLat;
        double lonDiff = x / metersPerLon;
        double lonDeg = this.lon0Deg + lonDiff;

        return new double[]{ latDeg, lonDeg };
    }

    /**
     * Replaces your old applyPdrIncrement logic.
     * Here, rawDx is the phone's "forward" offset, rawDy is "sideways."
     * We make "forward" => +Y and "sideways" => +X.
     */
    private void applyPdrIncrement() {
        float[] pdrNow = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrNow == null) return;
        if (!originSet) return; // or !kf

        if (lastPdr == null) {
            lastPdr = pdrNow.clone();
            return;
        }

        float rawDx = pdrNow[0] - lastPdr[0];  // phone "forward/back"
        float rawDy = pdrNow[1] - lastPdr[1];  // phone "left/right"
        lastPdr[0] = pdrNow[0];
        lastPdr[1] = pdrNow[1];

        // Try this mapping:
        //   forward => +Y,
        //   right   => +X.
        // That means dy = rawDx, dx = rawDy.
        // We also often put a minus sign on dx or dy if the phone's orientation is reversed.
        // If you see it's reversed or 90 degrees off, switch them or add a minus.

        float dx = rawDy;
        float dy = rawDx;

        // If you STILL see "north => east," try one of:
        // float dx =  rawDy;  float dy = -rawDx;
        // float dx = -rawDy;  float dy =  rawDx;
        // etc.

        kf.applyPdrDelta(dx, dy);
    }


    private void updateFloor(LatLng fusedLatLng) {
        if (indoorMapManager == null) return;
        // pass the lat/lon to manager
        indoorMapManager.setCurrentLocation(fusedLatLng);

        float elevationVal = sensorFusion.getElevation();
        int wifiFloor = sensorFusion.getWifiFloor();
        int elevFloor = (int)Math.round(elevationVal / indoorMapManager.getFloorHeight());
        int avgFloor = Math.round((wifiFloor + elevFloor)/2.0f);

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
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
}
