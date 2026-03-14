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
 * Centralises all trajectory-related rendering for {@link TrajectoryMapFragment}.
 *
 * <p>This class is intentionally UI-focused. It owns:
 * <ul>
 *     <li>the current-position marker</li>
 *     <li>the matched trajectory polyline</li>
 *     <li>the raw PDR observation polyline</li>
 *     <li>the WiFi and GNSS markers / polylines</li>
 *     <li>test-point markers</li>
 * </ul>
 *
 * <p>It does not perform map matching or floor reasoning. It only draws the
 * outputs produced elsewhere.
 */
class TrajectoryRenderer {

    // Matched trajectory styling
    private static final int TRAJECTORY_MAIN_COLOR = Color.RED;
    private static final int TRAJECTORY_OUTLINE_COLOR = Color.WHITE;
    private static final int TRAJECTORY_BLACK_OUTLINE_COLOR = Color.argb(210, 235, 235, 235);
    private static final float TRAJECTORY_WIDTH_MAIN_PX = 7f;
    private static final float TRAJECTORY_WIDTH_OUTLINE_PX = 18f;
    private static final float TRAJECTORY_Z_INDEX = 1000f;

    // Observation layer styling
    private static final int RAW_PDR_COLOR = Color.argb(170, 30, 136, 229);
    private static final int WIFI_COLOR = Color.argb(190, 46, 125, 50);
    private static final int GNSS_COLOR = Color.argb(200, 255, 167, 38);
    private static final float RAW_PDR_WIDTH_PX = 5f;
    private static final float WIFI_WIDTH_PX = 6f;
    private static final float GNSS_WIDTH_PX = 6f;

    /**
     * GNSS / WiFi traces are intentionally bounded so that overlays remain readable
     * and do not grow indefinitely during long sessions.
     */
    private static final int MAX_ABSOLUTE_HISTORY_POINTS = 20;

    @Nullable
    private GoogleMap map;

    // Main current-position marker
    @Nullable
    private Marker orientationMarker;

    // Absolute observation markers
    @Nullable
    private Marker gnssMarker;
    @Nullable
    private Marker wifiMarker;

    // Matched trajectory polylines (outline + main stroke)
    @Nullable
    private Polyline trajectoryOutline;
    @Nullable
    private Polyline trajectoryMain;

    // Raw / observation polylines
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

    /**
     * Binds the renderer to a map instance and recreates all map-owned drawing objects.
     */
    void attachToMap(@NonNull GoogleMap googleMap) {
        map = googleMap;
        resetMapArtifacts();
    }

    /**
     * Show or hide the raw PDR observation path.
     */
    void setShowPdrObservations(boolean show) {
        showPdrObservations = show;
        if (rawPdrPolyline != null) {
            rawPdrPolyline.setVisible(show);
        }
    }

    /**
     * Show or hide WiFi observation marker and path.
     */
    void setShowWifiObservations(boolean show) {
        showWifiObservations = show;
        applyWifiVisibility();
    }

    /**
     * Show or hide GNSS observation marker and path.
     */
    void setShowGnssObservations(boolean show) {
        showGnssObservations = show;
        applyGnssVisibility();
    }

    /**
     * Updates the current matched position marker.
     *
     * @param context           Fragment/activity context used for vector icon conversion
     * @param matchedLocation   current matched trajectory location
     * @param orientation       heading in degrees
     * @param shouldFollowCamera whether the camera should follow this marker
     * @param initialZoom       zoom level used when creating the marker for the first time
     */
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
                    .rotation(orientation)
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(
                                    context,
                                    R.drawable.ic_baseline_navigation_24
                            ))));

            map.moveCamera(CameraUpdateFactory.newLatLngZoom(matchedLocation, initialZoom));
            return;
        }

        orientationMarker.setPosition(matchedLocation);
        orientationMarker.setRotation(orientation);

        if (shouldFollowCamera) {
            map.animateCamera(CameraUpdateFactory.newLatLng(matchedLocation));
        }
    }

    /**
     * Appends a matched trajectory point to the displayed main trajectory.
     */
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

    /**
     * Appends one raw PDR observation point.
     */
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

    /**
     * Replay uses the same raw-path rendering as live raw PDR observations.
     */
    void appendRawReplayPoint(@NonNull LatLng rawLocation) {
        appendRawObservationPoint(rawLocation);
    }

    /**
     * Clears only the replay raw path.
     */
    void clearRawReplayPath() {
        clearRawObservationPath();
    }

    /**
     * Clears the raw PDR observation polyline while keeping the object alive.
     */
    void clearRawObservationPath() {
        if (rawPdrPolyline != null) {
            rawPdrPolyline.setPoints(new ArrayList<>());
            rawPdrPolyline.setVisible(showPdrObservations);
        }
    }

    /**
     * Updates the WiFi observation marker and its recent-history path.
     */
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

    /**
     * Compatibility wrapper used by {@link TrajectoryMapFragment}.
     */
    void updateWifi(@Nullable LatLng wifiLocation) {
        updateWifiObservation(wifiLocation);
    }

    /**
     * Adds a numbered test-point marker to the map.
     */
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

    /**
     * Updates the GNSS marker and recent-history path.
     */
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

    /**
     * Removes only the GNSS marker, leaving the GNSS polyline untouched.
     */
    void clearGnssMarker() {
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
    }

    /**
     * Clears the GNSS marker and resets the GNSS path.
     */
    void clearGnss() {
        clearGnssMarker();
        lastGnssLocation = null;

        if (gnssPolyline != null) {
            gnssPolyline.setPoints(new ArrayList<>());
            gnssPolyline.setVisible(showGnssObservations);
        }
    }

    /**
     * Clears the WiFi marker and resets the WiFi path.
     */
    void clearWifi() {
        if (wifiMarker != null) {
            wifiMarker.remove();
            wifiMarker = null;
        }
        lastWifiLocation = null;

        if (wifiPolyline != null) {
            wifiPolyline.setPoints(new ArrayList<>());
            wifiPolyline.setVisible(showWifiObservations);
        }
    }

    /**
     * Clears only the matched trajectory, keeping raw / GNSS / WiFi observations intact.
     */
    void clearMatchedTrajectoryOnly() {
        if (trajectoryOutline != null) {
            trajectoryOutline.setPoints(new ArrayList<>());
        }
        if (trajectoryMain != null) {
            trajectoryMain.setPoints(new ArrayList<>());
        }
        ensureTrajectoryStyling();
    }

    /**
     * Clears all rendered map state managed by this renderer, but keeps the map attachment alive.
     *
     * <p>This is useful when resetting the recording / replay session.
     */
    void clearAll() {
        clearMatchedTrajectoryOnly();
        clearRawObservationPath();
        clearGnss();
        clearWifi();

        if (orientationMarker != null) {
            orientationMarker.remove();
            orientationMarker = null;
        }

        for (Marker marker : testPointMarkers) {
            marker.remove();
        }
        testPointMarkers.clear();
    }

    /**
     * Toggles the matched trajectory between red and black styling.
     */
    void toggleTrajectoryColor() {
        useRedTrajectory = !useRedTrajectory;
        applyTrajectoryColor();
    }

    /**
     * Returns whether the main matched trajectory is currently using the red theme.
     */
    boolean isUsingRedTrajectory() {
        return useRedTrajectory;
    }

    /**
     * Returns the current orientation marker position if available.
     */
    @Nullable
    LatLng getOrientationPosition() {
        return orientationMarker != null ? orientationMarker.getPosition() : null;
    }

    /**
     * Recreates every renderer-owned map artifact.
     *
     * <p>Call this after attaching to a new map, or after a hard reset.
     */
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

    /**
     * Re-applies styling to the matched trajectory pair.
     */
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

    /**
     * Applies the currently selected matched-trajectory color theme.
     */
    private void applyTrajectoryColor() {
        if (trajectoryMain != null) {
            trajectoryMain.setColor(useRedTrajectory ? TRAJECTORY_MAIN_COLOR : Color.BLACK);
        }
        if (trajectoryOutline != null) {
            trajectoryOutline.setColor(
                    useRedTrajectory ? TRAJECTORY_OUTLINE_COLOR : TRAJECTORY_BLACK_OUTLINE_COLOR
            );
        }
    }

    /**
     * Keeps the outline and main matched polylines perfectly synchronised.
     */
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

    /**
     * Appends a point to a bounded-history absolute observation line.
     */
    private void appendAbsoluteHistoryPoint(@Nullable Polyline polyline, @NonNull LatLng point) {
        if (polyline == null) {
            return;
        }

        List<LatLng> points = new ArrayList<>(polyline.getPoints());
        if (points.isEmpty() || !points.get(points.size() - 1).equals(point)) {
            points.add(point);
        }

        if (points.size() > MAX_ABSOLUTE_HISTORY_POINTS) {
            points = new ArrayList<>(
                    points.subList(points.size() - MAX_ABSOLUTE_HISTORY_POINTS, points.size())
            );
        }

        polyline.setPoints(points);
    }

    /**
     * Applies WiFi overlay visibility to both line and marker.
     */
    private void applyWifiVisibility() {
        if (wifiPolyline != null) {
            wifiPolyline.setVisible(showWifiObservations);
        }
        if (wifiMarker != null) {
            wifiMarker.setVisible(showWifiObservations && lastWifiLocation != null);
        }
    }

    /**
     * Applies GNSS overlay visibility to both line and marker.
     */
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