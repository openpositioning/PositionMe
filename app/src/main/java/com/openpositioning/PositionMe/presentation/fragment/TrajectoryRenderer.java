package com.openpositioning.PositionMe.presentation.fragment;

import android.content.Context;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.JointType;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.RoundCap;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps trajectory rendering state out of TrajectoryMapFragment.
 * This class is intentionally UI-focused: markers, polylines, colors and camera-follow behavior.
 */
class TrajectoryRenderer {

    private static final int TRAJECTORY_MAIN_COLOR = Color.RED;
    private static final int TRAJECTORY_OUTLINE_COLOR = Color.WHITE;
    private static final float TRAJECTORY_WIDTH_MAIN_PX = 7f;
    private static final float TRAJECTORY_WIDTH_OUTLINE_PX = 18f;
    private static final float TRAJECTORY_Z_INDEX = 1000f;
    private static final int TRAJECTORY_BLACK_OUTLINE_COLOR = Color.argb(210, 235, 235, 235);

    @Nullable
    private GoogleMap map;
    @Nullable
    private Marker orientationMarker;
    @Nullable
    private Marker gnssMarker;
    @Nullable
    private Polyline trajectoryOutline;
    @Nullable
    private Polyline trajectoryMain;
    @Nullable
    private Polyline rawReplayPolyline;
    @Nullable
    private Polyline gnssPolyline;
    @Nullable
    private LatLng lastGnssLocation;

    private final List<Marker> testPointMarkers = new ArrayList<>();
    private boolean useRedTrajectory = true;

    void attachToMap(@NonNull GoogleMap googleMap) {
        map = googleMap;
        resetMapArtifacts();
    }

    void updateCurrentPosition(@NonNull Context context,
                               @NonNull LatLng matchedLocation,
                               float orientation,
                               boolean shouldFollowCamera,
                               float initialZoom) {
        if (map == null) {
            return;
        }

        if (orientationMarker == null) {
            orientationMarker = map.addMarker(new MarkerOptions()
                    .position(matchedLocation)
                    .flat(true)
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(context, R.drawable.ic_baseline_navigation_24))));
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(matchedLocation, initialZoom));
            return;
        }

        orientationMarker.setPosition(matchedLocation);
        orientationMarker.setRotation(orientation);
        if (shouldFollowCamera) {
            map.animateCamera(CameraUpdateFactory.newLatLng(matchedLocation));
        }
    }

    void appendMatchedLocation(@Nullable LatLng oldLocation, @NonNull LatLng matchedLocation) {
        if (trajectoryMain == null) {
            return;
        }
        List<LatLng> points = new ArrayList<>(trajectoryMain.getPoints());
        if (oldLocation == null || !oldLocation.equals(matchedLocation)) {
            points.add(matchedLocation);
            syncTrajectoryPolylinePoints(points);
        } else {
            ensureTrajectoryStyling();
        }
    }

    void appendRawReplayPoint(@NonNull LatLng rawLocation) {
        if (rawReplayPolyline == null) {
            return;
        }

        List<LatLng> rawPoints = new ArrayList<>(rawReplayPolyline.getPoints());
        if (rawPoints.isEmpty() || !rawPoints.get(rawPoints.size() - 1).equals(rawLocation)) {
            rawPoints.add(rawLocation);
            rawReplayPolyline.setPoints(rawPoints);
        }
    }

    void clearRawReplayPath() {
        if (rawReplayPolyline != null) {
            rawReplayPolyline.setPoints(new ArrayList<>());
        }
    }

    void addTestPointMarker(int index, long timestampMs, @NonNull LatLng position) {
        if (map == null) {
            return;
        }
        Marker marker = map.addMarker(new MarkerOptions()
                .position(position)
                .title("TP " + index)
                .snippet("t=" + timestampMs));
        if (marker != null) {
            marker.showInfoWindow();
            testPointMarkers.add(marker);
        }
    }

    void updateGnss(@NonNull LatLng gnssLocation, boolean gnssEnabled) {
        if (map == null || !gnssEnabled) {
            return;
        }
        if (gnssMarker == null) {
            gnssMarker = map.addMarker(new MarkerOptions()
                    .position(gnssLocation)
                    .title("GNSS Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
            lastGnssLocation = gnssLocation;
            return;
        }

        gnssMarker.setPosition(gnssLocation);
        if (lastGnssLocation != null && !lastGnssLocation.equals(gnssLocation) && gnssPolyline != null) {
            List<LatLng> gnssPoints = new ArrayList<>(gnssPolyline.getPoints());
            gnssPoints.add(gnssLocation);
            gnssPolyline.setPoints(gnssPoints);
        }
        lastGnssLocation = gnssLocation;
    }

    void clearGnssMarker() {
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
    }

    void toggleTrajectoryColor() {
        useRedTrajectory = !useRedTrajectory;
        applyTrajectoryColor();
    }

    boolean isUsingRedTrajectory() {
        return useRedTrajectory;
    }

    @Nullable
    LatLng getOrientationPosition() {
        return orientationMarker != null ? orientationMarker.getPosition() : null;
    }

    void resetMapArtifacts() {
        removePolyline(trajectoryOutline);
        trajectoryOutline = null;
        removePolyline(trajectoryMain);
        trajectoryMain = null;
        removePolyline(rawReplayPolyline);
        rawReplayPolyline = null;
        removePolyline(gnssPolyline);
        gnssPolyline = null;

        removeMarker(orientationMarker);
        orientationMarker = null;
        removeMarker(gnssMarker);
        gnssMarker = null;
        lastGnssLocation = null;

        for (Marker marker : testPointMarkers) {
            marker.remove();
        }
        testPointMarkers.clear();

        if (map == null) {
            return;
        }

        trajectoryOutline = map.addPolyline(buildTrajectoryOutlineOptions());
        trajectoryMain = map.addPolyline(buildTrajectoryMainOptions());
        rawReplayPolyline = map.addPolyline(buildRawReplayPolylineOptions());
        gnssPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .width(5f)
                .zIndex(TRAJECTORY_Z_INDEX - 19f)
                .add());
        ensureTrajectoryStyling();
        applyTrajectoryColor();
    }

    private void ensureTrajectoryStyling() {
        if (trajectoryOutline != null) {
            trajectoryOutline.setWidth(TRAJECTORY_WIDTH_OUTLINE_PX);
            trajectoryOutline.setZIndex(TRAJECTORY_Z_INDEX - 19f);
            trajectoryOutline.setStartCap(new RoundCap());
            trajectoryOutline.setEndCap(new RoundCap());
            trajectoryOutline.setJointType(JointType.ROUND);
        }
        if (trajectoryMain != null) {
            trajectoryMain.setWidth(TRAJECTORY_WIDTH_MAIN_PX);
            trajectoryMain.setZIndex(TRAJECTORY_Z_INDEX);
            trajectoryMain.setStartCap(new RoundCap());
            trajectoryMain.setEndCap(new RoundCap());
            trajectoryMain.setJointType(JointType.ROUND);
        }
        applyTrajectoryColor();
    }

    private void applyTrajectoryColor() {
        if (trajectoryMain != null) {
            trajectoryMain.setColor(useRedTrajectory ? TRAJECTORY_MAIN_COLOR : Color.BLACK);
        }
        if (trajectoryOutline != null) {
            trajectoryOutline.setColor(useRedTrajectory ? TRAJECTORY_OUTLINE_COLOR : TRAJECTORY_BLACK_OUTLINE_COLOR);
        }
    }

    private void syncTrajectoryPolylinePoints(@NonNull List<LatLng> points) {
        if (trajectoryOutline != null) {
            trajectoryOutline.setPoints(points);
        }
        if (trajectoryMain != null) {
            trajectoryMain.setPoints(points);
        }
        ensureTrajectoryStyling();
    }

    @NonNull
    private PolylineOptions buildTrajectoryOutlineOptions() {
        return new PolylineOptions()
                .color(TRAJECTORY_OUTLINE_COLOR)
                .width(TRAJECTORY_WIDTH_OUTLINE_PX)
                .startCap(new RoundCap())
                .endCap(new RoundCap())
                .jointType(JointType.ROUND)
                .zIndex(TRAJECTORY_Z_INDEX - 19f)
                .add();
    }

    @NonNull
    private PolylineOptions buildTrajectoryMainOptions() {
        return new PolylineOptions()
                .color(TRAJECTORY_MAIN_COLOR)
                .width(TRAJECTORY_WIDTH_MAIN_PX)
                .startCap(new RoundCap())
                .endCap(new RoundCap())
                .jointType(JointType.ROUND)
                .zIndex(TRAJECTORY_Z_INDEX)
                .add();
    }

    @NonNull
    private PolylineOptions buildRawReplayPolylineOptions() {
        return new PolylineOptions()
                .color(Color.argb(170, 30, 136, 229))
                .width(5f)
                .jointType(JointType.ROUND)
                .startCap(new RoundCap())
                .endCap(new RoundCap())
                .zIndex(TRAJECTORY_Z_INDEX - 19f)
                .add();
    }

    private static void removeMarker(@Nullable Marker marker) {
        if (marker != null) {
            marker.remove();
        }
    }

    private static void removePolyline(@Nullable Polyline polyline) {
        if (polyline != null) {
            polyline.remove();
        }
    }
}
