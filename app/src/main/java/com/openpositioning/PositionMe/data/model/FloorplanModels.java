package com.openpositioning.PositionMe.data.model;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class FloorplanModels {
    public static final class Floor {
        public final int floorIndex;
        @Nullable public final String floorName;
        @Nullable public final String imageUrl;
        @Nullable public final LatLngBounds bounds;
        @NonNull public final List<List<LatLng>> wallPolygons;
        @NonNull public final List<List<LatLng>> wallLines;
        public Floor(int floorIndex,
                     @Nullable String floorName,
                     @Nullable String imageUrl,
                     @Nullable LatLngBounds bounds) {
            this(floorIndex, floorName, imageUrl, bounds, new ArrayList<>(), new ArrayList<>());
        }
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
