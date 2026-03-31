package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.SensorManager;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.BuildingPolygon;
import com.openpositioning.PositionMe.utils.GeometryUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Objects;
import java.util.stream.Collectors;

// Processes data recorded in the {@link SensorFusion} class and calculates live PDR estimates.
// It calculates the position from the steps and directions detected, using either estimated values
// (eg. stride length from the Weiberg algorithm) or provided constants, calculates the elevation
// and attempts to estimate the current floor as well as elevators.
// @author Mate Stodulka
// @author Michal Dvorak
public class PdrProcessing {

    // region Static variables
    // Weiberg algorithm coefficient for stride calculations
    private static final float K = 0.364f;
    // Number of samples (seconds) to keep as memory for elevation calculation
    private static final int elevationSeconds = 4;
    // Number of samples (0.01 seconds)
    private static final int accelSamples = 100;
    // Threshold used to detect significant movement
    private static final float movementThreshold = 0.3f; // m/s^2
    // Threshold under which movement is considered non-existent
    private static final float epsilon = 0.18f;
    private static final int MIN_REQUIRED_SAMPLES = 2;
    private static final float FLOOR_SWITCH_ENTER_RATIO = 0.65f;
    private static final float FLOOR_SWITCH_STRONG_PROGRESS_RATIO = 0.9f;
    private static final float FLOOR_BASELINE_RECENTER_RATIO = 0.28f;
    private static final float ANCHORED_FLOOR_SWITCH_ENTER_RATIO = 0.35f;
    private static final float ANCHORED_FLOOR_SWITCH_STRONG_PROGRESS_RATIO = 0.45f;
    private static final int FLOOR_SWITCH_CONFIRMATIONS = 2;
    private static final int MAX_SINGLE_FLOOR_JUMP = 1;
    private static final double DEFAULT_MAG_LOOKUP_DISTANCE_METERS = 10.0;
    private static final float DEFAULT_MAG_MAX_CORRECTION_RAD = (float) Math.toRadians(15.0);
    // endregion

    // region Instance variables
    // Settings for accessing shared variables
    private SharedPreferences settings;

    // Step length
    private float stepLength;
    // Using manually input constants instead of estimated values
    private boolean useManualStep;

    // Current 2D position coordinates (raw PDR)
    private float positionX;
    private float positionY;

    // Fused 2D position coordinates in East/North meters (sensor fusion result)
    private double fusedX;
    private double fusedY;

    private long previousStepEnd = -1;

    // Motion analysis for stairs/elevator detection
    private float lastHorizontalMovement = 0f;
    private boolean isElevatorMotion = false;
    private boolean isStairsMotion = false;
    private float lastSmoothedRelativeElevation;
    private List<List<LatLng>> indoorStairsZones = new java.util.ArrayList<>();
    private List<List<LatLng>> indoorLiftZones = new java.util.ArrayList<>();
    private List<List<LatLng>> indoorWalls = new java.util.ArrayList<>();
    private float[] floorAltitudeAnchorsMeters = null;
    private int pendingFloorCandidate = Integer.MIN_VALUE;
    private int pendingFloorCandidateCount = 0;
    private boolean initialAnchorFloorResolved = false;

    // Predefined map elements for stairs/elevator zones (approximate coordinates)
    private static final float MAP_ZONE_RADIUS_METERS = 8.0f;
    private static final LatLng[] STAIRS_ZONES = new LatLng[] {
            new LatLng(55.92305, -3.17420), // nucleus stair
            new LatLng(55.92310, -3.17400)  // library stair
    };
    private static final LatLng[] LIFT_ZONES = new LatLng[] {
            new LatLng(55.92310, -3.17395), // nucleus lift
            new LatLng(55.92302, -3.17410)  // library lift
    };

    // Kalman filter covariances for fusion
    private double[][] P;
    // Kalman filter noise settings tuned for PDR-dominant indoor fusion.
    private final double[][] Q = {{0.01, 0}, {0, 0.01}};        // process noise (decreased from 0.5)
    private final double[][] R_GNSS = {{4, 0}, {0, 4}};
    private final double[][] R_WIFI = {{36, 0}, {0, 36}};
    
    // GNSS/WiFi correction limits to keep heading and path direction PDR-dominant.
    private static final float GNSS_ERROR_THRESHOLD_METERS = 5.0f;
    private static final float WIFI_ERROR_THRESHOLD_METERS = 3.0f;
    private static final float GNSS_MIN_ACCURACY_METERS = 6.0f;
    private static final float GNSS_STABLE_RADIUS_METERS = 1.2f;
    private static final int GNSS_STABLE_CONFIRMATIONS_REQUIRED = 2;
    private static final double GNSS_POSITION_NUDGE_FACTOR = 0.30; // 2% (previously 1%)
    private static final double WIFI_POSITION_NUDGE_FACTOR = 0.18;  // 1% ultra-light WiFi pull
    private static final float MIN_EFFECTIVE_STEP_DISPLACEMENT_METERS = 0.28f;
    private static final double GNSS_MAX_CORRECTION_PER_UPDATE_METERS = 0.60;
    private static final double WIFI_MAX_CORRECTION_PER_UPDATE_METERS = 0.50;

    private boolean fusionInitialized = false;
    private double originLat;
    private double originLon;

    // Vertical movement calculation
    private Float[] startElevationBuffer;
    private float startElevation;
    private int setupIndex = 0;
    private float elevation;
    private float floorHeight;
    private int currentFloor;

    // Buffer of most recent elevations calculated
    private CircularFloatBuffer elevationList;

    // Buffer for most recent directional acceleration magnitudes
    private CircularFloatBuffer verticalAccel;
    private CircularFloatBuffer horizontalAccel;

    // Step sum and length aggregation variables
    private float sumStepLength = 0;
    private int stepCount = 0;

    // Keep track of the most recent coordinates for each source.
    private static final int MAX_COORDINATE_HISTORY = 7;
    // History buffers for GNSS, WiFi, and PDR coordinates (stores LatLng)
    private java.util.Queue<LatLng> gnssHistoryBuffer = new java.util.LinkedList<>();
    private java.util.Queue<LatLng> wifiHistoryBuffer = new java.util.LinkedList<>();
    private java.util.Queue<LatLng> pdrHistoryBuffer = new java.util.LinkedList<>();
    private LatLng lastStableGnssCandidate = null;
    private int stableGnssCandidateCount = 0;

    // Magnetic heading compensation (grid lookup)
    private MagneticCompensation magneticCompensation = MagneticCompensation.empty();
    private boolean enableMagneticCompensation = true;
    // endregion

    // Public constructor for the PDR class.
    // Takes context for variable access. Sets initial values based on settings.
    // @param context Application context for variable access.
    public PdrProcessing(Context context) {
        // Initialise settings
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        // Check if estimate or manual values should be used
        this.useManualStep = this.settings.getBoolean("manual_step_values", false);
        if(useManualStep) {
            try {
                // Retrieve manual step length
                this.stepLength = this.settings.getInt("user_step_length", 75) / 100f;
            } catch (Exception e) {
                // Invalid values - reset to defaults
                this.stepLength = 0.75f;
                this.settings.edit().putInt("user_step_length", 75).apply();
            }
        }
        else {
            // Using estimated step length - set to zero
            this.stepLength = 0;
        }

        // Initial position and elevation - starts from zero
        this.positionX = 0f;
        this.positionY = 0f;
        this.elevation = 0f;


        if(this.settings.getBoolean("overwrite_constants", false)) {
            // Capacity - pressure is read with 1Hz - store values of past 10 seconds
            this.elevationList = new CircularFloatBuffer(Integer.parseInt(settings.getString("elevation_seconds", "4")));

            // Buffer for most recent acceleration values
            this.verticalAccel = new CircularFloatBuffer(Integer.parseInt(settings.getString("accel_samples", "4")));
            this.horizontalAccel = new CircularFloatBuffer(Integer.parseInt(settings.getString("accel_samples", "4")));
        }
        else {
            // Capacity - pressure is read with 1Hz - store values of past 10 seconds
            this.elevationList = new CircularFloatBuffer(elevationSeconds);

            // Buffer for most recent acceleration values
            this.verticalAccel = new CircularFloatBuffer(accelSamples);
            this.horizontalAccel = new CircularFloatBuffer(accelSamples);
        }

        // Distance between floors is building dependent, use manual value
        this.floorHeight = settings.getInt("floor_height", 4);
        // Array for holding initial values
        this.startElevationBuffer = new Float[3];
        // Start floor - assumed to be zero
        this.currentFloor = 0;

        initializeMagneticCompensation(context);
    }

    // Re-read settings from SharedPreferences (e.g. step length).
    // Call this when starting recording to ensure latest settings are used.
    public void refreshSettings() {
        this.useManualStep = this.settings.getBoolean("manual_step_values", false);
        if(useManualStep) {
            try {
                this.stepLength = this.settings.getInt("user_step_length", 75) / 100f;
            } catch (Exception e) {
                this.stepLength = 0.75f;
            }
        } else {
            this.stepLength = 0;
        }

        this.enableMagneticCompensation = this.settings.getBoolean("enable_magnetic_compensation", true);
        if (this.magneticCompensation != null) {
            this.magneticCompensation.setEnabled(this.enableMagneticCompensation);
        }
    }

    private void initializeMagneticCompensation(Context context) {
        this.enableMagneticCompensation = this.settings.getBoolean("enable_magnetic_compensation", true);
        this.magneticCompensation = MagneticGridLoader.loadFromAssets(
                context,
                "magnetic_grid.json",
                DEFAULT_MAG_LOOKUP_DISTANCE_METERS,
                DEFAULT_MAG_MAX_CORRECTION_RAD
        );
        this.magneticCompensation.setEnabled(this.enableMagneticCompensation);
        Log.d("PdrProcessing", "Magnetic compensation loaded: cells=" + this.magneticCompensation.getCellCount()
                + ", enabled=" + this.enableMagneticCompensation);
    }

    private float applyHeadingCompensation(float headingRad) {
        if (!enableMagneticCompensation || magneticCompensation == null) {
            return headingRad;
        }

        double currentX = this.fusionInitialized ? this.fusedX : this.positionX;
        double currentY = this.fusionInitialized ? this.fusedY : this.positionY;
        float correctionRad = magneticCompensation.getCorrectionAngle(currentX, currentY);
        if (Float.isNaN(correctionRad) || Float.isInfinite(correctionRad)) {
            correctionRad = 0.0f;
        }

        float corrected = normalizeRadians(headingRad + correctionRad);
        if (Math.abs(correctionRad) > 0.01f) {
            Log.d("PdrProcessing", String.format(
                    "Heading compensation applied: raw=%.3f rad, correction=%.3f rad, corrected=%.3f rad",
                    headingRad, correctionRad, corrected));
        }
        return corrected;
    }

    private float normalizeRadians(float angleRad) {
        float twoPi = (float) (Math.PI * 2.0);
        float normalized = angleRad % twoPi;
        if (normalized > Math.PI) {
            normalized -= twoPi;
        } else if (normalized < -Math.PI) {
            normalized += twoPi;
        }
        return normalized;
    }

    // Function to calculate PDR coordinates from sensor values.
    // Should be called from the step detector sensor's event with the sensor values since the last
    // step.
    // @param currentStepEnd relative time in milliseconds since the start of the recording.
    // @param accelMagnitudeOvertime recorded acceleration magnitudes since the last step.
    // @param headingRad heading relative to magnetic north in radians.
    public float[] updatePdr(long currentStepEnd, List<Double> accelMagnitudeOvertime, float headingRad) {
        if (accelMagnitudeOvertime == null || accelMagnitudeOvertime.size() < MIN_REQUIRED_SAMPLES) {
            return new float[]{this.positionX, this.positionY};  // Return current position without update
                                                                // TODO - temporary solution of the empty list issue
        }

        // check if accelMagnitudeOvertime is empty
        if (accelMagnitudeOvertime == null || accelMagnitudeOvertime.isEmpty()) {
            // return current position, do not update
            return new float[]{this.positionX, this.positionY};
        }
        
        // Calculate step length
        if(!useManualStep) {
            long stepDurationMs = -1;
            if (this.previousStepEnd != -1 && currentStepEnd > this.previousStepEnd) {
                stepDurationMs = currentStepEnd - this.previousStepEnd;
            }
            this.previousStepEnd = currentStepEnd;

            // ArrayList<Double> accelMagnitudeFiltered = filter(accelMagnitudeOvertime);
            // Estimate stride
            this.stepLength = weibergMinMax(accelMagnitudeOvertime, stepDurationMs);
            // System.err.println("Step Length" + stepLength);
        }

        // Increment aggregate variables
        sumStepLength += stepLength;
        stepCount++;

        // Apply position-aware heading correction from magnetic grid.
        float correctedHeadingRad = applyHeadingCompensation(headingRad);

        // Change angle so zero rad is east
        float adaptedHeading = (float) (Math.PI/2 - correctedHeadingRad);

        // Translate to cartesian coordinate system
        float x = (float) (stepLength * Math.cos(adaptedHeading));
        float y = (float) (stepLength * Math.sin(adaptedHeading));

        float stepDisplacement = (float) Math.hypot(x, y);
        if (stepDisplacement < MIN_EFFECTIVE_STEP_DISPLACEMENT_METERS) {
            Log.d("PdrProcessing", String.format(
                "Micro-step suppressed: %.3fm < %.3fm",
                stepDisplacement, MIN_EFFECTIVE_STEP_DISPLACEMENT_METERS));
            return new float[]{this.positionX, this.positionY};
        }

        // Update position values
        this.positionX += x;
        this.positionY += y;

        // Motion classification
        this.lastHorizontalMovement = (float)Math.hypot(x, y);
        this.isStairsMotion = this.lastHorizontalMovement > 0.5f;
        // Elevator motion monitored in estimateElevator() from barometer/accelerometer pattern

        // EKF fusion prediction step (PDR motion model in East/North meters)
        if (this.fusionInitialized) {
            predictFusion(x, y);
        }

        // Save current PDR position in history buffer.
        LatLng pdrPoint = localToLatLon(this.positionX, this.positionY);
        pdrHistoryBuffer.offer(pdrPoint);
        if (pdrHistoryBuffer.size() > MAX_COORDINATE_HISTORY) {
            pdrHistoryBuffer.poll();
        }

        // return raw PDR position
        return new float[]{this.positionX, this.positionY};
    }

    // Update PDR with a specific step length (e.g. from Weinberg algorithm).
    // @param stepLengthMeters The calculated stride length in meters.
    // @param headingRad Current heading in radians.
    // @return New [x, y] coordinates.
    public float[] updatePdrWithStride(float stepLengthMeters, float headingRad) {
        // Apply position-aware heading correction from magnetic grid.
        float correctedHeadingRad = applyHeadingCompensation(headingRad);

        // Change angle so zero rad is east
        float adaptedHeading = (float) (Math.PI/2 - correctedHeadingRad);

        // Increment aggregate variables
        this.sumStepLength += stepLengthMeters;
        this.stepCount++;

        // Translate to cartesian coordinate system
        float x = (float) (stepLengthMeters * Math.cos(adaptedHeading));
        float y = (float) (stepLengthMeters * Math.sin(adaptedHeading));

        float stepDisplacement = (float) Math.hypot(x, y);
        if (stepDisplacement < MIN_EFFECTIVE_STEP_DISPLACEMENT_METERS) {
            Log.d("PdrProcessing", String.format(
                "Micro-step suppressed (manual stride): %.3fm < %.3fm",
                stepDisplacement, MIN_EFFECTIVE_STEP_DISPLACEMENT_METERS));
            return new float[]{this.positionX, this.positionY};
        }

        // Update position values
        this.positionX += x;
        this.positionY += y;

        // Fusion prediction step
        if (this.fusionInitialized) {
            predictFusion(x, y);
        }

        // Save current PDR position in history buffer.
        LatLng pdrPoint = localToLatLon(this.positionX, this.positionY);
        pdrHistoryBuffer.offer(pdrPoint);
        if (pdrHistoryBuffer.size() > MAX_COORDINATE_HISTORY) {
            pdrHistoryBuffer.poll();
        }

        return new float[]{this.positionX, this.positionY};
    }

    // Calculates the relative elevation compared to the start position.
    // The start elevation is the median of the first three seconds of data to give the sensor time
    // to settle. The sea level is irrelevant as only values relative to the initial position are
    // reported.
    // @param absoluteElevation absolute elevation in meters compared to sea level.
    // @return current elevation in meters relative to the start position.
    public float updateElevation(float absoluteElevation) {
        // Set start to median of first three values
        if(setupIndex < 3) {
            // Add values to buffer until it's full
            this.startElevationBuffer[setupIndex] = absoluteElevation;
            // When buffer is full, find median, assign as startElevation
            if(setupIndex == 2) {
                Arrays.sort(startElevationBuffer);
                startElevation = startElevationBuffer[1];
            }
            this.setupIndex++;
        }
        else {
            // Add raw absolute elevation to buffer for smoothing
            this.elevationList.putNewest(absoluteElevation);

            // Update floor estimation from barometer-based height
            if(this.elevationList.isFull()) {
                // Use stabilized average over the recent window to avoid noise.
                List<Float> elevationMemory = this.elevationList.getListCopy();
                OptionalDouble currentAvg = elevationMemory.stream().mapToDouble(f -> f).average();
                float smoothedAbsoluteElevation = currentAvg.isPresent() ? (float) currentAvg.getAsDouble() : absoluteElevation;
                float smoothedRelativeElevation = smoothedAbsoluteElevation - startElevation;
                float deltaFromCurrentFloor = getDeltaFromCurrentFloor(smoothedAbsoluteElevation);
                int detectedFloor = detectFloorFromAbsoluteElevation(smoothedAbsoluteElevation, smoothedRelativeElevation);
                boolean bootstrapInitialFloorByAnchors = hasFloorAltitudeAnchors() && !initialAnchorFloorResolved;
                boolean strongVerticalProgress = Math.abs(deltaFromCurrentFloor)
                        >= getStrongProgressThreshold(this.currentFloor, detectedFloor);
                boolean floorAllowed = bootstrapInitialFloorByAnchors || isFloorChangeAllowed(strongVerticalProgress);
                boolean farEnoughFromCurrentFloor = Math.abs(deltaFromCurrentFloor)
                        >= getFloorSwitchEnterThreshold(this.currentFloor, detectedFloor);
                boolean floorJumpReasonable = bootstrapInitialFloorByAnchors
                    || Math.abs(detectedFloor - this.currentFloor) <= MAX_SINGLE_FLOOR_JUMP;
                int requiredConfirmations = bootstrapInitialFloorByAnchors ? 1 : FLOOR_SWITCH_CONFIRMATIONS;

                if (detectedFloor != this.currentFloor && farEnoughFromCurrentFloor && floorJumpReasonable && floorAllowed) {
                    if (pendingFloorCandidate == detectedFloor) {
                        pendingFloorCandidateCount++;
                    } else {
                        pendingFloorCandidate = detectedFloor;
                        pendingFloorCandidateCount = 1;
                    }

                    if (pendingFloorCandidateCount >= requiredConfirmations) {
                        this.currentFloor = detectedFloor;
                        pendingFloorCandidate = Integer.MIN_VALUE;
                        pendingFloorCandidateCount = 0;
                        if (bootstrapInitialFloorByAnchors) {
                            initialAnchorFloorResolved = true;
                        }
                        Log.d("PdrProcessing", "Floor changed to " + this.currentFloor + " (barometer elevation " + smoothedRelativeElevation + "m)");
                    }
                } else {
                    pendingFloorCandidate = Integer.MIN_VALUE;
                    pendingFloorCandidateCount = 0;
                    if (bootstrapInitialFloorByAnchors && detectedFloor == this.currentFloor) {
                        initialAnchorFloorResolved = true;
                    }
                }

                if (hasFloorAltitudeAnchors()) {
                    float floorAnchor = getAbsoluteFloorAnchor(this.currentFloor);
                    if (!Float.isNaN(floorAnchor)) {
                        this.startElevation = this.startElevation
                                + FLOOR_BASELINE_RECENTER_RATIO * (smoothedAbsoluteElevation - floorAnchor);
                    }
                } else if (!farEnoughFromCurrentFloor) {
                    float baselineTarget = smoothedAbsoluteElevation - this.currentFloor * this.floorHeight;
                    this.startElevation = this.startElevation
                            + FLOOR_BASELINE_RECENTER_RATIO * (baselineTarget - this.startElevation);
                }

                this.lastSmoothedRelativeElevation = smoothedRelativeElevation;
                this.elevation = smoothedAbsoluteElevation - this.startElevation;
                return this.elevation;
            }

            // Return current elevation
            this.elevation = absoluteElevation - startElevation;
            return this.elevation;
        }
        // Keep elevation at zero if there is no calculated value
        return 0;
    }

    // Uses the Weiberg Stride Length formula to calculate step length from accelerometer values.
    // Uses a step-frequency classifier to scale stride length.
    // @param accelMagnitude magnitude of acceleration values between the last and current step.
    // @param stepDurationMs duration of the current step in milliseconds.
    // @return float stride length in meters.
    private float weibergMinMax(List<Double> accelMagnitude, long stepDurationMs) {
        // if the list itself is null or empty, return 0 (or return other default values as needed)
        if (accelMagnitude == null || accelMagnitude.isEmpty()) {
            return 0f;
        }

        // filter out null values from the list
        List<Double> validAccel = accelMagnitude.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (validAccel.isEmpty()) {
            return 0f;
        }

        // calculate max and min values
        double maxAccel = Collections.max(validAccel);
        double minAccel = Collections.min(validAccel);

        // calculate bounce
        float bounce = (float) Math.pow((maxAccel - minAccel), 0.25);
        float weibergK = K;

        // determine which constant to use based on settings
        if (this.settings.getBoolean("overwrite_constants", false)) {
            weibergK = Float.parseFloat(settings.getString("weiberg_k", "0.934"));
        }

        // Base Weiberg calculation
        float baseStepLength = bounce * weibergK * 2;
        
        if (stepDurationMs <= 0 || stepDurationMs >= 2000) {
            return baseStepLength;
        }
        
        float stepFreq = 1000f / stepDurationMs; // Hz
        
        // Scale stride by walking cadence class.
        float frequencyFactor;
        if (stepFreq < 1.2f) {
            // Slow walking
            frequencyFactor = 0.85f;
        } else if (stepFreq < 1.8f) {
            // Normal walking
            frequencyFactor = 1.0f;
        } else if (stepFreq < 2.2f) {
            // Fast walking
            frequencyFactor = 1.1f;
        } else {
            // Very fast (jogging)
            frequencyFactor = 1.2f;
        }
        
        Log.d("PdrProcessing", String.format("Step: freq=%.2fHz factor=%.2f len=%.3fm", 
            stepFreq, frequencyFactor, baseStepLength * frequencyFactor));
        
        return baseStepLength * frequencyFactor;
    }

    // Get the current X and Y coordinates from the PDR processing class.
    // The coordinates are in meters, the start of the recording is the (0,0)
    // @return float array of size 2, with the X and Y coordinates respectively.
    public float[] getPDRMovement() {
        float [] pdrPosition= new float[] {positionX,positionY};
        return pdrPosition;

    }

    // Get the current elevation as calculated by the PDR class.
    // @return current elevation in meters, relative to the start position.
    public float getCurrentElevation() {
        return this.elevation;
    }

    // Get the current floor number as estimated by the PDR class.
    // @return current floor number, assuming start position is on level zero.
    public int getCurrentFloor() {
        return this.currentFloor;
    }

    public void setIndoorFeatureZones(List<List<LatLng>> stairsZones, List<List<LatLng>> liftZones, List<List<LatLng>> walls) {
        this.indoorStairsZones = stairsZones != null ? new java.util.ArrayList<>(stairsZones) : new java.util.ArrayList<>();
        this.indoorLiftZones = liftZones != null ? new java.util.ArrayList<>(liftZones) : new java.util.ArrayList<>();
        this.indoorWalls = walls != null ? new java.util.ArrayList<>(walls) : new java.util.ArrayList<>();
    }

    public void configureFloorReference(float floorHeightMeters, int floorIndex, float[] floorAltitudeAnchorsMeters) {
        if (!Float.isNaN(floorHeightMeters) && floorHeightMeters > 1.0f) {
            this.floorHeight = floorHeightMeters;
        }
        this.currentFloor = floorIndex;
        this.floorAltitudeAnchorsMeters = floorAltitudeAnchorsMeters != null
                ? java.util.Arrays.copyOf(floorAltitudeAnchorsMeters, floorAltitudeAnchorsMeters.length)
                : null;
        this.pendingFloorCandidate = Integer.MIN_VALUE;
        this.pendingFloorCandidateCount = 0;
        this.initialAnchorFloorResolved = false;
    }

    public void setMotionState(boolean stationary, float speedMetersPerSecond, float motionMagnitude) {
        // Compatibility hook for SensorFusion; keeps floor gating/motion hints in sync.
        if (!stationary && (speedMetersPerSecond > 0.55f || motionMagnitude > 0.22f)) {
            this.isStairsMotion = true;
        } else if (stationary) {
            this.isStairsMotion = false;
        }
    }

    private List<LatLng> initializationCandidates = new ArrayList<>();
    private static final int REQUIRED_INITIALIZATION_SAMPLES = 3;

    // Initialise fusion with a starting GNSS or WiFi location in latitude/longitude.
    // Modified to use average of early samples for more robust initialization.
    public void initializeWithLocation(double latitude, double longitude) {
        if (!this.fusionInitialized) {
            initializationCandidates.add(new LatLng(latitude, longitude));
            
            if (initializationCandidates.size() >= REQUIRED_INITIALIZATION_SAMPLES) {
                // Average the samples for better precision
                double sumLat = 0;
                double sumLon = 0;
                for (LatLng candidate : initializationCandidates) {
                    sumLat += candidate.latitude;
                    sumLon += candidate.longitude;
                }
                this.originLat = sumLat / initializationCandidates.size();
                this.originLon = sumLon / initializationCandidates.size();

                double[] local = latLonToLocal(this.originLat, this.originLon);
                this.fusedX = local[0];
                this.fusedY = local[1];

                this.positionX = 0f;
                this.positionY = 0f;

                // Reduced initial covariance for faster convergence.
                this.P = new double[][]{{1.0, 0}, {0, 1.0}};
                this.fusionInitialized = true;
                initializationCandidates.clear(); // clean up
            }
        }
    }

    private double[] latLonToLocal(double latitude, double longitude) {
        double dx = (longitude - this.originLon) * 111111.0 * Math.cos(Math.toRadians(this.originLat));
        double dy = (latitude - this.originLat) * 111111.0;
        return new double[]{dx, dy};
    }

    private LatLng localToLatLon(double x, double y) {
        double lat = this.originLat + (y / 111111.0);
        double lon = this.originLon + (x / (111111.0 * Math.cos(Math.toRadians(this.originLat))));
        return new LatLng(lat, lon);
    }

    private void applyMapMatching(double prevX, double prevY) {
        LatLng fusedLatLon = localToLatLon(this.fusedX, this.fusedY);
        if (!BuildingPolygon.inNucleus(fusedLatLon) && !BuildingPolygon.inLibrary(fusedLatLon)) {
            // Keep previous position to avoid walking through walls/outside building area
            this.fusedX = prevX;
            this.fusedY = prevY;
            return;
        }

        // Check if movement crossed any indoor wall
        if (indoorWalls != null && !indoorWalls.isEmpty()) {
            LatLng prevLatLon = localToLatLon(prevX, prevY);
            if (GeometryUtils.crossesWall(prevLatLon, fusedLatLon, indoorWalls)) {
                Log.d("PdrProcessing", "EKF Update blocked by indoor wall collision");
                this.fusedX = prevX;
                this.fusedY = prevY;
                return;
            }
        }

        // Additional proximity-based behavior: don't allow spontaneous floor change unless near stairs/lift
        if (!isNearStairsOrLift() && this.currentFloor != getFloorFromBarometer()) {
            // revert to previous floor if map policy forbids unstructured floor jump
            Log.d("PdrProcessing", "Floor change blocked, not near stairs/lift");
            this.currentFloor = getFloorFromBarometer();
        }
    }

    private void predictFusion(double dx, double dy) {
        double prevX = this.fusedX;
        double prevY = this.fusedY;

        // Process model: fused position shifts by PDR delta
        this.fusedX += dx;
        this.fusedY += dy;

        // Covariance update
        P[0][0] += Q[0][0];
        P[0][1] += Q[0][1];
        P[1][0] += Q[1][0];
        P[1][1] += Q[1][1];

        applyMapMatching(prevX, prevY);
    }

    private void updateWithMeasurement(double measX, double measY, double[][] R) {
        if (!fusionInitialized || P == null) {
            return;
        }

        double y0 = measX - this.fusedX;
        double y1 = measY - this.fusedY;

        double s00 = P[0][0] + R[0][0];
        double s01 = P[0][1] + R[0][1];
        double s10 = P[1][0] + R[1][0];
        double s11 = P[1][1] + R[1][1];

        double detS = s00 * s11 - s01 * s10;
        if (Math.abs(detS) < 1e-9) return;

        double invS00 = s11 / detS;
        double invS01 = -s01 / detS;
        double invS10 = -s10 / detS;
        double invS11 = s00 / detS;

        // Kalman gain K = P S^{-1}
        double k00 = P[0][0] * invS00 + P[0][1] * invS10;
        double k01 = P[0][0] * invS01 + P[0][1] * invS11;
        double k10 = P[1][0] * invS00 + P[1][1] * invS10;
        double k11 = P[1][0] * invS01 + P[1][1] * invS11;

        double prevX = this.fusedX;
        double prevY = this.fusedY;

        // State update
        this.fusedX += k00 * y0 + k01 * y1;
        this.fusedY += k10 * y0 + k11 * y1;

        // Covariance update
        double i00 = 1 - k00;
        double i01 = -k01;
        double i10 = -k10;
        double i11 = 1 - k11;
        double newP00 = i00 * P[0][0] + i01 * P[1][0];
        double newP01 = i00 * P[0][1] + i01 * P[1][1];
        double newP10 = i10 * P[0][0] + i11 * P[1][0];
        double newP11 = i10 * P[0][1] + i11 * P[1][1];
        P[0][0] = newP00;
        P[0][1] = newP01;
        P[1][0] = newP10;
        P[1][1] = newP11;

        applyMapMatching(prevX, prevY);
    }

    // Process a GNSS measurement - Light trajectory correction WITHOUT affecting heading direction.
    // GNSS is unreliable indoors but can provide light position hints.
    // Heading is NEVER affected - only XY position gets minimal adjustment.
    public void processGnssLocation(double latitude, double longitude, float accuracy) {
        LatLng gnssPoint = new LatLng(latitude, longitude);

        if (!fusionInitialized) {
            // Save GNSS position to history for reference only
            gnssHistoryBuffer.offer(gnssPoint);
            if (gnssHistoryBuffer.size() > MAX_COORDINATE_HISTORY) {
                gnssHistoryBuffer.poll();
            }
            return;
        }

        double[] local = latLonToLocal(latitude, longitude);
        
        // Calculate distance error between GNSS and current fused position
        double errorDistance = Math.sqrt(
            Math.pow(local[0] - this.fusedX, 2) + 
            Math.pow(local[1] - this.fusedY, 2)
        );
        
        // Apply only tiny position nudge with stricter GNSS gates.
        if (errorDistance > GNSS_ERROR_THRESHOLD_METERS) {
            Log.d("PdrProcessing", "GNSS rejected: error " + errorDistance + "m > threshold " + GNSS_ERROR_THRESHOLD_METERS + "m");
            stableGnssCandidateCount = 0;
        } else if (accuracy > GNSS_MIN_ACCURACY_METERS) {
            stableGnssCandidateCount = 0;
        } else if (accuracy <= GNSS_MIN_ACCURACY_METERS) {
            if (lastStableGnssCandidate != null
                    && getDistanceMeters(lastStableGnssCandidate, gnssPoint) <= GNSS_STABLE_RADIUS_METERS) {
                stableGnssCandidateCount++;
            } else {
                stableGnssCandidateCount = 1;
            }
            lastStableGnssCandidate = gnssPoint;

            if (stableGnssCandidateCount >= GNSS_STABLE_CONFIRMATIONS_REQUIRED) {
                double correctionFactor = GNSS_POSITION_NUDGE_FACTOR;
                double errorX = local[0] - this.fusedX;
                double errorY = local[1] - this.fusedY;

                applyBoundedCorrection(errorX, errorY, correctionFactor, GNSS_MAX_CORRECTION_PER_UPDATE_METERS);

                Log.d("PdrProcessing", String.format(
                        "GNSS gated correction: %.2fm error, stable %d times, applied %.2f%% adjustment",
                        errorDistance, GNSS_STABLE_CONFIRMATIONS_REQUIRED, correctionFactor * 100));

                // Apply only once per stable sequence.
                stableGnssCandidateCount = 0;
            } else {
                Log.d("PdrProcessing", "GNSS waiting stable confirmations: "
                        + stableGnssCandidateCount + "/" + GNSS_STABLE_CONFIRMATIONS_REQUIRED);
            }
        }
        
        // Save GNSS position to history for reference
        gnssHistoryBuffer.offer(gnssPoint);
        if (gnssHistoryBuffer.size() > MAX_COORDINATE_HISTORY) {
            gnssHistoryBuffer.poll();
        }
    }

    // Process a WiFi measurement - Light trajectory correction WITHOUT affecting heading direction.
    // WiFi is used primarily for floor level detection.
    // Position can receive light corrections but heading is NEVER affected.
    public void processWifiLocation(LatLng wifiLocation, int floor) {
        if (wifiLocation == null) {
            return;
        }

        // Apply light WiFi position correction and floor detection.
        Log.d("PdrProcessing", String.format("WiFi received: lat=%.6f, lon=%.6f, floor=%d",
            wifiLocation.latitude, wifiLocation.longitude, floor));
        
        if (fusionInitialized) {
            double[] local = latLonToLocal(wifiLocation.latitude, wifiLocation.longitude);
            
            // Calculate distance error between WiFi and current fused position
            double errorDistance = Math.sqrt(
                Math.pow(local[0] - this.fusedX, 2) + 
                Math.pow(local[1] - this.fusedY, 2)
            );
            
            // Keep WiFi pull extremely small to satisfy assignment without distorting direction.
            if (errorDistance <= WIFI_ERROR_THRESHOLD_METERS && WIFI_POSITION_NUDGE_FACTOR > 0.0) {
                double correctionFactor = WIFI_POSITION_NUDGE_FACTOR;
                double errorX = local[0] - this.fusedX;
                double errorY = local[1] - this.fusedY;

                applyBoundedCorrection(errorX, errorY, correctionFactor, WIFI_MAX_CORRECTION_PER_UPDATE_METERS);

                Log.d("PdrProcessing", String.format(
                    "WiFi ultra-light correction: %.2fm error, applied %.2f%% position adjustment (heading protected)",
                    errorDistance, correctionFactor * 100));
            } else {
                Log.d("PdrProcessing", "WiFi correction skipped (distance gate or nudge factor <= 0)");
            }
        }
        
        // Floor level detection - independent of position
        if (Math.abs(floor - this.currentFloor) > 0) {
            // Only change floor if we're in known stair/elevator zones
            boolean inStairsOrLift = isNearStairsOrLift() || this.isElevatorMotion || this.isStairsMotion;
            if (inStairsOrLift) {
                this.currentFloor = floor;
                Log.d("PdrProcessing", "WiFi floor change applied: " + floor + " (near stairs/lift)");
            } else {
                Log.d("PdrProcessing", "WiFi floor ignored (not near stairs/lift)");
            }
        }
        
        // Save WiFi position to history for reference
        wifiHistoryBuffer.offer(wifiLocation);
        if (wifiHistoryBuffer.size() > MAX_COORDINATE_HISTORY) {
            wifiHistoryBuffer.poll();
        }
    }

    public float[] getFusedPosition() {
        return new float[]{(float)this.fusedX, (float)this.fusedY};
    }

    public LatLng getFusedLatLon() {
        return localToLatLon(this.fusedX, this.fusedY);
    }

    private void applyBoundedCorrection(double errorX, double errorY, double correctionFactor, double maxCorrectionMeters) {
        double proposedX = errorX * correctionFactor;
        double proposedY = errorY * correctionFactor;
        double magnitude = Math.hypot(proposedX, proposedY);

        if (magnitude > maxCorrectionMeters && magnitude > 1e-6) {
            double scale = maxCorrectionMeters / magnitude;
            proposedX *= scale;
            proposedY *= scale;
        }

        this.fusedX += proposedX;
        this.fusedY += proposedY;
    }

    // Coordinate history getters
    // Get the history of GNSS verified coordinates (last up to 7 positions)
    // @return List of LatLng points representing historical GNSS positions
    public List<LatLng> getGnssHistoryBuffer() {
        return new ArrayList<>(gnssHistoryBuffer);
    }

    // Get the history of WiFi verified coordinates (last up to 7 positions)
    // @return List of LatLng points representing historical WiFi positions
    public List<LatLng> getWifiHistoryBuffer() {
        return new ArrayList<>(wifiHistoryBuffer);
    }

    // Get the history of PDR calculated coordinates (last up to 7 positions)
    // @return List of LatLng points representing historical PDR positions
    public List<LatLng> getPdrHistoryBuffer() {
        return new ArrayList<>(pdrHistoryBuffer);
    }

    // Get all coordinate histories in a combined format for display/logging
    // @return Map containing GNSS, WiFi, and PDR history buffers
    public java.util.Map<String, List<LatLng>> getAllCoordinateHistories() {
        java.util.Map<String, List<LatLng>> histories = new java.util.HashMap<>();
        histories.put("GNSS", new ArrayList<>(gnssHistoryBuffer));
        histories.put("WiFi", new ArrayList<>(wifiHistoryBuffer));
        histories.put("PDR", new ArrayList<>(pdrHistoryBuffer));
        return histories;
    }

    // Clear all coordinate history buffers (useful for resetting tracking)
    public void clearCoordinateHistories() {
        gnssHistoryBuffer.clear();
        wifiHistoryBuffer.clear();
        pdrHistoryBuffer.clear();
        Log.d("PdrProcessing", "Coordinate history buffers cleared");
    }

    public int getFloorFromBarometer() {
        float absoluteElevation = this.startElevation + this.elevation;
        return detectFloorFromAbsoluteElevation(absoluteElevation, this.elevation);
    }

    private boolean isNearZone(LatLng current, LatLng[] zone, float radiusMeters) {
        if (current == null) return false;
        for (LatLng point : zone) {
            if (getDistanceMeters(current, point) <= radiusMeters) {
                return true;
            }
        }
        return false;
    }

    private boolean isNearStairsOrLift() {
        LatLng fusedLatLon = getFusedLatLon();
        return isNearZone(fusedLatLon, STAIRS_ZONES, MAP_ZONE_RADIUS_METERS)
                || isNearZone(fusedLatLon, LIFT_ZONES, MAP_ZONE_RADIUS_METERS);
    }

    private boolean isNearMappedZone(LatLng current, List<List<LatLng>> zones) {
        if (current == null || zones == null || zones.isEmpty()) {
            return false;
        }
        for (List<LatLng> zone : zones) {
            if (GeometryUtils.isPointNearFeature(current, zone, MAP_ZONE_RADIUS_METERS)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMappedVerticalZones() {
        return (indoorStairsZones != null && !indoorStairsZones.isEmpty())
                || (indoorLiftZones != null && !indoorLiftZones.isEmpty());
    }

    private boolean isFloorChangeAllowed(boolean strongVerticalProgress) {
        LatLng fusedLatLon = getFusedLatLon();
        if (hasMappedVerticalZones()) {
            boolean nearStairs = isNearMappedZone(fusedLatLon, indoorStairsZones);
            boolean nearLift = isNearMappedZone(fusedLatLon, indoorLiftZones);
            return (nearStairs && (isStairsMotion || strongVerticalProgress))
                    || (nearLift && (isElevatorMotion || strongVerticalProgress));
        }
        return isNearStairsOrLift() || isElevatorMotion || isStairsMotion || strongVerticalProgress;
    }

    private float getDistanceMeters(LatLng a, LatLng b) {
        double lat1 = Math.toRadians(a.latitude);
        double lon1 = Math.toRadians(a.longitude);
        double lat2 = Math.toRadians(b.latitude);
        double lon2 = Math.toRadians(b.longitude);

        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;
        double r = 6371000; // Earth radius in meters

        double s = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s));
        return (float) (r * c);
    }

    public boolean isFusionInitialized() {
        return fusionInitialized;
    }

    // Estimates if the user is currently taking an elevator.
    // From the gravity and gravity-removed acceleration values the magnitude of horizontal and
    // vertical acceleration is calculated and stored over time. Averaging these values and
    // comparing with the thresholds set for this class, it estimates if the current movement
    // matches what is expected from an elevator ride.
    // @param gravity array of size three, strength of gravity along the phone's x-y-z axis.
    // @param acc array of size three, acceleration other than gravity detected by the phone.
    // @return boolean true if currently in an elevator, false otherwise.
    public boolean estimateElevator(float[] gravity, float[] acc) {
        // Standard gravity
        float g = SensorManager.STANDARD_GRAVITY;
        // get horizontal and vertical acceleration magnitude
        float verticalAcc = (float) Math.sqrt(
                Math.pow((acc[0] * gravity[0]/g),2) +
                Math.pow((acc[1] * gravity[1]/g), 2) +
                Math.pow((acc[2] * gravity[2]/g), 2));
        float horizontalAcc = (float) Math.sqrt(
                Math.pow((acc[0] * (1 - gravity[0]/g)), 2) +
                Math.pow((acc[1] * (1 - gravity[1]/g)), 2) +
                Math.pow((acc[2] * (1 - gravity[2]/g)), 2));
        // Save into buffer to compare with past values
        this.verticalAccel.putNewest(verticalAcc);
        this.horizontalAccel.putNewest(horizontalAcc);
        // Once buffer is full, evaluate data
        if(this.verticalAccel.isFull() && this.horizontalAccel.isFull()) {

            // calculate average vertical accel
            List<Float> verticalMemory = this.verticalAccel.getListCopy();
            OptionalDouble optVerticalAvg = verticalMemory.stream().mapToDouble(Math::abs).average();
            float verticalAvg = optVerticalAvg.isPresent() ? (float) optVerticalAvg.getAsDouble() : 0;


            // calculate average horizontal accel
            List<Float> horizontalMemory = this.horizontalAccel.getListCopy();
            OptionalDouble optHorizontalAvg = horizontalMemory.stream().mapToDouble(Math::abs).average();
            float horizontalAvg = optHorizontalAvg.isPresent() ? (float) optHorizontalAvg.getAsDouble() : 0;

            // System.err.println("LIFT: Vertical: " + verticalAvg);
            // System.err.println("LIFT: Horizontal: " + horizontalAvg);

            if(this.settings.getBoolean("overwrite_constants", false)) {
                float eps = Float.parseFloat(settings.getString("epsilon", "0.18"));
                return horizontalAvg < eps && verticalAvg > movementThreshold;
            }
            // Check if there is minimal horizontal and significant vertical movement
            boolean elevatorCandidate = horizontalAvg < epsilon && verticalAvg > movementThreshold;
            this.isElevatorMotion = elevatorCandidate;
            return elevatorCandidate;
        }

        this.isElevatorMotion = false;
        return false;

    }

    // Resets all values stored in the PDR function and re-initialises all buffers.
    // Used to reset to zero position and remove existing history.
    public void resetPDR() {
        // Check if estimate or manual values should be used
        this.useManualStep = this.settings.getBoolean("manual_step_values", false);
        if(useManualStep) {
            try {
                // Retrieve manual step length
                this.stepLength = this.settings.getInt("user_step_length", 75) / 100f;
            } catch (Exception e) {
                // Invalid values - reset to defaults
                this.stepLength = 0.75f;
                this.settings.edit().putInt("user_step_length", 75).apply();
            }
        }
        else {
            // Using estimated step length - set to zero
            this.stepLength = 0;
        }

        // Initial position and elevation - starts from zero
        this.positionX = 0f;
        this.positionY = 0f;
        this.elevation = 0f;

        if(this.settings.getBoolean("overwrite_constants", false)) {
            // Capacity - pressure is read with 1Hz - store values of past 10 seconds
            this.elevationList = new CircularFloatBuffer(Integer.parseInt(settings.getString("elevation_seconds", "4")));

            // Buffer for most recent acceleration values
            this.verticalAccel = new CircularFloatBuffer(Integer.parseInt(settings.getString("accel_samples", "4")));
            this.horizontalAccel = new CircularFloatBuffer(Integer.parseInt(settings.getString("accel_samples", "4")));
        }
        else {
            // Capacity - pressure is read with 1Hz - store values of past 10 seconds
            this.elevationList = new CircularFloatBuffer(elevationSeconds);

            // Buffer for most recent acceleration values
            this.verticalAccel = new CircularFloatBuffer(accelSamples);
            this.horizontalAccel = new CircularFloatBuffer(accelSamples);
        }

        // Distance between floors is building dependent, use manual value
        this.floorHeight = settings.getInt("floor_height", 4);
        // Array for holding initial values
        this.startElevationBuffer = new Float[3];
        // Start floor - assumed to be zero
        this.currentFloor = 0;
        this.pendingFloorCandidate = Integer.MIN_VALUE;
        this.pendingFloorCandidateCount = 0;
        this.initialAnchorFloorResolved = false;
        this.lastSmoothedRelativeElevation = 0f;
        this.enableMagneticCompensation = this.settings.getBoolean("enable_magnetic_compensation", true);
        if (this.magneticCompensation != null) {
            this.magneticCompensation.setEnabled(this.enableMagneticCompensation);
        }
    }

    public void setMagneticCompensationEnabled(boolean enabled) {
        this.enableMagneticCompensation = enabled;
        if (this.magneticCompensation != null) {
            this.magneticCompensation.setEnabled(enabled);
        }
    }

    public boolean isMagneticCompensationEnabled() {
        return this.enableMagneticCompensation;
    }

    private boolean hasFloorAltitudeAnchors() {
        return floorAltitudeAnchorsMeters != null && floorAltitudeAnchorsMeters.length > 0;
    }

    private float getAbsoluteFloorAnchor(int floorIndex) {
        if (!hasFloorAltitudeAnchors()) {
            return Float.NaN;
        }
        if (floorIndex < 0 || floorIndex >= floorAltitudeAnchorsMeters.length) {
            return Float.NaN;
        }
        return floorAltitudeAnchorsMeters[floorIndex];
    }

    private int detectFloorFromAbsoluteElevation(float absoluteElevation, float relativeElevation) {
        if (hasFloorAltitudeAnchors()) {
            int bestFloor = this.currentFloor;
            float bestDistance = Float.MAX_VALUE;
            for (int i = 0; i < floorAltitudeAnchorsMeters.length; i++) {
                float anchor = floorAltitudeAnchorsMeters[i];
                if (Float.isNaN(anchor)) continue;
                float d = Math.abs(absoluteElevation - anchor);
                if (d < bestDistance) {
                    bestDistance = d;
                    bestFloor = i;
                }
            }
            return bestFloor;
        }
        return Math.round(relativeElevation / this.floorHeight);
    }

    private float getDeltaFromCurrentFloor(float absoluteElevation) {
        float anchor = getAbsoluteFloorAnchor(this.currentFloor);
        if (!Float.isNaN(anchor)) {
            return absoluteElevation - anchor;
        }
        return absoluteElevation - (this.startElevation + this.currentFloor * this.floorHeight);
    }

    private float getFloorSpanMeters(int fromFloor, int toFloor) {
        if (hasFloorAltitudeAnchors()) {
            float anchorA = getAbsoluteFloorAnchor(fromFloor);
            float anchorB = getAbsoluteFloorAnchor(toFloor);
            if (!Float.isNaN(anchorA) && !Float.isNaN(anchorB)) {
                return Math.max(1.5f, Math.abs(anchorB - anchorA));
            }
        }
        return Math.max(1.5f, this.floorHeight);
    }

    private float getFloorSwitchEnterThreshold(int fromFloor, int toFloor) {
        float ratio = hasFloorAltitudeAnchors() ? ANCHORED_FLOOR_SWITCH_ENTER_RATIO : FLOOR_SWITCH_ENTER_RATIO;
        return getFloorSpanMeters(fromFloor, toFloor) * ratio;
    }

    private float getStrongProgressThreshold(int fromFloor, int toFloor) {
        float ratio = hasFloorAltitudeAnchors() ? ANCHORED_FLOOR_SWITCH_STRONG_PROGRESS_RATIO : FLOOR_SWITCH_STRONG_PROGRESS_RATIO;
        return getFloorSpanMeters(fromFloor, toFloor) * ratio;
    }

    // Getter for the average step length calculated from the aggregated distance and step count.
    // @return average step length in meters.
    public float getAverageStepLength(){
        // Calculate average step length
        float averageStepLength = sumStepLength/(float) stepCount;

        // Reset sum and number of steps
        stepCount = 0;
        sumStepLength = 0;

        // Return average step length
        return averageStepLength;
    }

}


