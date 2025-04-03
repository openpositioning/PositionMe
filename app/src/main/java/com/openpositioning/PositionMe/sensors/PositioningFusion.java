package com.openpositioning.PositionMe.sensors;

import android.graphics.PointF;
import android.location.Location;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.DataRecords.LocationData;
import com.openpositioning.PositionMe.DataRecords.LocationHistory;
import com.openpositioning.PositionMe.PdrProcessing;
import com.openpositioning.PositionMe.fragments.StartLocationFragment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimerTask;

import android.content.Context;

/**
 * Singleton class responsible for fusing different positioning sources:
 * WiFi, GNSS, and PDR using a Particle Filter-based approach.
 *
 * It periodically updates the fused location using available data, handles
 * coordinate system initialization and conversion, and maintains location history.
 *
 * @see SensorFusion for raw data input
 * @see ParticleFilter for filtering logic
 * @see LocalCoordinateSystem for coordinate transformations
 * @see LocationHistory for recording past positions
 */
public class PositioningFusion implements PositionObserver {

    /** Singleton instance */
    private static final PositioningFusion instance = new PositioningFusion();

    /** Application context for UI elements like Toasts */
    private static Context appContext;

    /** Particle filter instance for position fusion */
    private ParticleFilter particleFilter = new ParticleFilter();
    private List<ParticleFilter.Particle> currentParticles;

    /** Local coordinate system used for conversions */
    private LocalCoordinateSystem coordSystem = new LocalCoordinateSystem();

    // --- Position Sources ---

    /** Most recent WiFi position in global coordinates */
    private LatLng wifiPosition;

    /** Most recent GNSS position in global coordinates */
    private LatLng gnssPosition;

    /** WiFi position in local coordinates */
    private float[] wifiPositionLocal;

    /** GNSS position in local coordinates */
    private float[] gnssPositionLocal;

    /** Previous WiFi position (for motion computation) */
    private float[] lastWifiPosoitonLocal;

    /** Most recent PDR position in local coordinates */
    private float[] pdrPosition;

    /** Last known PDR position */
    private float[] lastPdrPosition;

    // --- Timers and Fusion Loop ---

    /** Periodic fusion task timer */
    private java.util.Timer fusionTimer = new java.util.Timer();

    /** Interval between fusion updates (ms) */
    private static final long FUSION_INTERVAL_MS = 1000;

    // --- Fusion Output ---

    /** Fused position in local coordinates */
    private float[] fusedPositionLocal;

    /** Fused position in global coordinates */
    private LatLng fusedPosition;

    /** First valid position (used as map center or reference) */
    private LatLng startPosition;

    // --- History Buffers ---

    /** History of fused WiFi positions */
    public LocationHistory wifiLocationHistory;

    /** History of fused GNSS positions */
    public LocationHistory gnssLocationHistory;

    /** History of PDR positions */
    public LocationHistory pdrLocationHistory;



    /**
     * Private constructor for singleton pattern.
     * Initializes internal states and clears position histories.
     */
    private PositioningFusion() {
        wifiPosition = null;
        gnssPosition = null;
        wifiPositionLocal = null;
        gnssPositionLocal = null;
        pdrPosition = null;
        fusedPositionLocal = null;
        fusedPosition = null;
        startPosition = null;
        wifiLocationHistory = new LocationHistory(20);
        gnssLocationHistory = new LocationHistory(20);
        pdrLocationHistory = new LocationHistory(10);
    }


    /**
     * Returns the singleton instance of PositioningFusion.
     */
    public static PositioningFusion getInstance() {
        return instance;
    }


    /**
     * Sets the application context used for showing UI elements (e.g., Toasts).
     */
    public static void setContext(Context context) {
        appContext = context.getApplicationContext();
    }


    /**
     * Returns the stored application context.
     */
    public static Context getContext() {
        return appContext;
    }


    /**
     * Checks if the local coordinate system has been initialized.
     */
    public boolean isInitialized() {
        return coordSystem.isInitialized();
    }


    /**
     * Checks if sufficient data is available to run fusion.
     */
    private boolean isReadyToFuse() {
//        Log.d("Fusion", String.format("WiFi: %s, GNSS: %s, PDR: %s", isWifiPositionSet(), isGNSSPositionSet(), isPDRPositionSet()));
//        Log.d("Fusion", String.format("Initialized: %s", coordSystem.isInitialized()));
//        Log.d("Fusion", String.format("ready to fuse: %s", coordSystem.isInitialized() && pdrPosition != null && (wifiPositionLocal != null || gnssPositionLocal != null)));
        return coordSystem.isInitialized() && pdrPosition != null && (wifiPosition != null || gnssPosition != null);
    }

    /**
     * Initializes the local coordinate system using the current available GNSS or WiFi position.
     * Resets all internal states and location histories.
     */
    public void initCoordSystem() {
        wifiPosition = null;
        gnssPosition = null;
        wifiPositionLocal = null;
        gnssPositionLocal = null;
        pdrPosition = null;
        fusedPositionLocal = null;
        fusedPosition = null;
        wifiLocationHistory.clearHistory();
        gnssLocationHistory.clearHistory();
        pdrLocationHistory.clearHistory();
        SensorFusion.getInstance().pdrReset();
        if (SensorFusion.getInstance().getLatLngWifiPositioning() != null) {
            Log.d("Fusion", "initialized position with wifi location");
            LatLng wifiLocation = SensorFusion.getInstance().getLatLngWifiPositioning();
            coordSystem.initReference(wifiLocation.latitude, wifiLocation.longitude);
            currentParticles = null;
            startPosition = wifiLocation;
            Toast.makeText(getContext(), "Initialized with Wifi position", Toast.LENGTH_SHORT).show();


        }

        else if (SensorFusion.getInstance().getGNSSLatitude(false)[0] != 0 && SensorFusion.getInstance().getGNSSLatitude(false)[1] != 0) {
            Log.d("Fusion", "initialized position with gnss location");
            coordSystem.initReference(SensorFusion.getInstance().getGNSSLatitude(false)[0], SensorFusion.getInstance().getGNSSLatitude(false)[1]);
            currentParticles = null;
            Toast.makeText(getContext(), "Initialized with GNSS position", Toast.LENGTH_SHORT).show();
            startPosition = new LatLng(SensorFusion.getInstance().getGNSSLatitude(false)[0], SensorFusion.getInstance().getGNSSLatitude(false)[1]);
            Toast.makeText(getContext(), "Stay still for 5 more seconds and reopen this page to use Wifi Positioning", Toast.LENGTH_SHORT).show();
        }
    }


    /**
     * Starts periodic fusion processing every FUSION_INTERVAL_MS.
     * Updates fused position and stores historical data.
     */
    public void startPeriodicFusion() {
        Log.d("Fusion", "startPeriodicFusion");
        fusionTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (isReadyToFuse()) {
                    fusePosition();
                    coordinateConversionToGlobal();
                    wifiLocationHistory.addRecord(new LocationData(getWifiPosition()));
                    gnssLocationHistory.addRecord(new LocationData(getGnssPosition()));
                    pdrLocationHistory.addRecord(new LocationData(getPdrPosition()));
                }
            }
        }, 0, FUSION_INTERVAL_MS);
    }


    /**
     * Stops the periodic fusion task and resets the timer.
     */
    public void stopPeriodicFusion() {
        Log.d("Fusion", "stopPeriodicFusion");
        fusionTimer.cancel();
        fusionTimer = new java.util.Timer(); // reinitializes for future use
    }

    // --- Position Updates ---
    public void updateFromWiFi(LatLng wifiLocation) {
        this.wifiPosition = wifiLocation;

    }

    public boolean isWifiPositionSet() {
        return wifiPosition != null;
    }

    public boolean isGNSSPositionSet() {
        return gnssPosition != null;
    }

    public boolean isPDRPositionSet() {
        boolean initialized = coordSystem.isInitialized();
        return pdrPosition != null && initialized;
    }

    public boolean isFusedPositionSet() {
        return fusedPosition != null && coordSystem.isInitialized();
    }

    public boolean isStartPositionSet() {
        return fusedPosition != null && coordSystem.isInitialized();
    }

    // Get WiFi Position (Global)
    public LatLng getWifiPosition() {
        return this.wifiPosition;
    }

    // Get GNSS Position (Global)
    public LatLng getGnssPosition() {
        return this.gnssPosition;
    }

    // Get PDR Position
    public LatLng getPdrPosition() {
        boolean initialized = coordSystem.isInitialized();
        if (!initialized) {
            return null;
        }
        return coordSystem.toGlobal(this.pdrPosition[0], this.pdrPosition[1]);
    }

    public float[] getPdrPositionLocal() {
        return pdrPosition;
    }

    public LatLng getStartPosition() {
        return startPosition;
    }


    // --- Data Updates ---
    public void updateFromGNSS(LatLng gnssLocation) {
        this.gnssPosition = gnssLocation;
    }

    public void updateFromPDR(float[] pdrXY) {
        this.pdrPosition = pdrXY;
    }

    // --- Update All Sources ---

    /**
     * Updates position data from all sources (WiFi, GNSS, PDR)
     * using the {@link SensorFusion} singleton.
     */
    public void updateAllSourse() {
        updateFromWiFi(SensorFusion.getInstance().getLatLngWifiPositioning());
        float[] gnssLatLon = SensorFusion.getInstance().getGNSSLatitude(false);
        LatLng glssLatLnglocation = new LatLng(gnssLatLon[0], gnssLatLon[1]);
        updateFromGNSS(glssLatLnglocation);
        float[] pdrXY = SensorFusion.getInstance().getSensorValueMap().get(SensorTypes.PDR);
        updateFromPDR(pdrXY);
    }

    // --- Conversion Methods ---
    public void coordinateConversionToLocal() {
        if (this.wifiPosition != null) {
            this.wifiPositionLocal = coordSystem.toLocal(this.wifiPosition.latitude, this.wifiPosition.longitude);
        }
        if (this.gnssPosition != null) {
            this.gnssPositionLocal = coordSystem.toLocal(this.gnssPosition.latitude, this.gnssPosition.longitude);

        }
    }

    public void coordinateConversionToGlobal() {
        if (this.fusedPositionLocal != null && coordSystem.isInitialized()) {
            this.fusedPosition = coordSystem.toGlobal(this.fusedPositionLocal[0], this.fusedPositionLocal[1]);
        }
    }

    /**
     * Detect outliers caused by wrong wifi positioning based on GNSS location
     * When the difference between GNSS and WiFi positions is too large (100m)
     * the WiFi position (NOTE: only the wifiPositionLocal) is set to null
     *  GNSSwifiDiff_x is the difference between GNSS and WiFi positions in x direction
     * GNSSwifiDiff_y is the difference between GNSS and WiFi positions in y direction
    */
    private void outlierRemoval() {
        if (wifiPositionLocal == null || gnssPositionLocal == null) {
            return;
        }
        float GNSSwifiDiff_x = 0.0f;
        float GNSSwifiDiff_y = 0.0f;
        GNSSwifiDiff_x = gnssPositionLocal[0] - wifiPositionLocal[0];
        GNSSwifiDiff_y = gnssPositionLocal[1] - wifiPositionLocal[1];
        if (GNSSwifiDiff_x > 100 || GNSSwifiDiff_x < -100 || GNSSwifiDiff_y > 100 || GNSSwifiDiff_y < -100)
        {
            wifiPositionLocal = null;
            Log.d("Fusion", "outlierRemoval:applied ");
        }
    }



    // --- Fusion Logic ---

    /**
     * Performs fusion using particle filter:
     * - Builds motion vector from PDR
     * - Chooses observation (WiFi preferred, GNSS fallback)
     * - Runs particle filter update
     * - Stores fused position
     */
    private void fusePosition() {
        float[] pdrMotion = new float[2];
        if (lastPdrPosition != null) {
            pdrMotion[0] = pdrPosition[0] - lastPdrPosition[0];
            pdrMotion[1] = pdrPosition[1] - lastPdrPosition[1];
        } else {
            pdrMotion = pdrPosition;
        }

        if (gnssPositionLocal != null) {

            //Construct Observation Data (WiFi/GNSS first)
            PointF observation = null;
            if (wifiPositionLocal != null) {
                observation = new PointF(wifiPositionLocal[0], wifiPositionLocal[1]);
            } else if (gnssPositionLocal != null) {
                observation = new PointF(gnssPositionLocal[0], gnssPositionLocal[1]);
            }

            //Construct PDR Motion Vector
            PointF motion = null;
            if (pdrMotion != null) {
                motion = new PointF(pdrMotion[0], pdrMotion[1]);
            }

            //Update Particle Filter
            ParticleFilter.Result result = particleFilter.updateParticleFilter(
                    currentParticles,
                    wifiPositionLocal != null ? new PointF(wifiPositionLocal[0], wifiPositionLocal[1]) : null,
                    gnssPositionLocal != null ? new PointF(gnssPositionLocal[0], gnssPositionLocal[1]) : null,
                    pdrMotion != null ? new PointF(pdrMotion[0], pdrMotion[1]) : null
            );


            //Update fused position
            currentParticles = result.particles;
            fusedPositionLocal = new float[]{(float) result.bestX, (float) result.bestY};
            Log.d("Posotioning Fusion",String.format("fused position: %s", Arrays.toString(fusedPositionLocal)));

            lastPdrPosition = pdrPosition;
        }
    }


    // --- Getters ---
    public LatLng getFusedPosition() {
        return this.fusedPosition;
    }


    /**
     * {@inheritDoc}
     * Triggered when sensor values are updated.
     * Updates data from all sources and performs local conversion if ready.
     */
    @Override
    public void onSensorFusionUpdate() {
        updateAllSourse();

        if (isReadyToFuse()) {
            coordinateConversionToLocal();
        }
    }
}
