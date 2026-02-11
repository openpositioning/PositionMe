package com.openpositioning.PositionMe.utils;

import android.util.Log;
import com.google.android.gms.maps.model.LatLng;

/**
 * Simple, correct Position Fusion.
 *
 * Design principle: PDR already accumulates steps into (x,y) coordinates.
 * We simply convert that to lat/lng and add drift correction from GNSS/anchors.
 * 
 * NO sliding windows. NO re-weighting. NO re-calculation.
 * Just: position = startLatLng + PDR_offset + drift_correction
 *
 * Drift correction is the key to long-term accuracy:
 * - GNSS provides periodic absolute position fixes
 * - User anchor points provide manual corrections
 * - The correction is applied as a simple offset that accumulates
 *
 * @author PositionMe
 */
public class SimplePositionFusion {
    
    private static final String TAG = "SimplePosFusion";
    
    // ========== START POSITION ==========
    private double startLat = 0;
    private double startLng = 0;
    
    // ========== DRIFT CORRECTION ==========
    // This is the accumulated correction offset (in meters)
    // It represents: (true_position - pdr_position)
    private double correctionX = 0;  // East-West correction in meters
    private double correctionY = 0;  // North-South correction in meters
    
    // ========== PDR TRACKING ==========
    private float lastPdrX = 0;
    private float lastPdrY = 0;
    
    // ========== OUTPUT SMOOTHING ==========
    // Simple low-pass filter on the output to remove jitter
    private double smoothLat = 0;
    private double smoothLng = 0;
    private static final double SMOOTH_ALPHA = 0.5;  // 0=very smooth, 1=no smoothing
    
    // ========== STATE ==========
    private boolean initialized = false;
    private int stepCount = 0;
    
    // ========== BUILDING CONSTRAINT ==========
    private double[] buildingBounds = null;
    private boolean constrainEnabled = false;
    
    // ========== CONSTANTS ==========
    private static final double METERS_PER_DEG_LAT = 111139.0;
    
    /**
     * Initialize with starting GPS position.
     */
    public void initialize(double latitude, double longitude, float accuracy) {
        startLat = latitude;
        startLng = longitude;
        
        correctionX = 0;
        correctionY = 0;
        
        lastPdrX = 0;
        lastPdrY = 0;
        
        smoothLat = latitude;
        smoothLng = longitude;
        
        stepCount = 0;
        initialized = true;
        
        checkBuildingConstraint(latitude, longitude);
        
        Log.d(TAG, String.format("Init at (%.6f, %.6f) acc=%.1f", latitude, longitude, accuracy));
    }
    
    public boolean isInitialized() { return initialized; }
    
    /**
     * Main PDR update. Called on each step.
     *
     * pdrX, pdrY are cumulative PDR position from PdrProcessing (meters from start).
     * We simply convert to lat/lng and add our correction offset.
     */
    public void updateWithPDR(float pdrX, float pdrY) {
        if (!initialized) {
            lastPdrX = pdrX;
            lastPdrY = pdrY;
            return;
        }
        
        lastPdrX = pdrX;
        lastPdrY = pdrY;
        stepCount++;
        
        // Calculate raw position: start + PDR + correction
        double metersPerDegLng = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(startLat));
        
        // PDR coordinate system: X is East (cos(heading)), Y is North (sin(heading))
        // mapped from PdrProcessing which uses: x = stepLen * cos(heading), y = stepLen * sin(heading)
        double rawLat = startLat + (pdrY + correctionY) / METERS_PER_DEG_LAT;
        double rawLng = startLng + (pdrX + correctionX) / metersPerDegLng;
        
        // Apply building constraint
        if (constrainEnabled && buildingBounds != null) {
            rawLat = Math.max(buildingBounds[0], Math.min(buildingBounds[1], rawLat));
            rawLng = Math.max(buildingBounds[2], Math.min(buildingBounds[3], rawLng));
        }
        
        // Smooth output
        smoothLat = smoothLat + SMOOTH_ALPHA * (rawLat - smoothLat);
        smoothLng = smoothLng + SMOOTH_ALPHA * (rawLng - smoothLng);
    }
    
    /**
     * Simplified PDR update with heading and step length (compatibility).
     */
    public void updateWithPDR(float pdrX, float pdrY, float heading, float stepLength) {
        updateWithPDR(pdrX, pdrY);
    }
    
    /**
     * Update with GNSS fix.
     * Calculates the error between where PDR thinks we are and where GNSS says we are,
     * then adds that error to our drift correction.
     */
    public boolean updateWithGNSS(double lat, double lng, float accuracy) {
        if (!initialized) {
            if (accuracy < 50) {
                initialize(lat, lng, accuracy);
                return true;
            }
            return false;
        }
        
        // Ignore poor GNSS in indoor mode
        if (constrainEnabled && accuracy > 15) {
            return false;
        }
        
        // Ignore very poor GNSS everywhere
        if (accuracy > 30) {
            return false;
        }
        
        // Calculate where PDR thinks we are (without correction we'd have applied)
        double metersPerDegLng = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(startLat));
        double pdrLat = startLat + (lastPdrY + correctionY) / METERS_PER_DEG_LAT;
        double pdrLng = startLng + (lastPdrX + correctionX) / metersPerDegLng;
        
        // Error = GNSS position - current PDR position
        double errorY = (lat - pdrLat) * METERS_PER_DEG_LAT;
        double errorX = (lng - pdrLng) * metersPerDegLng;
        
        // Weight based on accuracy (better accuracy = more trust)
        // accuracy 5m -> weight 0.5, accuracy 15m -> weight 0.17, accuracy 30m -> weight 0.08
        double weight = Math.min(0.5, 2.5 / accuracy);
        
        // Add weighted error to correction
        correctionX += errorX * weight;
        correctionY += errorY * weight;
        
        Log.d(TAG, String.format("GNSS correction: err=(%.1f,%.1f)m weight=%.2f acc=%.0f", 
            errorX, errorY, weight, accuracy));
        
        return true;
    }
    
    /**
     * User manually sets their position (anchor point).
     * This is a strong correction - apply most of the error immediately.
     */
    public void setAnchorPoint(double lat, double lng) {
        if (!initialized) return;
        
        double metersPerDegLng = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(startLat));
        double pdrLat = startLat + (lastPdrY + correctionY) / METERS_PER_DEG_LAT;
        double pdrLng = startLng + (lastPdrX + correctionX) / metersPerDegLng;
        
        // User anchor is highly trusted - apply 80% of correction immediately
        double errorY = (lat - pdrLat) * METERS_PER_DEG_LAT;
        double errorX = (lng - pdrLng) * metersPerDegLng;
        
        correctionX += errorX * 0.8;
        correctionY += errorY * 0.8;
        
        // Also update smooth output to jump closer
        smoothLat = smoothLat + 0.7 * (lat - smoothLat);
        smoothLng = smoothLng + 0.7 * (lng - smoothLng);
        
        checkBuildingConstraint(lat, lng);
        
        Log.d(TAG, String.format("Anchor: (%.6f,%.6f) correction now (%.1f,%.1f)m", 
            lat, lng, correctionX, correctionY));
    }
    
    // ========== BUILDING CONSTRAINT ==========
    
    private void checkBuildingConstraint(double lat, double lng) {
        // Nucleus
        if (lat >= 55.92282 && lat <= 55.92332 && lng >= -3.17460 && lng <= -3.17387) {
            buildingBounds = new double[]{55.92282, 55.92332, -3.17460, -3.17387};
            constrainEnabled = true;
            return;
        }
        // Library
        if (lat >= 55.92260 && lat <= 55.92310 && lng >= -3.17530 && lng <= -3.17440) {
            buildingBounds = new double[]{55.92260, 55.92310, -3.17530, -3.17440};
            constrainEnabled = true;
            return;
        }
        constrainEnabled = false;
        buildingBounds = null;
    }
    
    public void setConstrainToBuilding(boolean enable) {
        constrainEnabled = enable;
    }
    
    public void setBuildingBounds(double minLat, double maxLat, double minLng, double maxLng) {
        buildingBounds = new double[]{minLat, maxLat, minLng, maxLng};
        constrainEnabled = true;
    }
    
    // ========== OUTPUT ==========
    
    public double getFusedLatitude() { return smoothLat; }
    public double getFusedLongitude() { return smoothLng; }
    
    public LatLng getPosition() {
        return new LatLng(smoothLat, smoothLng);
    }
    
    public float getPositionUncertainty() {
        double corrMag = Math.sqrt(correctionX * correctionX + correctionY * correctionY);
        return (float) Math.min(5.0 + corrMag * 0.1, 20.0);
    }
    
    public float[] getPdrOffset() {
        return new float[]{lastPdrX, lastPdrY};
    }
    
    public boolean hasAnchorPoint() {
        return Math.abs(correctionX) > 0.1 || Math.abs(correctionY) > 0.1;
    }
    
    public boolean isConstrainedToBuilding() { return constrainEnabled; }
    public double getFilteredHeading() { return 0; }
    public double getAdaptiveStepLength() { return 0.65; }
    
    public long getTimeSinceLastGnss() { return Long.MAX_VALUE; }
    public boolean isGnssStale() { return true; }
    
    // ========== CONTROL ==========
    
    public void forceReset(double lat, double lng, float accuracy) {
        initialize(lat, lng, accuracy);
    }
    
    public void reset() {
        initialized = false;
        startLat = 0; startLng = 0;
        correctionX = 0; correctionY = 0;
        lastPdrX = 0; lastPdrY = 0;
        smoothLat = 0; smoothLng = 0;
        stepCount = 0;
        constrainEnabled = false;
        buildingBounds = null;
        Log.d(TAG, "Reset");
    }
    
    // Compatibility stubs for sensor updates (not needed in simple fusion)
    public void updateWithAccelerometer(float[] accel, float heading) { }
    public void updateWithGyroscope(float[] gyro) { }
    public void updateWithMagnetometer(float heading) { }
}
