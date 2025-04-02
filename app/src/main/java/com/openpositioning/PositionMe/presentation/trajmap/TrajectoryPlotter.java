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

    // Made protected in case subclasses need to ensure polyline exists.
    protected void initPolyline() {
        polyline = map.addPolyline(new PolylineOptions()
                .color(polylineColor)
                .zIndex(99) // highest layer to ensure visibility
                .width(5f));
    }

    /**
     * Update the plotted location with a new LatLng and orientation.
     */
    public void updateLocation(LatLng newLocation, float orientation) {
        // Create the polyline if it does not exist.
        if (polyline == null) {
            initPolyline();
        }
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

    // --- New GNSS Trajectory Plotter subclass ---
    public static class GnssTrajectoryPlotter extends TrajectoryPlotter {
        private Circle accuracyCircle;

        /**
         * We pass 0 as markerDrawableRes since we override marker creation.
         */
        public GnssTrajectoryPlotter(Context context, GoogleMap map) {
            super(context, map, Color.BLUE, 0);
        }

        @Override
        protected String getTitle() {
            return "GNSS Position";
        }

        /**
         * Update the GNSS location with an accuracy value.
         *
         * @param newLocation The new GNSS location.
         * @param accuracy    The accuracy (in meters) from GNSS.
         */
        public void updateGnssLocation(LatLng newLocation, float accuracy) {
            // Create the polyline if it does not exist.
            if (polyline == null) {
                initPolyline();
            }
            if (marker == null) {
                marker = map.addMarker(new MarkerOptions()
                        .position(newLocation)
                        .title(getTitle())
                        // Use default marker with azure hue for GNSS
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                accuracyCircle = map.addCircle(new CircleOptions()
                        .center(newLocation)
                        .radius(accuracy)
                        .strokeColor(Color.BLUE)
                        .fillColor(Color.argb(50, 0, 0, 255))
                        .strokeWidth(2f));
            } else {
                marker.setPosition(newLocation);
                if (accuracyCircle != null) {
                    accuracyCircle.setCenter(newLocation);
                    accuracyCircle.setRadius(accuracy);
                }
            }

            if (points.isEmpty() || !points.get(points.size() - 1).equals(newLocation)) {
                points.add(newLocation);
                polyline.setPoints(points);
            }
        }

        /**
         * To satisfy the abstract method, we override updateLocation.
         * In this case, we call updateGnssLocation with a default accuracy (0).
         * In practice, use updateGnssLocation(newLocation, accuracy) instead.
         */
        @Override
        public void updateLocation(LatLng newLocation, float orientation) {
            updateGnssLocation(newLocation, 0);
        }

        @Override
        public void clear() {
            super.clear();
            if (accuracyCircle != null) {
                accuracyCircle.remove();
                accuracyCircle = null;
            }
        }
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
            // Create the polyline if it does not exist.
            if (polyline == null) {
                initPolyline();
            }
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
            }
        }
    }

}





