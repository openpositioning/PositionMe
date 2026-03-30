package com.openpositioning.PositionMe.sensors;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.utils.BuildingPolygon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


class MapMatcher {

    private static final String TAG_LOAD = "MapMatcher";

    /** Constant: earth meters per degree (matches ParticleFilter). */
    private static final double METERS_PER_DEGREE = 111111.0;

    // =========================================================================
    // Inner data class
    // =========================================================================

    /**
     * A single wall polygon/polyline in the local coordinate frame.
     */
    static class WallFeature {
        /** Vertices as float[]{eastingM, northingM} in the local frame. */
        final List<float[]> localPoints;
        /** True for MultiPolygon/Polygon features; false for LineString features. */
        final boolean isPolygon;

        WallFeature(List<float[]> pts, boolean isPolygon) {
            this.localPoints = pts;
            this.isPolygon   = isPolygon;
        }
    }

    // =========================================================================
    // State
    // =========================================================================

    private final SensorFusion sensorFusion;

    // Null until tryLoadBuilding() succeeds
    private String  loadedBuildingId = null;
    private LatLng  mapOrigin        = null;
    private int     numFloors        = 0;
    // Maps floor displayName ("GF", "F1", ...) to its floorShapes index.
    // Built at load time; used by getLikelyFloorIndex() to resolve WiFi floor integers.
    private Map<String, Integer> displayNameToIndex = null;

    // Per-floor wall feature arrays (size = numFloors, indexed 0..numFloors-1)
    // Null until tryLoadBuilding() succeeds; getWallsForFloor() guards against this.
    @SuppressWarnings("unchecked")
    private List<WallFeature>[] wallsByFloor = null;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a MapMatcher that reads data from the given SensorFusion instance.
     * No loading is performed until tryLoadBuilding() is called.
     *
     * @param sf the SensorFusion singleton
     */
    MapMatcher(SensorFusion sf) {
        this.sensorFusion = sf;
    }

    // =========================================================================
    // Step 1: loading
    // =========================================================================

    /**
     * Loads wall/stair/lift features from the floorplan cache for the current building.
     *
     * Detection order:
     * 1. Direct look-up by preferredBuildingId if non-null.
     * 2. Ray-cast against API outline polygons using origin.
     * 3. Closest building center as last resort.
     *
     * Silently returns if origin is null or no building is found.
     * Runs the MapGeometry self-test (MapGeometry.selfTest()) on success.
     *
     * @param preferredBuildingId building name key (e.g. "nucleus_building"); may be null
     * @param origin              local coordinate frame origin from ParticleFilter
     */
    @SuppressWarnings("unchecked")
    void tryLoadBuilding(String preferredBuildingId, LatLng origin) {
        if (origin == null) {
            Log.w(TAG_LOAD, "tryLoadBuilding: origin is null — aborting");
            return;
        }

        // 1. Preferred building by name
        FloorplanApiClient.BuildingInfo building = null;
        if (preferredBuildingId != null && !preferredBuildingId.isEmpty()) {
            building = sensorFusion.getFloorplanBuilding(preferredBuildingId);
            if (building != null) {
                Log.d(TAG_LOAD, "Found preferred building: " + preferredBuildingId);
            }
        }

        // 2. Polygon detection against all cached buildings
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

        // 3. Closest center fallback
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

        // Build displayName → floorIndex map for WiFi floor resolution
        displayNameToIndex = new HashMap<>();
        for (int f = 0; f < numFloors; f++) {
            String name = floorShapes.get(f).getDisplayName();
            if (name != null) displayNameToIndex.put(name, f);
        }

        wallsByFloor = new List[numFloors];

        for (int f = 0; f < numFloors; f++) {
            wallsByFloor[f] = new ArrayList<>();

            FloorplanApiClient.FloorShapes floor = floorShapes.get(f);
            for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
                if (!"wall".equals(feature.getIndoorType())) continue;

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
            }

            Log.d(TAG_LOAD, String.format("Floor %d (%s): walls=%d",
                    f, floor.getDisplayName(), wallsByFloor[f].size()));
        }

        Log.d(TAG_LOAD, "Loaded: building=" + loadedBuildingId
                + "  floors=" + numFloors);

        // Step 2: run geometry self-test
        MapGeometry.selfTest();
    }

    /**
     * Returns true once building data has been successfully loaded.
     * All three conditions must hold: building ID set, origin set, and at least one floor parsed.
     */
    boolean isInitialised() {
        return loadedBuildingId != null && mapOrigin != null && numFloors > 0;
    }

    // =========================================================================
    // Step 2: floor accessor
    // =========================================================================

    /** Returns the wall features for the given floor, or an empty list if out of range. */
    List<WallFeature> getWallsForFloor(int floorIndex) {
        if (wallsByFloor == null || floorIndex < 0 || floorIndex >= numFloors) {
            return new ArrayList<>();
        }
        return wallsByFloor[floorIndex];
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    /** Returns the loaded building ID, or null if not loaded. */
    String getLoadedBuildingId() {
        return loadedBuildingId;
    }

    /**
     * Returns the floor index most likely occupied by the user, derived from
     * the current WiFi floor reading mapped to a floor display name.
     *
     * WiFi integers are converted to display names per building. The display name is then looked up in the index map built
     * at load time, so the result is correct regardless of API floor ordering.
     *
     * Falls back to 0 if WiFi returns an unrecognised value.
     */
    int getLikelyFloorIndex() {
        if (displayNameToIndex == null) return 0;
        int wifiFloor = sensorFusion.getWifiFloor();
        String name = wifiFloorToDisplayName(wifiFloor);
        Integer idx = (name != null) ? displayNameToIndex.get(name) : null;
        int result = (idx != null) ? idx : 0;
        Log.d(TAG_LOAD, "getLikelyFloorIndex: wifiFloor=" + wifiFloor
                + " elevation=" + sensorFusion.getElevation()
                + " name=" + name + " index=" + result);
        return result;
    }

    /**
     * Converts a WiFi floor integer to the display name used in the floorplan API
     * for the currently loaded building.
     *
     * Nucleus / Murchison: 0=GF, 1=F1, 2=F2, 3=F3 (LG not available via WiFi).
     * Generic fallback: treats the integer as a display-name string ("0", "1", ...).
     */
    private String wifiFloorToDisplayName(int wifiFloor) {
        if ("nucleus_building".equals(loadedBuildingId)
                || "murchison_house".equals(loadedBuildingId)) {
            switch (wifiFloor) {
                case 0: return "GF";
                case 1: return "F1";
                case 2: return "F2";
                case 3: return "F3";
                default:
                    // Floor not covered by WiFi (e.g. LG) — infer from barometer elevation
                    return (sensorFusion.getElevation() < -1.5f) ? "LG" : "GF";
            }
        }
        return String.valueOf(wifiFloor);
    }


    /**
     * Converts a WGS84 LatLng to a local easting/northing offset (meters)
     * relative to origin, using the same formula as ParticleFilter.
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
     * Returns the cached building whose centre is geographically closest to origin.
     * Used as a last-resort fallback when no polygon contains the origin.

     * Distance is computed as squared Euclidean in lat/lon degrees.
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
