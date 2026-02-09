package com.openpositioning.PositionMe.data.remote;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.openpositioning.PositionMe.BuildConfig;
import com.openpositioning.PositionMe.data.model.FloorplanModels;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
/**
 * Floorplan API client for Goal 5C.
 *
 * Endpoint pattern (as observed from your logs):
 *   POST https://openpositioning.org/api/live/floorplan/request/{userKey}?key={masterKey}
 * Body:
 *   { "lat": ..., "lon": ..., "macs": ["aa:bb:..", ...] }
 *
 * Response:
 *   - can be an array: [] or [ {venue...}, ...]
 *   - or an object containing an array: { "venues": [...] }
 */
public class FloorplanApi {
    public interface VenuesCallback {
        void onSuccess(@NonNull List<FloorplanModels.Venue> venues);
        void onError(@NonNull String message);
    }
    private static final String TAG = "FloorplanApi";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String userKey = BuildConfig.OPENPOSITIONING_API_KEY;
    private static final String masterKey = BuildConfig.OPENPOSITIONING_MASTER_KEY;
    public void requestNearbyVenues(@NonNull LatLng center,
                                    @NonNull List<String> macs,
                                    @NonNull VenuesCallback cb) {
        requestNearbyVenues(center, macs, null, cb);
    }
    /**
     * Same as requestNearbyVenues but with an optional request id for debugging.
     * Pass something like "#3.1" to correlate multi-probe requests.
     */
    public void requestNearbyVenues(@NonNull LatLng center,
                                    @NonNull List<String> macs,
                                    @Nullable String reqId,
                                    @NonNull VenuesCallback cb) {
        if (userKey == null || userKey.isEmpty() || masterKey == null || masterKey.isEmpty()) {
            postError(cb, "OPENPOSITIONING_API_KEY / MASTER_KEY is empty. Check secrets.properties");
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("lat", center.latitude);
            body.put("lon", center.longitude);
            JSONArray arr = new JSONArray();
            for (String m : macs) arr.put(m);
            body.put("macs", arr);
        } catch (Exception e) {
            postError(cb, "JSON build error: " + e.getMessage());
            return;
        }
        String url = "https://openpositioning.org/api/live/floorplan/request/" + userKey + "?key=" + masterKey;
        final String rid = (reqId == null || reqId.trim().isEmpty()) ? "" : ("[" + reqId + "] ");
        final long t0 = System.currentTimeMillis();
        Log.d(TAG, rid + "POST " + url + " lat=" + center.latitude + " lon=" + center.longitude + " body(macs)=" + macs.size());
        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON))
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                long dt = System.currentTimeMillis() - t0;
                Log.e(TAG, rid + "HTTP failed (" + dt + "ms): " + e.getClass().getSimpleName() + " " + e.getMessage());
                postError(cb, "Network error: " + e.getMessage());
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String raw = response.body() != null ? response.body().string() : "";
                String head = raw.length() > 240 ? raw.substring(0, 240) : raw;
                long dt = System.currentTimeMillis() - t0;
                Log.w(TAG, rid + "HTTP " + response.code() + " (" + dt + "ms) bytes=" + raw.length() + " body=" + head);
                if (!response.isSuccessful()) {
                    postError(cb, "HTTP " + response.code() + " " + head);
                    return;
                }
                parseAndReturn(raw, rid, cb);
            }
        });
    }
    /** Cancel in-flight requests (best-effort). */
    public void cancelAll() {
        try {
            client.dispatcher().cancelAll();
        } catch (Exception ignore) {
        }
    }
    private void parseAndReturn(@NonNull String raw, @NonNull String rid, @NonNull VenuesCallback cb) {
        try {
            raw = raw == null ? "" : raw.trim();
            // Case 1: direct array
            if (raw.startsWith("[")) {
                JSONArray arr = new JSONArray(raw);
                List<FloorplanModels.Venue> parsed = parseVenuesArray(arr);
                Log.d("C_DEBUG", "FloorplanApi" + rid + " parsed venues=" + parsed.size());
                postSuccess(cb, parsed);
                return;
            }
            // Case 2: object wrapping an array
            JSONObject json = new JSONObject(raw);
            JSONArray venuesArr = json.optJSONArray("venues");
            if (venuesArr == null) venuesArr = json.optJSONArray("results");
            if (venuesArr == null) venuesArr = json.optJSONArray("data");
            if (venuesArr == null) venuesArr = json.optJSONArray("floorplans");
            if (venuesArr == null) venuesArr = new JSONArray();
            List<FloorplanModels.Venue> parsed = parseVenuesArray(venuesArr);
            Log.d("C_DEBUG", "FloorplanApi" + rid + " parsed venues=" + parsed.size());
            postSuccess(cb, parsed);
        } catch (Exception e) {
            postError(cb, "Parse error: " + e.getMessage());
        }
    }
    private @NonNull List<FloorplanModels.Venue> parseVenuesArray(@NonNull JSONArray venuesArr) {
        List<FloorplanModels.Venue> venues = new ArrayList<>();
        for (int i = 0; i < venuesArr.length(); i++) {
            JSONObject v = venuesArr.optJSONObject(i);
            if (v == null) continue;
            // IMPORTANT:
            // Some server responses do NOT include a stable venue_id/uuid.
            // If we fall back to "venue_0/venue_1...", multi-probe merging will wrongly collapse
            // different buildings that happen to share the same index across probes.
            // -> Build a stable synthetic id based on name + geometry.
            String venueId = optStringFirst(v, "venue_id", "venueId", "id", "uuid");
            String venueName = optStringFirst(v, "venue_name", "venueName", "name", "title");
            List<com.google.android.gms.maps.model.LatLng> outline = parseOutline(v);
            LatLngBounds bounds = parseBounds(v);
            // If the backend didn't provide bounds but we have an outline, derive bounds from outline.
            if (bounds == null && outline != null && outline.size() >= 2) {
                double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
                double minLng = Double.POSITIVE_INFINITY, maxLng = Double.NEGATIVE_INFINITY;
                for (LatLng p : outline) {
                    if (p == null) continue;
                    minLat = Math.min(minLat, p.latitude);
                    maxLat = Math.max(maxLat, p.latitude);
                    minLng = Math.min(minLng, p.longitude);
                    maxLng = Math.max(maxLng, p.longitude);
                }
                if (Double.isFinite(minLat) && Double.isFinite(maxLat) && Double.isFinite(minLng) && Double.isFinite(maxLng)) {
                    try {
                        bounds = new LatLngBounds(new LatLng(minLat, minLng), new LatLng(maxLat, maxLng));
                    } catch (Exception ignore) {
                    }
                }
            }
            if (venueId == null || venueId.trim().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append(venueName != null ? venueName : "");
                sb.append('|');
                if (bounds != null) {
                    sb.append(bounds.southwest.latitude).append(',').append(bounds.southwest.longitude).append(',')
                            .append(bounds.northeast.latitude).append(',').append(bounds.northeast.longitude);
                }
                sb.append('|');
                if (outline != null && !outline.isEmpty()) {
                    com.google.android.gms.maps.model.LatLng p0 = outline.get(0);
                    sb.append(p0.latitude).append(',').append(p0.longitude).append(',').append(outline.size());
                }
                venueId = "v_" + Integer.toHexString(sb.toString().hashCode());
            }
// Floors/floorplans can be named differently depending on the server build.
// We accept both JSONArray and JSONObject (map) containers, and even simple string arrays.
            Object floorsNode = firstNode(v,
                    "floors", "levels", "floorplans", "floor_plans",
                    "floorplan_images", "images", "maps", "plans", "floorplan", "indoor_map");
            if (floorsNode == null) {
                // Sometimes nested under a "floorplan"/"indoor" object
                JSONObject nested = v.optJSONObject("floorplan");
                if (nested == null) nested = v.optJSONObject("indoor");
                if (nested == null) nested = v.optJSONObject("map");
                if (nested == null) nested = v.optJSONObject("indoor_map");
                if (nested != null) {
                    floorsNode = firstNode(nested,
                            "floors", "levels", "floorplans", "floor_plans",
                            "floorplan_images", "images", "maps", "plans");
                }
            }
// Some server builds return floors/images payload as a JSON string. Unwrap if needed.
            floorsNode = unwrapJsonString(floorsNode);
            List<FloorplanModels.Floor> floors = new ArrayList<>();
            if (floorsNode instanceof JSONArray) {
                JSONArray floorsArr = (JSONArray) floorsNode;
                for (int f = 0; f < floorsArr.length(); f++) {
                    Object node = floorsArr.opt(f);
                    node = unwrapJsonString(node);
                    if (node instanceof JSONObject) {
                        JSONObject fl = (JSONObject) node;
                        int floorIndex = firstInt(fl, f,
                                "floor", "level", "z", "floor_index", "index", "floorId", "floor_id");
                        String floorName = optStringFirst(fl, "name", "floor_name", "label", "title");
                        String imageUrl  = optStringFirst(fl,
                                "image", "image_url", "url", "png", "jpg", "href",
                                "path", "imagePath", "image_path", "file", "file_url");
                        imageUrl = normalizeImageUrl(imageUrl);
                        LatLngBounds fb = parseBounds(fl);
                        if (fb == null) fb = bounds;
                        FloorGeometry geom = parseFloorGeometry(fl);
                        floors.add(new FloorplanModels.Floor(floorIndex, floorName, imageUrl, fb, geom.wallPolygons, geom.wallLines));
                    } else if (node instanceof String) {
                        String imageUrl = normalizeImageUrl((String) node);
                        floors.add(new FloorplanModels.Floor(f, null, imageUrl, bounds));
                    }
                }
            } else if (floorsNode instanceof JSONObject) {
                JSONObject floorsObj = (JSONObject) floorsNode;
                JSONArray keys = floorsObj.names();
                if (keys != null) {
                    for (int k = 0; k < keys.length(); k++) {
                        String key = keys.optString(k, null);
                        if (key == null) continue;
                        Object val = unwrapJsonString(floorsObj.opt(key));
                        val = unwrapJsonString(val);
                        int fallbackIndex;
                        try { fallbackIndex = Integer.parseInt(key); }
                        catch (Exception ignore) { fallbackIndex = k; }
                        if (val instanceof JSONObject) {
                            JSONObject fl = (JSONObject) val;
                            int floorIndex = firstInt(fl, fallbackIndex,
                                    "floor", "level", "z", "floor_index", "index", "floorId", "floor_id");
                            String floorName = optStringFirst(fl, "name", "floor_name", "label", "title");
                            String imageUrl  = optStringFirst(fl,
                                    "image", "image_url", "url", "png", "jpg", "href",
                                    "path", "imagePath", "image_path", "file", "file_url");
                            imageUrl = normalizeImageUrl(imageUrl);
                            LatLngBounds fb = parseBounds(fl);
                            if (fb == null) fb = bounds;
                            FloorGeometry geom = parseFloorGeometry(fl);
                            floors.add(new FloorplanModels.Floor(floorIndex, floorName, imageUrl, fb, geom.wallPolygons, geom.wallLines));
                        } else if (val instanceof String) {
                            String imageUrl = normalizeImageUrl((String) val);
                            floors.add(new FloorplanModels.Floor(fallbackIndex, null, imageUrl, bounds));
                        }
                    }
                }
            } else if (floorsNode instanceof String) {
                // Rare: a single url
                String imageUrl = normalizeImageUrl((String) floorsNode);
                floors.add(new FloorplanModels.Floor(0, null, imageUrl, bounds));
            }
// Last resort: if there is an "images" array of urls but floorsNode didn't catch it
            if (floors.isEmpty()) {
                JSONArray imgs = v.optJSONArray("images");
                if (imgs != null) {
                    // NOTE: do NOT use loop variable name "i" here because the outer venue loop
                    // already uses "i" and Java forbids shadowing local variables.
                    for (int j = 0; j < imgs.length(); j++) {
                        String u = imgs.optString(j, null);
                        if (u == null) continue;
                        floors.add(new FloorplanModels.Floor(j, null, normalizeImageUrl(u), bounds));
                    }
                }
            }
// Another common pattern: the venue itself has a single floorplan image url, but no floors list.
// In that case we still create a single "floor 0" entry so the UI can display an overlay.
            if (floors.isEmpty()) {
                String u = optStringFirst(v,
                        "image", "image_url", "url", "href",
                        "floorplan", "floorplan_url", "map_url", "plan_url", "overlay", "overlay_url");
                if (u == null) {
                    // sometimes nested under floorplan/map
                    JSONObject nested = v.optJSONObject("floorplan");
                    if (nested == null) nested = v.optJSONObject("map");
                    if (nested == null) nested = v.optJSONObject("indoor");
                    if (nested != null) {
                        u = optStringFirst(nested,
                                "image", "image_url", "url", "href",
                                "floorplan", "floorplan_url", "map_url", "plan_url", "overlay", "overlay_url");
                    }
                }
                u = normalizeImageUrl(u);
                if (u != null) {
                    floors.add(new FloorplanModels.Floor(0, null, u, bounds));
                }
            }
            // Newer server representation (coursework update): map_shapes contains vector floorplan geometry.
            // Swagger UI example shows fields: name, outline, map_shapes.
            // If floors are not present, try to parse map_shapes into synthetic Floor entries with geometry.
            if (floors.isEmpty()) {
                Object mapShapesNode = firstNode(v,
                        "map_shapes", "mapShapes", "map_shapes_json", "mapShapesJson",
                        "floor_shapes", "floorShapes", "shapes", "mapshape", "map_shape");
                if (mapShapesNode == null) {
                    JSONObject nestedMs = v.optJSONObject("floorplan");
                    if (nestedMs == null) nestedMs = v.optJSONObject("map");
                    if (nestedMs == null) nestedMs = v.optJSONObject("indoor");
                    if (nestedMs == null) nestedMs = v.optJSONObject("indoor_map");
                    if (nestedMs != null) {
                        mapShapesNode = firstNode(nestedMs,
                                "map_shapes", "mapShapes", "map_shapes_json", "mapShapesJson",
                                "floor_shapes", "floorShapes", "shapes", "mapshape", "map_shape");
                    }
                }
                mapShapesNode = unwrapJsonString(mapShapesNode);
                if (mapShapesNode != null) {
                    List<FloorplanModels.Floor> geomFloors = parseMapShapesToFloors(mapShapesNode, bounds);
                    if (!geomFloors.isEmpty()) floors.addAll(geomFloors);
                }
            }
            int floorsImg = 0;
            int floorsGeom = 0;
            StringBuilder fdbg = new StringBuilder();
            for (int fi = 0; fi < floors.size(); fi++) {
                FloorplanModels.Floor f = floors.get(fi);
                if (f == null) continue;
                boolean hasImg = f.imageUrl != null && !f.imageUrl.trim().isEmpty();
                boolean hasGeom = f.hasGeometry();
                if (hasImg) floorsImg++;
                if (hasGeom) floorsGeom++;
                if (fi < 4) {
                    int polyN = f.wallPolygons != null ? f.wallPolygons.size() : 0;
                    int lineN = f.wallLines != null ? f.wallLines.size() : 0;
                    fdbg.append(" [").append(f.floorIndex)
                            .append(" '").append(f.floorName).append("' img=").append(hasImg)
                            .append(" geom=").append(hasGeom)
                            .append(" polys=").append(polyN)
                            .append(" lines=").append(lineN)
                            .append("]");
                }
            }
            Log.d(TAG, "venue '" + (venueName != null ? venueName : venueId) + "' outline=" + (outline != null ? outline.size() : 0)
                    + " bounds=" + (bounds != null) + " floors=" + floors.size()
                    + " floorsImg=" + floorsImg + " floorsGeom=" + floorsGeom
                    + (fdbg.length() > 0 ? (" floorsSample=" + fdbg) : ""));
            venues.add(new FloorplanModels.Venue(venueId, venueName, outline, bounds, floors));
        }
        return venues;
    }
    /**
     * Normalize image URLs returned by the floorplan API.
     * The backend may return relative paths (e.g. /media/..), schemeless URLs (//..),
     * or already-absolute http/https URLs.
     */
    private static String normalizeImageUrl(String url) {
        if (url == null) return null;
        url = url.trim();
        if (url.isEmpty() || "null".equalsIgnoreCase(url)) return null;
        // Schemeless
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        // Absolute
        if (url.startsWith("https://") || url.startsWith("http://")) {
            // Prefer https
            if (url.startsWith("http://openpositioning.org")) {
                return "https://openpositioning.org" + url.substring("http://openpositioning.org".length());
            }
            return url;
        }
        // Relative path
        if (url.startsWith("/")) {
            return "https://openpositioning.org" + url;
        }
        // Bare filename or unknown -> treat as relative under host
        return "https://openpositioning.org/" + url;
    }
    private static String optStringFirst(JSONObject obj, String... keys) {
        for (String k : keys) {
            String s = obj.optString(k, null);
            if (s != null && !s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
        }
        return null;
    }
    private static @Nullable JSONArray firstArray(@NonNull JSONObject obj, String... keys) {
        for (String k : keys) {
            JSONArray a = obj.optJSONArray(k);
            if (a != null && a.length() > 0) return a;
        }
        return null;
    }
    private static @Nullable Object firstNode(@NonNull JSONObject obj, String... keys) {
        for (String k : keys) {
            if (!obj.has(k)) continue;
            Object n = obj.opt(k);
            if (n instanceof JSONArray || n instanceof JSONObject) return n;
            if (n instanceof String && !((String) n).isEmpty()) return n;
        }
        return null;
    }
    private static int firstInt(@NonNull JSONObject obj, int fallback, String... keys) {
        for (String k : keys) {
            if (!obj.has(k)) continue;
            // optInt returns 0 if missing/non-int, so we guard with has()
            int v = obj.optInt(k, fallback);
            return v;
        }
        return fallback;
    }
    /**
     * Parse bounds from a variety of common API formats.
     *
     * Supported examples:
     * - {"bounds": {"sw": {"lat":..,"lng":..}, "ne": {...}}}
     * - {"sw": {...}, "ne": {...}}
     * - {"bounds": [[lat,lon],[lat,lon]]} or [[lon,lat],[lon,lat]]
     * - {"bbox": [west,south,east,north]} (GeoJSON)
     * - {"min_lat":..,"min_lng":..,"max_lat":..,"max_lng":..} (and similar key variants)
     */
    private static LatLngBounds parseBounds(JSONObject obj) {
        if (obj == null) return null;
        // 1) bounds object
        JSONObject boundsObj = obj.optJSONObject("bounds");
        if (boundsObj != null) {
            LatLngBounds b = parseBoundsFromBoundsObject(boundsObj);
            if (b != null) return b;
        }
        // 2) bbox array (GeoJSON: [west,south,east,north])
        JSONArray bbox = obj.optJSONArray("bbox");
        if (bbox == null && boundsObj != null) bbox = boundsObj.optJSONArray("bbox");
        LatLngBounds fromBbox = parseBoundsFromBboxArray(bbox);
        if (fromBbox != null) return fromBbox;
        // 3) bounds array (two corners)
        JSONArray boundsArr = obj.optJSONArray("bounds");
        LatLngBounds fromArray = parseBoundsFromCornersArray(boundsArr);
        if (fromArray != null) return fromArray;
        // 3b) corners array (can be 4 corners)
        JSONArray cornersArr = obj.optJSONArray("corners");
        fromArray = parseBoundsFromCornersArray(cornersArr);
        if (fromArray != null) return fromArray;
        if (boundsObj != null) {
            fromArray = parseBoundsFromCornersArray(boundsObj.optJSONArray("bounds"));
            if (fromArray != null) return fromArray;
        }
        // 4) sw/ne as objects at top-level
        LatLng sw = parseLatLng(obj.optJSONObject("sw"));
        LatLng ne = parseLatLng(obj.optJSONObject("ne"));
        if (sw != null && ne != null) return new LatLngBounds(sw, ne);
        // 5) min/max keys
        Double minLat = optDoubleFirst(obj, "min_lat", "south", "lat_min", "minLatitude");
        Double maxLat = optDoubleFirst(obj, "max_lat", "north", "lat_max", "maxLatitude");
        Double minLng = optDoubleFirst(obj, "min_lng", "min_lon", "west", "lng_min", "lon_min", "minLongitude");
        Double maxLng = optDoubleFirst(obj, "max_lng", "max_lon", "east", "lng_max", "lon_max", "maxLongitude");
        if (minLat != null && maxLat != null && minLng != null && maxLng != null) {
            return new LatLngBounds(new LatLng(minLat, minLng), new LatLng(maxLat, maxLng));
        }
        return null;
    }
    // -------------------- map_shapes helpers --------------------
    // Some coursework server builds return floorplans only via a "map_shapes" field (often as a JSON string)
    // instead of an explicit "floors" array. We convert that payload into synthetic Floor entries
    // that carry vector wall geometry so the UI can render them.
    private static int floorIndexFromLabel(@Nullable String label, int fallback) {
        if (label == null) return fallback;
        String s = label.trim();
        if (s.isEmpty()) return fallback;
        String u = s.toUpperCase();
        // Common aliases
        if (u.equals("LG") || u.equals("LOWERGROUND") || u.equals("LOWER GROUND") || u.equals("B1") || u.equals("BASEMENT")) return -1;
        if (u.equals("UG") || u.equals("UNDERGROUND") || u.equals("B2") || u.equals("BASEMENT2") || u.equals("BASEMENT 2")) return -2;
        if (u.equals("G") || u.equals("GROUND")) return 0;
        // Direct integer ("-1", "0", "1", ...)
        try {
            if (u.matches("-?\\d+")) return Integer.parseInt(u);
        } catch (Exception ignore) {}
        // Patterns like "L1", "F2", "2F"...
        try {
            Matcher m = Pattern.compile("-?\\d+").matcher(u);
            if (m.find()) return Integer.parseInt(m.group());
        } catch (Exception ignore) {}
        return fallback;
    }
    private static @NonNull JSONObject wrapGeometry(@Nullable Object g) {
        JSONObject w = new JSONObject();
        try { w.put("geometry", g); } catch (Exception ignore) {}
        return w;
    }
    private static @NonNull List<FloorplanModels.Floor> parseMapShapesToFloors(@NonNull Object mapShapesNode,
                                                                              @Nullable LatLngBounds venueBounds) {
        List<FloorplanModels.Floor> out = new ArrayList<>();
        Object node = unwrapJsonString(mapShapesNode);
        if (node == null) return out;
        // Case 1: JSONObject
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            // If this object itself looks like GeoJSON, treat it as a single floor.
            String type = o.optString("type", "");
            boolean looksGeo = o.has("features") || o.has("coordinates")
                    || "FEATURECOLLECTION".equalsIgnoreCase(type)
                    || "FEATURE".equalsIgnoreCase(type)
                    || "GEOMETRYCOLLECTION".equalsIgnoreCase(type)
                    || "POLYGON".equalsIgnoreCase(type)
                    || "MULTIPOLYGON".equalsIgnoreCase(type)
                    || "LINESTRING".equalsIgnoreCase(type)
                    || "MULTILINESTRING".equalsIgnoreCase(type);
            if (looksGeo) {
                FloorGeometry geom = parseFloorGeometry(wrapGeometry(o));
                if (!geom.wallPolygons.isEmpty() || !geom.wallLines.isEmpty()) {
                    out.add(new FloorplanModels.Floor(0, null, null, venueBounds, geom.wallPolygons, geom.wallLines));
                }
                return out;
            }
            // Otherwise, assume keys are floor labels (e.g., {"LG": {...}, "G": {...}, "1": {...}})
            JSONArray keys = o.names();
            int seq = 0;
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String label = keys.optString(i, null);
                    if (label == null) continue;
                    Object val = unwrapJsonString(o.opt(label));
                    if (val == null) continue;
                    JSONObject floorObj = (val instanceof JSONObject) ? (JSONObject) val : wrapGeometry(val);
                    FloorGeometry geom = parseFloorGeometry(floorObj);
                    if (geom.wallPolygons.isEmpty() && geom.wallLines.isEmpty()) continue;
                    LatLngBounds b = parseBounds(floorObj);
                    if (b == null) b = venueBounds;
                    int floorIndex = floorIndexFromLabel(label, seq);
                    out.add(new FloorplanModels.Floor(floorIndex, label, null, b, geom.wallPolygons, geom.wallLines));
                    seq++;
                }
            }
            // If still nothing, try parsing the whole object as generic geometry.
            if (out.isEmpty()) {
                FloorGeometry geom = parseFloorGeometry(wrapGeometry(o));
                if (!geom.wallPolygons.isEmpty() || !geom.wallLines.isEmpty()) {
                    out.add(new FloorplanModels.Floor(0, null, null, venueBounds, geom.wallPolygons, geom.wallLines));
                }
            }
            return out;
        }
        // Case 2: JSONArray
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object val = unwrapJsonString(arr.opt(i));
                if (val == null) continue;
                JSONObject floorObj = (val instanceof JSONObject) ? (JSONObject) val : wrapGeometry(val);
                FloorGeometry geom = parseFloorGeometry(floorObj);
                if (geom.wallPolygons.isEmpty() && geom.wallLines.isEmpty()) continue;
                String label = optStringFirst(floorObj, "name", "floor_name", "label", "title", "floor");
                LatLngBounds b = parseBounds(floorObj);
                if (b == null) b = venueBounds;
                int floorIndex = floorIndexFromLabel(label, i);
                out.add(new FloorplanModels.Floor(floorIndex, label, null, b, geom.wallPolygons, geom.wallLines));
            }
            // If nothing matched, treat the whole array as coordinates/geometry.
            if (out.isEmpty()) {
                FloorGeometry geom = parseFloorGeometry(wrapGeometry(arr));
                if (!geom.wallPolygons.isEmpty() || !geom.wallLines.isEmpty()) {
                    out.add(new FloorplanModels.Floor(0, null, null, venueBounds, geom.wallPolygons, geom.wallLines));
                }
            }
            return out;
        }
        // Other types: ignore
        return out;
    }
// ======================= Floor plan geometry (vector walls) =======================
    // Newer coursework versions return floor plans as vector walls (GeoJSON) rather than PNGs.
    // - Murchison House: walls as polygons
    // - Nucleus Building: walls as lines
    //
    // We parse both and store them on FloorplanModels.Floor so the map can render them.
    private static final class FloorGeometry {
        @NonNull final List<List<LatLng>> wallPolygons = new ArrayList<>();
        @NonNull final List<List<LatLng>> wallLines = new ArrayList<>();
    }
    private static @NonNull FloorGeometry parseFloorGeometry(@NonNull JSONObject floorObj) {
        FloorGeometry out = new FloorGeometry();
        if (floorObj == null) return out;
        // Some server builds nest geometry under "floorplan"/"map"/"indoor"
        JSONObject nested = floorObj.optJSONObject("floorplan");
        if (nested == null) nested = floorObj.optJSONObject("map");
        if (nested == null) nested = floorObj.optJSONObject("indoor");
        if (nested == null) nested = floorObj.optJSONObject("layout");
        Object polyNode = firstNode(floorObj,
                "wall_polygons", "walls_polygons", "polygons", "polygon_walls", "wallsAsPolygons", "walls_as_polygons");
        Object lineNode = firstNode(floorObj,
                "wall_lines", "walls_lines", "lines", "line_walls", "wallsAsLines", "walls_as_lines");
        if (polyNode == null && nested != null) {
            polyNode = firstNode(nested,
                    "wall_polygons", "walls_polygons", "polygons", "polygon_walls", "wallsAsPolygons", "walls_as_polygons");
        }
        if (lineNode == null && nested != null) {
            lineNode = firstNode(nested,
                    "wall_lines", "walls_lines", "lines", "line_walls", "wallsAsLines", "walls_as_lines");
        }
        polyNode = unwrapJsonString(polyNode);
        lineNode = unwrapJsonString(lineNode);
        if (polyNode != null) {
            collectGeometries(polyNode, out.wallPolygons, null, true);
        }
        if (lineNode != null) {
            collectGeometries(lineNode, null, out.wallLines, true);
        }
        // Generic "walls"/"geometry" field (FeatureCollection, Polygon, LineString, etc.)
        if (out.wallPolygons.isEmpty() && out.wallLines.isEmpty()) {
            Object wallsNode = firstNode(floorObj,
                    "walls", "wall", "geometry", "geojson", "features", "shapes", "floorplan", "plan", "map", "layout");
            if (wallsNode == null && nested != null) {
                wallsNode = firstNode(nested,
                        "walls", "wall", "geometry", "geojson", "features", "shapes");
            }
            wallsNode = unwrapJsonString(wallsNode);
            if (wallsNode != null) {
                collectGeometries(wallsNode, out.wallPolygons, out.wallLines, true);
            }
        }
        // Heuristic: if nothing found, scan all fields for something GeoJSON-like
        if (out.wallPolygons.isEmpty() && out.wallLines.isEmpty()) {
            JSONArray names = floorObj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i, null);
                    if (k == null) continue;
                    Object v = floorObj.opt(k);
                    if (v == null) continue;
                    v = unwrapJsonString(v);
                    if (v == null) continue;
                    int before = out.wallPolygons.size() + out.wallLines.size();
                    collectGeometries(v, out.wallPolygons, out.wallLines, false);
                    int after = out.wallPolygons.size() + out.wallLines.size();
                    if (after > before) break;
                }
            }
        }
        return out;
    }
    /**
     * Collect geometries from a GeoJSON-like structure.
     *
     * @param node          JSONObject/JSONArray/String containing GeoJSON or nested coordinate arrays
     * @param polysOut      append parsed polygons here (nullable)
     * @param linesOut      append parsed lines here (nullable)
     * @param strictGeoJson if true, assume [lon,lat] ordering for coordinate pairs unless obviously swapped
     */
    private static void collectGeometries(@Nullable Object node,
                                          @Nullable List<List<LatLng>> polysOut,
                                          @Nullable List<List<LatLng>> linesOut,
                                          boolean strictGeoJson) {
        if (node == null) return;
        node = unwrapJsonString(node);
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String type = o.optString("type", "");
            if ("FeatureCollection".equalsIgnoreCase(type) || o.has("features")) {
                JSONArray feats = o.optJSONArray("features");
                if (feats != null) {
                    for (int i = 0; i < feats.length(); i++) {
                        Object f = unwrapJsonString(feats.opt(i));
                        collectGeometries(f, polysOut, linesOut, true);
                    }
                }
                return;
            }
            if ("Feature".equalsIgnoreCase(type) || o.has("geometry")) {
                Object geom = o.opt("geometry");
                geom = unwrapJsonString(geom);
                if (geom != null) collectGeometries(geom, polysOut, linesOut, true);
                return;
            }
            if ("GeometryCollection".equalsIgnoreCase(type) || o.has("geometries")) {
                JSONArray geoms = o.optJSONArray("geometries");
                if (geoms != null) {
                    for (int i = 0; i < geoms.length(); i++) {
                        Object g = unwrapJsonString(geoms.opt(i));
                        collectGeometries(g, polysOut, linesOut, true);
                    }
                }
                return;
            }
            // Basic geometry object: {type, coordinates}
            Object coords = o.opt("coordinates");
            if (coords != null) {
                coords = unwrapJsonString(coords);
                collectTypedCoordinates(type, coords, polysOut, linesOut, strictGeoJson || true);
            }
            return;
        }
        if (node instanceof JSONArray) {
            // Try to interpret nested arrays as coordinates.
            collectUntypedCoordinateArray((JSONArray) node, polysOut, linesOut, strictGeoJson);
        }
    }
    private static void collectTypedCoordinates(@Nullable String type,
                                                @Nullable Object coords,
                                                @Nullable List<List<LatLng>> polysOut,
                                                @Nullable List<List<LatLng>> linesOut,
                                                boolean preferLonLat) {
        if (coords == null) return;
        if (!(coords instanceof JSONArray)) {
            // Might be wrapped as object/feature again
            collectGeometries(coords, polysOut, linesOut, preferLonLat);
            return;
        }
        JSONArray a = (JSONArray) coords;
        String t = (type == null) ? "" : type;
        if ("LineString".equalsIgnoreCase(t)) {
            List<LatLng> path = parsePath(a, preferLonLat);
            if (path.size() >= 2 && linesOut != null) linesOut.add(path);
            return;
        }
        if ("MultiLineString".equalsIgnoreCase(t)) {
            for (int i = 0; i < a.length(); i++) {
                Object line = a.opt(i);
                if (line instanceof JSONArray) {
                    List<LatLng> path = parsePath((JSONArray) line, preferLonLat);
                    if (path.size() >= 2 && linesOut != null) linesOut.add(path);
                }
            }
            return;
        }
        if ("Polygon".equalsIgnoreCase(t)) {
            // Polygon = [ ring1, ring2(hole), ... ] -> use ring1
            if (a.length() > 0 && a.opt(0) instanceof JSONArray) {
                List<LatLng> ring = parsePath((JSONArray) a.opt(0), preferLonLat);
                if (ring.size() >= 3 && polysOut != null) polysOut.add(ring);
            } else {
                List<LatLng> ring = parsePath(a, preferLonLat);
                if (ring.size() >= 3 && polysOut != null) polysOut.add(ring);
            }
            return;
        }
        if ("MultiPolygon".equalsIgnoreCase(t)) {
            for (int i = 0; i < a.length(); i++) {
                Object poly = a.opt(i);
                if (!(poly instanceof JSONArray)) continue;
                JSONArray polyArr = (JSONArray) poly;
                if (polyArr.length() > 0 && polyArr.opt(0) instanceof JSONArray) {
                    List<LatLng> ring = parsePath((JSONArray) polyArr.opt(0), preferLonLat);
                    if (ring.size() >= 3 && polysOut != null) polysOut.add(ring);
                }
            }
            return;
        }
        // Unknown type: fall back to untyped parsing
        collectUntypedCoordinateArray(a, polysOut, linesOut, preferLonLat);
    }
    /** Parse an array of coordinate pairs into a path. */
    private static @NonNull List<LatLng> parsePath(@NonNull JSONArray pairs, boolean preferLonLat) {
        List<LatLng> out = new ArrayList<>();
        for (int i = 0; i < pairs.length(); i++) {
            Object n = pairs.opt(i);
            if (n instanceof JSONArray) {
                JSONArray p = (JSONArray) n;
                LatLng ll = parseCoordPair(p, preferLonLat);
                if (ll != null) out.add(ll);
            } else if (n instanceof JSONObject) {
                LatLng ll = parseLatLng((JSONObject) n);
                if (ll != null) out.add(ll);
            }
        }
        // De-dup consecutive duplicates
        if (out.size() >= 2) {
            List<LatLng> cleaned = new ArrayList<>();
            LatLng last = null;
            for (LatLng p : out) {
                if (p == null) continue;
                if (last == null || (last.latitude != p.latitude || last.longitude != p.longitude)) {
                    cleaned.add(p);
                    last = p;
                }
            }
            out = cleaned;
        }
        return out;
    }
    private static @Nullable LatLng parseCoordPair(@NonNull JSONArray pair, boolean preferLonLat) {
        if (pair.length() < 2) return null;
        double a = pair.optDouble(0, Double.NaN);
        double b = pair.optDouble(1, Double.NaN);
        if (!Double.isFinite(a) || !Double.isFinite(b)) return null;
        // Heuristic: if one value looks like lat and the other like lon, respect that.
        boolean aLooksLat = Math.abs(a) <= 90.0;
        boolean bLooksLat = Math.abs(b) <= 90.0;
        if (aLooksLat && !bLooksLat) {
            return new LatLng(a, b);
        }
        if (!aLooksLat && bLooksLat) {
            return new LatLng(b, a);
        }
        // Default ordering
        if (preferLonLat) {
            return new LatLng(b, a);
        } else {
            return new LatLng(a, b);
        }
    }
    /**
     * Untyped coordinate arrays come in many nestings:
     * - [ [x,y], [x,y], ... ]                      => a single path
     * - [ [ [x,y],... ], [ [x,y],... ], ... ]      => multiple paths
     * - [ [ [ [x,y],... ] , ... ], ... ]          => multipolygons/collections
     *
     * We parse recursively and classify by whether the path is closed.
     */
    private static void collectUntypedCoordinateArray(@NonNull JSONArray arr,
                                                      @Nullable List<List<LatLng>> polysOut,
                                                      @Nullable List<List<LatLng>> linesOut,
                                                      boolean preferLonLat) {
        if (arr.length() == 0) return;
        Object first = arr.opt(0);
        // Case: list of coordinate pairs => one path
        if (first instanceof JSONArray && ((JSONArray) first).length() >= 2
                && (((JSONArray) first).opt(0) instanceof Number || ((JSONArray) first).opt(0) instanceof String)) {
            List<LatLng> path = parsePath(arr, preferLonLat);
            if (path.size() < 2) return;
            boolean closed = isClosed(path);
            if (closed) {
                if (polysOut != null && path.size() >= 3) polysOut.add(path);
            } else {
                if (linesOut != null) linesOut.add(path);
            }
            return;
        }
        // Otherwise: recurse into children arrays/objects
        for (int i = 0; i < arr.length(); i++) {
            Object child = unwrapJsonString(arr.opt(i));
            if (child instanceof JSONObject || child instanceof JSONArray) {
                collectGeometries(child, polysOut, linesOut, preferLonLat);
            }
        }
    }
    private static boolean isClosed(@NonNull List<LatLng> path) {
        if (path.size() < 3) return false;
        LatLng a = path.get(0);
        LatLng b = path.get(path.size() - 1);
        if (a == null || b == null) return false;
        double dLat = Math.abs(a.latitude - b.latitude);
        double dLng = Math.abs(a.longitude - b.longitude);
        return dLat < 1e-6 && dLng < 1e-6;
    }
    private static LatLngBounds parseBoundsFromBoundsObject(JSONObject boundsObj) {
        if (boundsObj == null) return null;
        LatLng sw = parseLatLng(boundsObj.optJSONObject("sw"));
        LatLng ne = parseLatLng(boundsObj.optJSONObject("ne"));
        if (sw != null && ne != null) return new LatLngBounds(sw, ne);
        sw = parseLatLng(boundsObj.optJSONObject("southwest"));
        ne = parseLatLng(boundsObj.optJSONObject("northeast"));
        if (sw != null && ne != null) return new LatLngBounds(sw, ne);
        // Sometimes boundsObj itself is the corner object
        sw = parseLatLng(boundsObj.optJSONObject("0"));
        ne = parseLatLng(boundsObj.optJSONObject("1"));
        if (sw != null && ne != null) return new LatLngBounds(sw, ne);
        return null;
    }
    private static LatLngBounds parseBoundsFromBboxArray(@Nullable JSONArray bbox) {
        if (bbox == null || bbox.length() < 4) return null;
        double west = bbox.optDouble(0, Double.NaN);
        double south = bbox.optDouble(1, Double.NaN);
        double east = bbox.optDouble(2, Double.NaN);
        double north = bbox.optDouble(3, Double.NaN);
        if (Double.isNaN(west) || Double.isNaN(south) || Double.isNaN(east) || Double.isNaN(north)) return null;
        return new LatLngBounds(new LatLng(south, west), new LatLng(north, east));
    }
    private static LatLngBounds parseBoundsFromCornersArray(@Nullable JSONArray arr) {
        if (arr == null || arr.length() < 2) return null;
        double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
        double minLng = Double.POSITIVE_INFINITY, maxLng = Double.NEGATIVE_INFINITY;
        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            LatLng p = parseLatLngAny(arr.opt(i));
            if (p == null) continue;
            minLat = Math.min(minLat, p.latitude);
            maxLat = Math.max(maxLat, p.latitude);
            minLng = Math.min(minLng, p.longitude);
            maxLng = Math.max(maxLng, p.longitude);
            n++;
        }
        if (n < 2 || !Double.isFinite(minLat) || !Double.isFinite(maxLat) || !Double.isFinite(minLng) || !Double.isFinite(maxLng)) {
            return null;
        }
        return new LatLngBounds(new LatLng(minLat, minLng), new LatLng(maxLat, maxLng));
    }
    private static @Nullable Double optDoubleFirst(@NonNull JSONObject obj, String... keys) {
        for (String k : keys) {
            if (!obj.has(k)) continue;
            double v = obj.optDouble(k, Double.NaN);
            if (!Double.isNaN(v)) return v;
        }
        return null;
    }
    private static LatLng parseLatLng(JSONObject o) {
        if (o == null) return null;
        double lat = o.has("lat") ? o.optDouble("lat") : o.optDouble("latitude");
        double lng = o.has("lng") ? o.optDouble("lng") : (o.has("lon") ? o.optDouble("lon") : o.optDouble("longitude"));
        if (Double.isNaN(lat) || Double.isNaN(lng)) return null;
        return new LatLng(lat, lng);
    }
    /**
     * Parse venue outline/polygon in a robust way.
     *
     * Common formats seen in practice:
     * - outline: [{lat,lng}, ...]
     * - outline: [[lat,lng], ...] or [[lng,lat], ...]
     * - boundary/geometry (GeoJSON): {"type":"Polygon","coordinates":[[[lng,lat],...]]}
     * - coordinates: [[[lng,lat],...]] (nested rings)
     */
    private static List<LatLng> parseOutline(JSONObject venueObj) {
        List<LatLng> outline = new ArrayList<>();
        if (venueObj == null) return outline;
        // Many servers return GeoJSON as a *string* (escaped JSON) in the "outline" field.
        // In that case, coordinates are almost always [lon, lat] (CRS84), and we must
        // parse the embedded JSON before extracting points.
        boolean preferLonLat = false;
        String pickedKey = null;
        // Prefer explicit outline/polygon arrays
        Object candidate = null;
        // Some responses nest geometry under a "venue" object
        if (venueObj.has("venue") && venueObj.opt("venue") instanceof JSONObject) {
            JSONObject nested = venueObj.optJSONObject("venue");
            if (nested != null) {
                List<LatLng> nestedOutline = parseOutline(nested);
                if (nestedOutline != null && !nestedOutline.isEmpty()) return nestedOutline;
            }
        }
        if (venueObj.has("outline")) { pickedKey = "outline"; candidate = venueObj.opt("outline"); }
        else if (venueObj.has("outlines")) { pickedKey = "outlines"; candidate = venueObj.opt("outlines"); }
        else if (venueObj.has("polygon")) { pickedKey = "polygon"; candidate = venueObj.opt("polygon"); }
        else if (venueObj.has("polygons")) { pickedKey = "polygons"; candidate = venueObj.opt("polygons"); }
        else if (venueObj.has("boundary")) { pickedKey = "boundary"; candidate = venueObj.opt("boundary"); }
        else if (venueObj.has("boundary_points")) { pickedKey = "boundary_points"; candidate = venueObj.opt("boundary_points"); }
        else if (venueObj.has("footprint")) { pickedKey = "footprint"; candidate = venueObj.opt("footprint"); }
        else if (venueObj.has("shape")) { pickedKey = "shape"; candidate = venueObj.opt("shape"); }
        else if (venueObj.has("geometry")) { pickedKey = "geometry"; candidate = venueObj.opt("geometry"); }
        else if (venueObj.has("geojson")) { pickedKey = "geojson"; candidate = venueObj.opt("geojson"); }
        else if (venueObj.has("coordinates")) { pickedKey = "coordinates"; candidate = venueObj.opt("coordinates"); }
        // If this came from a GeoJSON-ish field, prefer lon/lat ordering.
        if ("geometry".equals(pickedKey) || "geojson".equals(pickedKey) || "coordinates".equals(pickedKey)) {
            preferLonLat = true;
        }
        // Unwrap embedded JSON if the API returned it as a string.
        candidate = unwrapJsonString(candidate);
        if (candidate instanceof JSONObject) {
            JSONObject obj = (JSONObject) candidate;
            String type = obj.optString("type", "");
            if ("FeatureCollection".equalsIgnoreCase(type) || obj.has("features")) {
                // CRS84 / GeoJSON => lon,lat
                preferLonLat = true;
            }
        }
        // If boundary/geometry is an object, pull out its coordinates/geometry/features.
        if (candidate instanceof JSONObject) {
            JSONObject o = (JSONObject) candidate;
            // FeatureCollection => recurse into features
            Object feats = o.opt("features");
            if (feats != null) {
                candidate = feats;
                preferLonLat = true;
            } else {
                Object geom = o.opt("geometry");
                if (geom != null) {
                    candidate = geom;
                    preferLonLat = true;
                } else {
                    Object coords = o.opt("coordinates");
                    if (coords != null) {
                        candidate = coords;
                        preferLonLat = true;
                    }
                }
            }
        }
        // Extract lat/lng pairs from nested arrays/objects
        extractLatLngs(candidate, outline, preferLonLat);
// If we still didn't find a polygon, try a shallow scan of all fields looking for coordinate arrays.
        if (outline.size() < 3) {
            JSONArray names = venueObj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i, null);
                    if (k == null) continue;
                    Object val = venueObj.opt(k);
                    if (val == null) continue;
                    // Avoid scanning huge blobs (strings)
                    if (val instanceof String) {
                        // Might be embedded GeoJSON as string
                        Object unwrapped = unwrapJsonString(val);
                        if (unwrapped == val) continue;
                        val = unwrapped;
                    }
                    List<LatLng> tmp = new ArrayList<>();
                    extractLatLngs(val, tmp, preferLonLat);
                    if (tmp.size() >= 3) {
                        outline.clear();
                        outline.addAll(tmp);
                        break;
                    }
                }
            }
        }
        // Some APIs return GeoJSON Polygon -> first ring only (outline already extracted).
        // De-duplicate consecutive identical points.
        if (outline.size() >= 2) {
            List<LatLng> cleaned = new ArrayList<>();
            LatLng last = null;
            for (LatLng p : outline) {
                if (p == null) continue;
                if (last == null || (last.latitude != p.latitude || last.longitude != p.longitude)) {
                    cleaned.add(p);
                    last = p;
                }
            }
            outline = cleaned;
        }
        return outline;
    }
    /** Recursively extract coordinate pairs from nested JSON structures. */
    private static void extractLatLngs(@Nullable Object node, @NonNull List<LatLng> out) {
        extractLatLngs(node, out, false);
    }
    /** Recursively extract coordinate pairs; when preferLonLat is true, interpret [x,y] as [lon,lat]. */
    private static void extractLatLngs(@Nullable Object node, @NonNull List<LatLng> out, boolean preferLonLat) {
        if (node == null) return;
        if (node instanceof LatLng) {
            out.add((LatLng) node);
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            LatLng p = parseLatLng(o);
            if (p != null) {
                out.add(p);
                return;
            }
            // GeoJSON object / FeatureCollection
            Object feats = o.opt("features");
            if (feats != null) {
                extractLatLngs(feats, out, true);
                return;
            }
            Object geom = o.opt("geometry");
            if (geom != null) {
                extractLatLngs(geom, out, true);
                return;
            }
            Object coords = o.opt("coordinates");
            if (coords != null) extractLatLngs(coords, out, true);
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            // GeoJSON Polygon/MultiPolygon often looks like:
            // Polygon:      [ [ [lng,lat], ... ] , [hole...], ... ]
            // MultiPolygon: [ [ [ [lng,lat], ... ] ], ... ]
            // We only need the first ring of the first polygon to draw an outline.
            if (a.length() > 0 && a.opt(0) instanceof JSONArray) {
                Object first = a.opt(0);
                // MultiPolygon -> first polygon
                if (first instanceof JSONArray) {
                    JSONArray firstArr = (JSONArray) first;
                    if (firstArr.length() > 0 && firstArr.opt(0) instanceof JSONArray) {
                        Object maybePair = ((JSONArray) firstArr).opt(0);
                        // If it's a pair list (ring), drill into it.
                        if (maybePair instanceof JSONArray && ((JSONArray) maybePair).length() >= 2
                                && isNumber(((JSONArray) maybePair).opt(0)) && isNumber(((JSONArray) maybePair).opt(1))) {
                            extractLatLngs(firstArr, out, preferLonLat);
                            return;
                        }
                        // If it's MultiPolygon depth, drill into first polygon first ring.
                        if (maybePair instanceof JSONArray && ((JSONArray) maybePair).length() > 0
                                && ((JSONArray) maybePair).opt(0) instanceof JSONArray) {
                            extractLatLngs(maybePair, out, preferLonLat);
                            return;
                        }
                    }
                }
            }
            // If this array is a coordinate pair [x,y]
            if (a.length() >= 2 && isNumber(a.opt(0)) && isNumber(a.opt(1))) {
                double v0 = a.optDouble(0, Double.NaN);
                double v1 = a.optDouble(1, Double.NaN);
                if (!Double.isNaN(v0) && !Double.isNaN(v1)) {
                    // If preferLonLat is true (GeoJSON/CRS84), interpret as [lon,lat].
                    // Otherwise fall back to a heuristic.
                    LatLng p;
                    if (preferLonLat) {
                        p = new LatLng(v1, v0);
                    } else {
                        // Heuristic: if abs(v0) > 90 then it's likely lon first
                        p = (Math.abs(v0) > 90) ? new LatLng(v1, v0) : new LatLng(v0, v1);
                    }
                    out.add(p);
                }
                return;
            }
            // Otherwise, recurse into children
            for (int i = 0; i < a.length(); i++) {
                extractLatLngs(a.opt(i), out, preferLonLat);
            }
        }
    }
    /** If node is a JSON string (escaped or raw), parse it into JSONObject/JSONArray. */
    private static @Nullable Object unwrapJsonString(@Nullable Object node) {
        if (!(node instanceof String)) return node;
        String s = ((String) node).trim();
        if (s.isEmpty()) return node;
        // Some responses double-escape: "{...}". Try a light unescape first.
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1);
        }
        // Replace common escape sequences from embedding JSON in a string
        if (s.contains("\\\"")) s = s.replace("\\\"", "\"");
        if (s.contains("\\n")) s = s.replace("\\n", "");
        if (s.contains("\\r")) s = s.replace("\\r", "");
        if (s.contains("\\t")) s = s.replace("\\t", "");
        if (!(s.startsWith("{") || s.startsWith("["))) return node;
        try {
            return new org.json.JSONTokener(s).nextValue();
        } catch (Exception ignore) {
            return node;
        }
    }
    private static boolean isNumber(@Nullable Object o) {
        return o instanceof Number;
    }
    private static @Nullable LatLng parseLatLngAny(@Nullable Object node) {
        if (node == null) return null;
        if (node instanceof JSONObject) return parseLatLng((JSONObject) node);
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            if (a.length() >= 2 && isNumber(a.opt(0)) && isNumber(a.opt(1))) {
                double v0 = a.optDouble(0, Double.NaN);
                double v1 = a.optDouble(1, Double.NaN);
                if (!Double.isNaN(v0) && !Double.isNaN(v1)) {
                    return (Math.abs(v0) > 90) ? new LatLng(v1, v0) : new LatLng(v0, v1);
                }
            }
        }
        return null;
    }
    private void postSuccess(@NonNull VenuesCallback cb, @NonNull List<FloorplanModels.Venue> venues) {
        mainHandler.post(() -> cb.onSuccess(venues));
    }
    private void postError(@NonNull VenuesCallback cb, @NonNull String msg) {
        mainHandler.post(() -> cb.onError(msg));
    }
}
