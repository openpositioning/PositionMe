package com.openpositioning.PositionMe.fusion.particle;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.fusion.IPositionFusionAlgorithm;
import com.openpositioning.PositionMe.utils.CoordinateConverter;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.utils.WallDetectionManager;

import org.apache.commons.math3.distribution.TDistribution;
import org.ejml.simple.SimpleMatrix;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class ParticleFilterFusion implements IPositionFusionAlgorithm {
    private static final String TAG = "ParticleFilterFusion";

    private static final int STATE_SIZE = 3;         // Total state vector size
    private static final int MEAS_SIZE = 2;         // Total measurement vector size

    // Process noise scale (PDR) update
    private static final double[] dynamicPdrStds = {0.2, Math.PI/12};
    private static final double[] staticPdrStds = {1.5, 1.5, 0};
    private SimpleMatrix measGnssMat;
    private SimpleMatrix measWifiMat;
    private SimpleMatrix forwardModel;

    // Adjustable GNSS noise based on accuracy
    private static final double BASE_GNSS_NOISE = 10.0;  // Base measurement noise for GNSS (in meters)
    private static final double BASE_WIFI_NOISE = 5.0;  // Measurement noise for WiFi (in meters)

    // Outlier detection parameters
    private static final double OUTLIER_THRESHOLD = 3.0; // Number of standard deviations for outlier detection

    // The reference point for ENU coordinates
    private double[] referencePosition;        // [lat, lng, alt]
    private boolean referenceInitialized;      // Flag to track if reference is properly set
    private boolean headingInitialized;

    // Particle parameters
    private List<Particle> particles;  // List of particles
    private int numParticles;

    // Position history for outlier detection
    private List<double[]> gnssPositionHistory;
    private List<double[]> wifiPositionHistory;
    private static final int HISTORY_SIZE = 3;

    // Movement state tracking
    private long lastUpdateTime;
    private static final long STATIC_TIMEOUT_MS = 1000; // 1 seconds without updates = static update
    private Timer updateTimer; // Timer for regular updates
    private static final long UPDATE_INTERVAL_MS = 1000; // Update every 1 second

    // First position values to handle initialization correctly
    private LatLng firstGnssPosition;
    private LatLng firstWifiPosition;
    private boolean hasInitialGnssPosition;
    private boolean hasInitialWifiPosition;
    private boolean particlesInitialized;
    private boolean timerInitialized;
    private boolean dynamicUpdate;

    // Initialize PDR parameters
    private double previousHeading;
    private double currentHeading;
    private double[] previousPdrPos;
    private double[] currentPdrPos;
    private double enuAltitude;
    private double latlngAltitude;

    // Initialize WiFi and GNSS parameters
    private double[] currentGNSSpositionEMU;
    private SimpleMatrix gnssMeasurementVector;
    private double[] currentWiFipositionEMU;
    private SimpleMatrix wifiMeasurementVector;

    private PdrProcessing.ElevationDirection elevationDirection;
    private WallDetectionManager wallDetectionManager;
    private Context FragmentContext;
    private int buildingType;
    private int currentFloor;
    private boolean buildingTypeInitialized;

    // Constructor
    public ParticleFilterFusion(int numParticles, double[] referencePosition) {
        // Initialize particle array
        this.numParticles = numParticles;
        this.particles = new ArrayList<>();

        // Initialize moving average history
        this.gnssPositionHistory = new ArrayList<>();
        this.wifiPositionHistory = new ArrayList<>();

        this.referencePosition = referencePosition.clone(); // Clone to prevent modification
        this.referenceInitialized = (referencePosition[0] != 0 || referencePosition[1] != 0);

        this.elevationDirection = PdrProcessing.ElevationDirection.NEUTRAL;

        // Initialize first position tracking
        hasInitialGnssPosition = false;
        particlesInitialized = false;
        dynamicUpdate = false;
        buildingTypeInitialized = false;
        lastUpdateTime = System.currentTimeMillis();

        // Initialize heading and position estimates
        previousHeading = 0.0;
        currentHeading = 0.0;

        previousPdrPos = new double[]{0.0, 0.0};
        currentPdrPos = new double[]{0.0, 0.0};

        currentGNSSpositionEMU = new double[]{0.0, 0.0};
        currentWiFipositionEMU = new double[]{0.0, 0.0};

        // Set up measurement models
        forwardModel = new SimpleMatrix(MEAS_SIZE, STATE_SIZE);
        forwardModel.set(0, 0, 1.0);   // Measure east position
        forwardModel.set(1, 1, 1.0);   // Measure north position

        measGnssMat = SimpleMatrix.identity(MEAS_SIZE);
        measGnssMat = measGnssMat.scale(Math.pow(BASE_GNSS_NOISE, 2.0));

        measWifiMat = SimpleMatrix.identity(MEAS_SIZE);
        measWifiMat = measWifiMat.scale(Math.pow(BASE_WIFI_NOISE, 2.0));

        //startStaticUpdateTimer();

    }

    // Add this method to your ParticleFilterFusion class
    private void startStaticUpdateTimer() {
        if (updateTimer != null) {
            updateTimer.cancel();
        }

        updateTimer = new Timer();
        timerInitialized = true;

        updateTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                // Check if particles are initialized
                if (!particlesInitialized) {
                    return;
                }

                long currentTime = System.currentTimeMillis();
                // Check if we should do a static update
                if (currentTime - lastUpdateTime > STATIC_TIMEOUT_MS) {
                    dynamicUpdate = false;
                    Log.d(TAG, "Performing static update with timer");

                    // Apply static update to particles
                    moveParticlesStatic(staticPdrStds);

                    // Reweight particles with available measurements
                    if (currentGNSSpositionEMU != null) {
                        gnssMeasurementVector = new SimpleMatrix(MEAS_SIZE, 1);
                        for (int i = 0; i < gnssPositionHistory.size(); i++) {
                            double[] position = gnssPositionHistory.get(i);
                            gnssMeasurementVector.set(0, 0, position[0]);
                            gnssMeasurementVector.set(1, 0, position[1]);
                            reweightParticles(measGnssMat, forwardModel, gnssMeasurementVector);
                        }
                    }

                    if (currentWiFipositionEMU != null) {
                        wifiMeasurementVector = new SimpleMatrix(MEAS_SIZE, 1);
                        for (int i = 0; i < wifiPositionHistory.size(); i++) {
                            double[] position = wifiPositionHistory.get(i);
                            wifiMeasurementVector.set(0, 0, position[0]);
                            wifiMeasurementVector.set(1, 0, position[1]);
                            reweightParticles(measWifiMat, forwardModel, wifiMeasurementVector);
                        }
                    }

                    // Resample the particles
                    resampleParticles();

                    // Update last update time
                    lastUpdateTime = currentTime;
                }
            }
        }, 0, UPDATE_INTERVAL_MS);

        Log.d(TAG, "Static update timer started");
    }

    @Override
    public void processPdrUpdate(float eastMeters, float northMeters, float altitude) {
        long currentTime = System.currentTimeMillis();

        // Handle reference position and particles if not initialized
        if (!referenceInitialized && hasInitialGnssPosition) {
            initializeReferencePosition();
        }

        // If reference still not initialized, we can't process
        if (!referenceInitialized) {
            Log.w(TAG, "Skipping PDR update: reference position not initialized");
            return;
        }

        // Skip if initial particle position was not found yet
        if (!particlesInitialized) {
            return;
        }

        currentPdrPos[0] = eastMeters;
        currentPdrPos[1] = northMeters;

        // Calculate PDR movement and speed
        double dx = currentPdrPos[0] - previousPdrPos[0];
        double dy = currentPdrPos[1] - previousPdrPos[1];
        double stepLength = 0;
        if (this.elevationDirection != PdrProcessing.ElevationDirection.NEUTRAL){
            stepLength = 0.25;
            dynamicPdrStds[0] = 0.05;
        }else {
            stepLength = Math.sqrt(dx * dx + dy * dy);
            dynamicPdrStds[0] = 0.2 + 0.1*stepLength;
        }
        currentHeading = Math.atan2(dy, dx);
        if (!headingInitialized) {
            initializeHeading();
        }

        if (!hasInitialWifiPosition || buildingType == 0) {
            // Update particle parameters
            moveParticlesDynamic(stepLength, currentHeading, dynamicPdrStds, wallDetectionManager, buildingType, currentFloor);
        } else {
            moveParticlesDynamic(stepLength, currentHeading, dynamicPdrStds, null, buildingType, currentFloor);
        }

        if (currentGNSSpositionEMU != null) {
            gnssMeasurementVector = new SimpleMatrix(MEAS_SIZE, 1);
            for (int i = 0; i < gnssPositionHistory.size(); i++) {
                double[] dummyPos = gnssPositionHistory.get(i);
                gnssMeasurementVector.set(0, 0, dummyPos[0]);
                gnssMeasurementVector.set(1, 0, dummyPos[1]);
                reweightParticles(measGnssMat, forwardModel, gnssMeasurementVector);
            }
        }

        if (currentWiFipositionEMU != null) {
            wifiMeasurementVector = new SimpleMatrix(MEAS_SIZE, 1);
            for (int i = 0; i < wifiPositionHistory.size(); i++) {
                double[] dummyPos = wifiPositionHistory.get(i);
                wifiMeasurementVector.set(0, 0, dummyPos[0]);
                wifiMeasurementVector.set(1, 0, dummyPos[1]);
                reweightParticles(measWifiMat, forwardModel, wifiMeasurementVector);
            }
        }

        resampleParticles();

        previousHeading = currentHeading;
        previousPdrPos = currentPdrPos.clone();

        // Update last update time
        lastUpdateTime = currentTime;
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

        latlngAltitude = altitude;

        // Initialize particles if not yet initialized
        if (!particlesInitialized) {
            initializeParticles();
        }

        Random rand = new Random();

        // Convert GNSS position to ENU (using the reference position)
        double[] enu = CoordinateConverter.convertGeodeticToEnu(
                position.latitude, position.longitude, latlngAltitude,
                referencePosition[0], referencePosition[1], referencePosition[2]
        );

        if (!buildingTypeInitialized && wallDetectionManager != null) {
            setBuildingType(new double[] {enu[0], enu[1]});
            buildingTypeInitialized = true;
        }

        currentGNSSpositionEMU[0] = enu[0];
        currentGNSSpositionEMU[1] = enu[1];
        enuAltitude = enu[2];

        double[] currMovAvg = getMovingAvgGnss();
        double[] stdMovAvg = new double[] {0.0, 0.0};
        if (gnssPositionHistory.size() > 2) {
            stdMovAvg = gnssEstimateStd(currMovAvg);
            stdMovAvg[0] += BASE_GNSS_NOISE;
            stdMovAvg[1] += BASE_GNSS_NOISE;

            // Perform outlier detection
            if (outlierDetection(currentGNSSpositionEMU, stdMovAvg, currMovAvg)) {
                Log.w(TAG, "GNSS outlier detected and ignored");
                return;
            }
        } else {
            stdMovAvg[0] = BASE_GNSS_NOISE;
            stdMovAvg[1] = BASE_GNSS_NOISE;
        }

        // Update the moving average
        updateMovingAvgGnss(currentGNSSpositionEMU);

        // Update last update time
        lastUpdateTime = System.currentTimeMillis();
    }

    @Override
    public void processWifiUpdate(LatLng position, int floor) {
        if (!hasInitialGnssPosition) {
            firstWifiPosition = position;
            hasInitialWifiPosition = true;
            // Try to initialize reference position if needed
            if (!referenceInitialized) {
                initializeReferencePosition();
            }
        }

        // If reference still not initialized, we can't process
        if (!referenceInitialized) {
            Log.w(TAG, "Skipping WiFi update: reference position not initialized");
            return;
        }

        currentFloor = floor;
        hasInitialWifiPosition = true;

        Random rand = new Random();

        // Convert GNSS position to ENU (using the reference position)
        double[] enu = CoordinateConverter.convertGeodeticToEnu(
                position.latitude, position.longitude, 78,
                referencePosition[0], referencePosition[1], referencePosition[2]
        );

        currentWiFipositionEMU[0] = enu[0];
        currentWiFipositionEMU[1] = enu[1];
        enuAltitude = enu[2];

        double[] currMovAvg = getMovingAvgWifi();
        double[] stdMovAvg = new double[] {0.0, 0.0};
        if (wifiPositionHistory.size() > 3) {
            stdMovAvg = wifiEstimateStd(currMovAvg);
            stdMovAvg[0] += BASE_WIFI_NOISE;
            stdMovAvg[1] += BASE_WIFI_NOISE;

            // Perform outlier detection
            if (outlierDetection(currentWiFipositionEMU, stdMovAvg, currMovAvg)) {
                Log.w(TAG, "WiFi outlier detected and ignored");
                return;
            }
        }

        // Update the moving average
        updateMovingAvgWifi(currentWiFipositionEMU);

        // Update last update time
        lastUpdateTime = System.currentTimeMillis();
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

        // Check if building was changed during propagation
        if (avgPosition != null && this.wallDetectionManager != null) {
            setBuildingType(new double[] {avgPosition[0], avgPosition[1]});
        }

        // Convert ENU back to latitude/longitude
        LatLng result = CoordinateConverter.convertEnuToGeodetic(
                avgPosition[0], avgPosition[1], enuAltitude,
                referencePosition[0], referencePosition[1], referencePosition[2]
        );

        Log.d(TAG, "Fused position (after conversion): " +
                result.latitude + "," + result.longitude);

        return result;
    }

    @Override
    public void reset() {
        // Stop the update timer
        if (updateTimer != null) {
            updateTimer.cancel();
            updateTimer = null;
        }

        referenceInitialized = false;
        hasInitialGnssPosition = false;
        particlesInitialized = false;
        dynamicUpdate = false;
        particles.clear();
        gnssPositionHistory.clear();
        wifiPositionHistory.clear();
        Log.d(TAG, "Particle filter reset");
    }

    // Simulate motion update of particles
    public void moveParticlesDynamic(double stepLength, double headingChange, double[] pdrStds) {
        for (Particle particle : particles) {
            particle.updateDynamic(stepLength, headingChange, pdrStds);
        }
    }

    // Add this method to your ParticleFilterFusion class
    public void moveParticlesStatic(double[] staticStds) {
        Random rand = new Random();

        for (Particle particle : particles) {
            // Add random noise to represent random motion in static state
            particle.x += rand.nextGaussian() * staticStds[0];
            particle.y += rand.nextGaussian() * staticStds[1];
            //particle.theta += rand.nextGaussian() * staticStds[2];

            // Normalize angle
            //particle.theta = CoordinateConverter.normalizeAngleToPi(particle.theta);
        }

        Log.d(TAG, "Applied static motion model to particles");
    }

    public void reweightParticles(SimpleMatrix measurementCovariance,
                                  SimpleMatrix forwardModel,
                                  SimpleMatrix measurementVector) {

        // First pass - calculate log weights
        for (Particle particle : particles) {
            SimpleMatrix positionVector = new SimpleMatrix(3, 1);
            positionVector.set(0, 0, particle.x);
            positionVector.set(1, 0, particle.y);
            positionVector.set(2, 0, particle.theta);

            SimpleMatrix predicted = forwardModel.mult(positionVector);
            SimpleMatrix error = measurementVector.minus(predicted);

            // Calculate LogWeight
            double logWeight = -0.5 * error.transpose().mult(measurementCovariance.invert()).mult(error).get(0, 0)
                                -0.5 * Math.log(measurementCovariance.determinant());

            // Store log-weight temporarily
            particle.logWeight += logWeight;
        }

        // Second pass
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
            newParticle.logWeight = 0.0;
            newParticles.add(newParticle);
        }

        particles = newParticles;

        // Add jitter to prevent sample impoverishment
        addJitterToParticles();

        Log.d(TAG, "Resampled particles with jitter: " + numParticles);
    }

    /**
     * Adds random jitter to particles to prevent sample impoverishment.
     * The amount of jitter is proportional to the particle dispersion.
     */
    private void addJitterToParticles() {
        // Calculate current particle dispersion to scale jitter appropriately
        double dispersion = calculateParticleDispersion();

        // Jitter scale - smaller values for more precise distributions
        // Typically between 0.01 (very small jitter) to 0.2 (significant jitter)
        double jitterScale = 0.01;

        // Calculate jitter standard deviations
        double positionJitterStd = jitterScale * dispersion;
        double headingJitterStd = jitterScale * Math.PI / 12; // Was 6, ~30 degrees scaled by jitter

        Random rand = new Random();

        // Apply jitter to each particle
        for (Particle p : particles) {
            // Add position jitter
            p.x += rand.nextGaussian() * positionJitterStd;
            p.y += rand.nextGaussian() * positionJitterStd;

            // Add heading jitter and normalize to [-π, π]
            p.theta += rand.nextGaussian() * headingJitterStd;
            p.theta = CoordinateConverter.normalizeAngleToPi(p.theta);
        }

        Log.d(TAG, "Added jitter with scale: " + jitterScale + ", dispersion: " + dispersion);
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
    private void updateMovingAvgGnss(double[] gnssEmuPosition) {
        gnssPositionHistory.add(gnssEmuPosition);

        // Add current position to history
        if (gnssPositionHistory.size() > HISTORY_SIZE) {
            gnssPositionHistory.remove(0);
        }
    }

    private double[] getMovingAvgGnss() {
        double size = (double) gnssPositionHistory.size();

        double[] result = new double[]{0.0, 0.0};
        for (int i = 0; i < gnssPositionHistory.size(); i++) {
            result[0] += gnssPositionHistory.get(i)[0];
            result[1] += gnssPositionHistory.get(i)[1];
        }

        return new double[] {result[0]/size, result[1]/size};

    }

    // Update position history for outlier detection
    private void updateMovingAvgWifi(double[] wifiEmuPosition) {
        wifiPositionHistory.add(wifiEmuPosition);

        // Add current position to history
        if (wifiPositionHistory.size() > HISTORY_SIZE) {
            wifiPositionHistory.remove(0);
        }
    }

    private double[] getMovingAvgWifi() {
        double size = (double) wifiPositionHistory.size();

        double[] result = new double[]{0.0, 0.0};
        for (int i = 0; i < wifiPositionHistory.size(); i++) {
            result[0] += wifiPositionHistory.get(i)[0];
            result[1] += wifiPositionHistory.get(i)[1];
        }

        return new double[] {result[0]/size, result[1]/size};

    }

    private double[] gnssEstimateStd(double[] movingAvg) {

        // Calculate standard deviation
        double varX = 0, varY = 0;
        for (double[] pos : gnssPositionHistory) {
            varX += Math.pow(pos[0] - movingAvg[0], 2);
            varY += Math.pow(pos[1] - movingAvg[1], 2);
        }
        double stdX = Math.sqrt(varX / gnssPositionHistory.size());
        double stdY = Math.sqrt(varY / gnssPositionHistory.size());

        return new double[] {stdX, stdY};
    }

    private double[] wifiEstimateStd(double[] movingAvg) {

        // Calculate standard deviation
        double varX = 0, varY = 0;
        for (double[] pos : wifiPositionHistory) {
            varX += Math.pow(pos[0] - movingAvg[0], 2);
            varY += Math.pow(pos[1] - movingAvg[1], 2);
        }
        double stdX = Math.sqrt(varX / wifiPositionHistory.size());
        double stdY = Math.sqrt(varY / wifiPositionHistory.size());

        return new double[] {stdX, stdY};
    }

    private boolean outlierDetection(double[] measEnu, double[] stdVal, double[] movingAvg){

        //Check if measurement measurement is within threshold of standard deviations
        boolean isOutlierX = Math.abs(measEnu[0] - movingAvg[0]) > OUTLIER_THRESHOLD * stdVal[0];
        boolean isOutlierY = Math.abs(measEnu[1] - movingAvg[1]) > OUTLIER_THRESHOLD * stdVal[1];

        // If standard deviation is too small, don't mark as outlier (avoids false positives)
        if (stdVal[0] < 1.0 || stdVal[1] < 1.0) {
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

    public void setElevationStatus(PdrProcessing.ElevationDirection elevationDirection) {
        this.elevationDirection = elevationDirection;
    }

    /**
     * Initializes the particles, centered at the reference position.
     * Particles have uniform weights of 1/numParticles.
     */
    private void initializeParticles() {

        double[] initGnssCoordEnu = CoordinateConverter.convertGeodeticToEnu(
                firstGnssPosition.latitude, firstGnssPosition.longitude, latlngAltitude,
                referencePosition[0], referencePosition[1], referencePosition[2]);

        currentPdrPos[0] = initGnssCoordEnu[0];
        currentPdrPos[1] = initGnssCoordEnu[1];

        previousPdrPos[0] = initGnssCoordEnu[0];
        previousPdrPos[1] = initGnssCoordEnu[1];

        // Initial dispersion - spread particles in a reasonable area
        double initialDispersion = 2.5; // 2.5 meters initial uncertainty

        for (int i = 0; i < numParticles; i++) {
            Random rand = new Random();

            // Add some random noise to initial positions
            double x = previousPdrPos[0] + rand.nextGaussian() * initialDispersion;
            double y = previousPdrPos[1] + rand.nextGaussian() * initialDispersion;
            double theta = rand.nextGaussian() * 2 * Math.PI;  // Random orientation

            Particle particle = new Particle(x, y, theta);
            particle.weight = 1.0 / numParticles;
            particles.add(particle);
        }

        particlesInitialized = true;
    }

    private void initializeHeading() {
        for (Particle particle : particles) {
            Random rand = new Random();
            particle.theta = currentHeading;
        }

        headingInitialized = true;
    }

    public void retrieveContext(Context context) {
        this.FragmentContext = context;
    }

    private void setBuildingType(double[] position) {
        if (this.wallDetectionManager.inNucleusENU(position)) {
            buildingType = 1;
        } else if (this.wallDetectionManager.inLibraryENU(position)) {
            buildingType = 2;
        } else {
            buildingType = 0;
        }
        Log.w(TAG, "Building type set to" + buildingType);
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
        referencePosition[2] = 78;

        referenceInitialized = true;

        this.wallDetectionManager = new WallDetectionManager(referencePosition);

        if (FragmentContext != null) {
        this.wallDetectionManager.initializeWallData(this.FragmentContext);
        Log.d(TAG, "Wall data initialized at" + this.FragmentContext);
        }

        Log.d(TAG, "Reference position initialized from GNSS: " +
                "lat=" + referencePosition[0] + ", lng=" + referencePosition[1] +
                ", alt=" + referencePosition[2]);
    }

    /**
     * Called when the object is being destroyed or the application is shutting down
     * Ensures the timer is properly cleaned up
     */
    public void shutdown() {
        if (updateTimer != null) {
            updateTimer.cancel();
            updateTimer = null;
            Log.d(TAG, "Update timer cancelled during shutdown");
        }
    }
}