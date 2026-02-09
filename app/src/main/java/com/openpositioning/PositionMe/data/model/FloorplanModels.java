package com.openpositioning.PositionMe.data.model;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
/*
 * Minimal models for the OpenPositioning Floorplan API.
 *
 * Goal 5C uses:
 * - Venue outline / bounds for drawing clickable building polygons
 * - Floor imageUrl + bounds for placing a GroundOverlay
 */
public final class FloorplanModels {
    private FloorplanModels() {}
    public static final class Floor {
        public final int floorIndex;
        @Nullable public final String floorName;
        /**
         * Optional floorplan image overlay (legacy/server-provided). May be null.
         * Note: newer coursework versions return vector walls (GeoJSON) instead of PNGs.
         */
        @Nullable public final String imageUrl;
        /** Bounds to place an image overlay / to help camera fit. May be null. */
        @Nullable public final LatLngBounds bounds;
        /** Vector walls as polygons (closed rings). Each entry is one polygon ring. */
        @NonNull public final List<List<LatLng>> wallPolygons;
        /** Vector walls as line strings. Each entry is one line string. */
        @NonNull public final List<List<LatLng>> wallLines;
        /** Backward-compatible constructor (image only). */
        public Floor(int floorIndex,
                     @Nullable String floorName,
                     @Nullable String imageUrl,
                     @Nullable LatLngBounds bounds) {
            this(floorIndex, floorName, imageUrl, bounds, new ArrayList<>(), new ArrayList<>());
        }
        /** Full constructor (image + vector walls). */
        public Floor(int floorIndex,
                     @Nullable String floorName,
                     @Nullable String imageUrl,
                     @Nullable LatLngBounds bounds,
                     @NonNull List<List<LatLng>> wallPolygons,
                     @NonNull List<List<LatLng>> wallLines) {
            this.floorIndex = floorIndex;
            this.floorName = floorName;
            this.imageUrl = imageUrl;
            this.bounds = bounds;
            this.wallPolygons = wallPolygons != null ? wallPolygons : new ArrayList<>();
            this.wallLines = wallLines != null ? wallLines : new ArrayList<>();
        }
        /** True if this floor contains any vector geometry (walls). */
        public boolean hasGeometry() {
            return (wallPolygons != null && !wallPolygons.isEmpty())
                    || (wallLines != null && !wallLines.isEmpty());
        }
        @NonNull
        @Override
        public String toString() {
            if (floorName != null && !floorName.isEmpty()) return floorName;
            return "Floor " + floorIndex;
        }
    }
    public static final class Venue {
        @NonNull public final String venueId;
        @Nullable public final String venueName;
        @NonNull public final List<LatLng> outline;   // may be empty
        @Nullable public final LatLngBounds bounds;   // may be null
        @NonNull public final List<Floor> floors;     // may be empty
        public Venue(@NonNull String venueId,
                     @Nullable String venueName,
                     @NonNull List<LatLng> outline,
                     @Nullable LatLngBounds bounds,
                     @NonNull List<Floor> floors) {
            this.venueId = venueId;
            this.venueName = venueName;
            this.outline = outline != null ? outline : new ArrayList<>();
            this.bounds = bounds;
            this.floors = floors != null ? floors : new ArrayList<>();
        }
        /** Floors sorted by floorIndex ascending (useful for spinner). */
        @NonNull
        public List<Floor> floorsSorted() {
            if (floors.isEmpty()) return floors;
            List<Floor> copy = new ArrayList<>(floors);
            Collections.sort(copy, Comparator.comparingInt(f -> f.floorIndex));
            return copy;
        }
        @NonNull
        @Override
        public String toString() {
            if (venueName != null && !venueName.isEmpty()) return venueName;
            return venueId;
        }
    }
}
