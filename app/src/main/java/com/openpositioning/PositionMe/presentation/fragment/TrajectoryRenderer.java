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
    private static final int RAW_PDR_COLOR = Color.argb(170, 30, 136, 229);
    private static final int WIFI_COLOR = Color.argb(190, 46, 125, 50);
    private static final int GNSS_COLOR = Color.argb(200, 255, 167, 38);
    private static final float RAW_PDR_WIDTH_PX = 5f;
    private static final float WIFI_WIDTH_PX = 6f;
    private static final float GNSS_WIDTH_PX = 6f;
    private static final int MAX_ABSOLUTE_HISTORY_POINTS = 20;

    @Nullable
    private GoogleMap map;
    @Nullable
    private Marker orientationMarker;
    @Nullable
    private Marker gnssMarker;
    @Nullable
    private Marker wifiMarker;
    @Nullable
    private Polyline trajectoryOutline;
    @Nullable
    private Polyline trajectoryMain;
    @Nullable
    private Polyline rawPdrPolyline;
    @Nullable
    private Polyline gnssPolyline;
    @Nullable
    private Polyline wifiPolyline;
    @Nullable
    private LatLng lastGnssLocation;
    @Nullable
    private LatLng lastWifiLocation;

    private final List<Marker> testPointMarkers = new ArrayList<>();
    private boolean useRedTrajectory = true;
    private boolean showPdrObservations = true;
    private boolean showWifiObservations = true;
    private boolean showGnssObservations = true;

    void attachToMap(@NonNull GoogleMap googleMap) {
        map = googleMap;
        resetMapArtifacts();
    }

    void setShowPdrObservations(boolean show) {
        showPdrObservations = show;
        if (rawPdrPolyline != null) {
            rawPdrPolyline.setVisible(show);
        }
    }

    void setShowWifiObservations(boolean show) {
        showWifiObservations = show;
        applyWifiVisibility();
    }

    void setShowGnssObservations(boolean show) {
        showGnssObservations = show;
        applyGnssVisibility();
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

    void appendRawObservationPoint(@NonNull LatLng rawLocation) {
        if (rawPdrPolyline == null) {
            return;
        }

        List<LatLng> rawPoints = new ArrayList<>(rawPdrPolyline.getPoints());
        if (rawPoints.isEmpty() || !rawPoints.get(rawPoints.size() - 1).equals(rawLocation)) {
            rawPoints.add(rawLocation);
            rawPdrPolyline.setPoints(rawPoints);
        }
        rawPdrPolyline.setVisible(showPdrObservations);
    }

    void appendRawReplayPoint(@NonNull LatLng rawLocation) {
        appendRawObservationPoint(rawLocation);
    }

    void clearRawReplayPath() {
        clearRawObservationPath();
    }

    void clearRawObservationPath() {
        if (rawPdrPolyline != null) {
            rawPdrPolyline.setPoints(new ArrayList<>());
            rawPdrPolyline.setVisible(showPdrObservations);
        }
    }

    void updateWifiObservation(@Nullable LatLng wifiLocation) {
        if (map == null || wifiLocation == null) {
            return;
        }

        if (wifiMarker == null) {
            wifiMarker = map.addMarker(new MarkerOptions()
                    .position(wifiLocation)
                    .title("WiFi Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
        } else {
            wifiMarker.setPosition(wifiLocation);
        }

        if (lastWifiLocation == null || !lastWifiLocation.equals(wifiLocation)) {
            appendAbsoluteHistoryPoint(wifiPolyline, wifiLocation);
            lastWifiLocation = wifiLocation;
        }
        applyWifiVisibility();
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

    void updateGnss(@Nullable LatLng gnssLocation) {
        if (map == null || gnssLocation == null) {
            return;
        }
        if (gnssMarker == null) {
            gnssMarker = map.addMarker(new MarkerOptions()
                    .position(gnssLocation)
                    .title("GNSS Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)));
        } else {
            gnssMarker.setPosition(gnssLocation);
        }

        if (lastGnssLocation == null || !lastGnssLocation.equals(gnssLocation)) {
            appendAbsoluteHistoryPoint(gnssPolyline, gnssLocation);
            lastGnssLocation = gnssLocation;
        }
        applyGnssVisibility();
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
        removePolyline(rawPdrPolyline);
        rawPdrPolyline = null;
        removePolyline(gnssPolyline);
        gnssPolyline = null;
        removePolyline(wifiPolyline);
        wifiPolyline = null;

        removeMarker(orientationMarker);
        orientationMarker = null;
        removeMarker(gnssMarker);
        gnssMarker = null;
        removeMarker(wifiMarker);
        wifiMarker = null;
        lastGnssLocation = null;
        lastWifiLocation = null;

        for (Marker marker : testPointMarkers) {
            marker.remove();
        }
        testPointMarkers.clear();

        if (map == null) {
            return;
        }

        trajectoryOutline = map.addPolyline(buildTrajectoryOutlineOptions());
        trajectoryMain = map.addPolyline(buildTrajectoryMainOptions());
        rawPdrPolyline = map.addPolyline(buildRawPdrPolylineOptions());
        gnssPolyline = map.addPolyline(new PolylineOptions()
                .color(GNSS_COLOR)
                .width(GNSS_WIDTH_PX)
                .zIndex(TRAJECTORY_Z_INDEX - 16f)
                .startCap(new RoundCap())
                .endCap(new RoundCap())
                .jointType(JointType.ROUND)
                .add());
        wifiPolyline = map.addPolyline(new PolylineOptions()
                .color(WIFI_COLOR)
                .width(WIFI_WIDTH_PX)
                .zIndex(TRAJECTORY_Z_INDEX - 17f)
                .startCap(new RoundCap())
                .endCap(new RoundCap())
                .jointType(JointType.ROUND)
                .add());
        if (rawPdrPolyline != null) {
            rawPdrPolyline.setVisible(showPdrObservations);
        }
        applyWifiVisibility();
        applyGnssVisibility();
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
    private PolylineOptions buildRawPdrPolylineOptions() {
        return new PolylineOptions()
                .color(RAW_PDR_COLOR)
                .width(RAW_PDR_WIDTH_PX)
                .jointType(JointType.ROUND)
                .startCap(new RoundCap())
                .endCap(new RoundCap())
                .zIndex(TRAJECTORY_Z_INDEX - 18f)
                .add();
    }


    private void appendAbsoluteHistoryPoint(@Nullable Polyline polyline, @NonNull LatLng point) {
        if (polyline == null) {
            return;
        }
        List<LatLng> points = new ArrayList<>(polyline.getPoints());
        if (points.isEmpty() || !points.get(points.size() - 1).equals(point)) {
            points.add(point);
        }
        if (points.size() > MAX_ABSOLUTE_HISTORY_POINTS) {
            points = new ArrayList<>(points.subList(points.size() - MAX_ABSOLUTE_HISTORY_POINTS, points.size()));
        }
        polyline.setPoints(points);
    }

    private void applyWifiVisibility() {
        if (wifiPolyline != null) {
            wifiPolyline.setVisible(showWifiObservations);
        }
        if (wifiMarker != null) {
            wifiMarker.setVisible(showWifiObservations && lastWifiLocation != null);
        }
    }

    private void applyGnssVisibility() {
        if (gnssPolyline != null) {
            gnssPolyline.setVisible(showGnssObservations);
        }
        if (gnssMarker != null) {
            gnssMarker.setVisible(showGnssObservations && lastGnssLocation != null);
        }
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
