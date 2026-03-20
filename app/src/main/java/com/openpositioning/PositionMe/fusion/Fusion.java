package com.openpositioning.PositionMe.fusion;

import android.util.Log;
import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.PdrProcessing;

/**
 * Top-level fusion class that manages corrected position estimation.
 *
 * <p>Wraps a {@link ParticleFilter} and exposes a simplified interface for the rest of the
 * application. PDR updates are forwarded to the particle filter, and the best position estimate is
 * derived from the weighted particle population.
 *
 * @see ParticleFilter for the underlying sequential Monte Carlo implementation.
 * @see SensorFusion for the caller that drives PDR and WiFi updates.
 */
public class Fusion {
    // Current best position estimate in WGS84
    private LatLng bestEstimate;
    // Estimated floor level
    private int EstimatedFloor;
    // Whether the fusion system is actively tracking
    public boolean isActive;
    // Underlying particle filter instance
    private final ParticleFilter particleFilter;
    private PdrProcessing pdrProcessing;

    public Fusion() {
        this.particleFilter = new ParticleFilter();
    }

    /**
     * Forwards a PDR displacement to the particle filter for the prediction step.
     *
     * @param dx easting displacement in metres.
     * @param dy northing displacement in metres.
     */
    public void filterPDRUpdate(float dx, float dy) {
        this.particleFilter.updateWithPDR(dx, dy);
    }

    // Converts GNSS from WGS84 to local EN metres and queues as a particle filter observation
    // accuracyMetres comes from location.getAccuracy()
    public void onGnssUpdate(LatLng pos, float accuracyMetres) {
        if (particleFilter.isNotActive()) return;
        double[] en = particleFilter.latLngToEN(pos.latitude, pos.longitude);
        particleFilter.addObservation(en[0], en[1], accuracyMetres);
    }

    // Converts WiFi position from WGS84 to local EN metres and queues as a particle filter
    // observation
    // sigma is fixed at 10.0 m - arbitrary for now
    public void onWifiUpdate(LatLng pos, double sigmaMetres) {
        if (particleFilter.isNotActive()) return;
        double[] en = particleFilter.latLngToEN(pos.latitude, pos.longitude);
        particleFilter.addObservation(en[0], en[1], sigmaMetres);
    }

    /** Stops the fusion system and releases the particle filter resources. */
    public void stop() {
        Log.d("Fusion", "Fusion stopped", new Exception("stop() call stack"));
        this.particleFilter.stop();
        isActive = false;
    }

    /**
     * Initialises the fusion system and seeds the particle filter around the given position.
     *
     * @param initial_estimate is a place holder for the starting position in WGS84 coordinates. -
     */
    public void start(LatLng initial_estimate, PdrProcessing pdrProcessing) {
        isActive = true;
        Log.d("Fusion", "Fusion started at: " + initial_estimate);
        this.pdrProcessing = pdrProcessing;
        this.bestEstimate = getStartLocation(initial_estimate);
        this.particleFilter.start(this.bestEstimate);
    }

    /**
     * Returns the current fused position estimate from the particle filter.
     *
     * @return {@link LatLng} of the best estimated position.
     */
    public LatLng getBestEstimate() {
        LatLng particleFilterEstimate = particleFilter.getEstimated_position();
        bestEstimate = particleFilterEstimate;
        return bestEstimate;
    }

    /**
     * - TODO Returns a uniquely fused starting position estimate.
     *
     * @return {@link LatLng} of the best start position estimate.
     */
    public LatLng getStartLocation(LatLng initial_estimate) {
        return initial_estimate;
    }

    public int getEstimatedFloor() {
        return EstimatedFloor;
    }

    public boolean isActive() {
        return isActive;
    }
}
