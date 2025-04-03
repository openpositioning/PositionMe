package com.openpositioning.PositionMe.domain;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.CoordinateTransform;
import com.openpositioning.PositionMe.utils.MapConstraint;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A simple Particle Filter for indoor/outdoor positioning that can fuse PDR, Wi-Fi, and GNSS.
 *
 * Each particle represents a hypothesis of the user's state (position, optionally heading/floor).
 * The filter uses PDR steps for prediction, and Wi-Fi / GNSS for observations.
 *
 * This is a reference skeleton that can be extended with:
 *  - Additional map constraints
 *  - More complex measurement models
 *  - Floor transitions or elevator detection
 *
 * State model (2D + heading) example:
 *   x, y, theta
 *
 * Particle filter steps:
 *  1) Initialization
 *  2) Prediction: propagate each particle by PDR step + noise
 *  3) Update: compare each particle to Wi-Fi / GNSS measurement -> compute weight
 *  4) Resampling: resample particles according to updated weights
 */
public class ParticleFilter {

    /**
     * A small Particle data class.
     * Depending on your scenario, you may store additional states like floor, z, or velocity.
     */
    public static class Particle {
        public double x;
        public double y;
        // Optional: heading in radians
        public double theta;
        // weight
        public double weight;

        public Particle(double x, double y, double theta, double weight) {
            this.x = x;
            this.y = y;
            this.theta = theta;
            this.weight = weight;
        }
    }

    private List<Particle> particles;
    private int numParticles;
    private Random random;

    // For noise modeling
    private double stepLenNoiseSigma = 0.02; // meters
    private double headingNoiseSigma = 0.05; // radians

    // For measurement weighting
    private double wifiStd = 3.0;  // default Wi-Fi measurement std, can be tuned
    private double gnssStd = 5.0;  // default GNSS measurement std, can be tuned

    // Reference coords, so we unify everything in an ENU coordinate system
    //  e.g. same coords used by the existing EKF
    private double refLat;
    private double refLon;
    private double refAlt;
    private MapConstraint mapConstraint;

    /**
     * Constructor: initialize with a certain number of particles,
     * and a reference coordinate for coordinate transforms.
     */
    public ParticleFilter(int numParticles, double refLat, double refLon, double refAlt) {
        this.numParticles = numParticles;
        this.refLat = refLat;
        this.refLon = refLon;
        this.refAlt = refAlt;
        this.random = new Random();
        this.particles = new ArrayList<>(numParticles);
    }

    /**
     * Initialize the particles around a given initial location, with some spread.
     * If you also have an initial heading estimate, set it here.
     */
    public void initializeParticles(double initX, double initY, double initTheta,
                                    double spreadX, double spreadY, double spreadTheta) {
        particles.clear();
        for (int i = 0; i < numParticles; i++) {
            double x = initX + randomGaussian() * spreadX;
            double y = initY + randomGaussian() * spreadY;
            double th = initTheta + randomGaussian() * spreadTheta;
            particles.add(new Particle(x, y, th, 1.0 / numParticles));
        }
    }

    /**
     * Particle prediction step using PDR.
     * stepLen: step length in meters
     * deltaHeading: heading change in radians (if using heading updates from gyroscope/magnetometer)
     * If you rely on external orientation, pass that in. Otherwise, you can keep track of heading internally.
     */
    public void predict(double stepLen, double deltaHeading) {
        for (Particle p : particles) {
            // Add noise to step length and heading
            double noisyStep = stepLen + randomGaussian() * stepLenNoiseSigma;
            double noisyHeading = deltaHeading + randomGaussian() * headingNoiseSigma;

            // Update heading
            p.theta += noisyHeading;
            p.theta = wrapToPi(p.theta);

            // Move particle
            p.x += noisyStep * Math.sin(p.theta);
            p.y += noisyStep * Math.cos(p.theta);
            // Optional: constrain to map area
            if (mapConstraint != null && !mapConstraint.isInside(p.x, p.y)) {
                p.weight = 0.0;
            }
        }
    }

    /**
     * Weight update using Wi-Fi measurement.
     * Suppose we have a Wi-Fi position (wifiX, wifiY) in the same ENU coordinate system.
     * We compute the likelihood of each particle relative to this observation.
     * Then we multiply the existing weights by this likelihood.
     */
    public void updateWiFi(double wifiX, double wifiY) {
        double var = wifiStd * wifiStd;
        double twoVar = 2.0 * var;
        for (Particle p : particles) {
            // distance from particle to the WiFi measurement
            double dx = p.x - wifiX;
            double dy = p.y - wifiY;
            double distSq = dx * dx + dy * dy;

            // Weight by Gaussian likelihood
            double w = Math.exp(-distSq / twoVar);
            // multiply old weight
            p.weight *= w;
        }
        normalizeWeights();
    }

    /**
     * Weight update using GNSS measurement.
     * Suppose we have a GNSS position (gnssLat, gnssLon). Convert it to ENU => (gnssX, gnssY).
     * Then do the same approach as Wi-Fi.
     */
    public void updateGNSS(double gnssLat, double gnssLon) {
        // Convert gnssLat, gnssLon to ENU using reference coords
        double[] enu = CoordinateTransform.geodeticToEnu(
                gnssLat, gnssLon, 0.0, refLat, refLon, refAlt);
        double gnssX = enu[0];
        double gnssY = enu[1];
        double var = gnssStd * gnssStd;
        double twoVar = 2.0 * var;

        for (Particle p : particles) {
            double dx = p.x - gnssX;
            double dy = p.y - gnssY;
            double distSq = dx * dx + dy * dy;
            double w = Math.exp(-distSq / twoVar);
            p.weight *= w;
        }
        normalizeWeights();
    }

    /**
     * Resample particles using systematic or multinomial resampling.
     * The simpler version: draw with replacement, proportionally to weight.
     */
    public void resample() {
        double[] cdf = new double[numParticles];
        cdf[0] = particles.get(0).weight;
        for (int i = 1; i < numParticles; i++) {
            cdf[i] = cdf[i - 1] + particles.get(i).weight;
        }
        double sumW = cdf[numParticles - 1];
        if (sumW < 1e-15) {
            // If all weights are zero, re-init uniform
            for (Particle p : particles) {
                p.weight = 1.0 / numParticles;
            }
            return;
        }

        List<Particle> newParticles = new ArrayList<>(numParticles);
        for (int i = 0; i < numParticles; i++) {
            double r = random.nextDouble() * sumW;
            int idx = binarySearchIndex(cdf, r);
            Particle chosen = particles.get(idx);
            // copy the chosen particle
            Particle np = new Particle(chosen.x, chosen.y, chosen.theta, 1.0 / numParticles);
            newParticles.add(np);
        }
        // replace old with new
        this.particles = newParticles;
    }

    /**
     * Get the best estimate of user position from the particles.
     * Typically use weighted average or best-weight.
     * This method uses weighted average.
     */
    public double[] getEstimate2D() {
        double sumW = 0;
        double sumX = 0;
        double sumY = 0;
        double sumTheta = 0;
        for (Particle p : particles) {
            sumW += p.weight;
            sumX += p.x * p.weight;
            sumY += p.y * p.weight;
            sumTheta += p.theta * p.weight;
        }
        if (sumW < 1e-15) {
            return new double[]{0.0, 0.0, 0.0};
        }
        double estX = sumX / sumW;
        double estY = sumY / sumW;
        double estTheta = sumTheta / sumW;
        return new double[]{estX, estY, estTheta};
    }

    /**
     * Optionally convert the PF estimate back to LatLng for consistency.
     */
    public LatLng getEstimateLatLng() {
        double[] est = getEstimate2D();
        double estX = est[0];
        double estY = est[1];
        double estZ = 0.0; // assume 0 unless you also track altitude
        return CoordinateTransform.enuToGeodetic(estX, estY, estZ, refLat, refLon, refAlt);
    }

    /**
     * Normalize weights so they sum to 1.
     */
    private void normalizeWeights() {
        double sum = 0;
        for (Particle p : particles) {
            sum += p.weight;
        }
        if (sum < 1e-15) {
            // avoid dividing by zero. re-init uniform
            double w = 1.0 / numParticles;
            for (Particle p : particles) {
                p.weight = w;
            }
        } else {
            for (Particle p : particles) {
                p.weight /= sum;
            }
        }
    }

    private double randomGaussian() {
        return random.nextGaussian();
    }

    /**
     * A simple helper for resample: find index in cdf that is >= r.
     */
    private int binarySearchIndex(double[] cdf, double r) {
        int low = 0;
        int high = cdf.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (cdf[mid] < r) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Utility function to wrap angle to [-pi, pi].
     */
    private double wrapToPi(double angle) {
        while (angle > Math.PI) {
            angle -= 2 * Math.PI;
        }
        while (angle < -Math.PI) {
            angle += 2 * Math.PI;
        }
        return angle;
    }

    public void setMapConstraint(MapConstraint mapConstraint) {
        this.mapConstraint = mapConstraint;
    }
    // region Setters & getters for parameters
    public void setStepLenNoiseSigma(double stepLenNoiseSigma) {
        this.stepLenNoiseSigma = stepLenNoiseSigma;
    }

    public void setHeadingNoiseSigma(double headingNoiseSigma) {
        this.headingNoiseSigma = headingNoiseSigma;
    }

    public void setWifiStd(double wifiStd) {
        this.wifiStd = wifiStd;
    }

    public void setGnssStd(double gnssStd) {
        this.gnssStd = gnssStd;
    }

    public List<Particle> getParticles() {
        return particles;
    }
    // endregion

    public double computeEntropy() {
        double meanX = 0, meanY = 0;
        for (Particle p : particles) {
            meanX += p.x;
            meanY += p.y;
        }
        meanX /= particles.size();
        meanY /= particles.size();

        double varSum = 0;
        for (Particle p : particles) {
            double dx = p.x - meanX;
            double dy = p.y - meanY;
            varSum += dx * dx + dy * dy;
        }
        return varSum / particles.size(); // variance as a proxy for entropy
    }
}