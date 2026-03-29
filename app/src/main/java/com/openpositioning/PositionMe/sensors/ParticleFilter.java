package com.openpositioning.PositionMe.sensors;

import com.google.android.gms.maps.model.LatLng;

import android.util.Log;

import java.util.Random;

/**
 * The Particle Filter class represents a sensor fusion algorithm
 */

public class ParticleFilter {

    // Number of particles maintained by the filter
    private static final int Num_Particles = 200;

    // Meters conversion constant for WGS84 to easting, northing space
    private static final double Meters_Per_Degree = 111111.0;

    // Initial standard deviation spread from GNSS/WiFi fix.
    private static final float GNSS_Init_STD = 15.0f;
    private static final float WiFi_Init_STD = 8.0f;

    // PDR process noise standard deviation approximation (adjustable)
    public static final float PDR_Process_Noise_STD = 0.4f;

    // Min and max GNSS measurment accuracy for weight update (define rejected measurements)
    private static final float GNSS_Min_Accuracy = 5.0f;
    private static final float GNSS_Max_Accuracy = 50.0f;

    // The defined particle set
    private final Particle[] particles;

    // WGS84 reference point used as the local coordinate frame origin
    private LatLng originLatLng;

    // Weighted estimate easting and northing of the current position estimate
    private float estimatedX;
    private float estimatedY;

    // True once initial position estimation has been established
    private boolean initialised;

    private final Random random;
    private final SensorFusion sensorFusion;

    // Defines a single hypothesis about the user's position
    private static class Particle{
        // Easting offset from local origin
        float x;

        //Northing offset from local origin
        float y;

        // Normalised importance weight
        float weight;

        Particle(float x, float y, float weight) {
            this.x = x;
            this.y = y;
            this.weight = weight;
        }
    }

    /**
     * Constructor for the Particle Filter class.
     *
     * Attempts to initialise position and particles estimate from available WiFi and GNSS data.
     * If neither are available at construction, tryInitialise() attempts again on later call.
     */
    public ParticleFilter(){
        this.sensorFusion = SensorFusion.getInstance();
        this.particles = new Particle[Num_Particles];
        this.random = new Random();
        this.initialised = false;

        initialisePosition();
    }

    /**
     * Automatically estimates the first available position from WiFi or GNSS
     * and defines the particle set around this WGS84 point as an isotropic 2D
     * Gaussian distribution.
     *
     * WiFi estimation prioritised for its greater indoor positioning accuracy.
     * If unavailable, GNSS is used for the initial estimation instead.
     * If neither is available, tryIinitialise() is called later.
     */
    private void initialisePosition() {
        float[] gnssLatLon = sensorFusion.getGNSSLatitude(false);
        LatLng wifiLatLng = sensorFusion.getLatLngWifiPositioning();

        // GNSS positioning returns null if no reading is available
        boolean hasGNSS = gnssLatLon != null
                && (gnssLatLon[0] != 0f || gnssLatLon[1] != 0f);

        // WiFi positioning returns null if no reading is available
        boolean hasWifi = wifiLatLng != null;

        // Defer initialisation if neither GNSS or WiFi have a valid reading
        if (!hasGNSS && !hasWifi) {
            return; // Initialisation retried via tryInitialise() on later call
        }

        // Initialisation source and particle spread based on priority
        LatLng initLatLng;
        float spreadStd;

        // Uses WiFi positioning if available, otherwise GNSS as fallback
        if (hasWifi) {
            initLatLng = wifiLatLng;
            spreadStd = WiFi_Init_STD;
        }  else {
            initLatLng = new LatLng(gnssLatLon[0], gnssLatLon[1]);
            spreadStd = GNSS_Init_STD;
        }

        // Set local frame origin at initial estimated position
        originLatLng = initLatLng;
        Log.d("ParticleFilter", "Origin set to: "
                + originLatLng.latitude + ", "
                + originLatLng.longitude);

        // Distribute particles around (0 , 0) in local frame
        spreadParticles(0.0f, 0.0f, spreadStd);
        initialised = true;
    }

    /**
     * Function to retry initial position estimation if it was deferred at construction
      */
    public void tryInitialise() {
        if (!initialised) {
            initialisePosition();
        }
    }

    /**
     * Distributes particles as an isotropic 2D gaussian distribution around
     * (centerX, centerY) and assigns equal weights to each particle (1/N).
     */
    private void spreadParticles(float centerX, float centerY, float stdM) {
        float initWeight = 1.0f / Num_Particles;
        for (int i = 0; i < Num_Particles; i++) {
            float x = centerX + (float) (random.nextGaussian() * stdM);
            float y = centerY + (float) (random.nextGaussian() * stdM);
            particles[i] = new Particle(x, y, initWeight);
        }

        estimatedX = centerX;
        estimatedY = centerY;
    }

    /**
     * PDR motion model prediction
     *
     * Moves all defined particles according to PDR displacement with
     * added Gaussian process noise to account for uncertainty in step length
     * and heading.
     */
    public void predict(float deltaEast, float deltaNorth, float PDRstd) {
        // Early return if filter not initialised
        if (!initialised) {
            return;
        }

        // Shift each particle with Gaussian noise considered
        for (int i = 0; i < Num_Particles; i++) {
            float noiseX = (float) (random.nextGaussian() * PDRstd);
            float noiseY = (float) (random.nextGaussian() * PDRstd);
            particles[i].x += deltaEast + noiseX;
            particles[i].y += deltaNorth + noiseY;
        }

        // Update weighted mean estimate
        estimatedX = 0f;
        estimatedY = 0f;
        for (int i = 0; i < Num_Particles; i++) {
            estimatedX += particles[i].x * particles[i].weight;
            estimatedY += particles[i].y * particles[i].weight;
        }

        //Debug test
        Log.d("ParticleFilter", "Delta: (" + deltaEast + ", " + deltaNorth
            + " ; Estimate: ("+ estimatedX + ", " + estimatedY +")");
    }

    /**
     *  Particle weight update
     */
    public void updateWeights(LatLng measurementLatLng, float accuracy) {
        // Early return if not initialised
        if (!initialised || measurementLatLng == null) {
            return;
        }

        // Early return if out of accuracy range (poor measurement)
        if (accuracy <= 0 || accuracy >= GNSS_Max_Accuracy) {
            return;
        }
        float sigma = Math.max(accuracy, GNSS_Min_Accuracy);

        // Convert received measurement to local frame (meters)
        float[] Measurement_Local = latLngToLocal(measurementLatLng);
        float mx = Measurement_Local[0];
        float my = Measurement_Local[1];

        // Calculate each particle weighting
        float twoSigmaSquared = 2.0f * sigma * sigma;
        for (int i = 0; i < Num_Particles; i++) {
            // Calculate distance in meters from particle to measurement
            float dx = particles[i].x - mx;
            float dy = particles[i].y - my;

            // Gaussian likelihood equation
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float dSquared = d * d;
            float likelihood = (float) Math.exp(-dSquared / twoSigmaSquared);
            particles[i].weight *= likelihood;
        }

        // Normalise weights and to sum = 1.0
        float weightSum = 0f;
        for (int i = 0; i < Num_Particles; i++) {
            weightSum += particles[i].weight;
        }
        for (int i = 0; i < Num_Particles; i++) {
            particles[i].weight /= weightSum;
        }

        // Recalculate weighted mean estimate of position
        estimatedX = 0f;
        estimatedY = 0f;
        for (int i = 0; i < Num_Particles; i++) {
            estimatedX += particles[i].x * particles[i].weight;
            estimatedY += particles[i].y * particles[i].weight;
        }
    }

    /**
     * Converts a WGS84 LatLng to a local easting/northing offset in meters
     * relative to the local frame origin (originLatLng).
     */
    public float[] latLngToLocal(LatLng point) {
        if (originLatLng == null) return new float[]{0f, 0f};

        double latDiff = point.latitude  - originLatLng.latitude;
        double lonDiff = point.longitude - originLatLng.longitude;

        float northing = (float) (latDiff * Meters_Per_Degree);
        float easting  = (float) (lonDiff * Meters_Per_Degree
                * Math.cos(Math.toRadians(originLatLng.latitude)));

        return new float[]{easting, northing};
    }

    /**
     * Converts a local easting/northing offset in meters back to WGS84.
     */
    public LatLng localToLatLng(float eastingM, float northingM) {
        if (originLatLng == null) return null;

        double lat = originLatLng.latitude
                + northingM / Meters_Per_Degree;
        double lon = originLatLng.longitude
                + eastingM / (Meters_Per_Degree
                * Math.cos(Math.toRadians(originLatLng.latitude)));

        return new LatLng(lat, lon);
    }

    /**
     * State accessors defined below.
     */

    public boolean isInitialised() {
        return initialised;
    }

    public LatLng getEstimatedPosition() {
        if (!initialised) return null;
        return localToLatLng(estimatedX, estimatedY);
    }

    public float[] getEstimatedLocalPosition() {
        return new float[]{estimatedX, estimatedY};
    }

    public LatLng getOrigin() {
        return originLatLng;
    }
}
