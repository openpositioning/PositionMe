package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared overlay helper for Start Location so its actual-map display matches the
 * calibrated bounds used in trajectory/replay as closely as possible.
 */
public final class StartLocationOverlayHelper {

    private static final String CALIBRATION_PREFS_NAME = "actual_map_calibration";

    private StartLocationOverlayHelper() {}

    public static List<GroundOverlay> addActualMapOverlays(@NonNull Context context,
                                                           @NonNull GoogleMap map,
                                                           @Nullable FloorplanApiClient.BuildingInfo selectedBuilding,
                                                           @Nullable List<FloorplanApiClient.BuildingInfo> availableBuildings,
                                                           @Nullable String requestedFloorDisplayName,
                                                           int requestedFloorIndex) {
        List<GroundOverlay> overlays = new ArrayList<>();
        if (selectedBuilding == null) {
            return overlays;
        }

        String selectedKey = resolveKnownBuildingKey(selectedBuilding, selectedBuilding.getName());
        addOverlayForBuilding(context, map, overlays, selectedBuilding, selectedKey,
                requestedFloorDisplayName, requestedFloorIndex);

        if (shouldShowLinkedLibraryAndNucleus(selectedKey)) {
            String linkedKey = "library".equals(selectedKey) ? "nucleus_building" : "library";
            FloorplanApiClient.BuildingInfo linkedBuilding = findBuildingByKnownKey(linkedKey, availableBuildings);
            int linkedFloorIndex = resolveFallbackFloorIndexForKey(linkedKey, requestedFloorDisplayName, requestedFloorIndex);
            addOverlayForBuilding(context, map, overlays, linkedBuilding, linkedKey,
                    requestedFloorDisplayName, linkedFloorIndex);
        }
        return overlays;
    }

    public static int getGroundFloorIndex(@Nullable FloorplanApiClient.BuildingInfo building) {
        if (building == null || building.getFloorShapesList() == null || building.getFloorShapesList().isEmpty()) {
            return 0;
        }
        for (int i = 0; i < building.getFloorShapesList().size(); i++) {
            String display = building.getFloorShapesList().get(i).getDisplayName();
            if ("G".equals(canonicalFloorLabel(display))) {
                return i;
            }
        }
        return Math.min(1, building.getFloorShapesList().size() - 1);
    }

    public static String getDisplayFloorLabel(@Nullable FloorplanApiClient.BuildingInfo building, int floorIndex) {
        if (building == null || building.getFloorShapesList() == null || building.getFloorShapesList().isEmpty()) {
            return "";
        }
        int safeIndex = Math.max(0, Math.min(floorIndex, building.getFloorShapesList().size() - 1));
        String display = building.getFloorShapesList().get(safeIndex).getDisplayName();
        String canonical = canonicalFloorLabel(display);
        if ("1".equals(canonical)) return "F1";
        if ("2".equals(canonical)) return "F2";
        if ("3".equals(canonical)) return "F3";
        if ("G".equals(canonical)) return "G";
        if ("LG".equals(canonical)) return "LG";
        return display == null ? "" : display.trim().toUpperCase();
    }

    public static int clampFloorIndex(@Nullable FloorplanApiClient.BuildingInfo building, int floorIndex) {
        if (building == null || building.getFloorShapesList() == null || building.getFloorShapesList().isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(floorIndex, building.getFloorShapesList().size() - 1));
    }

    private static void addOverlayForBuilding(@NonNull Context context,
                                              @NonNull GoogleMap map,
                                              @NonNull List<GroundOverlay> overlays,
                                              @Nullable FloorplanApiClient.BuildingInfo building,
                                              @Nullable String buildingKey,
                                              @Nullable String requestedFloorDisplayName,
                                              int requestedFloorIndex) {
        String normalizedBuildingKey = normalizeBuildingKey(buildingKey);
        String requestedCanonicalFloor = canonicalFloorLabel(requestedFloorDisplayName);
        if (normalizedBuildingKey.isEmpty()) {
            return;
        }
        if ("library".equals(normalizedBuildingKey) && "LG".equals(requestedCanonicalFloor)) {
            return;
        }

        int resolvedFloorIndex = building != null
                ? findMatchingFloorIndex(building, requestedFloorDisplayName, requestedFloorIndex)
                : resolveFallbackFloorIndexForKey(normalizedBuildingKey, requestedFloorDisplayName, requestedFloorIndex);
        String resolvedFloorDisplayName = building != null
                ? normalizeFloorLabel(getFloorDisplayName(building, resolvedFloorIndex))
                : normalizeFloorLabel(requestedFloorDisplayName);
        String drawableFloorDisplayName = "library".equals(normalizedBuildingKey)
                ? requestedCanonicalFloor
                : resolvedFloorDisplayName;
        int drawableResId = resolveActualMapDrawable(normalizedBuildingKey, drawableFloorDisplayName, resolvedFloorIndex);
        LatLngBounds bounds = computeActualMapBounds(context, building, normalizedBuildingKey, drawableResId, drawableFloorDisplayName);
        if (drawableResId == 0 || bounds == null) {
            return;
        }

        GroundOverlay overlay = map.addGroundOverlay(new GroundOverlayOptions()
                .image(BitmapDescriptorFactory.fromResource(drawableResId))
                .positionFromBounds(bounds)
                .zIndex(5f));
        if (overlay != null) {
            overlays.add(overlay);
        }
    }

    private static boolean shouldShowLinkedLibraryAndNucleus(String buildingKey) {
        return "library".equals(buildingKey) || "nucleus_building".equals(buildingKey);
    }

    @Nullable
    private static FloorplanApiClient.BuildingInfo findBuildingByKnownKey(String knownKey,
                                                                          @Nullable List<FloorplanApiClient.BuildingInfo> availableBuildings) {
        if (knownKey == null || availableBuildings == null) {
            return null;
        }
        for (FloorplanApiClient.BuildingInfo building : availableBuildings) {
            if (building == null) continue;
            if (knownKey.equals(resolveKnownBuildingKey(building, building.getName()))) {
                return building;
            }
        }
        return null;
    }

    private static int findMatchingFloorIndex(@Nullable FloorplanApiClient.BuildingInfo building,
                                              @Nullable String requestedFloorDisplayName,
                                              int fallbackFloorIndex) {
        if (building == null || building.getFloorShapesList() == null || building.getFloorShapesList().isEmpty()) {
            return 0;
        }

        String normalizedRequestedFloor = normalizeFloorLabel(requestedFloorDisplayName);
        if (!normalizedRequestedFloor.isEmpty()) {
            for (int i = 0; i < building.getFloorShapesList().size(); i++) {
                String candidateLabel = normalizeFloorLabel(building.getFloorShapesList().get(i).getDisplayName());
                if (areEquivalentFloorLabels(normalizedRequestedFloor, candidateLabel)) {
                    return i;
                }
            }
        }
        return clampFloorIndex(building, fallbackFloorIndex);
    }

    private static boolean areEquivalentFloorLabels(String requestedFloorLabel, String candidateFloorLabel) {
        if (requestedFloorLabel == null || candidateFloorLabel == null) {
            return false;
        }
        if (requestedFloorLabel.equals(candidateFloorLabel)) {
            return true;
        }
        String requested = canonicalFloorLabel(requestedFloorLabel);
        String candidate = canonicalFloorLabel(candidateFloorLabel);
        return !requested.isEmpty() && requested.equals(candidate);
    }

    private static int resolveFallbackFloorIndexForKey(String buildingKey, String requestedFloorDisplayName, int requestedFloorIndex) {
        String normalizedKey = normalizeBuildingKey(buildingKey);
        String canonicalFloor = canonicalFloorLabel(requestedFloorDisplayName);

        if ("nucleus_building".equals(normalizedKey)) {
            switch (canonicalFloor) {
                case "LG": return 0;
                case "G": return 1;
                case "1": return 2;
                case "2": return 3;
                case "3": return 4;
                default: return Math.max(0, Math.min(requestedFloorIndex, 4));
            }
        }
        if ("library".equals(normalizedKey)) {
            switch (canonicalFloor) {
                case "G": return 0;
                case "1": return 1;
                case "2": return 2;
                case "3": return 3;
                default: return Math.max(0, Math.min(requestedFloorIndex, 3));
            }
        }
        return Math.max(0, requestedFloorIndex);
    }

    private static int resolveActualMapDrawable(String buildingName, String floorDisplayName, int floorIndex) {
        buildingName = normalizeBuildingKey(buildingName);
        String canonicalFloor = canonicalFloorLabel(floorDisplayName);
        if ("nucleus_building".equals(buildingName)) {
            if ("LG".equals(canonicalFloor)) return R.drawable.nucleuslg;
            if ("G".equals(canonicalFloor)) return R.drawable.nucleusg;
            if ("1".equals(canonicalFloor)) return R.drawable.nucleus1;
            if ("2".equals(canonicalFloor)) return R.drawable.nucleus2;
            if ("3".equals(canonicalFloor)) return R.drawable.nucleus3;
            switch (floorIndex) {
                case 0: return R.drawable.nucleuslg;
                case 1: return R.drawable.nucleusg;
                case 2: return R.drawable.nucleus1;
                case 3: return R.drawable.nucleus2;
                case 4: return R.drawable.nucleus3;
                default: return R.drawable.nucleusg;
            }
        }
        if ("library".equals(buildingName)) {
            if ("LG".equals(canonicalFloor)) return 0;
            if ("G".equals(canonicalFloor)) return R.drawable.libraryg;
            if ("1".equals(canonicalFloor)) return R.drawable.library1;
            if ("2".equals(canonicalFloor)) return R.drawable.library2;
            if ("3".equals(canonicalFloor)) return R.drawable.library3;
            switch (floorIndex) {
                case 0: return 0;
                case 1: return R.drawable.libraryg;
                case 2: return R.drawable.library1;
                case 3: return R.drawable.library2;
                case 4: return R.drawable.library3;
                default: return R.drawable.libraryg;
            }
        }
        return 0;
    }

    @Nullable
    private static LatLngBounds computeActualMapBounds(@NonNull Context context,
                                                       @Nullable FloorplanApiClient.BuildingInfo building,
                                                       String buildingName,
                                                       int drawableResId,
                                                       String floorDisplayName) {
        buildingName = normalizeBuildingKey(buildingName);
        ActualMapAlignmentConfig config = getActualMapAlignmentConfig(buildingName);

        LatLngBounds bounds;
        if ("library".equals(buildingName)) {
            bounds = computeFixedActualMapBounds(buildingName, drawableResId);
        } else if (building != null) {
            bounds = computeThreeEdgeAlignedBounds(context, building, drawableResId, config);
        } else {
            bounds = computeFixedActualMapBounds(buildingName, drawableResId);
        }

        if (bounds == null) {
            bounds = getFallbackBuildingBounds(buildingName);
        }
        if (bounds != null) {
            bounds = applySavedCalibration(context, bounds, buildingName, floorDisplayName);
        }
        return bounds;
    }

    @Nullable
    private static LatLngBounds computeFixedActualMapBounds(String buildingName, int drawableResId) {
        buildingName = normalizeBuildingKey(buildingName);
        if ("library".equals(buildingName)) {
            return buildRightAnchoredRectBounds(
                    BuildingPolygon.LIBRARY_SW,
                    BuildingPolygon.LIBRARY_NE,
                    1.000,
                    1.0,
                    0.008,
                    0.0);
        }
        return null;
    }

    @Nullable
    private static LatLngBounds buildRightAnchoredRectBounds(LatLng southWest,
                                                             LatLng northEast,
                                                             double widthScale,
                                                             double heightScale,
                                                             double eastShiftRatio,
                                                             double northShiftRatio) {
        if (southWest == null || northEast == null) {
            return null;
        }
        double rectWidth = northEast.longitude - southWest.longitude;
        double rectHeight = northEast.latitude - southWest.latitude;
        if (rectWidth <= 0d || rectHeight <= 0d) {
            return null;
        }
        double overlayWidth = rectWidth * widthScale;
        double overlayHeight = rectHeight * heightScale;
        double east = northEast.longitude + rectWidth * eastShiftRatio;
        double west = east - overlayWidth;
        double north = northEast.latitude + rectHeight * northShiftRatio;
        double south = north - overlayHeight;
        return new LatLngBounds(new LatLng(south, west), new LatLng(north, east));
    }

    @Nullable
    private static LatLngBounds computeThreeEdgeAlignedBounds(@NonNull Context context,
                                                              @Nullable FloorplanApiClient.BuildingInfo building,
                                                              int drawableResId,
                                                              @Nullable ActualMapAlignmentConfig config) {
        if (building == null || config == null) {
            return null;
        }
        List<LatLng> outline = building.getOutlinePolygon();
        if (outline == null || outline.size() < 3) {
            return null;
        }

        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double minLng = Double.POSITIVE_INFINITY;
        double maxLng = Double.NEGATIVE_INFINITY;
        for (LatLng point : outline) {
            if (point == null) continue;
            minLat = Math.min(minLat, point.latitude);
            maxLat = Math.max(maxLat, point.latitude);
            minLng = Math.min(minLng, point.longitude);
            maxLng = Math.max(maxLng, point.longitude);
        }

        if (!Double.isFinite(minLat) || !Double.isFinite(maxLat) || !Double.isFinite(minLng) || !Double.isFinite(maxLng)
                || maxLat <= minLat || maxLng <= minLng) {
            return null;
        }

        double latSpan = maxLat - minLat;
        double lngSpan = maxLng - minLng;
        double visibleNorth = maxLat - latSpan * config.northInsetRatio;
        double visibleSouth = minLat + latSpan * config.southInsetRatio;
        if (visibleSouth >= visibleNorth) {
            return null;
        }

        double fullAspectRatio = getDrawableAspectRatio(context, drawableResId);
        if (fullAspectRatio <= 0d) {
            return null;
        }

        DrawableContentInsets contentInsets = new DrawableContentInsets(
                config.leftVisibleInsetRatio,
                config.topVisibleInsetRatio,
                config.rightVisibleInsetRatio,
                config.bottomVisibleInsetRatio);

        double visibleContentHeightFraction = contentInsets.contentHeightFraction();
        double visibleContentWidthFraction = contentInsets.contentWidthFraction();
        if (visibleContentHeightFraction <= 0d || visibleContentWidthFraction <= 0d) {
            return null;
        }

        double visibleHeightDeg = visibleNorth - visibleSouth;
        double totalHeightDeg = visibleHeightDeg / visibleContentHeightFraction;
        double overlayNorth = visibleNorth + totalHeightDeg * contentInsets.topFraction;
        double overlaySouth = overlayNorth - totalHeightDeg;

        double midLatitudeRadians = Math.toRadians((visibleNorth + visibleSouth) / 2.0);
        double metersPerDegreeLng = Math.max(111320.0 * Math.cos(midLatitudeRadians), 1.0);
        double totalHeightMeters = totalHeightDeg * 111320.0;
        double totalWidthMeters = (totalHeightMeters / fullAspectRatio) * config.widthScale;
        double totalWidthLng = totalWidthMeters / metersPerDegreeLng;
        double visibleContentWidthLng = totalWidthLng * visibleContentWidthFraction;

        double visibleEast;
        double visibleWest;
        switch (config.horizontalAnchor) {
            case RIGHT:
                visibleEast = maxLng - lngSpan * config.horizontalInsetRatio;
                visibleWest = visibleEast - visibleContentWidthLng;
                break;
            case LEFT:
                visibleWest = minLng + lngSpan * config.horizontalInsetRatio;
                visibleEast = visibleWest + visibleContentWidthLng;
                break;
            case CENTER:
            default:
                double centerLng = ((minLng + maxLng) / 2.0) + (lngSpan * (config.leftVisibleInsetRatio - config.rightVisibleInsetRatio) * 0.5);
                visibleWest = centerLng - visibleContentWidthLng / 2.0;
                visibleEast = centerLng + visibleContentWidthLng / 2.0;
                break;
        }

        double overlayWest = visibleWest - totalWidthLng * contentInsets.leftFraction;
        double overlayEast = overlayWest + totalWidthLng;
        return new LatLngBounds(new LatLng(overlaySouth, overlayWest), new LatLng(overlayNorth, overlayEast));
    }

    private static double getDrawableAspectRatio(@NonNull Context context, int drawableResId) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(context.getResources(), drawableResId, options);
            if (options.outWidth > 0 && options.outHeight > 0) {
                return (double) options.outHeight / (double) options.outWidth;
            }
        } catch (Exception ignored) {
        }
        return 1d;
    }

    @Nullable
    private static LatLngBounds getFallbackBuildingBounds(String buildingName) {
        buildingName = normalizeBuildingKey(buildingName);
        if ("nucleus_building".equals(buildingName)) {
            return new LatLngBounds(BuildingPolygon.NUCLEUS_SW, BuildingPolygon.NUCLEUS_NE);
        }
        if ("library".equals(buildingName)) {
            return new LatLngBounds(BuildingPolygon.LIBRARY_SW, BuildingPolygon.LIBRARY_NE);
        }
        if ("murchison_house".equals(buildingName)) {
            return new LatLngBounds(BuildingPolygon.MURCHISON_SW, BuildingPolygon.MURCHISON_NE);
        }
        return null;
    }

    private static ActualMapAlignmentConfig getActualMapAlignmentConfig(String buildingName) {
        buildingName = normalizeBuildingKey(buildingName);
        if ("nucleus_building".equals(buildingName)) {
            return new ActualMapAlignmentConfig(HorizontalAnchor.RIGHT, 0.0, 0.0, 0.0, 0.99,
                    0.0, 0.0, 0.0, 0.0);
        }
        if ("library".equals(buildingName)) {
            return new ActualMapAlignmentConfig(HorizontalAnchor.RIGHT, 0.0, 0.0, 0.0, 0.985,
                    0.0, 0.0, 0.0, 0.0);
        }
        if ("murchison_house".equals(buildingName)) {
            return new ActualMapAlignmentConfig(HorizontalAnchor.CENTER, 0.0, 0.0, 0.0, 1.0,
                    0.0, 0.0, 0.0, 0.0);
        }
        return new ActualMapAlignmentConfig(HorizontalAnchor.CENTER, 0.0, 0.0, 0.0, 1.0,
                0.0, 0.0, 0.0, 0.0);
    }

    private static LatLngBounds applySavedCalibration(@NonNull Context context,
                                                      LatLngBounds baseBounds,
                                                      String buildingKey,
                                                      String floorDisplayName) {
        if (baseBounds == null) {
            return null;
        }
        OverlayCalibration calibration = loadOverlayCalibration(context, buildingKey, floorDisplayName);
        double baseNorth = baseBounds.northeast.latitude;
        double baseSouth = baseBounds.southwest.latitude;
        double baseEast = baseBounds.northeast.longitude;
        double baseWest = baseBounds.southwest.longitude;
        double latSpan = baseNorth - baseSouth;
        double lngSpan = baseEast - baseWest;
        if (latSpan <= 0d || lngSpan <= 0d) {
            return baseBounds;
        }

        double centerLat = ((baseNorth + baseSouth) / 2d) + latSpan * calibration.shiftLatRatio;
        double centerLng = ((baseEast + baseWest) / 2d) + lngSpan * calibration.shiftLngRatio;
        double adjustedLatSpan = latSpan * calibration.heightScale;
        double adjustedLngSpan = lngSpan * calibration.widthScale;

        double north = centerLat + adjustedLatSpan / 2d;
        double south = centerLat - adjustedLatSpan / 2d;
        double east = centerLng + adjustedLngSpan / 2d;
        double west = centerLng - adjustedLngSpan / 2d;
        return new LatLngBounds(new LatLng(south, west), new LatLng(north, east));
    }

    private static OverlayCalibration loadOverlayCalibration(@NonNull Context context,
                                                             String buildingKey,
                                                             String floorDisplayName) {
        OverlayCalibration defaults = getHardcodedCalibrationDefault(buildingKey, floorDisplayName);
        SharedPreferences prefs = context.getSharedPreferences(CALIBRATION_PREFS_NAME, Context.MODE_PRIVATE);
        String baseKey = getCalibrationPrefBaseKey(buildingKey, floorDisplayName);
        return new OverlayCalibration(
                prefs.getFloat(baseKey + "_shift_lat", defaults.shiftLatRatio),
                prefs.getFloat(baseKey + "_shift_lng", defaults.shiftLngRatio),
                prefs.getFloat(baseKey + "_width", defaults.widthScale),
                prefs.getFloat(baseKey + "_height", defaults.heightScale)
        );
    }

    private static String getCalibrationPrefBaseKey(String buildingKey, String floorDisplayName) {
        String normalizedBuilding = normalizeBuildingKey(buildingKey);
        String normalizedFloor = canonicalFloorLabel(floorDisplayName);
        return normalizedBuilding + "__" + normalizedFloor;
    }

    private static OverlayCalibration getHardcodedCalibrationDefault(String buildingKey, String floorDisplayName) {
        String normalizedBuilding = normalizeBuildingKey(buildingKey);
        String normalizedFloor = canonicalFloorLabel(floorDisplayName);

        if ("nucleus_building".equals(normalizedBuilding)) {
            switch (normalizedFloor) {
                case "LG": return new OverlayCalibration(0.012f, -0.022f, 0.967f, 0.986f);
                case "G": return new OverlayCalibration(0.029f, -0.052f, 0.958f, 0.942f);
                case "1": return new OverlayCalibration(0.004f, 0.000f, 1.000f, 0.981f);
                case "2": return new OverlayCalibration(0.005f, -0.005f, 1.000f, 1.000f);
                case "3": return new OverlayCalibration(0.005f, 0.005f, 1.015f, 0.990f);
                default: return OverlayCalibration.identity();
            }
        }

        if ("library".equals(normalizedBuilding)) {
            switch (normalizedFloor) {
                case "G": return new OverlayCalibration(-0.072f, 0.018f, 0.965f, 1.098f);
                case "1": return new OverlayCalibration(-0.053f, 0.019f, 0.945f, 1.050f);
                case "2": return new OverlayCalibration(-0.075f, 0.025f, 0.950f, 1.070f);
                case "3": return new OverlayCalibration(-0.070f, 0.025f, 0.960f, 1.065f);
                default: return OverlayCalibration.identity();
            }
        }
        return OverlayCalibration.identity();
    }

    private static String getFloorDisplayName(@Nullable FloorplanApiClient.BuildingInfo building, int floorIndex) {
        if (building == null || building.getFloorShapesList() == null || floorIndex < 0 || floorIndex >= building.getFloorShapesList().size()) {
            return "";
        }
        String displayName = building.getFloorShapesList().get(floorIndex).getDisplayName();
        return displayName == null ? "" : displayName.trim().toUpperCase();
    }

    public static String normalizeBuildingKey(String buildingName) {
        if (buildingName == null) {
            return "";
        }
        String key = buildingName.trim().toLowerCase();
        if (key.contains("nucleus") || key.contains("nuclear")) {
            return "nucleus_building";
        }
        if (key.contains("library") || key.contains("kenneth") || key.contains("murray")) {
            return "library";
        }
        if (key.contains("murchison")) {
            return "murchison_house";
        }
        return key;
    }

    public static String resolveKnownBuildingKey(@Nullable FloorplanApiClient.BuildingInfo building, @Nullable String buildingName) {
        String normalized = normalizeBuildingKey(buildingName);
        if ("nucleus_building".equals(normalized) || "library".equals(normalized) || "murchison_house".equals(normalized)) {
            return normalized;
        }

        LatLng center = building != null ? building.getCenter() : null;
        if (center != null) {
            if (BuildingPolygon.inLibrary(center)) return "library";
            if (BuildingPolygon.inNucleus(center)) return "nucleus_building";
            if (BuildingPolygon.inMurchison(center)) return "murchison_house";
        }

        List<LatLng> outline = building != null ? building.getOutlinePolygon() : null;
        if (outline != null && !outline.isEmpty()) {
            LatLng centroid = computeOutlineCentroid(outline);
            if (centroid != null) {
                if (BuildingPolygon.inLibrary(centroid)) return "library";
                if (BuildingPolygon.inNucleus(centroid)) return "nucleus_building";
                if (BuildingPolygon.inMurchison(centroid)) return "murchison_house";
            }
        }
        return normalized;
    }

    @Nullable
    private static LatLng computeOutlineCentroid(@Nullable List<LatLng> outline) {
        if (outline == null || outline.isEmpty()) {
            return null;
        }
        double latSum = 0d;
        double lngSum = 0d;
        int count = 0;
        for (LatLng point : outline) {
            if (point == null) continue;
            latSum += point.latitude;
            lngSum += point.longitude;
            count++;
        }
        if (count == 0) {
            return null;
        }
        return new LatLng(latSum / count, lngSum / count);
    }

    public static String canonicalFloorLabel(String floorLabel) {
        String normalized = normalizeFloorLabel(floorLabel);
        switch (normalized) {
            case "LG":
            case "LOWERGROUND":
            case "LOWERG":
            case "B1":
            case "BASEMENT1":
                return "LG";
            case "G":
            case "GF":
            case "GROUND":
            case "GROUNDFLOOR":
            case "0":
                return "G";
            case "1":
            case "F1":
            case "FIRST":
            case "FIRSTFLOOR":
                return "1";
            case "2":
            case "F2":
            case "SECOND":
            case "SECONDFLOOR":
                return "2";
            case "3":
            case "F3":
            case "THIRD":
            case "THIRDFLOOR":
                return "3";
            default:
                return normalized;
        }
    }

    private static String normalizeFloorLabel(String floorDisplayName) {
        if (floorDisplayName == null) {
            return "";
        }
        return floorDisplayName.trim().toUpperCase().replace(" ", "");
    }

    private enum HorizontalAnchor { LEFT, CENTER, RIGHT }

    private static final class ActualMapAlignmentConfig {
        final HorizontalAnchor horizontalAnchor;
        final double northInsetRatio;
        final double southInsetRatio;
        final double horizontalInsetRatio;
        final double widthScale;
        final double topVisibleInsetRatio;
        final double bottomVisibleInsetRatio;
        final double rightVisibleInsetRatio;
        final double leftVisibleInsetRatio;

        ActualMapAlignmentConfig(HorizontalAnchor horizontalAnchor,
                                 double northInsetRatio,
                                 double southInsetRatio,
                                 double horizontalInsetRatio,
                                 double widthScale,
                                 double topVisibleInsetRatio,
                                 double bottomVisibleInsetRatio,
                                 double rightVisibleInsetRatio,
                                 double leftVisibleInsetRatio) {
            this.horizontalAnchor = horizontalAnchor;
            this.northInsetRatio = northInsetRatio;
            this.southInsetRatio = southInsetRatio;
            this.horizontalInsetRatio = horizontalInsetRatio;
            this.widthScale = widthScale;
            this.topVisibleInsetRatio = topVisibleInsetRatio;
            this.bottomVisibleInsetRatio = bottomVisibleInsetRatio;
            this.rightVisibleInsetRatio = rightVisibleInsetRatio;
            this.leftVisibleInsetRatio = leftVisibleInsetRatio;
        }
    }

    private static final class DrawableContentInsets {
        final double leftFraction;
        final double topFraction;
        final double rightFraction;
        final double bottomFraction;

        DrawableContentInsets(double leftFraction, double topFraction, double rightFraction, double bottomFraction) {
            this.leftFraction = clamp01(leftFraction);
            this.topFraction = clamp01(topFraction);
            this.rightFraction = clamp01(rightFraction);
            this.bottomFraction = clamp01(bottomFraction);
        }

        double contentWidthFraction() {
            return Math.max(0.01d, 1d - leftFraction - rightFraction);
        }

        double contentHeightFraction() {
            return Math.max(0.01d, 1d - topFraction - bottomFraction);
        }
    }

    private static final class OverlayCalibration {
        final float shiftLatRatio;
        final float shiftLngRatio;
        final float widthScale;
        final float heightScale;

        OverlayCalibration(float shiftLatRatio, float shiftLngRatio, float widthScale, float heightScale) {
            this.shiftLatRatio = shiftLatRatio;
            this.shiftLngRatio = shiftLngRatio;
            this.widthScale = widthScale;
            this.heightScale = heightScale;
        }

        static OverlayCalibration identity() {
            return new OverlayCalibration(0f, 0f, 1f, 1f);
        }
    }

    private static double clamp01(double value) {
        return Math.max(0d, Math.min(1d, value));
    }
}
