package com.openpositioning.PositionMe.utils;

/**
 * Centralized defaults for map-matching parameters.
 * Values are in meters unless stated otherwise.
 * No behavior change is made by instantiating this class; it is a holder for later tuning.
 */
public class MapMatchingConfig {

    /** Minimum vertical delta from barometer to consider a floor change (meters). */
    public final float baroHeightThreshold;

    /** Maximum horizontal displacement to still classify a cross-floor move as lift (meters). */
    public final float liftHorizontalMax;

    /** Minimum horizontal displacement to classify a cross-floor move as stairs (meters). */
    public final float stairsHorizontalMin;

    /** Padding distance to keep corrected paths away from walls (meters). */
    public final float wallPadding;

    /** Proximity radius to accept cross-floor features (meters). */
    public final float crossFeatureProximity;

    public MapMatchingConfig() {
        // Defaults: Nucleus floor height 5.5 m, 3 m baro threshold, 10 m feature proximity
        this(3.0f, 1.5f, 2.0f, 0.2f, 10.0f);
    }

    public MapMatchingConfig(float baroHeightThreshold,
                             float liftHorizontalMax,
                             float stairsHorizontalMin,
                             float wallPadding,
                             float crossFeatureProximity) {
        this.baroHeightThreshold = baroHeightThreshold;
        this.liftHorizontalMax = liftHorizontalMax;
        this.stairsHorizontalMin = stairsHorizontalMin;
        this.wallPadding = wallPadding;
        this.crossFeatureProximity = crossFeatureProximity;
    }
}
