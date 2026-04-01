package com.openpositioning.PositionMe.utils;

import android.graphics.Color;
import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages indoor floor map display for all supported buildings
 * (Nucleus, Library, Murchison). Uses vector shape data from the floorplan API
 * to dynamically draw walls, rooms, and other indoor features on the Google Map.
 * Provides unified floor indexing, floor switching, and building detection.
 *
 * @see BuildingPolygon Describes the bounds of buildings and the methods to check if a point is
 *                      within a building
 * @see FloorplanApiClient.FloorShapes Per-floor vector shape data
 */
public class IndoorMapManager {

    private static final String TAG = "IndoorMapManager";

    // Hysteresis buffer distances (meters) for building boundary detection.
    // Entry buffer (negative = shrink polygon): user must be clearly inside before triggering.
    // Exit buffer (positive = expand polygon): user must be clearly outside before clearing.
    private static final double ENTRY_BUFFER_METERS = -5.0;
    private static final double EXIT_BUFFER_METERS = 15.0;

    /** Building identifiers for tracking which building the user is in. */
    public static final int BUILDING_NONE = 0;
    public static final int BUILDING_NUCLEUS = 1;
    public static final int BUILDING_LIBRARY = 2;
    public static final int BUILDING_MURCHISON = 3;
    public static final int BUILDING_CAMPUS = 4; // Nucleus + Library combined

    private GoogleMap gMap;
    private LatLng currentLocation;
    private boolean isIndoorMapSet = false;
    private int currentFloor;
    private int currentBuilding = BUILDING_NONE;
    private float floorHeight;

    // Vector shapes currently drawn on the map (cleared on floor switch or exit)
    private final List<Polygon> drawnPolygons = new ArrayList<>();
    private final List<Polyline> drawnPolylines = new ArrayList<>();

    // Per-floor vector shape data for the current building
    private List<FloorplanApiClient.FloorShapes> currentFloorShapes;

    // Campus mode: floor shapes for both buildings loaded simultaneously
    private List<FloorplanApiClient.FloorShapes> nucleusFloorShapes;
    private List<FloorplanApiClient.FloorShapes> libraryFloorShapes;

    // Cached wall segments for current floor [start, end] LatLng pairs
    private List<LatLng[]> cachedWallSegments;
    private int cachedWallFloor = -1;
    private int cachedWallBuilding = BUILDING_NONE;

    // Consecutive collision counter — releases position when stuck
    private int consecutiveCollisions = 0;

    // Average floor heights per building (meters), used for barometric auto-floor
    public static final float NUCLEUS_FLOOR_HEIGHT = 4.2F;
    public static final float LIBRARY_FLOOR_HEIGHT = 3.6F;
    public static final float MURCHISON_FLOOR_HEIGHT = 4.0F;

    // Colours for different indoor feature types
    private static final int WALL_STROKE = Color.argb(200, 80, 80, 80);
    private static final int ROOM_STROKE = Color.argb(180, 33, 150, 243);
    private static final int ROOM_FILL = Color.argb(40, 33, 150, 243);
    private static final int DEFAULT_STROKE = Color.argb(150, 100, 100, 100);
    private static final int STAIRS_STROKE = Color.argb(200, 76, 175, 80);
    private static final int STAIRS_FILL   = Color.argb(60, 76, 175, 80);
    private static final int LIFT_STROKE   = Color.argb(200, 255, 152, 0);
    private static final int LIFT_FILL     = Color.argb(60, 255, 152, 0);

    // Proximity threshold (meters) for stairs/lift features to allow floor changes
    private static final double VERTICAL_TRANSPORT_PROXIMITY_METERS = 10.0;

    // Wall collision constants
    private static final String WALL_TAG = "WALL_COLLISION";
    private static final double WALL_METERS_PER_DEG = 111320.0;
    private static final double WALL_BUFFER_M = 0.3;   // Min distance from wall after slide (meters)
    private static final double BOUNCE_DISTANCE_M = 0.5; // Bounce-back distance from wall (meters)
    private static final double HEAD_ON_DOT_THRESHOLD = 0.5; // cos(60°): above = bounce, below = slide
    private static final double MAX_CONSTRAIN_DIST_M = 2.0; // Skip wall check for large fusion jumps (meters)
    private static final int MAX_CONSECUTIVE_BOUNCE = 2;     // Switch from bounce to force-slide after N hits
    private static final int MAX_CONSECUTIVE_COLLISIONS = 8; // Release position entirely after N hits

    /**
     * Constructor to set the map instance.
     *
     * @param map the map on which the indoor floor map shapes are drawn
     */
    public IndoorMapManager(GoogleMap map) {
        this.gMap = map;
    }

    /**
     * Updates the current location of the user and displays the indoor map
     * if the user is in a building with indoor maps available.
     *
     * @param currentLocation new location of user
     */
    public void setCurrentLocation(LatLng currentLocation) {
        this.currentLocation = currentLocation;
        setBuildingOverlay();
    }

    /**
     * Returns the current building's floor height.
     *
     * @return the floor height of the current building the user is in
     */
    public float getFloorHeight() {
        if (currentBuilding == BUILDING_CAMPUS && currentLocation != null) {
            if (BuildingPolygon.inLibrary(currentLocation)) {
                return LIBRARY_FLOOR_HEIGHT;
            }
            return NUCLEUS_FLOOR_HEIGHT;
        }
        return floorHeight;
    }

    /**
     * Returns whether an indoor floor map is currently being displayed.
     *
     * @return true if an indoor map is visible to the user, false otherwise
     */
    public boolean getIsIndoorMapSet() {
        return isIndoorMapSet;
    }

    /**
     * Returns the identifier of the building the user is currently in.
     *
     * @return one of {@link #BUILDING_NONE}, {@link #BUILDING_NUCLEUS},
     *         {@link #BUILDING_LIBRARY}, or {@link #BUILDING_MURCHISON}
     */
    public int getCurrentBuilding() {
        return currentBuilding;
    }

    /**
     * Returns the current floor index being displayed.
     *
     * @return the current floor index in the active building's floor list
     */
    public int getCurrentFloor() {
        return currentFloor;
    }

    /**
     * Clamps the given position to within the current building's outer boundary.
     * If the user is detected as being inside a building and the position falls
     * outside that building's polygon, the position is snapped to the nearest
     * point on the building boundary. If the user is not in any building, or the
     * position is already inside, the original position is returned unchanged.
     *
     * @param position the fused position to clamp
     * @return the clamped position (unchanged if inside or not in a building)
     */
    public LatLng clampToBuildingBoundary(LatLng position) {
        if (currentBuilding == BUILDING_NONE || position == null) {
            return position;
        }

        // Determine the boundary polygon for the current building
        List<LatLng> polygon = getCurrentBuildingPolygon();
        if (polygon == null || polygon.size() < 3) {
            return position;
        }

        // If already inside, no clamping needed
        if (BuildingPolygon.pointInPolygon(position, polygon)) {
            return position;
        }

        // Position is outside — clamp to the nearest boundary edge
        LatLng clamped = BuildingPolygon.clampToPolygon(position, polygon);
        Log.e(TAG, "WALL_CLAMP: position outside building boundary, clamped"
                + " | original=(" + String.format("%.7f", position.latitude)
                + "," + String.format("%.7f", position.longitude) + ")"
                + " | clamped=(" + String.format("%.7f", clamped.latitude)
                + "," + String.format("%.7f", clamped.longitude) + ")"
                + " | building=" + currentBuilding);
        return clamped;
    }

    /**
     * Returns the boundary polygon for the current building, preferring
     * the real API outline over the legacy hard-coded rectangle.
     *
     * @return polygon vertices, or null if no building is active
     */
    private List<LatLng> getCurrentBuildingPolygon() {
        String targetApiName;
        List<LatLng> fallbackPolygon;
        switch (currentBuilding) {
            case BUILDING_NUCLEUS:
                targetApiName = "nucleus_building";
                fallbackPolygon = BuildingPolygon.NUCLEUS_POLYGON;
                break;
            case BUILDING_LIBRARY:
                targetApiName = "library";
                fallbackPolygon = BuildingPolygon.LIBRARY_POLYGON;
                break;
            case BUILDING_MURCHISON:
                targetApiName = "murchison_house";
                fallbackPolygon = BuildingPolygon.MURCHISON_POLYGON;
                break;
            case BUILDING_CAMPUS:
                // Campus uses the combined bounding polygon
                return BuildingPolygon.CAMPUS_POLYGON;
            default:
                return null;
        }

        // Try API polygon first (more accurate real outline)
        List<FloorplanApiClient.BuildingInfo> apiBuildings =
                SensorFusion.getInstance().getFloorplanBuildings();
        for (FloorplanApiClient.BuildingInfo building : apiBuildings) {
            if (targetApiName.equals(building.getName())) {
                List<LatLng> outline = building.getOutlinePolygon();
                if (outline != null && outline.size() >= 3) {
                    return outline;
                }
            }
        }

        // Fallback: legacy hard-coded polygon
        return fallbackPolygon;
    }

    /**
     * Returns the display name for the current floor (e.g. "LG", "G", "1").
     * Falls back to the numeric index if no display name is available.
     *
     * @return human-readable floor label
     */
    public String getCurrentFloorDisplayName() {
        if (currentBuilding == BUILDING_CAMPUS && nucleusFloorShapes != null
                && currentFloor >= 0 && currentFloor < nucleusFloorShapes.size()) {
            return nucleusFloorShapes.get(currentFloor).getDisplayName();
        }
        if (currentFloorShapes != null
                && currentFloor >= 0
                && currentFloor < currentFloorShapes.size()) {
            return currentFloorShapes.get(currentFloor).getDisplayName();
        }
        return String.valueOf(currentFloor);
    }

    /**
     * Returns the auto-floor bias for the current building. Buildings with a
     * lower-ground floor at index 0 need a +1 bias so that WiFi/barometric
     * floor 0 (ground) maps to the correct floor index.
     *
     * @return the floor index offset for auto-floor conversion
     */
    public int getAutoFloorBias() {
        switch (currentBuilding) {
            case BUILDING_NUCLEUS:
            case BUILDING_MURCHISON:
            case BUILDING_CAMPUS: // Campus uses Nucleus indexing (LG at index 0)
                return 1;
            case BUILDING_LIBRARY:
            default:
                return 0; // G at index 0
        }
    }

    //region Vertical transport proximity (stairs/lift gate for floor changes)

    /**
     * Checks all floors of the current building for "stairs" or "lift" features
     * and returns the type of the nearest one within the proximity threshold.
     *
     * @param position the user's current position
     * @return "stairs", "lift", or "unknown" (no map data) if within threshold; null if too far
     */
    public String getNearbyVerticalTransport(LatLng position) {
        if (position == null) return null;

        // Collect all floor shape sets to scan
        List<List<FloorplanApiClient.FloorShapes>> allShapeSets = new ArrayList<>();
        if (currentBuilding == BUILDING_CAMPUS) {
            if (nucleusFloorShapes != null) allShapeSets.add(nucleusFloorShapes);
            if (libraryFloorShapes != null) allShapeSets.add(libraryFloorShapes);
        } else if (currentFloorShapes != null) {
            allShapeSets.add(currentFloorShapes);
        }
        if (allShapeSets.isEmpty()) return null;

        boolean hasAnyTransport = false;
        String nearest = null;
        double nearestDist = Double.MAX_VALUE;

        // Scan all floors of all buildings — stairs/lifts span multiple levels
        for (List<FloorplanApiClient.FloorShapes> shapeSet : allShapeSets) {
            for (FloorplanApiClient.FloorShapes floor : shapeSet) {
                for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
                    String type = feature.getIndoorType();
                    if (!"stairs".equals(type) && !"lift".equals(type)) continue;

                    hasAnyTransport = true;

                    for (List<LatLng> part : feature.getParts()) {
                        for (LatLng coord : part) {
                            double dist = distanceInMeters(position, coord);
                            if (dist < nearestDist) {
                                nearestDist = dist;
                                nearest = type;
                            }
                        }
                    }
                }
            }
        }

        if (!hasAnyTransport) {
            Log.e(TAG, "FLOOR_GATE: no stairs/lift features in map data, allowing floor change");
            return "unknown";
        }

        if (nearestDist <= VERTICAL_TRANSPORT_PROXIMITY_METERS) {
            Log.e(TAG, "FLOOR_GATE: near " + nearest
                    + " | dist=" + String.format("%.1f", nearestDist) + "m"
                    + " | threshold=" + VERTICAL_TRANSPORT_PROXIMITY_METERS + "m");
            return nearest;
        }

        Log.e(TAG, "FLOOR_GATE: NOT near any vertical transport"
                + " | nearest=" + nearest
                + " | dist=" + String.format("%.1f", nearestDist) + "m"
                + " | threshold=" + VERTICAL_TRANSPORT_PROXIMITY_METERS + "m"
                + " | pos=(" + String.format("%.6f", position.latitude)
                + "," + String.format("%.6f", position.longitude) + ")");
        return null;
    }

    /**
     * Convenience check: is the user near any stairs or lift feature?
     *
     * @param position the user's current position
     * @return true if near stairs or lift (or no map data available)
     */
    public boolean isNearStairsOrLift(LatLng position) {
        return getNearbyVerticalTransport(position) != null;
    }

    /**
     * Approximate distance in meters between two LatLng points (flat-earth).
     */
    private static double distanceInMeters(LatLng a, LatLng b) {
        double dLat = (b.latitude - a.latitude) * 111320.0;
        double dLng = (b.longitude - a.longitude) * 111320.0
                * Math.cos(Math.toRadians(a.latitude));
        return Math.sqrt(dLat * dLat + dLng * dLng);
    }

    //endregion

    //region Wall collision detection and response

    /**
     * Caches wall line segments for the given floor from the floorplan API data.
     * Extracts segments from features with indoorType "wall", handling both
     * LineString/MultiLineString and Polygon/MultiPolygon geometries.
     *
     * @param floor the floor index to cache walls for
     */
    private void cacheWallSegments(int floor) {
        cachedWallSegments = new ArrayList<>();
        cachedWallFloor = floor;
        cachedWallBuilding = currentBuilding;

        if (currentBuilding == BUILDING_CAMPUS) {
            // Campus mode: collect walls from both buildings
            int nucIdx = floor; // Nucleus uses unified campus index directly
            if (nucleusFloorShapes != null && nucIdx >= 0 && nucIdx < nucleusFloorShapes.size()) {
                addWallSegmentsFromFloor(nucleusFloorShapes.get(nucIdx));
            }
            int libIdx = campusToLibraryFloorIndex(floor);
            if (libraryFloorShapes != null && libIdx >= 0 && libIdx < libraryFloorShapes.size()) {
                addWallSegmentsFromFloor(libraryFloorShapes.get(libIdx));
            }
            Log.e(WALL_TAG, "Cached " + cachedWallSegments.size() + " wall segments (campus)"
                    + " | floor=" + floor + " | nucIdx=" + nucIdx + " | libIdx=" + libIdx);
            return;
        }

        if (currentFloorShapes == null || floor < 0 || floor >= currentFloorShapes.size()) {
            Log.e(WALL_TAG, "Cannot cache walls: no floor data"
                    + " | floor=" + floor + " | building=" + currentBuilding);
            return;
        }

        addWallSegmentsFromFloor(currentFloorShapes.get(floor));

        Log.e(WALL_TAG, "Cached " + cachedWallSegments.size() + " wall segments"
                + " | floor=" + floor
                + " | floorName=" + currentFloorShapes.get(floor).getDisplayName()
                + " | building=" + currentBuilding);
    }

    /**
     * Extracts wall segments from a single floor's features and adds them to
     * {@link #cachedWallSegments}.
     */
    private void addWallSegmentsFromFloor(FloorplanApiClient.FloorShapes floorData) {
        for (FloorplanApiClient.MapShapeFeature feature : floorData.getFeatures()) {
            if (!"wall".equals(feature.getIndoorType())) continue;

            String geoType = feature.getGeometryType();
            boolean isPolygon = "MultiPolygon".equals(geoType) || "Polygon".equals(geoType);

            for (List<LatLng> part : feature.getParts()) {
                if (part.size() < 2) continue;
                for (int i = 0; i < part.size() - 1; i++) {
                    cachedWallSegments.add(new LatLng[]{part.get(i), part.get(i + 1)});
                }
                if (isPolygon && part.size() >= 3) {
                    cachedWallSegments.add(new LatLng[]{part.get(part.size() - 1), part.get(0)});
                }
            }
        }
    }

    /**
     * Maps a campus unified floor index to the Library's floor index.
     * Campus index 1 (Nucleus G) = Library index 0 (Library G).
     * Returns -1 if no corresponding Library floor exists.
     */
    private int campusToLibraryFloorIndex(int campusFloor) {
        int libIdx = campusFloor - 1; // Nucleus LG is index 0, Library G is campus index 1
        if (libIdx < 0 || libraryFloorShapes == null || libIdx >= libraryFloorShapes.size()) {
            return -1;
        }
        return libIdx;
    }

    /**
     * Constrains movement to respect inner wall boundaries on the current floor.
     * Detects if the movement from previousPos to newPos crosses any wall segment:
     * <ul>
     *   <li>Side/glancing collision (angle > 60° from wall normal): slides along the wall</li>
     *   <li>Head-on collision (angle <= 60° from wall normal): bounces back from the wall</li>
     * </ul>
     *
     * @param previousPos the user's previous position (before this step)
     * @param newPos      the user's new unconstrained position
     * @return the constrained position, or newPos unchanged if no wall was crossed
     */
    public LatLng constrainToWalls(LatLng previousPos, LatLng newPos) {
        if (currentBuilding == BUILDING_NONE || currentFloorShapes == null
                || previousPos == null || newPos == null) {
            return newPos;
        }

        // Refresh cache if floor or building changed
        if (cachedWallFloor != currentFloor || cachedWallBuilding != currentBuilding) {
            cacheWallSegments(currentFloor);
        }

        if (cachedWallSegments == null || cachedWallSegments.isEmpty()) {
            return newPos;
        }

        // Use previousPos as reference origin for lat/lng -> meter conversion
        double refLat = previousPos.latitude;
        double cosLat = Math.cos(Math.toRadians(refLat));

        // Movement vector in meters (previousPos = origin)
        double moveE = (newPos.longitude - previousPos.longitude) * WALL_METERS_PER_DEG * cosLat;
        double moveN = (newPos.latitude - previousPos.latitude) * WALL_METERS_PER_DEG;
        double moveDist = Math.sqrt(moveE * moveE + moveN * moveN);

        if (moveDist < 0.01) return newPos; // No significant movement

        // Skip wall constraint for large fusion jumps (GPS/WiFi corrections)
        // — only constrain normal walking steps, not sensor fusion teleports
        if (moveDist > MAX_CONSTRAIN_DIST_M) {
            Log.e(WALL_TAG, "SKIP: moveDist=" + String.format("%.2f", moveDist)
                    + "m > " + MAX_CONSTRAIN_DIST_M + "m (fusion correction, not walking)");
            return newPos;
        }

        // Find the closest wall intersection along the movement vector
        double closestT = Double.MAX_VALUE;
        double[] hitWall = null; // [e1, n1, e2, n2] in meters

        for (LatLng[] wall : cachedWallSegments) {
            // Convert wall endpoints to meters relative to previousPos
            double wE1 = (wall[0].longitude - previousPos.longitude) * WALL_METERS_PER_DEG * cosLat;
            double wN1 = (wall[0].latitude - previousPos.latitude) * WALL_METERS_PER_DEG;
            double wE2 = (wall[1].longitude - previousPos.longitude) * WALL_METERS_PER_DEG * cosLat;
            double wN2 = (wall[1].latitude - previousPos.latitude) * WALL_METERS_PER_DEG;

            double wdE = wE2 - wE1;
            double wdN = wN2 - wN1;

            // 2D cross product of movement direction and wall direction
            double cross = moveE * wdN - moveN * wdE;
            if (Math.abs(cross) < 1e-10) continue; // Parallel — no intersection

            // Line-segment intersection parameters (t for movement, u for wall)
            double t = (wE1 * wdN - wN1 * wdE) / cross;
            double u = (moveN * wE1 - moveE * wN1) / cross;

            // Valid intersection: t ∈ (0, 1] on movement, u ∈ [0, 1] on wall
            if (t > 0.001 && t <= 1.0 && u >= -0.01 && u <= 1.01) {
                if (t < closestT) {
                    closestT = t;
                    hitWall = new double[]{wE1, wN1, wE2, wN2};
                }
            }
        }

        if (hitWall == null) {
            if (consecutiveCollisions > 0) {
                Log.e(WALL_TAG, "CLEAR: no collision, resetting counter (was " + consecutiveCollisions + ")");
            }
            consecutiveCollisions = 0;
            return newPos; // No wall crossed
        }

        consecutiveCollisions++;

        // Two-tier response (no release — never cross walls):
        // 1-3: bounce (normal wall response)
        // 4+:  force slide along wall (try to find a way around)
        boolean forceSlide = consecutiveCollisions > MAX_CONSECUTIVE_BOUNCE;

        // Intersection point (meters from previousPos)
        double intE = closestT * moveE;
        double intN = closestT * moveN;

        // Wall direction vector (normalized)
        double wallDE = hitWall[2] - hitWall[0];
        double wallDN = hitWall[3] - hitWall[1];
        double wallLen = Math.sqrt(wallDE * wallDE + wallDN * wallDN);
        if (wallLen < 0.001) return newPos;
        wallDE /= wallLen;
        wallDN /= wallLen;

        // Wall normal (perpendicular to wall), oriented toward user's previous position
        double normalE = -wallDN;
        double normalN = wallDE;
        if (normalE * (-intE) + normalN * (-intN) < 0) {
            normalE = -normalE;
            normalN = -normalN;
        }

        // Movement direction (normalized)
        double moveDirE = moveE / moveDist;
        double moveDirN = moveN / moveDist;

        // How "head-on" the collision is: 0 = parallel to wall, 1 = perpendicular
        double dotMoveNormal = Math.abs(moveDirE * normalE + moveDirN * normalN);

        double resultE, resultN;

        if (!forceSlide && dotMoveNormal > HEAD_ON_DOT_THRESHOLD) {
            // HEAD-ON collision: bounce back from wall
            resultE = intE + normalE * BOUNCE_DISTANCE_M;
            resultN = intN + normalN * BOUNCE_DISTANCE_M;

            Log.e(WALL_TAG, "BOUNCE | angle="
                    + String.format("%.1f", Math.toDegrees(Math.acos(Math.min(1.0, dotMoveNormal))))
                    + "deg | dot=" + String.format("%.3f", dotMoveNormal)
                    + " | hit=(" + String.format("%.2f", intE) + "," + String.format("%.2f", intN) + ")m"
                    + " | result=(" + String.format("%.2f", resultE) + "," + String.format("%.2f", resultN) + ")m"
                    + " | bounceBack=" + BOUNCE_DISTANCE_M + "m"
                    + " | moveDist=" + String.format("%.3f", moveDist) + "m");
        } else {
            // SLIDE: either glancing angle, or forced slide to prevent stuck-in-wall loop
            double remainE = moveE - intE;
            double remainN = moveN - intN;
            double slideAmount = remainE * wallDE + remainN * wallDN;

            resultE = intE + slideAmount * wallDE + normalE * WALL_BUFFER_M;
            resultN = intN + slideAmount * wallDN + normalN * WALL_BUFFER_M;

            Log.e(WALL_TAG, (forceSlide ? "FORCE_SLIDE" : "SLIDE") + " | angle="
                    + String.format("%.1f", Math.toDegrees(Math.acos(Math.min(1.0, dotMoveNormal))))
                    + "deg | dot=" + String.format("%.3f", dotMoveNormal)
                    + " | hit=(" + String.format("%.2f", intE) + "," + String.format("%.2f", intN) + ")m"
                    + " | slide=" + String.format("%.3f", slideAmount) + "m"
                    + " | result=(" + String.format("%.2f", resultE) + "," + String.format("%.2f", resultN) + ")m"
                    + " | moveDist=" + String.format("%.3f", moveDist) + "m");
        }

        // Convert result back to LatLng
        double resultLat = previousPos.latitude + resultN / WALL_METERS_PER_DEG;
        double resultLng = previousPos.longitude + resultE / (WALL_METERS_PER_DEG * cosLat);

        Log.e(WALL_TAG, "Position constrained"
                + " | original=(" + String.format("%.7f", newPos.latitude)
                + "," + String.format("%.7f", newPos.longitude) + ")"
                + " | constrained=(" + String.format("%.7f", resultLat)
                + "," + String.format("%.7f", resultLng) + ")"
                + " | floor=" + currentFloor + " | building=" + currentBuilding
                + " | wallSegments=" + cachedWallSegments.size());

        return new LatLng(resultLat, resultLng);
    }

    //endregion

    /**
     * Sets the floor to display. When called from auto-floor, the floor number
     * is a logical floor (0=G, -1=LG, 1=Floor 1, etc.) and the building bias
     * is applied. When called manually, the floor number is the direct index.
     *
     * @param newFloor  the floor the user is at
     * @param autoFloor true if called by auto-floor feature
     */
    public void setCurrentFloor(int newFloor, boolean autoFloor) {
        int maxFloors = getMaxFloorCount();
        if (maxFloors <= 0) return;

        if (autoFloor) {
            newFloor += getAutoFloorBias();
        }

        if (newFloor >= 0 && newFloor < maxFloors
                && newFloor != this.currentFloor) {
            this.currentFloor = newFloor;
            drawFloorShapes(newFloor);
        }
    }

    /**
     * Returns the total number of displayable floors.
     * In campus mode, uses the Nucleus floor count (the building with more floors).
     */
    private int getMaxFloorCount() {
        if (currentBuilding == BUILDING_CAMPUS) {
            return (nucleusFloorShapes != null) ? nucleusFloorShapes.size() : 0;
        }
        return (currentFloorShapes != null) ? currentFloorShapes.size() : 0;
    }

    /**
     * Increments the current floor and changes to a higher floor's map
     * (if a higher floor exists).
     */
    public void increaseFloor() {
        this.setCurrentFloor(currentFloor + 1, false);
    }

    /**
     * Decrements the current floor and changes to the lower floor's map
     * (if a lower floor exists).
     */
    public void decreaseFloor() {
        this.setCurrentFloor(currentFloor - 1, false);
    }

    /**
     * Sets the map overlay for the building if the user's current location is
     * inside a building and the overlay is not already set. Removes the overlay
     * if the user leaves all buildings.
     *
     * <p>Detection priority: floorplan API real polygon outlines first,
     * then legacy hard-coded rectangular boundaries as fallback.</p>
     */
    private void setBuildingOverlay() {
        try {
            // When outside, use shrunk boundary for entry (harder to falsely enter);
            // when inside, use normal boundary for the initial check.
            double entryBuffer = isIndoorMapSet ? 0.0 : ENTRY_BUFFER_METERS;
            int detected = detectCurrentBuilding(entryBuffer);

            // Promote Nucleus or Library to Campus so both maps load together
            if (detected == BUILDING_NUCLEUS || detected == BUILDING_LIBRARY) {
                detected = BUILDING_CAMPUS;
            }

            boolean inAnyBuilding = (detected != BUILDING_NONE);

            if (inAnyBuilding && !isIndoorMapSet) {
                currentBuilding = detected;

                if (detected == BUILDING_CAMPUS) {
                    // Load BOTH buildings' floor shapes
                    FloorplanApiClient.BuildingInfo nucleusInfo =
                            SensorFusion.getInstance().getFloorplanBuilding("nucleus_building");
                    FloorplanApiClient.BuildingInfo libraryInfo =
                            SensorFusion.getInstance().getFloorplanBuilding("library");

                    nucleusFloorShapes = (nucleusInfo != null)
                            ? nucleusInfo.getFloorShapesList() : null;
                    libraryFloorShapes = (libraryInfo != null)
                            ? libraryInfo.getFloorShapesList() : null;

                    // Primary floor shapes = Nucleus (more floors)
                    currentFloorShapes = nucleusFloorShapes;
                    currentFloor = 1; // Ground floor in Nucleus indexing (LG=0, G=1)
                    floorHeight = NUCLEUS_FLOOR_HEIGHT;

                    if (getMaxFloorCount() > 0) {
                        drawFloorShapes(currentFloor);
                        isIndoorMapSet = true;
                        Log.e(TAG, "Campus mode activated: Nucleus floors="
                                + (nucleusFloorShapes != null ? nucleusFloorShapes.size() : 0)
                                + " Library floors="
                                + (libraryFloorShapes != null ? libraryFloorShapes.size() : 0));
                    }
                } else {
                    // Murchison (unchanged)
                    currentFloor = 1;
                    floorHeight = MURCHISON_FLOOR_HEIGHT;

                    FloorplanApiClient.BuildingInfo building =
                            SensorFusion.getInstance().getFloorplanBuilding("murchison_house");
                    if (building != null) {
                        currentFloorShapes = building.getFloorShapesList();
                    }

                    if (currentFloorShapes != null && !currentFloorShapes.isEmpty()) {
                        drawFloorShapes(currentFloor);
                        isIndoorMapSet = true;
                    }
                }

            } else if (!inAnyBuilding && isIndoorMapSet) {
                // Use expanded boundary for exit — only clear when truly outside
                if (!isInsideCurrentBuildingWithBuffer()) {
                    clearDrawnShapes();
                    isIndoorMapSet = false;
                    currentBuilding = BUILDING_NONE;
                    currentFloor = 0;
                    currentFloorShapes = null;
                    nucleusFloorShapes = null;
                    libraryFloorShapes = null;
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error with overlay: " + ex.toString());
        }
    }

    /**
     * Draws all vector shapes for the given floor index on the Google Map.
     * Clears any previously drawn shapes before drawing the new floor.
     *
     * @param floorIndex the floor index (0-based, matching FloorShapes list order)
     */
    private void drawFloorShapes(int floorIndex) {
        clearDrawnShapes();

        if (currentBuilding == BUILDING_CAMPUS) {
            // Campus mode: draw shapes from both Nucleus and Library
            if (nucleusFloorShapes != null && floorIndex >= 0
                    && floorIndex < nucleusFloorShapes.size()) {
                drawSingleFloorFeatures(nucleusFloorShapes.get(floorIndex));
            }
            int libIdx = campusToLibraryFloorIndex(floorIndex);
            if (libraryFloorShapes != null && libIdx >= 0
                    && libIdx < libraryFloorShapes.size()) {
                drawSingleFloorFeatures(libraryFloorShapes.get(libIdx));
            }
            return;
        }

        if (currentFloorShapes == null || floorIndex < 0
                || floorIndex >= currentFloorShapes.size()) return;

        drawSingleFloorFeatures(currentFloorShapes.get(floorIndex));
    }

    /**
     * Draws all features of a single floor onto the map (without clearing first).
     */
    private void drawSingleFloorFeatures(FloorplanApiClient.FloorShapes floor) {
        for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
            String geoType = feature.getGeometryType();
            String indoorType = feature.getIndoorType();

            if ("MultiPolygon".equals(geoType) || "Polygon".equals(geoType)) {
                for (List<LatLng> ring : feature.getParts()) {
                    if (ring.size() < 3) continue;
                    Polygon p = gMap.addPolygon(new PolygonOptions()
                            .addAll(ring)
                            .strokeColor(getStrokeColor(indoorType))
                            .strokeWidth(5f)
                            .fillColor(getFillColor(indoorType)));
                    drawnPolygons.add(p);
                }
            } else if ("MultiLineString".equals(geoType)
                    || "LineString".equals(geoType)) {
                for (List<LatLng> line : feature.getParts()) {
                    if (line.size() < 2) continue;
                    Polyline pl = gMap.addPolyline(new PolylineOptions()
                            .addAll(line)
                            .color(getStrokeColor(indoorType))
                            .width(6f));
                    drawnPolylines.add(pl);
                }
            }
        }
    }

    /**
     * Removes all vector shapes currently drawn on the map.
     */
    private void clearDrawnShapes() {
        for (Polygon p : drawnPolygons) p.remove();
        for (Polyline p : drawnPolylines) p.remove();
        drawnPolygons.clear();
        drawnPolylines.clear();
    }

    /**
     * Returns the stroke colour for a given indoor feature type.
     *
     * @param indoorType the indoor_type property value
     * @return ARGB colour value
     */
    private int getStrokeColor(String indoorType) {
        if ("wall".equals(indoorType)) return WALL_STROKE;
        if ("room".equals(indoorType)) return ROOM_STROKE;
        if ("stairs".equals(indoorType)) return STAIRS_STROKE;
        if ("lift".equals(indoorType)) return LIFT_STROKE;
        return DEFAULT_STROKE;
    }

    /**
     * Returns the fill colour for a given indoor feature type.
     *
     * @param indoorType the indoor_type property value
     * @return ARGB colour value
     */
    private int getFillColor(String indoorType) {
        if ("room".equals(indoorType)) return ROOM_FILL;
        if ("stairs".equals(indoorType)) return STAIRS_FILL;
        if ("lift".equals(indoorType)) return LIFT_FILL;
        return Color.TRANSPARENT;
    }

    /**
     * Detects which building the user is currently in, optionally applying a
     * buffer to the boundary polygons. A negative buffer shrinks the polygon
     * (for stricter entry detection); zero uses the original boundary.
     *
     * @param bufferMeters buffer in meters (negative = shrink, 0 = original)
     * @return building type constant, or {@link #BUILDING_NONE}
     */
    private int detectCurrentBuilding(double bufferMeters) {
        // Phase 1: API real polygon outlines
        List<FloorplanApiClient.BuildingInfo> apiBuildings =
                SensorFusion.getInstance().getFloorplanBuildings();
        for (FloorplanApiClient.BuildingInfo building : apiBuildings) {
            List<LatLng> outline = building.getOutlinePolygon();
            if (outline != null && outline.size() >= 3) {
                List<LatLng> poly = (bufferMeters != 0.0)
                        ? BuildingPolygon.expandPolygon(outline, bufferMeters)
                        : outline;
                if (BuildingPolygon.pointInPolygon(currentLocation, poly)) {
                    int type = resolveBuildingType(building.getName());
                    if (type != BUILDING_NONE) return type;
                }
            }
        }

        // Phase 2: legacy hard-coded fallback
        if (checkLegacyBuilding(BuildingPolygon.NUCLEUS_POLYGON, bufferMeters))
            return BUILDING_NUCLEUS;
        if (checkLegacyBuilding(BuildingPolygon.LIBRARY_POLYGON, bufferMeters))
            return BUILDING_LIBRARY;
        if (checkLegacyBuilding(BuildingPolygon.MURCHISON_POLYGON, bufferMeters))
            return BUILDING_MURCHISON;

        return BUILDING_NONE;
    }

    /** Checks if currentLocation is inside a legacy polygon with optional buffer. */
    private boolean checkLegacyBuilding(List<LatLng> polygon, double bufferMeters) {
        List<LatLng> poly = (bufferMeters != 0.0)
                ? BuildingPolygon.expandPolygon(polygon, bufferMeters)
                : polygon;
        return BuildingPolygon.pointInPolygon(currentLocation, poly);
    }

    /**
     * Maps a floorplan API building name to a building type constant.
     *
     * @param apiName building name from API (e.g. "nucleus_building")
     * @return building type constant, or {@link #BUILDING_NONE} if unrecognised
     */
    private int resolveBuildingType(String apiName) {
        if (apiName == null) return BUILDING_NONE;
        switch (apiName) {
            case "nucleus_building": return BUILDING_NUCLEUS;
            case "murchison_house":  return BUILDING_MURCHISON;
            case "library":          return BUILDING_LIBRARY;
            default:                 return BUILDING_NONE;
        }
    }

    /**
     * Checks whether the current location is inside the current building's
     * boundary expanded by {@link #EXIT_BUFFER_METERS}. Used for exit
     * hysteresis so the floor plan stays visible when the user is near the
     * outer wall rather than clearly outside.
     *
     * @return true if inside the expanded boundary of the current building
     */
    private boolean isInsideCurrentBuildingWithBuffer() {
        if (currentBuilding == BUILDING_CAMPUS) {
            // Campus exit: must leave the expanded campus bounding rectangle
            List<LatLng> expanded =
                    BuildingPolygon.expandPolygon(BuildingPolygon.CAMPUS_POLYGON, EXIT_BUFFER_METERS);
            return BuildingPolygon.pointInPolygon(currentLocation, expanded);
        }

        String targetApiName;
        List<LatLng> fallbackPolygon;
        switch (currentBuilding) {
            case BUILDING_NUCLEUS:
                targetApiName = "nucleus_building";
                fallbackPolygon = BuildingPolygon.NUCLEUS_POLYGON;
                break;
            case BUILDING_LIBRARY:
                targetApiName = "library";
                fallbackPolygon = BuildingPolygon.LIBRARY_POLYGON;
                break;
            case BUILDING_MURCHISON:
                targetApiName = "murchison_house";
                fallbackPolygon = BuildingPolygon.MURCHISON_POLYGON;
                break;
            default:
                return false;
        }

        // Try API polygon with buffer first
        List<FloorplanApiClient.BuildingInfo> apiBuildings =
                SensorFusion.getInstance().getFloorplanBuildings();
        for (FloorplanApiClient.BuildingInfo building : apiBuildings) {
            if (targetApiName.equals(building.getName())) {
                List<LatLng> outline = building.getOutlinePolygon();
                if (outline != null && outline.size() >= 3) {
                    List<LatLng> expanded =
                            BuildingPolygon.expandPolygon(outline, EXIT_BUFFER_METERS);
                    return BuildingPolygon.pointInPolygon(currentLocation, expanded);
                }
            }
        }

        // Fallback: legacy hard-coded polygon with buffer
        List<LatLng> expanded =
                BuildingPolygon.expandPolygon(fallbackPolygon, EXIT_BUFFER_METERS);
        return BuildingPolygon.pointInPolygon(currentLocation, expanded);
    }

    /**
     * Draws green polyline indicators around all buildings with available
     * indoor floor maps. Uses floorplan API outlines when available,
     * falls back to legacy hard-coded polygons otherwise.
     */
    public void setIndicationOfIndoorMap() {
        List<FloorplanApiClient.BuildingInfo> apiBuildings =
                SensorFusion.getInstance().getFloorplanBuildings();

        boolean nucleusDrawn = false, libraryDrawn = false, murchisonDrawn = false;

        // Phase 1: draw API outlines
        for (FloorplanApiClient.BuildingInfo building : apiBuildings) {
            List<LatLng> outline = building.getOutlinePolygon();
            if (outline == null || outline.size() < 3) continue;

            List<LatLng> closed = new ArrayList<>(outline);
            closed.add(closed.get(0));
            gMap.addPolyline(new PolylineOptions().color(Color.GREEN).addAll(closed));

            switch (building.getName()) {
                case "nucleus_building": nucleusDrawn = true; break;
                case "library":          libraryDrawn = true; break;
                case "murchison_house":  murchisonDrawn = true; break;
            }
        }

        // Phase 2: fallback for buildings not covered by API
        if (!nucleusDrawn) {
            List<LatLng> pts = new ArrayList<>(BuildingPolygon.NUCLEUS_POLYGON);
            pts.add(pts.get(0));
            gMap.addPolyline(new PolylineOptions().color(Color.GREEN).addAll(pts));
        }
        if (!libraryDrawn) {
            List<LatLng> pts = new ArrayList<>(BuildingPolygon.LIBRARY_POLYGON);
            pts.add(pts.get(0));
            gMap.addPolyline(new PolylineOptions().color(Color.GREEN).addAll(pts));
        }
        if (!murchisonDrawn) {
            List<LatLng> pts = new ArrayList<>(BuildingPolygon.MURCHISON_POLYGON);
            pts.add(pts.get(0));
            gMap.addPolyline(new PolylineOptions().color(Color.GREEN).addAll(pts));
        }
    }
}
