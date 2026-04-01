package com.openpositioning.PositionMe.sensors;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.utils.BuildingPolygon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Seperated in a different class for modularity.
 * Loads and stores floorplan geometry for the current building.
 * Provides wall data to ParticleFilter, stair/lift centroids to SensorFusion,
 * and floor index resolution combining WiFi with barometer overrides.
 * Handles buildings where the API floor list is not in physical order
 * (e.g. Nucleus stores GF at index 4).
 *
 * Call tryLoadBuilding() once at the start of a recording to initialise.
 */
class MapMatcher {

    private static final String TAG_LOAD = "MapMatcher";

    // Equatorial approximation: metres per degree of lat/lon. Matches the constant in ParticleFilter.
    private static final double METERS_PER_DEGREE = 111111.0;



    // A single wall polygon or polyline from the floorplan API, converted to local metres.
    static class WallFeature {
        // Vertices as {eastingM, northingM} relative to the particle filter origin
        final List<float[]> localPoints;
        // True for filled Polygon/MultiPolygon shapes, false for LineString wall segments
        final boolean isPolygon;

        WallFeature(List<float[]> pts, boolean isPolygon) {
            this.localPoints = pts;
            this.isPolygon   = isPolygon;
        }
    }


    private final SensorFusion sensorFusion;

    // Set by tryLoadBuilding(); null/zero until loading succeeds
    private String  loadedBuildingId = null;
    private LatLng  mapOrigin        = null;
    private int     numFloors        = 0;

    // displayName ("GF", "F1", "LG" etc.) -> floorShapes list index.
    // Built at load time. Used by getAdjacentFloorIndex() for reverse-lookup.
    private Map<String, Integer> displayNameToIndex = null;

    // Arrays indexed [0..numFloors-1], one list per floor.
    // All three are null until tryLoadBuilding() completes.
    @SuppressWarnings("unchecked")
    private List<WallFeature>[] wallsByFloor = null;

    // Centroids of staircase and lift features on each floor, in local metres.
    // Used by SensorFusion.isNearTransition() to gate barometer floor changes.
    @SuppressWarnings("unchecked")
    private List<float[]>[] stairCentersByFloor = null;
    @SuppressWarnings("unchecked")
    private List<float[]>[] liftCentersByFloor = null;

    // When SensorFusion accepts a barometer floor change, it calls setCurrentFloor() to
    // store the confirmed API index here. -1 means no override, fall back to WiFi.
    private int currentFloorOverride = -1;

    // Physical floor number (LG=-1, GF=0, F1=1 etc...) -> API floorShapes list index.
    // Built at load time. Needed because some buildings store floors out of physical order
    // (e.g. Nucleus: (LG, F1, F2, F3, GF) so GF is at index 4, not 0).
    private Map<Integer, Integer> physicalToApiIndex = null;



    /**
     * Creates a MapMatcher. No geometry is loaded until tryLoadBuilding() is called.
     */
    MapMatcher(SensorFusion sf) {
        this.sensorFusion = sf;
    }


    /**
     * Loads wall, stair, and lift features from the floorplan cache for the current building.
     *
     * Building is selected in order: preferred name first (if provided), then
     * point-in-polygon check against all cached building outlines, then closest center
     * as fallback. Returns early if origin is null or no building is found in the cache.
     *
     * On success, builds the displayName and physicalFloor index lookup maps, converts
     * all floor geometry to local metres, and runs MapGeometry.selfTest().
     *
     */
    @SuppressWarnings("unchecked")
    void tryLoadBuilding(String preferredBuildingId, LatLng origin) {
        if (origin == null) {
            Log.w(TAG_LOAD, "tryLoadBuilding: origin is null — aborting");
            return;
        }

        // Preferred building by name
        FloorplanApiClient.BuildingInfo building = null;
        if (preferredBuildingId != null && !preferredBuildingId.isEmpty()) {
            building = sensorFusion.getFloorplanBuilding(preferredBuildingId);
            if (building != null) {
                Log.d(TAG_LOAD, "Found preferred building: " + preferredBuildingId);
            }
        }

        // Polygon detection against all cached buildings
        if (building == null) {
            for (FloorplanApiClient.BuildingInfo b : sensorFusion.getFloorplanBuildings()) {
                List<LatLng> outline = b.getOutlinePolygon();
                if (outline != null && outline.size() >= 3
                        && BuildingPolygon.pointInPolygon(origin, outline)) {
                    building = b;
                    Log.d(TAG_LOAD, "Building detected via polygon: " + b.getName());
                    break;
                }
            }
        }

        // Closest center fallback
        if (building == null) {
            building = closestBuilding(origin);
            if (building != null) {
                Log.d(TAG_LOAD, "Building selected by closest center: " + building.getName());
            }
        }

        if (building == null) {
            Log.w(TAG_LOAD, "tryLoadBuilding: no building found in cache — map matching disabled");
            return;
        }

        List<FloorplanApiClient.FloorShapes> floorShapes = building.getFloorShapesList();
        if (floorShapes == null || floorShapes.isEmpty()) {
            Log.w(TAG_LOAD, "tryLoadBuilding: building has no floor shapes");
            return;
        }

        // Allocate per-floor lists
        numFloors = floorShapes.size();
        mapOrigin = origin;
        loadedBuildingId = building.getName();

        // Build both lookup maps: displayName to index (for adjacent-floor arithmetic)
        // and physicalFloor to index (for WiFi floor conversion)
        displayNameToIndex = new HashMap<>();
        physicalToApiIndex = new HashMap<>();
        for (int f = 0; f < numFloors; f++) {
            String name = floorShapes.get(f).getDisplayName();
            if (name != null) {
                displayNameToIndex.put(name, f);
                physicalToApiIndex.put(displayNameToPhysical(name), f);
            }
        }

        wallsByFloor       = new List[numFloors];
        stairCentersByFloor = new List[numFloors];
        liftCentersByFloor  = new List[numFloors];
        currentFloorOverride = -1;

        for (int f = 0; f < numFloors; f++) {
            wallsByFloor[f]        = new ArrayList<>();
            stairCentersByFloor[f] = new ArrayList<>();
            liftCentersByFloor[f]  = new ArrayList<>();

            FloorplanApiClient.FloorShapes floor = floorShapes.get(f);
            // Sort features into walls (for crossing checks) and
            // stairs/lifts (centroid stored for proximity gating).
            for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
                String type = feature.getIndoorType();

                if ("wall".equals(type)) {
                    String geoType = feature.getGeometryType();
                    boolean isPoly = "MultiPolygon".equals(geoType) || "Polygon".equals(geoType);

                    for (List<LatLng> part : feature.getParts()) {
                        List<float[]> localPts = new ArrayList<>(part.size());
                        for (LatLng ll : part) {
                            localPts.add(latLngToLocal(ll, origin));
                        }
                        if (localPts.size() < 2) continue;
                        wallsByFloor[f].add(new WallFeature(localPts, isPoly));
                    }

                } else if ("stairs".equals(type) || "staircase".equals(type)) {
                    for (List<LatLng> part : feature.getParts()) {
                        float[] centroid = computeCentroidLocal(part, origin);
                        if (centroid != null) stairCentersByFloor[f].add(centroid);
                    }

                } else if ("lift".equals(type) || "elevator".equals(type)) {
                    for (List<LatLng> part : feature.getParts()) {
                        float[] centroid = computeCentroidLocal(part, origin);
                        if (centroid != null) liftCentersByFloor[f].add(centroid);
                    }
                }
            }

            Log.d(TAG_LOAD, String.format("Floor %d (%s): walls=%d stairs=%d lifts=%d",
                    f, floor.getDisplayName(),
                    wallsByFloor[f].size(),
                    stairCentersByFloor[f].size(),
                    liftCentersByFloor[f].size()));
        }

        Log.d(TAG_LOAD, "Loaded: building=" + loadedBuildingId
                + "  floors=" + numFloors);

        // Step 2: run geometry self-test
        MapGeometry.selfTest();
    }

    /**
     * Returns true once tryLoadBuilding() has successfully parsed at least one floor.
     */
    boolean isInitialised() {
        return loadedBuildingId != null && mapOrigin != null && numFloors > 0;
    }


    // Returns the wall features for the given API floor index, or an empty list if out of range.
    List<WallFeature> getWallsForFloor(int floorIndex) {
        if (wallsByFloor == null || floorIndex < 0 || floorIndex >= numFloors) {
            return new ArrayList<>();
        }
        return wallsByFloor[floorIndex];
    }



    // Returns the building name that was loaded, or null if tryLoadBuilding() has not succeeded yet.
    String getLoadedBuildingId() {
        return loadedBuildingId;
    }

    /**
     * Returns the current API floorShapes index. Index 0 = floorShapesList[0], etc.
     *
     * A barometer-confirmed override set by SensorFusion takes priority over the WiFi
     * reading. When no override is set, the WiFi floor integer is converted to an API
     * index via physicalToApiIndex. Falls back to the bias formula if the lookup map
     * is not ready yet (Nucleus/Murchison: WiFi 0 maps to index 1; others: index 0).
     */
    int getLikelyFloorIndex() {
        // Barometer override beats WiFi when SensorFusion has confirmed a floor change
        if (currentFloorOverride >= 0 && currentFloorOverride < numFloors) {
            Log.d(TAG_LOAD, "getLikelyFloorIndex: using barometer override=" + currentFloorOverride);
            return currentFloorOverride;
        }
        int wifiFloor = sensorFusion.getWifiFloor();
        // physicalToApiIndex handles buildings where the API list is not in physical order
        // (e.g. Nucleus: (LG, F1, F2, F3, GF) - WiFi 0 = GF = API index 4, not index 1).
        // Falls back to the bias formula when the map has not loaded yet.
        Integer apiIdx = (physicalToApiIndex != null) ? physicalToApiIndex.get(wifiFloor) : null;
        int result = (apiIdx != null)
                ? apiIdx
                : Math.max(0, Math.min(numFloors - 1, wifiFloor + getAutoFloorBias()));
        Log.d(TAG_LOAD, "getLikelyFloorIndex: wifiFloor=" + wifiFloor
                + " bias=" + getAutoFloorBias() + " index=" + result);
        return result;
    }

    /**
     * Returns the index offset between WiFi floor 0 (GF) and its position in the API floor list.
     * Nucleus and Murchison store LG at index 0, so GF is at index 1 (bias = 1).
     * All other buildings place GF at index 0 (bias = 0).
     */
    int getAutoFloorBias() {
        if ("nucleus_building".equals(loadedBuildingId)
                || "murchison_house".equals(loadedBuildingId)) {
            return 1;
        }
        return 0;
    }

    /**
     * Returns the floor-to-floor height in metres for the loaded building.
     * SensorFusion divides the accumulated barometer elevation change by this value
     * to determine how many floors the user has moved.
     */
    float getFloorHeight() {
        if ("nucleus_building".equals(loadedBuildingId)) return 5.0f;
        if ("murchison_house".equals(loadedBuildingId)) return 4.0f;
        if ("library".equals(loadedBuildingId)) return 3.6f;
        return 4.0f; // generic fallback
    }

    /**
     * Records a barometer-confirmed floor index so getLikelyFloorIndex() returns it
     * instead of the WiFi estimate. Called by SensorFusion.checkAndApplyFloorChange().
     * Pass -1 to clear the override and let WiFi drive floor selection again.
     */
    void setCurrentFloor(int floor) {
        currentFloorOverride = floor;
    }

    // Returns the staircase centroids in local metres for floor f, or an empty list.
    List<float[]> getStairCentersForFloor(int f) {
        if (stairCentersByFloor == null || f < 0 || f >= numFloors) return new ArrayList<>();
        return stairCentersByFloor[f];
    }

    // Returns the lift centroids in local metres for floor f, or an empty list.
    List<float[]> getLiftCentersForFloor(int f) {
        if (liftCentersByFloor == null || f < 0 || f >= numFloors) return new ArrayList<>();
        return liftCentersByFloor[f];
    }

    /**
     * Converts a physical floor number (LG=-1, GF=0, F1=1, F2=2, etc ...) to its
     * position in the API floorShapes list. Returns -1 if not found.
     *
     * SensorFusion calls this when WiFi changes floor rather than going through
     * getLikelyFloorIndex(), because getLikelyFloorIndex() already reflects the
     * new WiFi value and combining it with getAdjacentFloorIndex() would shift
     * one floor too far.
     */
    int physicalFloorToApiIndex(int physicalFloor) {
        if (physicalToApiIndex == null) return -1;
        Integer idx = physicalToApiIndex.get(physicalFloor);
        return (idx != null) ? idx : -1;
    }

    /**
     * Converts a WiFi floor integer to the display name used in the floorplan API.
     *
     * For Nucleus and Murchison, the API stores floors in this order:
     *   index 0=LG, 1=F1, 2=F2, 3=F3, 4=GF
     * GF is at index 4, so this cannot simply call String.valueOf(wifiFloor).
     * For other buildings, the WiFi integer is used as a plain string ("0", "1", etc.).
     */
    private String wifiFloorToDisplayName(int wifiFloor) {
        if ("nucleus_building".equals(loadedBuildingId)
                || "murchison_house".equals(loadedBuildingId)) {
            switch (wifiFloor) {
                case 0: return "LG";
                case 1: return "F1";
                case 2: return "F2";
                case 3: return "F3";
                case 4: return "GF";
                default:
                    // Unknown floor — infer from barometer elevation
                    return (sensorFusion.getElevation() < -1.5f) ? "LG" : "GF";
            }
        }
        return String.valueOf(wifiFloor);
    }


    /**
     * Returns the API index that is physicalDelta floors above/below currentApiIndex.
     * Can't just do +/-1 on the index for buildings with non-physical API ordering
     * (e.g. Nucleus GF is at index 4, not 0), so we convert via display name instead.
     * Fixes the GF/F1 off-by-one seen in the auto-floor feature.
     * Returns currentApiIndex unchanged if the target floor doesn't exist.
     */
    int getAdjacentFloorIndex(int currentApiIndex, int physicalDelta) {
        if (physicalToApiIndex == null || displayNameToIndex == null) return currentApiIndex;
        // Reverse-lookup: find the display name that maps to this API index
        for (Map.Entry<String, Integer> e : displayNameToIndex.entrySet()) {
            if (e.getValue() == currentApiIndex) {
                int currentPhysical = displayNameToPhysical(e.getKey());
                int targetPhysical  = currentPhysical + physicalDelta;
                Integer targetApi   = physicalToApiIndex.get(targetPhysical);
                return (targetApi != null) ? targetApi : currentApiIndex;
            }
        }
        return currentApiIndex;
    }

    /**
     * Converts a floor display name to a physical floor number.
     * LG = -1, GF = 0, F1 = 1, F2 = 2, F3 = 3
     * Unknown names default to 0.
     */
    private static int displayNameToPhysical(String name) {
        if (name == null) return 0;
        switch (name) {
            case "LG": return -1;
            case "GF": return 0;
            default:
                if (name.length() > 1 && name.charAt(0) == 'F') {
                    try { return Integer.parseInt(name.substring(1)); }
                    catch (NumberFormatException ignored) {}
                }
                try { return Integer.parseInt(name); }
                catch (NumberFormatException ignored) { return 0; }
        }
    }

    /**
     * Computes the centroid of a set of LatLng points in local metres.
     * Used to get a single representative point for stairs/lift polygons.
     * Returns null if pts is null or empty.
     */
    private static float[] computeCentroidLocal(List<LatLng> pts, LatLng origin) {
        if (pts == null || pts.isEmpty()) return null;
        float sumE = 0f, sumN = 0f;
        for (LatLng ll : pts) {
            float[] local = latLngToLocal(ll, origin);
            sumE += local[0];
            sumN += local[1];
        }
        return new float[]{sumE / pts.size(), sumN / pts.size()};
    }

    /**
     * Converts a WGS84 LatLng to a local easting/northing offset in metres
     * relative to the particle filter origin. Uses the same equirectangular
     * approximation as ParticleFilter.latLngToLocal() to keep coordinate frames
     * consistent between the two classes.
     */
    private static float[] latLngToLocal(LatLng point, LatLng origin) {
        double latDiff = point.latitude  - origin.latitude;
        double lonDiff = point.longitude - origin.longitude;
        float northing = (float) (latDiff * METERS_PER_DEGREE);
        float easting  = (float) (lonDiff * METERS_PER_DEGREE
                * Math.cos(Math.toRadians(origin.latitude)));
        return new float[]{easting, northing};
    }

    /**
     * Returns the cached building whose centre is closest to origin.
     * Used as a last resort when no building polygon contains the origin point.
     * Distance is compared as squared lat/lon degrees (no sqrt needed for ordering).
     */
    private FloorplanApiClient.BuildingInfo closestBuilding(LatLng origin) {
        FloorplanApiClient.BuildingInfo best = null;
        double bestDist = Double.MAX_VALUE;
        for (FloorplanApiClient.BuildingInfo b : sensorFusion.getFloorplanBuildings()) {
            LatLng center = b.getCenter();
            double dl = center.latitude  - origin.latitude;
            double dn = center.longitude - origin.longitude;
            double dist = dl * dl + dn * dn;
            if (dist < bestDist) {
                bestDist = dist;
                best = b;
            }
        }
        return best;
    }
}