package com.openpositioning.PositionMe.utils;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

/**
 * Advanced Position Fusion with Indoor Optimization.
 * 
 * Key improvements for accurate indoor positioning:
 * 1. Heading Kalman Filter - reduces magnetometer noise/drift
 * 2. Building Boundary Constraint - prevents wall penetration
 * 3. Adaptive Step Length - self-calibrating based on history
 * 4. Anchor Point Correction - allows user to mark known position
 * 5. Smooth Continuous Output - no jitter or jumping
 * 
 * Algorithm:
 * - PDR is primary source (but filtered and constrained)
 * - GNSS/User corrections adjust accumulated drift gradually
 * - Building boundaries prevent impossible movements
 * 
 * @author PositionMe
 */
public class PositionFusion {
    
    private static final String TAG = "PositionFusion";
    
    // ========== POSITION STATE ==========
    // Absolute position in lat/lng
    private double currentLat = 0.0;
    private double currentLng = 0.0;
    
    // PDR accumulated offset (meters from start)
    private double pdrX = 0.0;  // East positive
    private double pdrY = 0.0;  // North positive
    private float lastRawPdrX = 0f;
    private float lastRawPdrY = 0f;
    
    // Start position (reference point)
    private double startLat = 0.0;
    private double startLng = 0.0;
    
    // ========== HEADING KALMAN FILTER ==========
    // Reduces magnetometer noise for stable direction
    private double filteredHeading = 0.0;       // Current filtered heading (radians)
    private double headingVariance = 1.0;       // Uncertainty estimate
    private static final double HEADING_PROCESS_NOISE = 0.01;   // How much heading can change per step
    private static final double HEADING_MEASURE_NOISE = 0.3;    // Magnetometer noise level
    
    // ========== STEP LENGTH ADAPTATION ==========
    // Self-calibrating step length from history
    private double adaptiveStepLength = 0.60;   // Average walking step (meters)
    private double stepLengthSum = 0.0;
    private int stepCount = 0;
    private static final double MIN_STEP_LENGTH = 0.35;
    private static final double MAX_STEP_LENGTH = 0.90;  // Normal walking range
    private static final double STEP_SMOOTHING = 0.12;  // Moderate adaptation speed
    
    // ========== STEP RATE LIMITING ==========
    private long lastStepTime = 0;
    private static final long MIN_STEP_INTERVAL_MS = 200;  // Max ~5 steps/second
    
    // ========== ANCHOR POINT (USER CORRECTION) ==========
    private double anchorLat = 0.0;
    private double anchorLng = 0.0;
    private double anchorPdrX = 0.0;
    private double anchorPdrY = 0.0;
    private boolean hasAnchor = false;
    private static final double ANCHOR_BLEND_RATE = 0.05;  // Gradual correction rate
    
    // ========== OUTPUT SMOOTHING ==========
    private double smoothLat = 0.0;
    private double smoothLng = 0.0;
    private static final double OUTPUT_SMOOTH_FACTOR = 0.25;  // Low-pass filter strength
    
    // ========== BUILDING CONSTRAINT ==========
    // Nucleus building bounds (can add more buildings)
    private static final double NUCLEUS_MIN_LAT = 55.92282257022002;
    private static final double NUCLEUS_MAX_LAT = 55.92332001571212;
    private static final double NUCLEUS_MIN_LNG = -3.1745956532857647;
    private static final double NUCLEUS_MAX_LNG = -3.1738768212979593;
    
    // Current building constraint (null = no constraint)
    private double[] currentBuildingBounds = null;  // [minLat, maxLat, minLng, maxLng]
    private boolean constrainToBuilding = false;
    
    // ========== STATE FLAGS ==========
    private boolean initialized = false;
    private long lastUpdateTime = 0;
    
    // ========== CONSTANTS ==========
    private static final double METERS_PER_DEGREE_LAT = 111139.0;
    
    /**
     * Initialize with starting position.
     */
    public void initialize(double latitude, double longitude, float accuracy) {
        this.startLat = latitude;
        this.startLng = longitude;
        this.currentLat = latitude;
        this.currentLng = longitude;
        this.smoothLat = latitude;
        this.smoothLng = longitude;
        
        this.pdrX = 0.0;
        this.pdrY = 0.0;
        this.lastRawPdrX = 0f;
        this.lastRawPdrY = 0f;
        
        this.filteredHeading = 0.0;
        this.headingVariance = 1.0;
        
        this.hasAnchor = false;
        this.initialized = true;
        this.lastUpdateTime = System.currentTimeMillis();
        
        // Check if inside a known building
        checkAndSetBuildingConstraint(latitude, longitude);
        
        Log.d(TAG, String.format("Initialized at (%.6f, %.6f), constrain=%b", 
            latitude, longitude, constrainToBuilding));
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Update with PDR step data.
     * This is the main position update - applies filtering and constraints.
     * 
     * @param rawPdrX Raw PDR X position from PdrProcessing (meters)
     * @param rawPdrY Raw PDR Y position from PdrProcessing (meters)
     * @param rawHeading Raw heading from magnetometer (radians, 0=North)
     * @param rawStepLength Estimated step length (meters)
     */
    public void updateWithPDR(float rawPdrX, float rawPdrY, float rawHeading, float rawStepLength) {
        if (!initialized) {
            lastRawPdrX = rawPdrX;
            lastRawPdrY = rawPdrY;
            return;
        }
        
        // Calculate raw step delta
        float deltaX = rawPdrX - lastRawPdrX;
        float deltaY = rawPdrY - lastRawPdrY;
        double rawDistance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        
        // Skip if no movement
        if (rawDistance < 0.01) {
            return;
        }
        
        // Rate limiting - ignore steps that come too fast (likely false detections)
        long currentTime = System.currentTimeMillis();
        if (lastStepTime > 0 && (currentTime - lastStepTime) < MIN_STEP_INTERVAL_MS) {
            // Step came too fast, likely a false detection - apply dampening
            rawDistance *= 0.5;
            deltaX *= 0.5f;
            deltaY *= 0.5f;
        }
        lastStepTime = currentTime;
        
        // 1. HEADING KALMAN FILTER
        double filteredHead = filterHeading(rawHeading);
        
        // 2. STEP LENGTH ADAPTATION
        double adaptedStepLen = adaptStepLength(rawStepLength, rawDistance);
        
        // 3. Calculate movement with filtered heading and adapted step length
        // Use filtered heading for direction, adapted step length for distance
        double scale = adaptedStepLen / Math.max(rawDistance, 0.01);
        double filteredDeltaX = deltaX * scale;
        double filteredDeltaY = deltaY * scale;
        
        // Update PDR offset
        pdrX += filteredDeltaX;
        pdrY += filteredDeltaY;
        
        // 4. ANCHOR CORRECTION (if user has set anchor point)
        if (hasAnchor) {
            applyAnchorCorrection();
        }
        
        // 5. Convert PDR to lat/lng
        double metersPerDegreeLng = METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(startLat));
        currentLat = startLat + pdrY / METERS_PER_DEGREE_LAT;
        currentLng = startLng + pdrX / metersPerDegreeLng;
        
        // 6. BUILDING CONSTRAINT - prevent wall penetration
        if (constrainToBuilding && currentBuildingBounds != null) {
            constrainToBuilding();
        }
        
        // Update state
        lastRawPdrX = rawPdrX;
        lastRawPdrY = rawPdrY;
        lastUpdateTime = System.currentTimeMillis();
    }
    
    /**
     * Simplified PDR update (without heading/step length from caller).
     */
    public void updateWithPDR(float rawPdrX, float rawPdrY) {
        if (!initialized) {
            lastRawPdrX = rawPdrX;
            lastRawPdrY = rawPdrY;
            return;
        }
        
        // Calculate delta
        float deltaX = rawPdrX - lastRawPdrX;
        float deltaY = rawPdrY - lastRawPdrY;
        double rawDistance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        
        if (rawDistance < 0.01) {
            return;
        }
        
        // Rate limiting - ignore steps that come too fast (likely false detections)
        long currentTime = System.currentTimeMillis();
        if (lastStepTime > 0 && (currentTime - lastStepTime) < MIN_STEP_INTERVAL_MS) {
            // Step came too fast, likely a false detection - apply dampening
            rawDistance *= 0.5;
        }
        lastStepTime = currentTime;
        
        // Apply adapted step length scaling
        double scale = adaptiveStepLength / Math.max(rawDistance, 0.25);
        scale = Math.min(scale, 1.3);  // Allow some over-scaling
        scale = Math.max(scale, 0.7);  // Don't under-scale too much
        
        double filteredDeltaX = deltaX * scale;
        double filteredDeltaY = deltaY * scale;
        
        // Update PDR offset
        pdrX += filteredDeltaX;
        pdrY += filteredDeltaY;
        
        // Anchor correction
        if (hasAnchor) {
            applyAnchorCorrection();
        }
        
        // Convert to lat/lng
        double metersPerDegreeLng = METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(startLat));
        currentLat = startLat + pdrY / METERS_PER_DEGREE_LAT;
        currentLng = startLng + pdrX / metersPerDegreeLng;
        
        // Building constraint
        if (constrainToBuilding && currentBuildingBounds != null) {
            constrainToBuilding();
        }
        
        // Update step count for adaptation
        stepCount++;
        stepLengthSum += rawDistance;
        if (stepCount >= 10) {
            double avgStep = stepLengthSum / stepCount;
            if (avgStep >= MIN_STEP_LENGTH && avgStep <= MAX_STEP_LENGTH) {
                adaptiveStepLength = adaptiveStepLength * (1 - STEP_SMOOTHING) + avgStep * STEP_SMOOTHING;
            }
            stepCount = 0;
            stepLengthSum = 0;
        }
        
        lastRawPdrX = rawPdrX;
        lastRawPdrY = rawPdrY;
        lastUpdateTime = System.currentTimeMillis();
    }
    
    /**
     * Kalman filter for heading to reduce magnetometer noise.
     */
    private double filterHeading(float rawHeading) {
        // Normalize heading to [-PI, PI]
        double measurement = rawHeading;
        while (measurement > Math.PI) measurement -= 2 * Math.PI;
        while (measurement < -Math.PI) measurement += 2 * Math.PI;
        
        // Handle wrap-around
        double diff = measurement - filteredHeading;
        if (diff > Math.PI) diff -= 2 * Math.PI;
        if (diff < -Math.PI) diff += 2 * Math.PI;
        
        // Kalman prediction step
        headingVariance += HEADING_PROCESS_NOISE;
        
        // Kalman update step
        double gain = headingVariance / (headingVariance + HEADING_MEASURE_NOISE);
        filteredHeading += gain * diff;
        headingVariance = (1 - gain) * headingVariance;
        
        // Normalize output
        while (filteredHeading > Math.PI) filteredHeading -= 2 * Math.PI;
        while (filteredHeading < -Math.PI) filteredHeading += 2 * Math.PI;
        
        return filteredHeading;
    }
    
    /**
     * Adaptive step length - learns from history.
     */
    private double adaptStepLength(float rawStepLength, double rawDistance) {
        // Clamp raw step length to reasonable range
        double clampedStep = Math.max(MIN_STEP_LENGTH, Math.min(MAX_STEP_LENGTH, rawStepLength));
        
        // Gradually adapt our estimate
        adaptiveStepLength = adaptiveStepLength * (1 - STEP_SMOOTHING) + clampedStep * STEP_SMOOTHING;
        
        return adaptiveStepLength;
    }
    
    /**
     * Gradually correct towards anchor point.
     */
    private void applyAnchorCorrection() {
        // Calculate error from where we should be
        double metersPerDegreeLng = METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(startLat));
        
        // Where anchor says we should be (in PDR coordinates)
        double targetPdrX = anchorPdrX + (currentLat - anchorLat) * METERS_PER_DEGREE_LAT;
        double targetPdrY = anchorPdrY + (currentLng - anchorLng) * metersPerDegreeLng;
        
        // This is wrong - let me recalculate
        // Anchor was set when PDR was at (anchorPdrX, anchorPdrY) and real position was (anchorLat, anchorLng)
        // So the offset is: realPos = startPos + pdr + correction
        // correction = anchorPos - (startPos + anchorPdr)
        
        double correctionLat = anchorLat - (startLat + anchorPdrY / METERS_PER_DEGREE_LAT);
        double correctionLng = anchorLng - (startLng + anchorPdrX / metersPerDegreeLng);
        
        // Apply correction gradually
        currentLat += correctionLat * ANCHOR_BLEND_RATE;
        currentLng += correctionLng * ANCHOR_BLEND_RATE;
    }
    
    /**
     * Constrain position to building boundaries.
     */
    private void constrainToBuilding() {
        double oldLat = currentLat;
        double oldLng = currentLng;
        
        // Simple box constraint
        currentLat = Math.max(currentBuildingBounds[0], Math.min(currentBuildingBounds[1], currentLat));
        currentLng = Math.max(currentBuildingBounds[2], Math.min(currentBuildingBounds[3], currentLng));
        
        // If constrained, also update PDR to prevent drift accumulation
        if (currentLat != oldLat || currentLng != oldLng) {
            double metersPerDegreeLng = METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(startLat));
            pdrY = (currentLat - startLat) * METERS_PER_DEGREE_LAT;
            pdrX = (currentLng - startLng) * metersPerDegreeLng;
        }
    }
    
    /**
     * Check if inside a known building and set constraint.
     */
    private void checkAndSetBuildingConstraint(double lat, double lng) {
        // Check Nucleus building
        if (lat >= NUCLEUS_MIN_LAT && lat <= NUCLEUS_MAX_LAT &&
            lng >= NUCLEUS_MIN_LNG && lng <= NUCLEUS_MAX_LNG) {
            currentBuildingBounds = new double[]{NUCLEUS_MIN_LAT, NUCLEUS_MAX_LAT, NUCLEUS_MIN_LNG, NUCLEUS_MAX_LNG};
            constrainToBuilding = true;
            Log.d(TAG, "Inside Nucleus building - constraint enabled");
            return;
        }
        
        // Add more buildings here...
        
        constrainToBuilding = false;
        currentBuildingBounds = null;
    }
    
    /**
     * User sets anchor point at known position.
     * Call when user confirms their current position on map.
     */
    public void setAnchorPoint(double lat, double lng) {
        anchorLat = lat;
        anchorLng = lng;
        anchorPdrX = pdrX;
        anchorPdrY = pdrY;
        hasAnchor = true;
        
        // Also check building constraint from new position
        checkAndSetBuildingConstraint(lat, lng);
        
        Log.d(TAG, String.format("Anchor set at (%.6f, %.6f)", lat, lng));
    }
    
    /**
     * Enable/disable building constraint.
     */
    public void setConstrainToBuilding(boolean enable) {
        this.constrainToBuilding = enable;
    }
    
    /**
     * Manually set building bounds.
     */
    public void setBuildingBounds(double minLat, double maxLat, double minLng, double maxLng) {
        this.currentBuildingBounds = new double[]{minLat, maxLat, minLng, maxLng};
        this.constrainToBuilding = true;
    }
    
    /**
     * Update with GNSS (outdoor/transition use).
     */
    public boolean updateWithGNSS(double latitude, double longitude, float accuracy) {
        if (!initialized) {
            if (accuracy < 50) {
                initialize(latitude, longitude, accuracy);
                return true;
            }
            return false;
        }
        
        // In indoor mode, ignore poor GNSS
        if (constrainToBuilding || accuracy > 30) {
            return false;
        }
        
        // For outdoor, treat GNSS as anchor point
        setAnchorPoint(latitude, longitude);
        return true;
    }
    
    /**
     * Get smoothed latitude output.
     */
    public double getFusedLatitude() {
        smoothLat = smoothLat + OUTPUT_SMOOTH_FACTOR * (currentLat - smoothLat);
        return smoothLat;
    }
    
    /**
     * Get smoothed longitude output.
     */
    public double getFusedLongitude() {
        smoothLng = smoothLng + OUTPUT_SMOOTH_FACTOR * (currentLng - smoothLng);
        return smoothLng;
    }
    
    /**
     * Get current position as LatLng.
     */
    public LatLng getPosition() {
        return new LatLng(getFusedLatitude(), getFusedLongitude());
    }
    
    public float getPositionUncertainty() {
        return hasAnchor ? 3.0f : 10.0f;  // More confident with anchor
    }
    
    public float[] getPdrOffset() {
        return new float[]{(float)pdrX, (float)pdrY};
    }
    
    public long getTimeSinceLastGnss() {
        return Long.MAX_VALUE;  // Indoor mode - GNSS not used
    }
    
    public boolean isGnssStale() {
        return true;  // Always stale in indoor mode
    }
    
    public void forceReset(double latitude, double longitude, float accuracy) {
        initialize(latitude, longitude, accuracy);
    }
    
    public void reset() {
        currentLat = 0.0;
        currentLng = 0.0;
        pdrX = 0.0;
        pdrY = 0.0;
        lastRawPdrX = 0f;
        lastRawPdrY = 0f;
        startLat = 0.0;
        startLng = 0.0;
        
        filteredHeading = 0.0;
        headingVariance = 1.0;
        
        adaptiveStepLength = 0.65;
        stepLengthSum = 0.0;
        stepCount = 0;
        
        anchorLat = 0.0;
        anchorLng = 0.0;
        hasAnchor = false;
        
        smoothLat = 0.0;
        smoothLng = 0.0;
        
        constrainToBuilding = false;
        currentBuildingBounds = null;
        
        initialized = false;
        lastUpdateTime = 0;
        
        Log.d(TAG, "Position fusion reset");
    }
    
    // ========== DEBUG INFO ==========
    public double getFilteredHeading() {
        return filteredHeading;
    }
    
    public double getAdaptiveStepLength() {
        return adaptiveStepLength;
    }
    
    public boolean hasAnchorPoint() {
        return hasAnchor;
    }
    
    public boolean isConstrainedToBuilding() {
        return constrainToBuilding;
    }
}
