package com.openpositioning.PositionMe.Fusion;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.CoordinateTransform;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.Random;

/**
 * ParticleFilter is a simple implementation of a particle filter for fusing sensor-based position estimates.
 * It maintains an array of particles representing possible positions in the ENU coordinate system relative to a reference point obtained from SensorFusion.
 * The filter initializes by converting the reference latitude, longitude, and altitude to ENU coordinates and then randomly dispersing a fixed number of particles around this reference using a Gaussian distribution.
 * When a new position measurement (such as from GNSS or Wi-Fi) is received, the measurement is transformed into ENU coordinates and each particle is slightly nudged toward this measurement.
 * The overall estimated position is then computed as the average of the particles’ positions, which is converted back to geographic coordinates for further processing.
 * This minimal design does not implement advanced resampling or weight adjustments, but it provides a straightforward framework for fusing sensor data to estimate position.
 */
public class ParticleFilter implements FusionAlgorithm {

    // 1) Number of particles
    private static final int NUM_PARTICLES = 50;
    // 2) Particles array
    private Particle[] particles;
    private final Random random;

    // 3) Reference lat, lon, alt (from sensorFusion)
    private final double refLatitude;
    private final double refLongitude;
    private final double refAltitude;

    // 4) ENU reference for initialization
    private final double initialEasting;
    private final double initialNorthing;

    /**
     * Constructor:
     *  - obtains a reference latitude/longitude/altitude from SensorFusion
     *  - computes the ENU (0,0) reference
     *  - initializes particles around that reference
     */
    public ParticleFilter() {
        // 1) Initial Refference
        double[] startRef = SensorFusion.getInstance().getGNSSLatLngAlt(true);
        this.refLatitude  = startRef[0];
        this.refLongitude = startRef[1];
        this.refAltitude  = startRef[2];

        // 2) Convert the starting point to ENU yourself (it should become approximately (0,0)).
        double[] enuRef = CoordinateTransform.geodeticToEnu(
                refLatitude, refLongitude, refAltitude,
                refLatitude, refLongitude, refAltitude
        );
        this.initialEasting  = enuRef[0];
        this.initialNorthing = enuRef[1];

        // 3) Initialize particle array and random number generator
        this.particles = new Particle[NUM_PARTICLES];
        this.random = new Random();

        // 4) Randomly scatter a batch of particles near the initial position
        initializeParticles();
    }

    /**
     * Randomly distribute particles around the initial easting/northing.
     */
    private void initializeParticles() {
        double initStdDev = 5.0; // example: 5 m standard dev for initialization
        for (int i = 0; i < NUM_PARTICLES; i++) {
            double easting = initialEasting  + random.nextGaussian() * initStdDev;
            double northing = initialNorthing + random.nextGaussian() * initStdDev;
            // Weight is simply unified first = 1/NUM_PARTICLES
            particles[i] = new Particle(easting, northing, 1.0 / NUM_PARTICLES);
        }
    }

    /**
     * A minimal update function – receives a new lat/lon measurement, transforms it to ENU,
     * and adjusts each particle slightly toward that measurement.
     *
     * @param measuredLat  new measurement's latitude
     * @param measuredLon  new measurement's longitude
     */
    public void update(double measuredLat, double measuredLon) {
        // 1) Convert measurement to ENU
        double[] enuMeas = CoordinateTransform.geodeticToEnu(
                measuredLat, measuredLon, refAltitude,
                refLatitude, refLongitude, refAltitude
        );
        double measEasting  = enuMeas[0];
        double measNorthing = enuMeas[1];

        // 2) Update each particle – for a minimal example:
        //    Let each particle drift 10% toward the measurement
        for (Particle p : particles) {
            p.easting  += 0.1 * (measEasting  - p.easting);
            p.northing += 0.1 * (measNorthing - p.northing);
        }

        // 3) Predict overall position (simple average)
        LatLng fusedLatLng = predict();

        // 4) Pass fusedLatLng back to UI
        SensorFusion.getInstance().notifyFusedUpdate(fusedLatLng);
    }

    /**
     * Returns an estimate of the current location by taking the
     * weighted average of all particle positions. Simplified to
     * uniform weighting in this minimal example.
     */
    public LatLng predict() {
        double sumEast  = 0.0;
        double sumNorth = 0.0;

        for (Particle p : particles) {
            sumEast  += p.easting;
            sumNorth += p.northing;
        }
        double avgEast  = sumEast  / NUM_PARTICLES;
        double avgNorth = sumNorth / NUM_PARTICLES;

        // Convert ENU back to lat/lon
        return CoordinateTransform.enuToGeodetic(
                avgEast, avgNorth, refAltitude,
                initialEasting, initialNorthing, refAltitude
        );
    }

    @Override
    public void init(float[] initialPos) {
        // You could re-initialize particles around a new ENU origin here if needed
        // For now, we leave it empty since the constructor already does it
    }

    @Override
    public void predict(float[] delta) {
        // Move each particle by delta[0] (east), delta[1] (north)
        for (Particle p : particles) {
            p.easting += delta[0];
            p.northing += delta[1];
        }
    }

    @Override
    public void updateFromGnss(float[] gnssPos) {
        // Assume input is ENU coordinates: gnssPos[0] = easting, gnssPos[1] = northing
        for (Particle p : particles) {
            p.easting += 0.1 * (gnssPos[0] - p.easting);
            p.northing += 0.1 * (gnssPos[1] - p.northing);
        }

        LatLng fusedLatLng = predict();
        SensorFusion.getInstance().notifyFusedUpdate(fusedLatLng);
    }

    @Override
    public void updateFromWifi(float[] wifiPos) {
        // Same as GNSS update — minimal approach
        updateFromGnss(wifiPos);
    }

    @Override
    public float[] getFusedPosition() {
        double sumEast = 0.0;
        double sumNorth = 0.0;
        for (Particle p : particles) {
            sumEast += p.easting;
            sumNorth += p.northing;
        }
        float avgEast = (float) (sumEast / NUM_PARTICLES);
        float avgNorth = (float) (sumNorth / NUM_PARTICLES);
        return new float[]{avgEast, avgNorth};
    }

    @Override
    public void onOpportunisticUpdate(double east, double north, boolean isGNSS, long refTime) {
        // Minimal handling for now – treat this like a basic update
        updateFromGnss(new float[]{(float) east, (float) north});
    }

    @Override
    public void onStepDetected(double pdrEast, double pdrNorth, double altitude, long refTime) {
        // Optionally: nudge particles toward new PDR step
        // This is a placeholder example – real PF would resample/move particles more smartly
        for (Particle p : particles) {
            p.easting += 0.5 * (pdrEast - p.easting);
            p.northing += 0.5 * (pdrNorth - p.northing);
        }
        SensorFusion.getInstance().notifyFusedUpdate(predict());
    }

    @Override
    public double[] getState() {
        // Return [bearing=0, x, y] for compatibility
        LatLng pos = predict();
        return new double[]{0, pos.latitude, pos.longitude};
    }

    @Override
    public void stopFusion() {
        // Nothing to stop for PF; no thread running
    }



    /**
     * An extremely simple Particle class storing (easting, northing, weight).
     * For this minimal version, we won't do advanced weighting/resampling, etc.
     */
    private static class Particle {
        double easting;
        double northing;
        double weight;

        Particle(double e, double n, double w) {
            this.easting  = e;
            this.northing = n;
            this.weight   = w;
        }
    }

}

