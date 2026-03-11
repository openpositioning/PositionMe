package com.openpositioning.PositionMe.Fusion;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.PdrProcessing;

/**
 * Top-level fusion class that manages corrected position estimation.
 *
 * <p>Wraps a {@link ParticleFilter} and exposes a simplified interface for the rest of the
 * application. PDR updates are forwarded to the particle filter, and the best position
 * estimate is derived from the weighted particle population.
 *
 * @see ParticleFilter for the underlying sequential Monte Carlo implementation.
 * @see SensorFusion for the caller that drives PDR and WiFi updates.
 */
public class Fusion {
    // Current best position estimate in WGS84
    private LatLng BestEstimate;
    // Estimated floor level
    private int EstimatedFloor;
    // Whether the fusion system is actively tracking
    public boolean isActive;
    // Underlying particle filter instance
    private final ParticleFilter particleFilter;
    private PdrProcessing pdrProcessing;

    public Fusion(){
        this.particleFilter = new ParticleFilter();
    }

    /**
     * Forwards a PDR displacement to the particle filter for the prediction step.
     *
     * @param dx easting displacement in metres.
     * @param dy northing displacement in metres.
     */
    public void filterPDRUpdate(float dx, float dy){
        this.particleFilter.updateWithPDR(dx, dy);
    }

    /**
     * Stops the fusion system and releases the particle filter resources.
     */
    public void stop(){
        this.particleFilter.stop();
        isActive = false;
    }

    /**
     * Initialises the fusion system and seeds the particle filter around the given position.
     *
     * @param initial_pos starting position in WGS84 coordinates.
     */
    public void start(LatLng initial_pos, PdrProcessing pdrProcessing){
        isActive = true;
        this.BestEstimate = initial_pos;
        this.particleFilter.start(initial_pos);
    }

    /**
     * Returns the current fused position estimate from the particle filter.
     *
     * @return {@link LatLng} of the best estimated position.
     */
    public LatLng getBestEstimate() {
        LatLng particleFilterEstimate = particleFilter.getEstimated_position();
        return BestEstimate;
    }

    public int getEstimatedFloor() {
        return EstimatedFloor;
    }

    public boolean isActive() {
        return isActive;
    }
}