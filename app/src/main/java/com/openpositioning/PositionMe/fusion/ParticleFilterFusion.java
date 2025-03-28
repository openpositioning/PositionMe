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
    private static final int MEAS_SIZE = 2;         // Total state vector size

    // Process noise scale (PDR) update {X, Y}
    private static final double[] staticPdrStds = {1.5, Math.PI/6};
    private static final double[] dynamicPdrStds = {0.5, Math.PI/12};
    private SimpleMatrix measGnssMat;
    private SimpleMatrix measWifiMat;
    private SimpleMatrix forwardModel;
    private static final double GNSS_NOISE = 5.0;       // Measurement noise for GNSS (in meters)
    private static final double WIFI_NOISE = 4.0;      // Measurement noise for WiFi (in meters)

    // The reference point for ENU coordinates
    private double[] referencePosition;        // [lat, lng, alt]
    private boolean referenceInitialized;      // Flag to track if reference is properly set

    // Particle parameters
    private List<Particle> particles;  // List of particles
    private int numParticles;
    private int numExtra;


    // First position values to handle initialization correctly
    private LatLng firstGnssPosition;
    private double[] firstGnssPositionEnu;
    private boolean hasInitialGnssPosition;
    private boolean particlesInitialized;
    private boolean dynamicUpdate;

    private LatLng FusedPosition;

    // Initialize PDR parameters
    private double previousHeading;
    private double currentHeading;
    private double[] previousPdrPos;
    private double[] currentPdrPos;

    private double[] particleAvg;


    // Constructor
    public ParticleFilterFusion(int numParticles, double[] referencePosition) {

        // Get particle positions
        this.numParticles = numParticles;
        //this.mapBoundaries = mapBoundaries;
        this.particles = new ArrayList<>();

        this.referencePosition = referencePosition.clone(); // Clone to prevent modification
        this.referenceInitialized = (referencePosition[0] != 0 || referencePosition[1] != 0);


        // Initialize first position tracking
        hasInitialGnssPosition = false;
        particlesInitialized = false;
        dynamicUpdate = false;

        // Initialize heading and position estimates
        previousHeading = 0.0;
        currentHeading = 0.0;

        previousPdrPos = new double[]{0.0, 0.0};
        currentPdrPos = new double[]{0.0, 0.0};

        forwardModel = new SimpleMatrix(MEAS_SIZE, STATE_SIZE);
        forwardModel.set(0, 0, 1.0);   // Measure east position
        forwardModel.set(1, 1, 1.0); // Measure north position

        measGnssMat = SimpleMatrix.identity(MEAS_SIZE);
        measGnssMat = measGnssMat.scale(Math.pow(GNSS_NOISE, 2.0));

        measWifiMat = SimpleMatrix.identity(MEAS_SIZE);
        measWifiMat = measWifiMat.scale(Math.pow(WIFI_NOISE, 2.0));
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

        // Initialize particles if not yet initialized
        if (!particlesInitialized) {
            initializeParticles();
        }

        currentPdrPos[0] = eastMeters;
        currentPdrPos[1] = northMeters;

        // Calculate PDR movement and speed
        double dx = currentPdrPos[0] - previousPdrPos[0];
        double dy = currentPdrPos[1] - previousPdrPos[1];
        double stepLength = Math.sqrt(dx * dx + dy * dy);

        // Calculate bearing from movement direction (compensating for ENU frame)
        currentHeading = Math.atan2(dx, dy); // 0 = North, positive clockwise
        double headingChange = currentHeading - previousHeading;

        // Update particle parameters
        moveParticlesDynamic(stepLength, headingChange, dynamicPdrStds);
        dynamicUpdate = true;

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
            Log.w(TAG, "Skipping PDR update: reference position not initialized");
            return;
        }

        // Initialize particles if not yet initialized
        if (!particlesInitialized) {
            initializeParticles();
        }
        if (!dynamicUpdate){
            // Update particle parameters in static mode if dynamic update is not detected
            moveParticlesStatic(staticPdrStds);
        }

        // Convert GNSS position to ENU (using the reference position)
        double[] enu = CoordinateConverter.convertGeodeticToEnu(
                position.latitude, position.longitude, altitude,
                referencePosition[0], referencePosition[1], referencePosition[2]
        );

        SimpleMatrix measurementVector = new SimpleMatrix(MEAS_SIZE, 1);
        measurementVector.set(0, 0, enu[0]);
        measurementVector.set(1, 0, enu[1]);

        // Reweight and resample
        reweightParticles(measGnssMat, forwardModel, measurementVector);

        resampleParticles();

        dynamicUpdate = false;

        previousHeading = currentHeading;
        previousPdrPos = currentPdrPos;

    }

    public void processWifiUpdate(LatLng position, int floor) {

        // If reference still not initialized, we can't process
        if (!referenceInitialized) {
            Log.w(TAG, "Skipping PDR update: reference position not initialized");
            return;
        }

        // Initialize particles if not yet initialized
        if (!particlesInitialized) {
            initializeParticles();
        }
        if (!dynamicUpdate){
            // Update particle parameters in static mode if dynamic update is not detected
            moveParticlesStatic(staticPdrStds);
        }

        // Convert WiFi position to ENU (using the reference position)
        // TODO: Fix altitude estimate for wifi
        double[] enu = CoordinateConverter.convertGeodeticToEnu(
                position.latitude, position.longitude, referencePosition[2],
                referencePosition[0], referencePosition[1], referencePosition[2]
        );

        SimpleMatrix measurementVector = new SimpleMatrix(MEAS_SIZE, 1);
        measurementVector.set(0, 0, enu[0]);
        measurementVector.set(1, 0, enu[1]);

        // Reweight and resample
        reweightParticles(measWifiMat, forwardModel, measurementVector);
        resampleParticles();

        dynamicUpdate = false;

        previousHeading = currentHeading;
        previousPdrPos = currentPdrPos;

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

        Log.d(TAG, "Kalman filter reset");
    }

    // Simulate motion update of particles
    public void moveParticlesDynamic(double stepLength, double headingChange, double[] dynamicPdrStds) {
        for (Particle particle : particles) {
            particle.updateDynamic(stepLength, headingChange, dynamicPdrStds);
        }
    }

    // Simulate motion update of particles
    public void moveParticlesStatic(double[] staticPDRStds) {
        for (Particle particle : particles) {
            particle.updateStatic(staticPDRStds);
        }
    }


    public void reweightParticles(SimpleMatrix measurementCovariance,
                                  SimpleMatrix forwardModel,
                                  SimpleMatrix measurementVector){

        for (Particle particle : particles) {
            particle.reweight(measurementCovariance, forwardModel, measurementVector);

        }

    }

    // Resample particles based on their weights (simplified here)
    public void resampleParticles() {

        List<Particle> newParticles = new ArrayList<>();

        // Normalize weights
        double totWeight = 0.0;
        for (Particle particle : particles) {
            totWeight += particle.weight;
        }
        for (Particle particle : particles) {
            particle.weight /= totWeight;
        }

        // Calculate cumulative sum of the weights
        double[] cumulativeSum = new double[numParticles];
        cumulativeSum[0] = particles.get(0).weight;
        for (int i = 1; i < numParticles; i++) {
            cumulativeSum[i] = cumulativeSum[i - 1] + particles.get(i).weight;
        }

        // Generate a random offset (u) between 0 and 1/numParticles
        Random rand = new Random();
        double u = rand.nextDouble() / numParticles;

        // Madow systematic resampling
        int index = 0;
        for (int i = 0; i < numParticles; i++) {
            double target = u + i * (1.0 / numParticles);
            while (index < (numParticles-1) && target > cumulativeSum[index]) {
                index++;
            }
            newParticles.add(particles.get(index));
        }

        particles = newParticles;

        // TODO: Resolve last particle issue
        //Particle extra = new Particle(currentPdrPos[0], currentPdrPos[1], currentHeading);
        //extra.weight = 1.0 / numParticles;
        //particles.add(extra);
    }

    // Get the estimated position (average position of all particles)
    public double[] getEstimatedPosition() {
        double avgX = 0, avgY = 0, avgTheta = 0;
        for (Particle particle : particles) {
            avgX += particle.x;
            avgY += particle.y;
            avgTheta += particle.theta;
        }
        avgX /= numParticles;
        avgY /= numParticles;
        avgTheta /= numParticles;

        return new double[]{avgX, avgY, avgTheta};
    }

    /**
     * Initializes the particles initially, centered at the reference position.
     * Particles have uniform weights of 1/numParticles.
     */
    private void initializeParticles() {
        Random rand = new Random();

        double[] initGnssCoordEmu = CoordinateConverter.convertGeodeticToEnu(
                firstGnssPosition.latitude, firstGnssPosition.longitude, 0,
                referencePosition[0], referencePosition[1], referencePosition[2]);

        currentPdrPos[0] = initGnssCoordEmu[0];
        currentPdrPos[1] = initGnssCoordEmu[1];

        previousPdrPos[0] = initGnssCoordEmu[0];
        previousPdrPos[1] = initGnssCoordEmu[1];

        for (int i = 0; i < numParticles; i++) {
            double x = previousPdrPos[0];
            double y = previousPdrPos[1];
            double theta = rand.nextDouble() * 2 * Math.PI - Math.PI;  // Random orientation

            Particle particle = new Particle(x, y, theta);
            particle.weight = (double) 1/numParticles;
            particles.add(particle);
        }

        particlesInitialized = true;
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