
package com.openpositioning.PositionMe.sensors;
import android.util.Log;
import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import java.util.ArrayList;
import java.util.List;


import java.util.Random; //FOR PARTICLE FILTER

public class ParticleFilter {
    private static final String TAG = "ParticleFilter";
    private static final int NUM_PARTICLES = 200; // Number of particles
    private float [] [] particles; 

    //[num particles][2] is {easting, northing} 
    private float [] weights; // Weights for each particle
    private LatLng origin; // Origin point for ENU conversion

    private boolean initialized = false;
    private final Random random = new Random();

    public void initialise(LatLng firstFix, float accuracyMeters) {
        if (initialized) return;          // ignore subsequent calls, DONE
        this.origin   = firstFix;
        this.particles = new float[NUM_PARTICLES][2];


        this.weights   = new float[NUM_PARTICLES];
        float spread   = accuracyMeters;

        for (int i = 0; i < NUM_PARTICLES; i++) {
            particles[i][0] = (float) (random.nextGaussian() * spread); // east
            particles[i][1] = (float) (random.nextGaussian() * spread); // north
            weights[i]      = 1.0f / NUM_PARTICLES;
        }
        initialized = true;
        Log.d(TAG, "Initialized at " + firstFix + " accuraccy is =" + accuracyMeters + "m");
    }

    public boolean isInitialized() {
        return initialized ;
    }
    public LatLng getOrigin() {
        return origin;
    }


    public static final float HEADING_NOISE_STD = 0.01f; //TODO CHANGE LATER??
    public static final float STRIDE_LENGTH_NOISE_STD = 0.01f; // WE CAN TUNE THESE NOISE PARAMETERS BASED ON EXPECTED SENSOR ACCURACY AND ENVIRONMENTAL CONDITIONS

    public void predict(float deltaEasting, float deltaNorthing) {
        if (!initialized) return; // ignore if not initialized


        float stride = (float) Math.sqrt(deltaEasting * deltaEasting + deltaNorthing * deltaNorthing);
        float heading = (float) Math.atan2(deltaNorthing, deltaEasting); // calculate heading from deltas

        for (int i = 0; i < NUM_PARTICLES; i++) {
            // Add noise to heading and stride
            float noisyHeading = heading + (float) (random.nextGaussian() * HEADING_NOISE_STD);
            float noisyStride = stride + (float) (random.nextGaussian() * STRIDE_LENGTH_NOISE_STD);

            // Update particle position based on noisy heading and stride
            particles[i][0] += noisyStride * Math.cos(noisyHeading); // update east
            particles[i][1] += noisyStride * Math.sin(noisyHeading); // update north
        }

    }

        
}
