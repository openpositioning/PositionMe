package com.openpositioning.PositionMe.sensors;

import android.graphics.PointF;
import android.location.Location;
import android.util.Log;
import com.google.android.gms.maps.model.LatLng;

import java.util.List;


public class PositioningFusion {

    // Singleton instance
    private static final PositioningFusion instance = new PositioningFusion();

    private ParticleFilter particleFilter = new ParticleFilter();
    private List<ParticleFilter.Particle> currentParticles;     // Current set of particles

    private LocalCoordinateSystem coordSystem = new LocalCoordinateSystem();

    // Position data sources
    private LatLng wifiPosition;

    private LatLng gnssPosition;

    private float[] lastWifiPosoitonLocal;
    private float[] wifiPositionLocal;
    private float[] lastGnssPositionLocal;
    private float[] gnssPositionLocal;
    private float[] lastPdrPosition;
    private float[] pdrPosition; // [x, y] in meters


    // Fused position
    private float[] fusedPositionLocal;
    private LatLng fusedPosition;


    // Private constructor for singleton
    private PositioningFusion() {
        wifiPosition = null;
        gnssPosition = null;
        wifiPositionLocal = null;
        gnssPositionLocal = null;
        pdrPosition = null;
        fusedPositionLocal = null;
        fusedPosition = null;
    }


    // Singleton accessor
    public static PositioningFusion getInstance() {
        return instance;
    }

    // origin initialization
    public void initCoordSystem(double latitude, double longitude) {
        wifiPosition = null;
        gnssPosition = null;
        wifiPositionLocal = null;
        gnssPositionLocal = null;
        pdrPosition = null;
        fusedPositionLocal = null;
        fusedPosition = null;
        coordSystem.initReference(latitude, longitude);
    }

    // --- Update Methods ---
    public void updateFromWiFi(LatLng wifiLocation) {
        this.wifiPosition = wifiLocation;
        Log.d("Fusion", "WiFi updated: " + wifiLocation);
//        fusePosition();
    }

    public boolean isWifiPositionSet() {
        return wifiPosition != null;
    }

    public boolean isGNSSPositionSet() {
        return gnssPosition != null;
    }

    public boolean isPDRPositionSet() {
        return pdrPosition != null;
    }

    public void updateFromGNSS(LatLng gnssLocation) {
        this.gnssPosition = gnssLocation;
        Log.d("Fusion", "GNSS updated: " + gnssLocation);
//        fusePosition();
    }

    public void updateFromPDR(float[] pdrXY) {
        this.pdrPosition = pdrXY;
        Log.d("Fusion", "PDR updated: " + pdrXY);
//        fusePosition();
    }

    public void updateAllSourse() {
        updateFromWiFi(SensorFusion.getInstance().getLatLngWifiPositioning());
        float[] gnssLatLon = SensorFusion.getInstance().getGNSSLatitude(false);
        LatLng glssLatLnglocation = new LatLng(gnssLatLon[0], gnssLatLon[1]);
        updateFromGNSS(glssLatLnglocation);
        float[] pdrXY = SensorFusion.getInstance().getSensorValueMap().get(SensorTypes.PDR);

        updateFromPDR(pdrXY);
    }

    public void coordinateConversionToLocal() {
        if (this.wifiPosition != null && this.gnssPosition != null) {
            this.wifiPositionLocal = coordSystem.toLocal(this.wifiPosition.latitude, this.wifiPosition.longitude);
            this.gnssPositionLocal = coordSystem.toLocal(this.gnssPosition.latitude, this.gnssPosition.longitude);
        }
    }

    public void coordinateConversionToGlobal() {
        if (this.fusedPositionLocal != null) {
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


//    // --- Fusion Logic ---
//
    private void fusePosition() {
        float[] pdrMotion = new float[2];
        if (lastPdrPosition != null) {
            pdrMotion[0] = pdrPosition[0] - lastPdrPosition[0];
            pdrMotion[1] = pdrPosition[1] - lastPdrPosition[1];
        } else {
            pdrMotion = pdrPosition;
        }
        if (wifiPositionLocal != null && gnssPositionLocal != null) {
            // 1. |EN: Construct Observation Data (WiFi/GNSS first)
            //    |CHS: 构造观测数据（WiFi/GNSS 优先使用）
            PointF observation = null;
            if (wifiPositionLocal != null) {
                observation = new PointF(wifiPositionLocal[0], wifiPositionLocal[1]);
            } else if (gnssPositionLocal != null) {
                observation = new PointF(gnssPositionLocal[0], gnssPositionLocal[1]);
            }

            // 2. |EN: Construct PDR Motion Vector
            //    |CHS: 构造 PDR 位移向量
            PointF motion = null;
            if (pdrMotion != null) {
                motion = new PointF(pdrMotion[0], pdrMotion[1]);
            }

            // 3. |EN: Update Particle Filter
            //    |CHS: 执行粒子滤波更新
            ParticleFilter.Result result = particleFilter.updateParticleFilter(
                    currentParticles,
                    wifiPositionLocal != null ? new PointF(wifiPositionLocal[0], wifiPositionLocal[1]) : null,
                    gnssPositionLocal != null ? new PointF(gnssPositionLocal[0], gnssPositionLocal[1]) : null,
                    pdrMotion != null ? new PointF(pdrMotion[0], pdrMotion[1]) : null
            );


            // 4. |EN: Update fused position
            //    |CHS: 更新粒子集合和融合位置
            currentParticles = result.particles;
            fusedPositionLocal = new float[]{(float) result.bestX, (float) result.bestY};

            lastPdrPosition = pdrPosition;
        }
    }

    // --- Accessor for fused result ---
    public LatLng getFusedPosition() {
        updateAllSourse();
        coordinateConversionToLocal();
        outlierRemoval();
        fusePosition();
        coordinateConversionToGlobal();
        return this.fusedPosition;
    }
}
