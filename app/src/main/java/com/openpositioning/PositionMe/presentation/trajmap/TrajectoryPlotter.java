package com.openpositioning.PositionMe.presentation.trajmap;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.*;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.openpositioning.PositionMe.R;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class that encapsulates plotting of a trajectory on a GoogleMap.
 * It handles creation/updating of a marker and polyline.
 */
public abstract class TrajectoryPlotter {
    // static log id
    private static final String TAG = "TrajectoryPlotter";
    protected GoogleMap map;
    protected Context context;
    protected Marker marker;
    protected Polyline polyline;
    protected List<LatLng> points;
    protected int polylineColor;
    protected int markerDrawableRes;

    public TrajectoryPlotter(Context context, GoogleMap map, int polylineColor, int markerDrawableRes) {
        this.context = context;
        this.map = map;
        this.polylineColor = polylineColor;
        this.markerDrawableRes = markerDrawableRes;
        this.points = new ArrayList<>();
        initPolyline();
    }

    private void initPolyline() {
        polyline = map.addPolyline(new PolylineOptions()
                .color(polylineColor)
                .width(5f));
    }

    /**
     * Update the plotted location with a new LatLng and orientation.
     */
    public void updateLocation(LatLng newLocation, float orientation) {
        // Create the marker if it does not exist.
        if (marker == null) {
            // Move the camera to the new location
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 18));
            Log.d(TAG, "Pin Init - Moved camera to: " + newLocation);
            marker = map.addMarker(new MarkerOptions()
                    .position(newLocation)
                    .flat(true)
                    .title(getTitle())
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(context, markerDrawableRes))));
        } else {
            marker.setPosition(newLocation);
            marker.setRotation(orientation);
        }

        // Append new point if the position changed.
        if (points.isEmpty() || !points.get(points.size() - 1).equals(newLocation)) {
            points.add(newLocation);
            polyline.setPoints(points);
        }
    }

    /**
     * Set the visibility of both the marker and polyline.
     */
    public void setVisible(boolean visible) {
        if (polyline != null) polyline.setVisible(visible);
        if (marker != null) marker.setVisible(visible);
    }

    /**
     * Clear all plotted data.
     */
    public void clear() {
        if (polyline != null) {
            polyline.remove();
            polyline = null;
        }
        if (marker != null) {
            marker.remove();
            marker = null;
        }
        points.clear();
    }

    /**
     * Provide a title for the marker.
     */
    protected abstract String getTitle();

    public Polyline getPolyline() {
        return polyline;
    }


    public static class RawTrajectoryPlotter extends TrajectoryPlotter {
        public RawTrajectoryPlotter(Context context, GoogleMap map) {
            super(context, map, Color.RED, R.drawable.ic_baseline_navigation_24);
        }

        @Override protected String getTitle() {
            return "Raw Position";
        }
    }

    public static class FusionTrajectoryPlotter extends TrajectoryPlotter {
        public FusionTrajectoryPlotter(Context context, GoogleMap map) {
            super(context, map, Color.GREEN, R.drawable.ic_baseline_fused_location_target_svgrepo_com);
        }

        @Override protected String getTitle() {
            return "Fusion Position";
        }

        @Override
        public void updateLocation(LatLng newLocation, float orientation) {
            // Create the marker if it does not exist.
            if (marker == null) {
                marker = map.addMarker(new MarkerOptions()
                        .position(newLocation)
                        .flat(true)
                        .title(getTitle())
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(context, markerDrawableRes))));
            } else {
                marker.setPosition(newLocation);
//                marker.setRotation(orientation);
            }

            // Append new point if the position changed.
            if (points.isEmpty() || !points.get(points.size() - 1).equals(newLocation)) {
                points.add(newLocation);
                polyline.setPoints(points);
            }
        }
    }


    public static class WifiTrajectoryPlotter extends TrajectoryPlotter {
        public WifiTrajectoryPlotter(Context context, GoogleMap map) {
            super(context, map, Color.GREEN, R.drawable.ic_baseline_wifi_position_round_1034_svgrepo_com);
        }

        @Override protected String getTitle() {
            return "WIFI Position";
        }

        /**
         * Update the plotted location with a new LatLng and orientation.
         */
        @Override
        public void updateLocation(LatLng newLocation, float orientation) {
            // Create the marker if it does not exist.
            if (marker == null) {
                marker = map.addMarker(new MarkerOptions()
                        .position(newLocation)
                        .flat(true)
                        .title(getTitle())
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(context, markerDrawableRes))));
            } else {
                marker.setPosition(newLocation);
                marker.setRotation(orientation);
            }
        }
    }

}





