package com.openpositioning.PositionMe;


import android.graphics.Color;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to draw and manage a movement trajectory on Google Map.
 */
public class TrajectoryDrawer {

    private final GoogleMap map;
    private final List<LatLng> trajectoryPoints = new ArrayList<>();
    private Polyline trajectoryLine;

    public TrajectoryDrawer(GoogleMap map) {
        this.map = map;
    }

    /**
     * Add a new point to the trajectory and update the line.
     */
    public void addPoint(LatLng point) {
        trajectoryPoints.add(point);
        redrawLine();
    }

    /**
     * Redraw the entire trajectory line on the map.
     */
    private void redrawLine() {
        if (trajectoryLine != null) {
            trajectoryLine.remove(); // Remove old line
        }

        PolylineOptions polylineOptions = new PolylineOptions()
                .addAll(trajectoryPoints)
                .width(5f)
                .color(Color.BLUE)
                .geodesic(true);

        trajectoryLine = map.addPolyline(polylineOptions);
    }

    /**
     * Clear the current trajectory from map and memory.
     */
    public void clear() {
        trajectoryPoints.clear();
        if (trajectoryLine != null) {
            trajectoryLine.remove();
            trajectoryLine = null;
        }
    }
}

