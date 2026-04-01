package com.openpositioning.PositionMe.utils;

import androidx.annotation.Nullable;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.List;
import java.util.Locale;

/**
 * CW2 shared spatial model used by fusion, map matching and floor control.
 *
 * <p>This class is intentionally independent from GoogleMap rendering. It only works with cached
 * floorplan geometry and provides geometry queries needed by the particle filter and floor logic.</p>
 */
public class IndoorSpatialConstraintModel {

    private static final double WALKABLE_BOUNDARY_TOLERANCE_METERS = 2.2;

    private String currentBuildingId;
    private int currentLogicalFloor;

    public void reset() {
        currentBuildingId = null;
        currentLogicalFloor = 0;
    }

    /**
     * Updates the active building context from a geographic position.
     *
     * <p>When the user enters a different building polygon, the logical floor is reset to that
     * building's default floor so later modules start from a valid indoor context.</p>
     */
    public void updatePosition(@Nullable LatLng position) {
        if (position == null) {
            return;
        }

        String inferredBuilding = inferBuildingId(position);
        if (inferredBuilding == null) {
            currentBuildingId = null;
            currentLogicalFloor = 0;
            return;
        }

        if (!inferredBuilding.equals(currentBuildingId)) {
            currentBuildingId = inferredBuilding;
            currentLogicalFloor = defaultLogicalFloorForBuilding(inferredBuilding);
        }
    }

    @Nullable
    public String getCurrentBuildingId() {
        return currentBuildingId;
    }

    public boolean hasIndoorContext() {
        return getCurrentFloorShapes() != null;
    }

    public boolean hasIndoorContext(@Nullable String buildingId, int logicalFloor) {
        return getFloorShapes(buildingId, logicalFloor) != null;
    }

    public int getCurrentLogicalFloor() {
        return currentLogicalFloor;
    }

    public void setCurrentLogicalFloor(int logicalFloor) {
        if (!hasIndoorContext()) {
            currentLogicalFloor = logicalFloor;
            return;
        }

        int clamped = clampFloorIndex(
                currentBuildingId,
                logicalToFloorIndex(currentBuildingId, logicalFloor)
        );
        currentLogicalFloor = floorIndexToLogical(currentBuildingId, clamped);
    }

    public int clampLogicalFloor(@Nullable String buildingId, int logicalFloor) {
        FloorplanApiClient.BuildingInfo building = getBuilding(buildingId);
        if (building == null || building.getFloorShapesList() == null
                || building.getFloorShapesList().isEmpty()) {
            return logicalFloor;
        }

        int clampedIndex = clampFloorIndex(
                buildingId,
                logicalToFloorIndex(buildingId, logicalFloor)
        );
        return floorIndexToLogical(buildingId, clampedIndex);
    }

    public int normalizeExternalFloorObservation(int observedFloor) {
        return normalizeExternalFloorObservation(currentBuildingId, observedFloor);
    }

    /**
     * Normalises an external floor observation to the app's logical floor convention.
     *
     * <p>This compensates for APIs that encode floors as shifted indices, for example when
     * lower-ground and ground floors are offset by one.</p>
     */
    public int normalizeExternalFloorObservation(@Nullable String buildingId, int observedFloor) {
        Integer exactIndex = findFloorIndexByLogicalFloor(buildingId, observedFloor);
        if (exactIndex != null) {
            return clampLogicalFloor(buildingId, observedFloor);
        }

        Integer shiftedIndex = findFloorIndexByLogicalFloor(buildingId, observedFloor - 1);
        if (shiftedIndex != null) {
            return clampLogicalFloor(buildingId, observedFloor - 1);
        }
        return clampLogicalFloor(buildingId, observedFloor);
    }

    public float getCurrentFloorHeight() {
        if (currentBuildingId == null) {
            return 0f;
        }
        switch (currentBuildingId) {
            case "nucleus_building":
                return IndoorMapManager.NUCLEUS_FLOOR_HEIGHT;
            case "murchison_house":
                return IndoorMapManager.MURCHISON_FLOOR_HEIGHT;
            case "library":
                return IndoorMapManager.LIBRARY_FLOOR_HEIGHT;
            default:
                return 0f;
        }
    }

    public String getCurrentFloorDisplayName() {
        return getFloorDisplayName(currentBuildingId, currentLogicalFloor);
    }

    public String getFloorDisplayName(@Nullable String buildingId, int logicalFloor) {
        FloorplanApiClient.FloorShapes floorShapes = getFloorShapes(buildingId, logicalFloor);
        if (floorShapes == null) {
            return String.valueOf(logicalFloor);
        }
        return floorShapes.getDisplayName();
    }

    public boolean isNearFeature(@Nullable LatLng point, String indoorType, double radiusMeters) {
        return isNearFeature(point, indoorType, radiusMeters, currentBuildingId, currentLogicalFloor);
    }

    public boolean isNearFeature(@Nullable LatLng point,
                                 String indoorType,
                                 double radiusMeters,
                                 @Nullable String buildingId,
                                 int logicalFloor) {
        if (point == null) {
            return false;
        }

        FloorplanApiClient.FloorShapes floor = getFloorShapes(buildingId, logicalFloor);
        if (floor == null) {
            return false;
        }

        for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
            if (!indoorType.equals(feature.getIndoorType())) {
                continue;
            }
            boolean polygonGeometry = isPolygonGeometry(feature.getGeometryType());
            for (List<LatLng> part : feature.getParts()) {
                if (part.size() < 2) {
                    continue;
                }
                if (polygonGeometry
                        && part.size() >= 3
                        && BuildingPolygon.pointInPolygon(point, part)) {
                    return true;
                }
                if (distancePointToPathMeters(point, part, polygonGeometry) <= radiusMeters) {
                    return true;
                }
            }
        }

        return false;
    }

    public ConstraintResult constrainMotion(@Nullable LatLng previous,
                                            @Nullable LatLng candidate) {
        return constrainMotion(previous, candidate, currentBuildingId, currentLogicalFloor);
    }

    /**
     * Constrains a motion segment against the active floor geometry.
     *
     * <p>If a segment crosses a wall, the motion is clipped to the last legal point and its
     * returned weight is reduced so the particle filter can penalise impossible movement.</p>
     */
    public ConstraintResult constrainMotion(@Nullable LatLng previous,
                                            @Nullable LatLng candidate,
                                            @Nullable String buildingId,
                                            int logicalFloor) {
        if (candidate == null) {
            return new ConstraintResult(previous, 1.0, false);
        }

        FloorplanApiClient.FloorShapes floor = getFloorShapes(buildingId, logicalFloor);
        if (floor == null || previous == null) {
            return new ConstraintResult(candidate, scorePoint(candidate, buildingId, logicalFloor), false);
        }

        if (!isBlocked(previous, candidate, floor)) {
            return new ConstraintResult(
                    candidate,
                    scorePoint(candidate, buildingId, logicalFloor, floor),
                    false
            );
        }

        double low = 0d;
        double high = 1d;
        LatLng safe = previous;
        for (int i = 0; i < 14; i++) {
            double mid = (low + high) / 2d;
            LatLng probe = interpolate(previous, candidate, mid);
            if (isBlocked(previous, probe, floor)) {
                high = mid;
            } else {
                low = mid;
                safe = probe;
            }
        }

        double motionPreserved = UtilFunctions.distanceBetweenPoints(previous, safe);
        double attempted = UtilFunctions.distanceBetweenPoints(previous, candidate);
        double ratio = attempted > 0.05 ? motionPreserved / attempted : 1.0;
        double penalty = 0.05 + 0.45 * Math.max(0d, Math.min(1d, ratio));
        return new ConstraintResult(safe, penalty, true);
    }

    public double scorePoint(@Nullable LatLng point) {
        return scorePoint(point, currentBuildingId, currentLogicalFloor);
    }

    /**
     * Scores how plausible a point is on the requested building/floor geometry.
     *
     * <p>Walkable points retain a high score, while points that land inside walls or outside the
     * expected indoor envelope are down-weighted for fusion and map matching.</p>
     */
    public double scorePoint(@Nullable LatLng point,
                             @Nullable String buildingId,
                             int logicalFloor) {
        FloorplanApiClient.FloorShapes floor = getFloorShapes(buildingId, logicalFloor);
        return scorePoint(point, buildingId, logicalFloor, floor);
    }

    /**
     * Infers the most likely building from the current position.
     */
    @Nullable
    public String inferBuildingId(@Nullable LatLng position) {
        if (position == null) {
            return null;
        }

        String nearestBuilding = null;
        double nearestDistance = Double.MAX_VALUE;

        for (FloorplanApiClient.BuildingInfo building : SensorFusion.getInstance().getFloorplanBuildings()) {
            List<LatLng> outline = building.getOutlinePolygon();
            if (outline != null && outline.size() >= 3
                    && BuildingPolygon.pointInPolygon(position, outline)) {
                return building.getName();
            }

            LatLng center = building.getCenter();
            double distanceMeters = UtilFunctions.distanceBetweenPoints(position, center);
            if (distanceMeters < nearestDistance) {
                nearestDistance = distanceMeters;
                nearestBuilding = building.getName();
            }
        }

        return nearestDistance <= 80.0 ? nearestBuilding : null;
    }

    private double scorePoint(@Nullable LatLng point,
                              @Nullable String buildingId,
                              int logicalFloor,
                              @Nullable FloorplanApiClient.FloorShapes floor) {
        if (point == null) {
            return 1.0;
        }
        if (floor == null) {
            return 1.0;
        }

        if (isInsideWall(point, floor)) {
            return 0.01;
        }

        List<List<LatLng>> walkablePolygons = getWalkablePolygons(floor);
        if (walkablePolygons.isEmpty()) {
            return 1.0;
        }

        boolean insideWalkable = false;
        double nearestBoundary = Double.MAX_VALUE;
        for (List<LatLng> polygon : walkablePolygons) {
            if (polygon.size() >= 3 && BuildingPolygon.pointInPolygon(point, polygon)) {
                insideWalkable = true;
                break;
            }
            nearestBoundary = Math.min(
                    nearestBoundary,
                    distancePointToPathMeters(point, polygon, true)
            );
        }

        if (insideWalkable) {
            return 1.0;
        }

        if (nearestBoundary <= WALKABLE_BOUNDARY_TOLERANCE_METERS) {
            return 0.55;
        }
        return 0.18;
    }

    private boolean isBlocked(LatLng previous,
                              LatLng candidate,
                              FloorplanApiClient.FloorShapes floor) {
        return isInsideWall(candidate, floor) || segmentCrossesWall(previous, candidate, floor);
    }

    private boolean isInsideWall(LatLng point, FloorplanApiClient.FloorShapes floor) {
        for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
            if (!"wall".equals(feature.getIndoorType())
                    || !isPolygonGeometry(feature.getGeometryType())) {
                continue;
            }

            for (List<LatLng> part : feature.getParts()) {
                if (part.size() >= 3 && BuildingPolygon.pointInPolygon(point, part)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean segmentCrossesWall(LatLng start,
                                       LatLng end,
                                       FloorplanApiClient.FloorShapes floor) {
        for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
            if (!"wall".equals(feature.getIndoorType())) {
                continue;
            }

            boolean polygonGeometry = isPolygonGeometry(feature.getGeometryType());
            for (List<LatLng> part : feature.getParts()) {
                if (part.size() < 2) {
                    continue;
                }
                if (pathIntersects(start, end, part, polygonGeometry)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<List<LatLng>> getWalkablePolygons(FloorplanApiClient.FloorShapes floor) {
        List<List<LatLng>> result = new java.util.ArrayList<>();
        for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
            if ("wall".equals(feature.getIndoorType())) {
                continue;
            }
            if (!isPolygonGeometry(feature.getGeometryType())) {
                continue;
            }
            result.addAll(feature.getParts());
        }
        return result;
    }

    private FloorplanApiClient.FloorShapes getCurrentFloorShapes() {
        return getFloorShapes(currentBuildingId, currentLogicalFloor);
    }

    private FloorplanApiClient.FloorShapes getFloorShapes(@Nullable String buildingId,
                                                          int logicalFloor) {
        FloorplanApiClient.BuildingInfo building = getBuilding(buildingId);
        if (building == null) {
            return null;
        }

        List<FloorplanApiClient.FloorShapes> floors = building.getFloorShapesList();
        if (floors == null || floors.isEmpty()) {
            return null;
        }

        int index = clampFloorIndex(buildingId, logicalToFloorIndex(buildingId, logicalFloor));
        return floors.get(index);
    }

    private FloorplanApiClient.BuildingInfo getCurrentBuilding() {
        return getBuilding(currentBuildingId);
    }

    private FloorplanApiClient.BuildingInfo getBuilding(@Nullable String buildingId) {
        if (buildingId == null || buildingId.isEmpty()) {
            return null;
        }
        return SensorFusion.getInstance().getFloorplanBuilding(buildingId);
    }

    private int logicalToFloorIndex(@Nullable String buildingId, int logicalFloor) {
        Integer mappedIndex = findFloorIndexByLogicalFloor(buildingId, logicalFloor);
        if (mappedIndex != null) {
            return mappedIndex;
        }
        return logicalFloor + getFloorBias(buildingId);
    }

    private int floorIndexToLogical(@Nullable String buildingId, int floorIndex) {
        Integer mappedLogicalFloor = parseLogicalFloor(buildingId, floorIndex);
        if (mappedLogicalFloor != null) {
            return mappedLogicalFloor;
        }
        return floorIndex - getFloorBias(buildingId);
    }

    private int clampFloorIndex(@Nullable String buildingId, int floorIndex) {
        FloorplanApiClient.BuildingInfo building = getBuilding(buildingId);
        if (building == null || building.getFloorShapesList() == null
                || building.getFloorShapesList().isEmpty()) {
            return floorIndex;
        }
        return Math.max(0, Math.min(building.getFloorShapesList().size() - 1, floorIndex));
    }

    private int getFloorBias(@Nullable String buildingId) {
        if ("nucleus_building".equals(buildingId) || "murchison_house".equals(buildingId)) {
            return 1;
        }
        return 0;
    }

    private int defaultLogicalFloorForBuilding(@Nullable String buildingId) {
        return 0;
    }

    @Nullable
    private Integer findFloorIndexByLogicalFloor(@Nullable String buildingId, int logicalFloor) {
        FloorplanApiClient.BuildingInfo building = getBuilding(buildingId);
        if (building == null || building.getFloorShapesList() == null) {
            return null;
        }

        List<FloorplanApiClient.FloorShapes> floors = building.getFloorShapesList();
        for (int i = 0; i < floors.size(); i++) {
            Integer parsedLogical = parseLogicalFloorLabel(
                    floors.get(i).getDisplayName(),
                    floors.get(i).getKey()
            );
            if (parsedLogical != null && parsedLogical == logicalFloor) {
                return i;
            }
        }
        return null;
    }

    @Nullable
    private Integer parseLogicalFloor(@Nullable String buildingId, int floorIndex) {
        FloorplanApiClient.BuildingInfo building = getBuilding(buildingId);
        if (building == null || building.getFloorShapesList() == null
                || floorIndex < 0 || floorIndex >= building.getFloorShapesList().size()) {
            return null;
        }

        FloorplanApiClient.FloorShapes floor = building.getFloorShapesList().get(floorIndex);
        return parseLogicalFloorLabel(floor.getDisplayName(), floor.getKey());
    }

    @Nullable
    private Integer parseLogicalFloorLabel(@Nullable String displayName, @Nullable String fallbackKey) {
        Integer parsed = parseSingleFloorLabel(displayName);
        if (parsed != null) {
            return parsed;
        }
        return parseSingleFloorLabel(fallbackKey);
    }

    @Nullable
    private Integer parseSingleFloorLabel(@Nullable String rawLabel) {
        if (rawLabel == null) {
            return null;
        }

        String normalized = rawLabel.trim().toUpperCase(Locale.UK);
        if (normalized.isEmpty()) {
            return null;
        }

        normalized = normalized
                .replace("FLOOR", "")
                .replace("LEVEL", "")
                .replace("STOREY", "")
                .replace("STORY", "")
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");

        if ("G".equals(normalized) || "GF".equals(normalized) || "GROUND".equals(normalized)) {
            return 0;
        }
        if ("LG".equals(normalized) || "LOWGROUND".equals(normalized)
                || "LOWERGROUND".equals(normalized)) {
            return -1;
        }
        if ("UG".equals(normalized) || "UPGROUND".equals(normalized)
                || "UPPERGROUND".equals(normalized)) {
            return 1;
        }

        if (normalized.startsWith("B") && normalized.length() > 1) {
            try {
                return -Integer.parseInt(normalized.substring(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        if (normalized.startsWith("L") && normalized.length() > 1) {
            try {
                return Integer.parseInt(normalized.substring(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isPolygonGeometry(String geometryType) {
        return "Polygon".equals(geometryType) || "MultiPolygon".equals(geometryType);
    }

    private boolean pathIntersects(LatLng start, LatLng end, List<LatLng> path, boolean closed) {
        int segmentCount = closed ? path.size() : path.size() - 1;
        for (int i = 0; i < segmentCount; i++) {
            LatLng segStart = path.get(i);
            LatLng segEnd = path.get((i + 1) % path.size());
            if (segmentsIntersect(start, end, segStart, segEnd)) {
                return true;
            }
        }
        return false;
    }

    private boolean segmentsIntersect(LatLng a, LatLng b, LatLng c, LatLng d) {
        double o1 = orientation(a, b, c);
        double o2 = orientation(a, b, d);
        double o3 = orientation(c, d, a);
        double o4 = orientation(c, d, b);

        if ((o1 > 0 && o2 < 0 || o1 < 0 && o2 > 0)
                && (o3 > 0 && o4 < 0 || o3 < 0 && o4 > 0)) {
            return true;
        }

        return Math.abs(o1) < 1e-10 && onSegment(a, c, b)
                || Math.abs(o2) < 1e-10 && onSegment(a, d, b)
                || Math.abs(o3) < 1e-10 && onSegment(c, a, d)
                || Math.abs(o4) < 1e-10 && onSegment(c, b, d);
    }

    private double orientation(LatLng a, LatLng b, LatLng c) {
        return (b.longitude - a.longitude) * (c.latitude - a.latitude)
                - (b.latitude - a.latitude) * (c.longitude - a.longitude);
    }

    private boolean onSegment(LatLng a, LatLng p, LatLng b) {
        return p.longitude >= Math.min(a.longitude, b.longitude) - 1e-10
                && p.longitude <= Math.max(a.longitude, b.longitude) + 1e-10
                && p.latitude >= Math.min(a.latitude, b.latitude) - 1e-10
                && p.latitude <= Math.max(a.latitude, b.latitude) + 1e-10;
    }

    private LatLng interpolate(LatLng start, LatLng end, double ratio) {
        return new LatLng(
                start.latitude + (end.latitude - start.latitude) * ratio,
                start.longitude + (end.longitude - start.longitude) * ratio
        );
    }

    private double distancePointToPathMeters(LatLng point, List<LatLng> path, boolean closed) {
        double bestDistance = Double.MAX_VALUE;
        int segmentCount = closed ? path.size() : path.size() - 1;
        for (int i = 0; i < segmentCount; i++) {
            LatLng segStart = path.get(i);
            LatLng segEnd = path.get((i + 1) % path.size());
            bestDistance = Math.min(
                    bestDistance,
                    distancePointToSegmentMeters(point, segStart, segEnd)
            );
        }
        return bestDistance;
    }

    private double distancePointToSegmentMeters(LatLng point, LatLng segStart, LatLng segEnd) {
        double cosLat = Math.cos(Math.toRadians(point.latitude));
        double ax = (segStart.longitude - point.longitude) * 111_111d * cosLat;
        double ay = (segStart.latitude - point.latitude) * 111_111d;
        double bx = (segEnd.longitude - point.longitude) * 111_111d * cosLat;
        double by = (segEnd.latitude - point.latitude) * 111_111d;
        double abx = bx - ax;
        double aby = by - ay;
        double abSquared = abx * abx + aby * aby;
        if (abSquared <= 1e-9) {
            return Math.hypot(ax, ay);
        }

        double projection = -(ax * abx + ay * aby) / abSquared;
        projection = Math.max(0d, Math.min(1d, projection));
        double closestX = ax + projection * abx;
        double closestY = ay + projection * aby;
        return Math.hypot(closestX, closestY);
    }

    public static final class ConstraintResult {
        private final LatLng correctedPosition;
        private final double weightScale;
        private final boolean clipped;

        public ConstraintResult(@Nullable LatLng correctedPosition,
                                double weightScale,
                                boolean clipped) {
            this.correctedPosition = correctedPosition;
            this.weightScale = weightScale;
            this.clipped = clipped;
        }

        @Nullable
        public LatLng getCorrectedPosition() {
            return correctedPosition;
        }

        public double getWeightScale() {
            return weightScale;
        }

        public boolean isClipped() {
            return clipped;
        }
    }
}
