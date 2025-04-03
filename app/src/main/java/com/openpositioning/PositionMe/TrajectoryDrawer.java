package com.openpositioning.PositionMe;


import android.graphics.Color;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * A utility class responsible for drawing and managing a real-time movement trajectory
 * on a {@link GoogleMap}. The trajectory is rendered as a polyline that updates with each
 * new location point.
 *
 * @see GoogleMap the map surface used to display the polyline.
 * @see LatLng the type of points added to the trajectory.
 * @see Polyline the visual object representing the line.
 *
 * Created to support real-time visual tracking in the {@link com.openpositioning.PositionMe.fragments.DataDisplay} fragment.
 */
public class TrajectoryDrawer {

    // Google Map instance used for drawing
    private final GoogleMap map;

    // List of all recorded LatLng points forming the trajectory
    private final List<LatLng> trajectoryPoints = new ArrayList<>();

    // Current polyline object displayed on the map
    private Polyline trajectoryLine;


    /**
     * Constructs a {@link TrajectoryDrawer} associated with a given map instance.
     *
     * @param map the Google Map on which the trajectory will be drawn.
     */
    public TrajectoryDrawer(GoogleMap map) {
        this.map = map;
    }

    /**
     * Adds a new {@link LatLng} point to the trajectory.
     * This method also triggers a redraw of the entire trajectory line.
     *
     * @param point the new point to be added to the trajectory.
     */
    public void addPoint(LatLng point) {
        trajectoryPoints.add(point);
        redrawLine();
    }

    /**
     * Removes any existing polyline and redraws the full trajectory line
     * using the accumulated points. This method ensures the map reflects
     * the current state of the trajectory list.
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
     * Clears the trajectory data both visually from the map and internally
     * from memory. Use this to reset the path or start a new tracking session.
     */
    public void clear() {
        trajectoryPoints.clear();
        if (trajectoryLine != null) {
            trajectoryLine.remove();
            trajectoryLine = null;
        }
    }
}

