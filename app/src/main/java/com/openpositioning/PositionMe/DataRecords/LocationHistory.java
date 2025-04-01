package com.openpositioning.PositionMe.DataRecords;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LocationHistory {
    private final List<LocationData> history;
    private Polyline polyline; // this is the drawable line

    private int maxSize = -1; // -1 means unlimited

    public LocationHistory() {
        this.history = new ArrayList<>();
    }

    public LocationHistory(int maxSize) {
        this.history = new ArrayList<>();
        this.maxSize = maxSize;
    }

    public void addRecord(LocationData record) {
        history.add(record);
        if (maxSize > 0 && history.size() > maxSize) {
            history.remove(0);
        }
    }

    public LocationData getRecord(int index) {
        if (index >= 0 && index < history.size()) {
            return history.get(index);
        }
        return null;
    }

    public List<LocationData> getAllRecords() {
        return Collections.unmodifiableList(history);
    }

    public void clearHistory() {
        history.clear();
        if (polyline != null) {
            polyline.remove(); // now correct
            polyline = null;
        }
    }

    public int size() {
        return history.size();
    }

    public boolean isEmpty() {
        return history.isEmpty();
    }

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
        for (LocationData record : history) {
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

    public void remove(GoogleMap gMap) {
        if (polyline != null) {
            polyline.remove();
        }

    }
}