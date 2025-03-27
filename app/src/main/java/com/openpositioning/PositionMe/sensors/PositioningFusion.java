package com.openpositioning.PositionMe.sensors;

import android.util.Log;
import com.google.android.gms.maps.model.LatLng;

import java.util.Arrays;
import java.util.Map;

public class PositioningFusion {

    // Singleton instance
    private static final PositioningFusion instance = new PositioningFusion();

    private LocalCoordinateSystem coordSystem = new LocalCoordinateSystem();

    // Position data sources
    private LatLng wifiPosition;
    private LatLng gnssPosition;

    private float[] wifiPositionLocal;
    private float[] gnssPositionLocal;
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
        coordSystem.initReference(latitude, longitude);
    }

    // --- Update Methods ---
    public void updateFromWiFi(LatLng wifiLocation) {
        this.wifiPosition = wifiLocation;
        Log.d("Fusion", "WiFi updated: " + wifiLocation);
//        fusePosition();
    }

    public void updateFromGNSS(LatLng gnssLocation) {
        this.gnssPosition = gnssLocation;
        Log.d("Fusion", "GNSS updated: " + gnssLocation);
//        fusePosition();
    }

    public void updateFromPDR(float[] pdrXY) {
        this.pdrPosition = pdrXY;
        Log.d("Fusion", "PDR updated: " + Arrays.toString(pdrXY));
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



//    // --- Fusion Logic ---
//
    private void fusePosition() {
//        if (wifiPositionLocal != null && gnssPositionLocal != null && pdrPosition != null) {
            this.fusedPositionLocal = this.wifiPositionLocal;;
//        }
    }

    // --- Accessor for fused result ---
    public LatLng getFusedPosition() {
        updateAllSourse();
        coordinateConversionToLocal();
        fusePosition();
        coordinateConversionToGlobal();
        return this.fusedPosition;
    }
}
