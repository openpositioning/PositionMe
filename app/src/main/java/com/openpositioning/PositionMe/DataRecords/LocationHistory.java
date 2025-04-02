package com.openpositioning.PositionMe.DataRecords;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/**
 * This class maintains a history of location records and optionally visualizes them on a Google Map.
 */
public class LocationHistory {
    // List to store location data records
    private final List<LocationData> history;

    // Reference to the currently drawn polyline on the map
    private Polyline polyline; // this is the drawable line


    // Maximum number of records to store (-1 means unlimited)
    private int maxSize = -1; // -1 means unlimited


    // Default constructor with unlimited history
    public LocationHistory() {
        this.history = new ArrayList<>();
    }


    // Constructor with a specified maximum history size
    public LocationHistory(int maxSize) {
        this.history = new ArrayList<>();
        this.maxSize = maxSize;
    }


    /**
     * Adds a new location record to the history.
     * If maxSize is set and exceeded, removes the oldest record.
     */
    public void addRecord(LocationData record) {
        history.add(record);
        if (maxSize > 0 && history.size() > maxSize) {
            history.remove(0);
        }
    }


    /**
     * Retrieves a location record at the specified index.
     * Returns null if the index is out of bounds.
     */
    public LocationData getRecord(int index) {
        if (index >= 0 && index < history.size()) {
            return history.get(index);
        }
        return null;
    }


    /**
     * Returns an unmodifiable list of all location records.
     */
    public List<LocationData> getAllRecords() {
        return Collections.unmodifiableList(history);
    }


    /**
     * Clears all history records and removes the polyline from the map if present.
     */
    public void clearHistory() {
        history.clear();
        if (polyline != null) {
            polyline.remove(); // now correct
            polyline = null;
        }
    }


    /**
     * Returns the number of records in history.
     */
    public int size() {
        return history.size();
    }



    /**
     * Checks if the history is empty.
     */
    public boolean isEmpty() {
        return history.isEmpty();
    }


    /**
     * Sets the maximum number of records to keep.
     * If current size exceeds the new limit, removes oldest records.
     */
    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
        while (maxSize > 0 && history.size() > maxSize) {
            history.remove(0);
        }
    }

    public int getMaxSize() {
        return maxSize;
    }



    /**
     * Draws the location history as a connected polyline on the given Google Map with custom color.
     */
    public void drawOnMap(GoogleMap map, int color) {
        if (history.isEmpty()) return;

        List<LatLng> points = new ArrayList<>();
        List<LocationData> safeCopy = new ArrayList<>(history);
        for (LocationData record : safeCopy) {
            if (record != null && record.getLocation() != null) {
                points.add(record.getLocation());
            }
        }

        // avoid adding an empty polyline
        if (points.size() < 2) return;

        if (polyline != null) {
            polyline.remove();
        }

        PolylineOptions polylineOptions = new PolylineOptions()
                .addAll(points)
                .width(5)
                .color(color);

        polyline = map.addPolyline(polylineOptions);
    }


    /**
     * Removes the polyline from the provided map.
     */
    public void remove(GoogleMap gMap) {
        if (polyline != null) {
            polyline.remove();
        }

    }
}