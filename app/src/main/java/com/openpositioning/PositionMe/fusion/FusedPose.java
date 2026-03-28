package com.openpositioning.PositionMe.fusion;
import com.google.android.gms.maps.model.LatLng;
import androidx.annotation.NonNull;

/**
 * Immutable model representing the latest fused pose estimate produced by the
 * particle filter.
 *
 * <p>The pose is stored both in local metric coordinates (x/y in meters)
 * and in geographic coordinates (LatLng) so that the filter can work in a
 * local ENU-like frame while the UI can render the result directly on the
 * Google Map.</p>
 */
public class FusedPose {
    private final double xMeters;
    private final double yMeters;
    private final double headingRad;
    private final int floor;
    private final LatLng latLng;
    private final float confidence;

    /**
     * Creates a new fused pose estimate.
     *
     * @param xMeters local x position in meters
     * @param yMeters local y position in meters
     * @param headingRad heading in radians
     * @param floor estimated floor
     * @param latLng geographic representation of the position
     * @param confidence confidence indicator in the range [0, 1]
     */
    public FusedPose(double xMeters,
                     double yMeters,
                     double headingRad,
                     int floor,
                     LatLng latLng,
                     float confidence) {
        this.xMeters = xMeters;
        this.yMeters = yMeters;
        this.headingRad = headingRad;
        this.floor = floor;
        this.latLng = latLng;
        this.confidence = confidence;
    }

    /**
     * Returns the fused local x coordinate in meters.
     *
     * @return local x coordinate
     */
    public double getXMeters() {
        return xMeters;
    }

    /**
     * Returns the fused local y coordinate in meters.
     *
     * @return local y coordinate
     */
    public double getYMeters() {
        return yMeters;
    }

    /**
     * Returns the fused heading in radians.
     *
     * @return heading in radians
     */
    public double getHeadingRad() {
        return headingRad;
    }

    /**
     * Returns the fused floor estimate.
     *
     * @return estimated floor
     */
    public int getFloor() {
        return floor;
    }

    /**
     * Returns the fused position as a geographic coordinate.
     *
     * @return fused geographic position
     */
    public LatLng getLatLng() {
        return latLng;
    }

    /**
     * Returns a simple confidence score derived from particle diversity.
     *
     * @return confidence in the range [0, 1]
     */
    public float getConfidence() {
        return confidence;
    }
}
