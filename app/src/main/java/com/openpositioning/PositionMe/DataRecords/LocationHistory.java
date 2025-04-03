package com.openpositioning.PositionMe.DataRecords;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/**
 * A class responsible for storing and managing a history of {@link LocationData} entries.
 * It optionally supports drawing the historical trajectory on a {@link GoogleMap} as a polyline.
 *
 * This is primarily used for visualizing the motion path of different positioning sources
 * (e.g., WiFi, GNSS, PDR) over time.
 *
 * @see LocationData for a single location entry.
 * @see GoogleMap for map visualization.
 */
public class LocationHistory {
    /** Internal list to store recorded locations */
    private final List<LocationData> history;

    /** Currently drawn polyline on the map, representing the trajectory */
    private Polyline polyline; // this is the drawable line


    /** Maximum number of records to store (-1 = unlimited) */
    private int maxSize = -1; // -1 means unlimited


    /**
     * Constructs a {@link LocationHistory} object with unlimited history capacity.
     */
    public LocationHistory() {
        this.history = new ArrayList<>();
    }


    /**
     * Constructs a {@link LocationHistory} with a specified maximum number of entries.
     *
     * @param maxSize maximum number of records to retain (-1 for unlimited)
     */
    public LocationHistory(int maxSize) {
        this.history = new ArrayList<>();
        this.maxSize = maxSize;
    }


    /**
     * Adds a new {@link LocationData} record to the history.
     * If the history exceeds the max size, the oldest entry is removed.
     *
     * @param record the location data to add
     */
    public void addRecord(LocationData record) {
        history.add(record);
        if (maxSize > 0 && history.size() > maxSize) {
            history.remove(0);
        }
    }


    /**
     * Retrieves the {@link LocationData} at a specific index.
     *
     * @param index the index to retrieve
     * @return the location data, or null if index is invalid
     */
    public LocationData getRecord(int index) {
        if (index >= 0 && index < history.size()) {
            return history.get(index);
        }
        return null;
    }


    /**
     * Returns an unmodifiable list of all stored location records.
     *
     * @return a read-only list of {@link LocationData}
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
     * Sets the maximum number of records to retain in history.
     * If the new limit is smaller than the current size, oldest entries are removed.
     *
     * @param maxSize maximum number of entries (-1 for unlimited)
     */
    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
        while (maxSize > 0 && history.size() > maxSize) {
            history.remove(0);
        }
    }


    /**
     * Gets the currently configured maximum history size.
     *
     * @return max number of records allowed (-1 for unlimited)
     */
    public int getMaxSize() {
        return maxSize;
    }



    /**
     * Draws the recorded history as a polyline on the provided {@link GoogleMap}.
     * Points with null locations are skipped. Requires at least 2 valid points.
     *
     * @param map the map on which to draw
     * @param color the color of the polyline
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
     * Removes the currently drawn polyline from the provided map, if present.
     *
     * @param gMap the map to remove the polyline from
     */
    public void remove(GoogleMap gMap) {
        if (polyline != null) {
            polyline.remove();
        }

    }
}