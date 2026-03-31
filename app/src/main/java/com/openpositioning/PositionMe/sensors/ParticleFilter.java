
package com.openpositioning.PositionMe.sensors;
import android.util.Log;
import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import java.util.ArrayList;
import java.util.List;


import java.util.Random; //FOR PARTICLE FILTER

public class ParticleFilter {
    private static final String TAG = "ParticleFilter";
    private static final int NUM_PARTICLES = 1000; // Number of particles
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
}
