package com.openpositioning.PositionMe.utils;

import android.util.Log;
import com.google.android.gms.maps.model.LatLng;

/**
 * Advanced Position Fusion using Extended Kalman Filter (EKF).
 * 
 * This implements a high-accuracy indoor positioning system with:
 * 1. Extended Kalman Filter - fuses position, velocity, heading
 * 2. Acceleration Integration - real-time position updates between steps
 * 3. Zero Velocity Update (ZUPT) - resets drift when stationary
 * 4. Adaptive Step Length - dynamic based on acceleration magnitude
 * 5. Heading Fusion - combines gyroscope and magnetometer
 * 6. Multi-source Correction - GNSS, WiFi, user anchor points
 * 
 * State Vector: [x, y, vx, vy, heading]
 * - x, y: position in meters (local coordinate)
 * - vx, vy: velocity in m/s
 * - heading: direction in radians
 * 
 * @author PositionMe
 */
public class AdvancedPositionFusion {
    
    private static final String TAG = "AdvancedPosFusion";
    
    // ========== EKF STATE (5 dimensions) ==========
    // State: [x, y, vx, vy, heading]
    private double[] state = new double[5];
    
    // Covariance matrix (5x5)
    private double[][] P = new double[5][5];
    
    // Process noise
    private static final double Q_POS = 0.01;      // Position process noise
    private static final double Q_VEL = 0.5;       // Velocity process noise
    private static final double Q_HEAD = 0.02;     // Heading process noise
    
    // Measurement noise
    private static final double R_STEP = 0.15;     // Step measurement noise
    private static final double R_ACCEL = 1.0;     // Accelerometer noise
    private static final double R_GYRO = 0.05;     // Gyroscope noise
    private static final double R_MAG = 0.3;       // Magnetometer noise
    private static final double R_GNSS = 5.0;      // GNSS measurement noise
    private static final double R_ANCHOR = 0.5;    // User anchor noise (very trusted)
    
    // ========== REFERENCE POSITION ==========
    private double startLat = 0.0;
    private double startLng = 0.0;
    private boolean initialized = false;
    
    // ========== STEP DETECTION ==========
    private float lastPdrX = 0f;
    private float lastPdrY = 0f;
    private long lastStepTime = 0;
    private double stepLengthEstimate = 0.65;      // Running average
    private int stepCount = 0;
    
    // ========== ACCELERATION INTEGRATION ==========
    private double[] lastAccel = new double[3];    // World frame acceleration
    private long lastAccelTime = 0;
    private double accumulatedDx = 0;              // Accumulated displacement since last step
    private double accumulatedDy = 0;
    
    // ========== ZERO VELOCITY UPDATE (ZUPT) ==========
    private double[] accelHistory = new double[20]; // Circular buffer
    private int accelHistoryIndex = 0;
    private boolean isStationary = false;
    private long stationaryStartTime = 0;
    private static final double ZUPT_THRESHOLD = 0.15;  // m/s^2 variance threshold
    private static final long ZUPT_MIN_DURATION = 300;  // ms before declaring stationary
    
    // ========== HEADING FUSION ==========
    private double gyroHeading = 0;                // Integrated gyroscope heading
    private double magHeading = 0;                 // Magnetometer heading
    private long lastGyroTime = 0;
    
    // ========== ANCHOR & CORRECTION ==========
    private double anchorLat = 0;
    private double anchorLng = 0;
    private double anchorX = 0;
    private double anchorY = 0;
    private boolean hasAnchor = false;
    private double correctionX = 0;                // Accumulated correction to apply gradually
    private double correctionY = 0;
    
    // ========== OUTPUT SMOOTHING ==========
    private double smoothX = 0;
    private double smoothY = 0;
    private static final double SMOOTH_FACTOR = 0.45;  // Higher = more responsive
    
    // ========== BUILDING CONSTRAINT ==========
    private double[] buildingBounds = null;        // [minLat, maxLat, minLng, maxLng]
    private boolean constrainEnabled = false;
    
    // ========== CONSTANTS ==========
    private static final double METERS_PER_DEG_LAT = 111139.0;
    private static final double GRAVITY = 9.81;
    private static final double MIN_STEP_LENGTH = 0.40;
    private static final double MAX_STEP_LENGTH = 0.85;
    private static final long MIN_STEP_INTERVAL = 250;   // ms between steps
    private static final long MAX_STEP_INTERVAL = 1500;  // ms - slower than this is not walking
    
    /**
     * Initialize EKF state and covariance.
     */
    public void initialize(double latitude, double longitude, float accuracy) {
        this.startLat = latitude;
        this.startLng = longitude;
        
        // Initialize state: [x=0, y=0, vx=0, vy=0, heading=0]
        state = new double[]{0, 0, 0, 0, 0};
        
        // Initialize covariance with high uncertainty
        P = new double[5][5];
        P[0][0] = accuracy * accuracy;  // x variance
        P[1][1] = accuracy * accuracy;  // y variance
        P[2][2] = 1.0;                  // vx variance
        P[3][3] = 1.0;                  // vy variance
        P[4][4] = 1.0;                  // heading variance
        
        // Reset accumulators
        accumulatedDx = 0;
        accumulatedDy = 0;
        smoothX = 0;
        smoothY = 0;
        
        // Reset step tracking
        lastPdrX = 0;
        lastPdrY = 0;
        stepCount = 0;
        
        this.initialized = true;
        this.hasAnchor = false;
        
        // Check for building constraint
        checkBuildingConstraint(latitude, longitude);
        
        Log.d(TAG, String.format("EKF initialized at (%.6f, %.6f) accuracy=%.1fm", 
            latitude, longitude, accuracy));
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    // ========== MAIN UPDATE METHODS ==========
    
    /**
     * Update with PDR step detection.
     * This is triggered when step detector fires.
     */
    public void updateWithStep(float pdrX, float pdrY) {
        if (!initialized) {
            lastPdrX = pdrX;
            lastPdrY = pdrY;
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        long stepInterval = currentTime - lastStepTime;
        
        // Calculate step delta
        float deltaX = pdrX - lastPdrX;
        float deltaY = pdrY - lastPdrY;
        double stepDistance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        
        // Filter out invalid steps
        if (stepDistance < 0.05 || stepDistance > 2.0) {
            lastPdrX = pdrX;
            lastPdrY = pdrY;
            return;
        }
        
        // Rate limiting
        if (lastStepTime > 0 && stepInterval < MIN_STEP_INTERVAL) {
            // Too fast - likely false detection, reduce impact
            stepDistance *= 0.6;
            deltaX *= 0.6f;
            deltaY *= 0.6f;
        }
        
        // Adaptive step length
        double adaptedStep = adaptStepLength(stepDistance, stepInterval);
        double scale = adaptedStep / Math.max(stepDistance, 0.1);
        
        // Scale the step
        double scaledDx = deltaX * scale;
        double scaledDy = deltaY * scale;
        
        // Calculate step heading
        double stepHeading = Math.atan2(scaledDx, scaledDy);
        
        // EKF measurement update with step
        ekfUpdateWithStep(scaledDx, scaledDy, stepHeading);
        
        // Clear accumulated acceleration displacement (step supersedes)
        accumulatedDx = 0;
        accumulatedDy = 0;
        
        // Update tracking
        lastPdrX = pdrX;
        lastPdrY = pdrY;
        lastStepTime = currentTime;
        stepCount++;
        isStationary = false;
        
        // Apply any pending corrections
        if (hasAnchor) {
            applyGradualCorrection();
        }
    }
    
    /**
     * Update with accelerometer data.
     * Called frequently (~50-100Hz) for smooth position updates.
     */
    public void updateWithAccelerometer(float[] linearAccel, float heading) {
        if (!initialized) return;
        
        long currentTime = System.currentTimeMillis();
        
        // Store for ZUPT detection
        double accelMag = Math.sqrt(linearAccel[0]*linearAccel[0] + 
                                   linearAccel[1]*linearAccel[1] + 
                                   linearAccel[2]*linearAccel[2]);
        accelHistory[accelHistoryIndex] = accelMag;
        accelHistoryIndex = (accelHistoryIndex + 1) % accelHistory.length;
        
        // Check for stationary (ZUPT)
        checkStationary(currentTime);
        
        if (isStationary) {
            // Zero velocity update
            ekfZeroVelocityUpdate();
            return;
        }
        
        // EKF prediction with time delta
        if (lastAccelTime > 0) {
            double dt = (currentTime - lastAccelTime) / 1000.0;
            if (dt > 0 && dt < 0.5) {
                ekfPredict(dt);
                
                // Heading update
                if (Math.abs(heading - state[4]) > 0.1) {
                    ekfUpdateHeading(heading);
                }
            }
        }
        
        lastAccelTime = currentTime;
    }
    
    /**
     * Update with gyroscope for heading integration.
     */
    public void updateWithGyroscope(float[] gyro) {
        long currentTime = System.currentTimeMillis();
        
        if (lastGyroTime > 0) {
            double dt = (currentTime - lastGyroTime) / 1000.0;
            if (dt > 0 && dt < 0.1) {
                // Integrate gyro for heading
                gyroHeading += gyro[2] * dt;  // Z-axis is yaw
                
                // Normalize
                while (gyroHeading > Math.PI) gyroHeading -= 2 * Math.PI;
                while (gyroHeading < -Math.PI) gyroHeading += 2 * Math.PI;
            }
        }
        lastGyroTime = currentTime;
    }
    
    /**
     * Update with magnetometer heading.
     */
    public void updateWithMagnetometer(float heading) {
        this.magHeading = heading;
        
        // Fuse with gyro
        // Complementary filter: 95% gyro, 5% magnetometer (magnetometer is noisy but no drift)
        double diff = magHeading - gyroHeading;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;
        
        gyroHeading += 0.05 * diff;  // Gradually correct gyro drift
    }
    
    /**
     * Update with GNSS position.
     * @return true if GNSS was used for update
     */
    public boolean updateWithGNSS(double lat, double lng, float accuracy) {
        if (!initialized) {
            if (accuracy < 30) {
                initialize(lat, lng, accuracy);
                return true;
            }
            return false;
        }
        
        // Indoor mode - ignore GNSS
        if (constrainEnabled || accuracy > 25) {
            return false;
        }
        
        // Convert to local coordinates
        double metersPerDegLng = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(startLat));
        double measX = (lng - startLng) * metersPerDegLng;
        double measY = (lat - startLat) * METERS_PER_DEG_LAT;
        
        // EKF update with GNSS measurement
        ekfUpdateWithPosition(measX, measY, accuracy);
        return true;
    }
    
    /**
     * User sets a known anchor position.
     */
    public void setAnchorPoint(double lat, double lng) {
        double metersPerDegLng = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(startLat));
        
        // Store anchor in local coordinates
        anchorLat = lat;
        anchorLng = lng;
        anchorX = (lng - startLng) * metersPerDegLng;
        anchorY = (lat - startLat) * METERS_PER_DEG_LAT;
        
        // Calculate correction needed
        correctionX = anchorX - state[0];
        correctionY = anchorY - state[1];
        
        hasAnchor = true;
        
        // Check building constraint at new position
        checkBuildingConstraint(lat, lng);
        
        Log.d(TAG, String.format("Anchor set at (%.6f, %.6f), correction=(%.2f, %.2f)m",
            lat, lng, correctionX, correctionY));
    }
    
    // ========== EKF CORE METHODS ==========
    
    /**
     * EKF prediction step.
     */
    private void ekfPredict(double dt) {
        // State prediction: x = F * x
        // Position updates based on velocity
        state[0] += state[2] * dt;  // x += vx * dt
        state[1] += state[3] * dt;  // y += vy * dt
        // Velocity and heading remain (will be corrected by measurements)
        
        // Velocity decay (friction model) - prevents runaway velocity
        state[2] *= 0.95;
        state[3] *= 0.95;
        
        // Covariance prediction: P = F * P * F' + Q
        // Simplified: just add process noise
        P[0][0] += Q_POS;
        P[1][1] += Q_POS;
        P[2][2] += Q_VEL;
        P[3][3] += Q_VEL;
        P[4][4] += Q_HEAD;
    }
    
    /**
     * EKF update with step measurement.
     */
    private void ekfUpdateWithStep(double dx, double dy, double heading) {
        // Measurement: position displacement and heading
        double[] z = {state[0] + dx, state[1] + dy, heading};
        double[] h = {state[0], state[1], state[4]};  // Predicted measurement
        
        // Innovation
        double[] y = {z[0] - h[0], z[1] - h[1], z[2] - h[2]};
        
        // Normalize heading innovation
        while (y[2] > Math.PI) y[2] -= 2 * Math.PI;
        while (y[2] < -Math.PI) y[2] += 2 * Math.PI;
        
        // Kalman gain (simplified for diagonal R)
        double kPos = P[0][0] / (P[0][0] + R_STEP);
        double kHead = P[4][4] / (P[4][4] + R_MAG);
        
        // State update
        state[0] += kPos * y[0];
        state[1] += kPos * y[1];
        state[4] += kHead * y[2];
        
        // Estimate velocity from step
        long timeSinceLastStep = System.currentTimeMillis() - lastStepTime;
        if (timeSinceLastStep > 100 && timeSinceLastStep < 2000) {
            double dt = timeSinceLastStep / 1000.0;
            double vx = dx / dt;
            double vy = dy / dt;
            
            // Blend with current velocity estimate
            state[2] = 0.5 * state[2] + 0.5 * vx;
            state[3] = 0.5 * state[3] + 0.5 * vy;
        }
        
        // Covariance update
        P[0][0] = (1 - kPos) * P[0][0];
        P[1][1] = (1 - kPos) * P[1][1];
        P[4][4] = (1 - kHead) * P[4][4];
        
        // Apply building constraints
        if (constrainEnabled && buildingBounds != null) {
            constrainPosition();
        }
    }
    
    /**
     * EKF update with absolute position measurement (GNSS/Anchor).
     */
    private void ekfUpdateWithPosition(double measX, double measY, double noise) {
        // Innovation
        double dx = measX - state[0];
        double dy = measY - state[1];
        
        // Kalman gain
        double k = P[0][0] / (P[0][0] + noise * noise);
        
        // State update
        state[0] += k * dx;
        state[1] += k * dy;
        
        // Covariance update
        P[0][0] = (1 - k) * P[0][0];
        P[1][1] = (1 - k) * P[1][1];
    }
    
    /**
     * EKF heading update.
     */
    private void ekfUpdateHeading(double measHeading) {
        double diff = measHeading - state[4];
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;
        
        double k = P[4][4] / (P[4][4] + R_MAG);
        state[4] += k * diff;
        P[4][4] = (1 - k) * P[4][4];
        
        // Normalize heading
        while (state[4] > Math.PI) state[4] -= 2 * Math.PI;
        while (state[4] < -Math.PI) state[4] += 2 * Math.PI;
    }
    
    /**
     * Zero Velocity Update - when stationary, velocity should be zero.
     */
    private void ekfZeroVelocityUpdate() {
        // Strong correction toward zero velocity
        double k = 0.8;  // High gain for ZUPT
        state[2] *= (1 - k);
        state[3] *= (1 - k);
        
        // Reduce velocity uncertainty
        P[2][2] *= 0.5;
        P[3][3] *= 0.5;
    }
    
    // ========== HELPER METHODS ==========
    
    /**
     * Adaptive step length based on timing and history.
     */
    private double adaptStepLength(double rawStep, long interval) {
        // Normal walking: 0.5-1.5 steps per second
        double expectedStep;
        
        if (interval < MIN_STEP_INTERVAL) {
            // Very fast - probably running or false detection
            expectedStep = MIN_STEP_LENGTH;
        } else if (interval > MAX_STEP_INTERVAL) {
            // Very slow - small steps
            expectedStep = MIN_STEP_LENGTH;
        } else {
            // Normal walking - interpolate based on speed
            double stepsPerSecond = 1000.0 / interval;
            // Faster walking = longer steps (0.5-2 steps/sec maps to 0.5-0.85m)
            expectedStep = MIN_STEP_LENGTH + (stepsPerSecond - 0.7) * 0.25;
            expectedStep = Math.max(MIN_STEP_LENGTH, Math.min(MAX_STEP_LENGTH, expectedStep));
        }
        
        // Blend with running average
        stepLengthEstimate = 0.85 * stepLengthEstimate + 0.15 * expectedStep;
        
        // Return clamped value
        return Math.max(MIN_STEP_LENGTH, Math.min(MAX_STEP_LENGTH, stepLengthEstimate));
    }
    
    /**
     * Check if user is stationary using acceleration variance.
     */
    private void checkStationary(long currentTime) {
        // Calculate variance of recent accelerations
        double sum = 0, sumSq = 0;
        for (double a : accelHistory) {
            sum += a;
            sumSq += a * a;
        }
        double mean = sum / accelHistory.length;
        double variance = sumSq / accelHistory.length - mean * mean;
        
        if (variance < ZUPT_THRESHOLD && mean < 0.5) {
            // Possibly stationary
            if (!isStationary) {
                if (stationaryStartTime == 0) {
                    stationaryStartTime = currentTime;
                } else if (currentTime - stationaryStartTime > ZUPT_MIN_DURATION) {
                    isStationary = true;
                    Log.d(TAG, "ZUPT: Stationary detected");
                }
            }
        } else {
            isStationary = false;
            stationaryStartTime = 0;
        }
    }
    
    /**
     * Apply graduated correction toward anchor point.
     */
    private void applyGradualCorrection() {
        double rate = 0.03;  // 3% per update
        
        state[0] += correctionX * rate;
        state[1] += correctionY * rate;
        
        correctionX *= (1 - rate);
        correctionY *= (1 - rate);
        
        // Clear anchor if correction is small
        if (Math.abs(correctionX) < 0.1 && Math.abs(correctionY) < 0.1) {
            hasAnchor = false;
        }
    }
    
    /**
     * Check and set building constraint.
     */
    private void checkBuildingConstraint(double lat, double lng) {
        // Nucleus building
        if (lat >= 55.92282 && lat <= 55.92332 &&
            lng >= -3.17460 && lng <= -3.17387) {
            buildingBounds = new double[]{55.92282, 55.92332, -3.17460, -3.17387};
            constrainEnabled = true;
            Log.d(TAG, "Inside Nucleus - constraint enabled");
            return;
        }
        
        // Library building
        if (lat >= 55.92260 && lat <= 55.92310 &&
            lng >= -3.17530 && lng <= -3.17440) {
            buildingBounds = new double[]{55.92260, 55.92310, -3.17530, -3.17440};
            constrainEnabled = true;
            Log.d(TAG, "Inside Library - constraint enabled");
            return;
        }
        
        constrainEnabled = false;
        buildingBounds = null;
    }
    
    /**
     * Constrain position to building bounds.
     */
    private void constrainPosition() {
        if (buildingBounds == null) return;
        
        double metersPerDegLng = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(startLat));
        
        // Convert state to lat/lng
        double lat = startLat + state[1] / METERS_PER_DEG_LAT;
        double lng = startLng + state[0] / metersPerDegLng;
        
        // Constrain
        double oldLat = lat, oldLng = lng;
        lat = Math.max(buildingBounds[0], Math.min(buildingBounds[1], lat));
        lng = Math.max(buildingBounds[2], Math.min(buildingBounds[3], lng));
        
        // If constrained, update state
        if (lat != oldLat || lng != oldLng) {
            state[0] = (lng - startLng) * metersPerDegLng;
            state[1] = (lat - startLat) * METERS_PER_DEG_LAT;
            
            // Also zero velocity in constrained direction
            if (lat != oldLat) state[3] = 0;
            if (lng != oldLng) state[2] = 0;
        }
    }
    
    // ========== OUTPUT METHODS ==========
    
    /**
     * Get fused latitude with smoothing.
     */
    public double getFusedLatitude() {
        double rawLat = startLat + state[1] / METERS_PER_DEG_LAT;
        smoothY = smoothY + SMOOTH_FACTOR * (state[1] - smoothY);
        return startLat + smoothY / METERS_PER_DEG_LAT;
    }
    
    /**
     * Get fused longitude with smoothing.
     */
    public double getFusedLongitude() {
        double metersPerDegLng = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(startLat));
        smoothX = smoothX + SMOOTH_FACTOR * (state[0] - smoothX);
        return startLng + smoothX / metersPerDegLng;
    }
    
    /**
     * Get current position as LatLng.
     */
    public LatLng getPosition() {
        return new LatLng(getFusedLatitude(), getFusedLongitude());
    }
    
    /**
     * Get position uncertainty (meters).
     */
    public float getPositionUncertainty() {
        return (float) Math.sqrt(P[0][0] + P[1][1]);
    }
    
    /**
     * Get current velocity magnitude (m/s).
     */
    public double getSpeed() {
        return Math.sqrt(state[2]*state[2] + state[3]*state[3]);
    }
    
    /**
     * Get current heading (radians).
     */
    public double getHeading() {
        return state[4];
    }
    
    /**
     * Get PDR offset in meters.
     */
    public float[] getPdrOffset() {
        return new float[]{(float)state[0], (float)state[1]};
    }
    
    /**
     * Get step count.
     */
    public int getStepCount() {
        return stepCount;
    }
    
    /**
     * Check if stationary.
     */
    public boolean isStationary() {
        return isStationary;
    }
    
    /**
     * Check if constrained to building.
     */
    public boolean isConstrainedToBuilding() {
        return constrainEnabled;
    }
    
    /**
     * Get adaptive step length estimate.
     */
    public double getAdaptiveStepLength() {
        return stepLengthEstimate;
    }
    
    /**
     * Has anchor point.
     */
    public boolean hasAnchorPoint() {
        return hasAnchor;
    }
    
    // ========== CONTROL METHODS ==========
    
    public void setConstrainToBuilding(boolean enable) {
        this.constrainEnabled = enable;
    }
    
    public void setBuildingBounds(double minLat, double maxLat, double minLng, double maxLng) {
        this.buildingBounds = new double[]{minLat, maxLat, minLng, maxLng};
        this.constrainEnabled = true;
    }
    
    public void forceReset(double lat, double lng, float accuracy) {
        initialize(lat, lng, accuracy);
    }
    
    public void reset() {
        state = new double[5];
        P = new double[5][5];
        for (int i = 0; i < 5; i++) P[i][i] = 10.0;
        
        startLat = 0;
        startLng = 0;
        initialized = false;
        
        lastPdrX = 0;
        lastPdrY = 0;
        lastStepTime = 0;
        stepCount = 0;
        
        smoothX = 0;
        smoothY = 0;
        
        hasAnchor = false;
        constrainEnabled = false;
        buildingBounds = null;
        
        Log.d(TAG, "EKF reset");
    }
    
    // Compatibility methods for old interface
    public void updateWithPDR(float pdrX, float pdrY) {
        updateWithStep(pdrX, pdrY);
    }
    
    public void updateWithPDR(float pdrX, float pdrY, float heading, float stepLength) {
        updateWithStep(pdrX, pdrY);
        updateWithMagnetometer(heading);
    }
    
    public long getTimeSinceLastGnss() { return Long.MAX_VALUE; }
    public boolean isGnssStale() { return true; }
    public double getFilteredHeading() { return state[4]; }
}
