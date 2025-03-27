package com.openpositioning.PositionMe.Fusion;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.CoordinateTransform;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.Random;

/**
 *  Particle Filter.
 */
public class ParticleFilter {

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
        // 1) 取起始参考坐标
        double[] startRef = SensorFusion.getInstance().getGNSSLatLngAlt(true);
        this.refLatitude  = startRef[0];
        this.refLongitude = startRef[1];
        this.refAltitude  = startRef[2];

        // 2) 将起始点自己转换为 ENU (它应当变成近似 (0,0) ).
        double[] enuRef = CoordinateTransform.geodeticToEnu(
                refLatitude, refLongitude, refAltitude,
                refLatitude, refLongitude, refAltitude
        );
        this.initialEasting  = enuRef[0];
        this.initialNorthing = enuRef[1];

        // 3) 初始化粒子数组与随机数生成器
        this.particles = new Particle[NUM_PARTICLES];
        this.random = new Random();

        // 4) 在初始位置附近随机撒一批粒子
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
            // weight 先简单统一 = 1/NUM_PARTICLES
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