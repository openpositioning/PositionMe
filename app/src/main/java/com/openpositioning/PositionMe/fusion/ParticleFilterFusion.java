package com.openpositioning.PositionMe.fusion;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.CoordinateConverter;

import org.ejml.simple.SimpleMatrix;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ParticleFilterFusion implements IPositionFusionAlgorithm {
    private static final String TAG = "ParticleFilterFusion";

    private static final int STATE_SIZE = 3;         // Total state vector size
    private static final int MEAS_SIZE = 2;         // Total measurement vector size

    // Process noise scale (PDR) update {X, Y}
    private static final double[] dynamicPdrStds = {0.25, Math.PI/6};
    private SimpleMatrix measGnssMat;
    private SimpleMatrix measWifiMat;
    private SimpleMatrix forwardModel;

    // Adjustable GNSS noise based on accuracy
    private static final double GNSS_NOISE = 20.0;  // Base measurement noise for GNSS (in meters)
    private static final double BASE_WIFI_NOISE = 4.0;        // Measurement noise for WiFi (in meters)

    // GNSS outlier detection parameters
    private static final double MAX_GNSS_INNOVATION = 25.0;  // Maximum acceptable innovation (m)
    private static final double GNSS_OUTLIER_THRESHOLD = 3.0; // Number of standard deviations for outlier detection

    // Particle diversity parameters
    private static final double MIN_EFFECTIVE_PARTICLES_RATIO = 0.5; // Only resample if effective particles < 50%
    private static final double JITTER_AFTER_RESAMPLE = 0.25; // Small jitter to add after resampling

    // The reference point for ENU coordinates
    private double[] referencePosition;        // [lat, lng, alt]
    private boolean referenceInitialized;      // Flag to track if reference is properly set

    // Particle parameters
    private List<Particle> particles;  // List of particles
    private int numParticles;

    // Position history for outlier detection
    private List<double[]> positionHistory;
    private static final int HISTORY_SIZE = 5;

    // Movement state tracking
    private long lastUpdateTime;
    private static final long STATIC_TIMEOUT_MS = 3000; // 3 seconds without updates = static

    // First position values to handle initialization correctly
    private LatLng firstGnssPosition;
    private boolean hasInitialGnssPosition;
    private boolean particlesInitialized;
    private boolean dynamicUpdate;

    // Initialize PDR parameters
    private double previousHeading;
    private double currentHeading;
    private double[] previousPdrPos;
    private double[] currentPdrPos;
    private double previousStepLength;
    private double currentStepLength;

    // Constructor
    public ParticleFilterFusion(int numParticles, double[] referencePosition) {
        // Get particle positions
        this.numParticles = numParticles;
        this.particles = new ArrayList<>();
        this.positionHistory = new ArrayList<>();

        this.referencePosition = referencePosition.clone(); // Clone to prevent modification
        this.referenceInitialized = (referencePosition[0] != 0 || referencePosition[1] != 0);

        // Initialize first position tracking
        hasInitialGnssPosition = false;
        particlesInitialized = false;
        dynamicUpdate = false;
        lastUpdateTime = System.currentTimeMillis();

        // Initialize heading and position estimates
        previousHeading = 0.0;
        currentHeading = 0.0;

        previousStepLength = 0.0;
        currentStepLength = 0.0;

        previousPdrPos = new double[]{0.0, 0.0};
        currentPdrPos = new double[]{0.0, 0.0};

        // Set up measurement models
        forwardModel = new SimpleMatrix(MEAS_SIZE, STATE_SIZE);
        forwardModel.set(0, 0, 1.0);   // Measure east position
        forwardModel.set(1, 1, 1.0);   // Measure north position

        measGnssMat = SimpleMatrix.identity(MEAS_SIZE);
        measGnssMat = measGnssMat.scale(Math.pow(GNSS_NOISE, 2.0));

        measWifiMat = SimpleMatrix.identity(MEAS_SIZE);
        measWifiMat = measWifiMat.scale(Math.pow(BASE_WIFI_NOISE, 2.0));
    }

    @Override
    public void processPdrUpdate(float eastMeters, float northMeters, float altitude) {
        // Handle reference position and particles if not initialized
        if (!referenceInitialized && hasInitialGnssPosition) {
            initializeReferencePosition();
        }

        // If reference still not initialized, we can't process
        if (!referenceInitialized) {
            Log.w(TAG, "Skipping PDR update: reference position not initialized");
            return;
        }

        currentPdrPos[0] = eastMeters;
        currentPdrPos[1] = northMeters;

        // Calculate PDR movement and speed
        double dx = currentPdrPos[0] - previousPdrPos[0];
        double dy = currentPdrPos[1] - previousPdrPos[1];

        currentStepLength = Math.sqrt(dx * dx + dy * dy);
        currentHeading = Math.atan2(dx, dy); // 0 = North, positive clockwise

        // Update particle parameters
        moveParticlesDynamic(currentStepLength, currentHeading, dynamicPdrStds);

        // Update history with current estimate
        updatePositionHistory();
    }

    @Override
    public void processGnssUpdate(LatLng position, double altitude) {
        // Store first GNSS position for reference initialization if needed
        if (!hasInitialGnssPosition) {
            firstGnssPosition = position;
            hasInitialGnssPosition = true;

            // Try to initialize reference position if needed
            if (!referenceInitialized) {
                initializeReferencePosition();
            }
        }

        // If reference still not initialized, we can't process
        if (!referenceInitialized) {
            Log.w(TAG, "Skipping GNSS update: reference position not initialized");
            return;
        }

        // Initialize particles if not yet initialized
        if (!particlesInitialized) {
            initializeParticles();
        }

        /*
        // Convert GNSS position to ENU (using the reference position)
        double[] enu = CoordinateConverter.convertGeodeticToEnu(
                position.latitude, position.longitude, altitude,
                referencePosition[0], referencePosition[1], referencePosition[2]
        );

        // Create measurement vector
        SimpleMatrix measurementVector = new SimpleMatrix(MEAS_SIZE, 1);
        measurementVector.set(0, 0, enu[0]);
        measurementVector.set(1, 0, enu[1]);

        // Perform outlier detection
        if (isGnssOutlier(enu)) {
            Log.w(TAG, "GNSS outlier detected and ignored");
            dynamicUpdate = false;
            previousHeading = currentHeading;
            previousPdrPos = currentPdrPos.clone();
            return;
        }

        // Reweight particles
        reweightParticles(measGnssMat, forwardModel, measurementVector);

        // Only resample if effective sample size is below threshold
        if (calculateEffectiveSampleSize() < MIN_EFFECTIVE_PARTICLES_RATIO * numParticles) {
            resampleParticles();
            // Add a small jitter to particles after resampling to avoid sample impoverishment
            if (isMoving) {
                addJitterToParticles(JITTER_AFTER_RESAMPLE);
            }
        }

        dynamicUpdate = false;
        previousHeading = currentHeading;
        previousPdrPos = currentPdrPos.clone();

        // Update position history
        updatePositionHistory();
        */

    }

    @Override
    public void processWifiUpdate(LatLng position, int floor) {
        // If reference still not initialized, we can't process
        if (!referenceInitialized) {
            Log.w(TAG, "Skipping WiFi update: reference position not initialized");
            return;
        }


        // Convert WiFi position to ENU (using the reference position)
        double[] enu = CoordinateConverter.convertGeodeticToEnu(
                position.latitude, position.longitude, referencePosition[2],
                referencePosition[0], referencePosition[1], referencePosition[2]
        );

        // Create measurement vector
        SimpleMatrix measurementVector = new SimpleMatrix(MEAS_SIZE, 1);
        measurementVector.set(0, 0, enu[0]);
        measurementVector.set(1, 0, enu[1]);

        // Reweight particles
        reweightParticles(measWifiMat, forwardModel, measurementVector);

        // Only resample if effective sample size is below threshold
        if (calculateEffectiveSampleSize() < MIN_EFFECTIVE_PARTICLES_RATIO * numParticles) {
            resampleParticles();
            // Add a small jitter to particles after resampling
            addJitterToParticles(JITTER_AFTER_RESAMPLE);
        }

        previousHeading = currentHeading;
        previousPdrPos = currentPdrPos.clone();

        // Update position history
        updatePositionHistory();
    }

    public LatLng getFusedPosition() {
        if (!referenceInitialized) {
            if (hasInitialGnssPosition) {
                Log.w(TAG, "Using initial GNSS position as fusion result (reference not initialized)");
                return firstGnssPosition;
            } else {
                Log.e(TAG, "Cannot get fused position: no reference position and no GNSS position");
                return null;
            }
        }

        double[] avgPosition = getEstimatedPosition();

        // If we haven't received any updates yet, return the reference position
        if (avgPosition[0] == 0 && avgPosition[1] == 0) {
            Log.d(TAG, "Returning reference position as fusion result (no updates yet)");
            return new LatLng(referencePosition[0], referencePosition[1]);
        }

        // Convert ENU back to latitude/longitude
        LatLng result = CoordinateConverter.convertEnuToGeodetic(
                avgPosition[0], avgPosition[1], 0, // Assume altitude=0 for the return value
                referencePosition[0], referencePosition[1], referencePosition[2]
        );

        Log.d(TAG, "Fused position (after conversion): " +
                result.latitude + "," + result.longitude);

        return result;
    }

    @Override
    public void reset() {
        referenceInitialized = false;
        hasInitialGnssPosition = false;
        particlesInitialized = false;
        dynamicUpdate = false;
        particles.clear();
        positionHistory.clear();
        Log.d(TAG, "Particle filter reset");
    }

    // Simulate motion update of particles
    public void moveParticlesDynamic(double stepLength, double headingChange, double[] pdrStds) {
        for (Particle particle : particles) {
            particle.updateDynamic(stepLength, headingChange, pdrStds);
            particle.theta = CoordinateConverter.normalizeAngleToPi(particle.theta);
        }
    }


    // Add jitter to particles to prevent sample impoverishment
    private void addJitterToParticles(double jitterAmount) {
        Random rand = new Random();
        for (Particle particle : particles) {
            particle.x += rand.nextGaussian() * jitterAmount;
            particle.y += rand.nextGaussian() * jitterAmount;
            particle.theta += rand.nextGaussian() * (jitterAmount * 0.5 * Math.PI);
            particle.theta = CoordinateConverter.normalizeAngleToPi(particle.theta);
        }
    }

    // Calculate effective sample size to determine when to resample
    private double calculateEffectiveSampleSize() {
        double sumSquaredWeights = 0.0;
        for (Particle particle : particles) {
            sumSquaredWeights += particle.weight * particle.weight;
        }
        return 1.0 / sumSquaredWeights;
    }

    public void reweightParticles(SimpleMatrix measurementCovariance,
                                  SimpleMatrix forwardModel,
                                  SimpleMatrix measurementVector) {

        // First pass - calculate log weights and find maximum
        for (Particle particle : particles) {
            SimpleMatrix positionVector = new SimpleMatrix(3, 1);
            positionVector.set(0, 0, particle.x);
            positionVector.set(1, 0, particle.y);
            positionVector.set(2, 0, particle.theta);

            SimpleMatrix predicted = forwardModel.mult(positionVector);
            SimpleMatrix error = measurementVector.minus(predicted);

            // Calculate LogWeight
            double logWeight = -0.5 * error.transpose().mult(measurementCovariance.invert()).mult(error).get(0, 0);

            // Store log-weight temporarily and track maximum
            particle.logWeight = logWeight;
        }

        // Second pass - normalize weights using log-sum-exp trick for numerical stability
        double sumWeights = 0.0;
        for (Particle particle : particles) {

            particle.weight = Math.exp(particle.logWeight);
            sumWeights += particle.weight;
        }

        // Normalize weights
        for (Particle particle : particles) {
            particle.weight /= sumWeights;
        }
    }

    // Resample particles based on their weights using systematic resampling
    public void resampleParticles() {
        List<Particle> newParticles = new ArrayList<>();

        // Calculate cumulative sum of the weights
        double[] cumulativeSum = new double[numParticles];
        cumulativeSum[0] = particles.get(0).weight;
        for (int i = 1; i < numParticles; i++) {
            cumulativeSum[i] = cumulativeSum[i - 1] + particles.get(i).weight;
        }

        // Generate a random offset (u) between 0 and 1/numParticles
        Random rand = new Random();
        double u = rand.nextDouble() / numParticles;

        // Systematic resampling
        int index = 0;
        for (int i = 0; i < numParticles; i++) {
            double target = u + i * (1.0 / numParticles);
            while (index < (numParticles-1) && target > cumulativeSum[index]) {
                index++;
            }

            // Create a copy of the selected particle
            Particle original = particles.get(index);
            Particle newParticle = new Particle(original.x, original.y, original.theta);
            newParticle.weight = 1.0 / numParticles;  // Reset weights to uniform
            newParticles.add(newParticle);
        }

        particles = newParticles;
        Log.d(TAG, "Resampled particles: " + numParticles);
    }

    // Get the estimated position (weighted average of all particles)
    public double[] getEstimatedPosition() {
        double avgX=0, avgY=0, cosSum = 0, sinSum = 0;

        for (Particle particle : particles) {
            avgX += particle.weight * particle.x;
            avgY += particle.weight * particle.y;

            // Average angle using trigonometry to handle circular values correctly
            cosSum += particle.weight * Math.cos(particle.theta);
            sinSum += particle.weight * Math.sin(particle.theta);
        }

        double avgTheta = Math.atan2(sinSum, cosSum);
        return new double[]{avgX, avgY, avgTheta};
    }

    // Update position history for outlier detection
    private void updatePositionHistory() {
        double[] currentEstimate = getEstimatedPosition();

        // Add current position to history
        positionHistory.add(currentEstimate);

        // Keep history to manageable size
        if (positionHistory.size() > HISTORY_SIZE) {
            positionHistory.remove(0);
        }
    }

    // Detect GNSS outliers using innovation and historical positions
    private boolean isGnssOutlier(double[] gnssEnu) {
        // If we don't have enough history, we can't detect outliers reliably
        if (positionHistory.size() < 2) {
            return false;
        }

        // Get current estimated position
        double[] currentEstimate = getEstimatedPosition();

        // 1. Check innovation (difference between measurement and prediction)
        double innovationX = gnssEnu[0] - currentEstimate[0];
        double innovationY = gnssEnu[1] - currentEstimate[1];
        double innovation = Math.sqrt(innovationX*innovationX + innovationY*innovationY);

        // Hard threshold to reject obviously bad measurements
        if (innovation > MAX_GNSS_INNOVATION) {
            Log.w(TAG, "GNSS outlier rejected: innovation = " + innovation + "m");
            return true;
        }

        // 2. Calculate average position and standard deviation from history
        double sumX = 0, sumY = 0;
        for (double[] pos : positionHistory) {
            sumX += pos[0];
            sumY += pos[1];
        }
        double avgX = sumX / positionHistory.size();
        double avgY = sumY / positionHistory.size();

        // Calculate standard deviation
        double varX = 0, varY = 0;
        for (double[] pos : positionHistory) {
            varX += Math.pow(pos[0] - avgX, 2);
            varY += Math.pow(pos[1] - avgY, 2);
        }
        double stdX = Math.sqrt(varX / positionHistory.size());
        double stdY = Math.sqrt(varY / positionHistory.size());

        // 3. Check if GNSS measurement is within threshold of standard deviations
        boolean isOutlierX = Math.abs(gnssEnu[0] - avgX) > GNSS_OUTLIER_THRESHOLD * stdX;
        boolean isOutlierY = Math.abs(gnssEnu[1] - avgY) > GNSS_OUTLIER_THRESHOLD * stdY;

        // If standard deviation is too small, don't mark as outlier (avoids false positives)
        if ((stdX < 1.0 || stdY < 1.0) && innovation < MAX_GNSS_INNOVATION / 2) {
            return false;
        }

        return isOutlierX || isOutlierY;
    }

    // Calculate particle dispersion as a measure of uncertainty
    private double calculateParticleDispersion() {
        double[] mean = getEstimatedPosition();
        double sumSquaredDistance = 0.0;

        for (Particle p : particles) {
            double dx = p.x - mean[0];
            double dy = p.y - mean[1];
            sumSquaredDistance += dx*dx + dy*dy;
        }

        return Math.sqrt(sumSquaredDistance / numParticles);
    }

    /**
     * Initializes the particles, centered at the reference position.
     * Particles have uniform weights of 1/numParticles.
     */
    private void initializeParticles() {
        Random rand = new Random();

        double[] initGnssCoordEnu = CoordinateConverter.convertGeodeticToEnu(
                firstGnssPosition.latitude, firstGnssPosition.longitude, 0,
                referencePosition[0], referencePosition[1], referencePosition[2]);

        currentPdrPos[0] = initGnssCoordEnu[0];
        currentPdrPos[1] = initGnssCoordEnu[1];

        previousPdrPos[0] = initGnssCoordEnu[0];
        previousPdrPos[1] = initGnssCoordEnu[1];

        // Initial dispersion - spread particles in a reasonable area
        double initialDispersion = 5.0; // 5 meters initial uncertainty

        for (int i = 0; i < numParticles; i++) {
            // Add some random noise to initial positions
            double x = previousPdrPos[0] + rand.nextGaussian() * initialDispersion;
            double y = previousPdrPos[1] + rand.nextGaussian() * initialDispersion;
            double theta = rand.nextDouble() * 2 * Math.PI - Math.PI;  // Random orientation

            Particle particle = new Particle(x, y, theta);
            particle.weight = 1.0 / numParticles;
            particles.add(particle);
        }

        particlesInitialized = true;

        // Initialize position history
        double[] initialPos = {previousPdrPos[0], previousPdrPos[1], 0.0};
        positionHistory.add(initialPos);
    }

    /**
     * Initializes the reference position if it wasn't provided or was zeros.
     * Uses the first GNSS position as the reference.
     */
    private void initializeReferencePosition() {
        if (referenceInitialized || !hasInitialGnssPosition) {
            return;
        }

        // Use the first GNSS position as reference
        referencePosition[0] = firstGnssPosition.latitude;
        referencePosition[1] = firstGnssPosition.longitude;
        referencePosition[2] = 0; // Assume zero altitude if not provided

        referenceInitialized = true;

        Log.d(TAG, "Reference position initialized from GNSS: " +
                "lat=" + referencePosition[0] + ", lng=" + referencePosition[1] +
                ", alt=" + referencePosition[2]);
    }
}