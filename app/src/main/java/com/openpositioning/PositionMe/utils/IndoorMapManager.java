package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.openpositioning.PositionMe.sensors.Wifi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class IndoorMapManager {
    private static final String TAG = "IndoorMapManager";
    private static final String FLOORPLAN_REQUEST_URL = "https://openpositioning.org/api/live/floorplan/request";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long REQUEST_INTERVAL_MS = 8_000L;
    private static final float REQUEST_DISTANCE_M = 8f;
    private static final float DEFAULT_FLOOR_HEIGHT_M = 3.6f;

    private static final int VENUE_STROKE = Color.argb(220, 0, 190, 255);
    private static final int VENUE_FILL = Color.argb(45, 0, 190, 255);
    private static final int SELECTED_STROKE = Color.argb(255, 255, 193, 7);
    private static final int SELECTED_FILL = Color.argb(90, 255, 193, 7);

    private final GoogleMap gMap;
    private final OkHttpClient httpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, VenueModel> venuesById = new LinkedHashMap<>();
    private final Map<String, Polygon> polygonsByVenueId = new HashMap<>();
    private final Map<String, BitmapDescriptor> floorImageCache = new HashMap<>();

    private GroundOverlay groundOverlay;
    private LatLng currentLocation;
    private LatLng lastRequestLocation;
    private long lastRequestTs;
    private boolean requestInFlight;
    private boolean isIndoorMapSet;
    private int currentFloor;
    private float floorHeight = DEFAULT_FLOOR_HEIGHT_M;
    private int floorImageToken;
    private String selectedVenueId;
    private VenueSelectionListener venueSelectionListener;

    public interface VenueSelectionListener {
        void onVenueSelected(@Nullable String venueId, @Nullable String venueName);
    }

    public IndoorMapManager(@NonNull GoogleMap map) {
        this.gMap = map;
        this.httpClient = new OkHttpClient();
    }

    public IndoorMapManager(@NonNull Context context, @NonNull GoogleMap map) {
        this(map);
    }

    public void setVenueSelectionListener(@Nullable VenueSelectionListener listener) {
        this.venueSelectionListener = listener;
    }

    public void setCurrentLocation(@Nullable LatLng location) {
        this.currentLocation = location;
    }

    public void refreshNearbyVenues(@Nullable LatLng location, @Nullable List<Wifi> observedAps) {
        if (location == null || requestInFlight) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastRequestLocation != null) {
            if (now - lastRequestTs < REQUEST_INTERVAL_MS
                    && distanceMeters(lastRequestLocation, location) < REQUEST_DISTANCE_M) {
                return;
            }
        }

        JSONObject payload;
        try {
            payload = buildRequestPayload(location, observedAps);
        } catch (JSONException e) {
            Log.e(TAG, "Cannot build floorplan payload", e);
            return;
        }

        requestInFlight = true;
        lastRequestTs = now;
        lastRequestLocation = location;

        Request request = new Request.Builder()
                .url(FLOORPLAN_REQUEST_URL)
                .post(RequestBody.create(payload.toString(), JSON))
                .addHeader("accept", "application/json")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                requestInFlight = false;
                Log.e(TAG, "Nearby floorplan request failed", e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful() || body == null) {
                        Log.w(TAG, "Nearby floorplan request not successful: " + response.code());
                        return;
                    }
                    List<VenueModel> venues = parseVenueResponse(body.string());
                    mainHandler.post(() -> applyNearbyVenues(venues));
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to parse nearby floorplan response", ex);
                } finally {
                    requestInFlight = false;
                }
            }
        });
    }

    public boolean onVenuePolygonClicked(@Nullable Polygon polygon) {
        if (polygon == null || polygon.getTag() == null) {
            return false;
        }
        return selectVenue(String.valueOf(polygon.getTag()));
    }

    public boolean selectVenue(@Nullable String venueId) {
        if (TextUtils.isEmpty(venueId)) {
            return false;
        }
        VenueModel venue = venuesById.get(venueId);
        if (venue == null) {
            return false;
        }

        selectedVenueId = venue.id;
        floorHeight = venue.floorHeight;
        currentFloor = Math.min(currentFloor, Math.max(venue.floors.size() - 1, 0));
        renderCurrentFloor();
        updatePolygonStyle();

        if (venueSelectionListener != null) {
            venueSelectionListener.onVenueSelected(venue.id, venue.name);
        }
        return true;
    }

    public boolean getIsIndoorMapSet() {
        return isIndoorMapSet;
    }

    public float getFloorHeight() {
        return floorHeight;
    }

    public int getFloorCount() {
        VenueModel selected = getSelectedVenue();
        return selected == null ? 0 : selected.floors.size();
    }

    public int getCurrentFloor() {
        return currentFloor;
    }

    @Nullable
    public String getSelectedVenueId() {
        return selectedVenueId;
    }

    @Nullable
    public String getSelectedVenueName() {
        VenueModel selected = getSelectedVenue();
        return selected == null ? null : selected.name;
    }

    public void setCurrentFloor(int newFloor, boolean autoFloor) {
        VenueModel selected = getSelectedVenue();
        if (selected == null || selected.floors.isEmpty()) {
            return;
        }
        int bounded = Math.max(0, Math.min(newFloor, selected.floors.size() - 1));
        if (bounded == currentFloor && isIndoorMapSet) {
            return;
        }
        currentFloor = bounded;
        renderCurrentFloor();
    }

    public void increaseFloor() {
        setCurrentFloor(currentFloor + 1, false);
    }

    public void decreaseFloor() {
        setCurrentFloor(currentFloor - 1, false);
    }

    private void renderCurrentFloor() {
        VenueModel selected = getSelectedVenue();
        if (selected == null || selected.floors.isEmpty()) {
            removeGroundOverlay();
            return;
        }
        FloorModel floor = selected.floors.get(currentFloor);
        BitmapDescriptor cached = floorImageCache.get(floor.imageUrl);
        if (cached != null) {
            setGroundOverlay(cached, floor.bounds);
            return;
        }

        int requestToken = ++floorImageToken;
        Request req = new Request.Builder().url(floor.imageUrl).get().build();
        httpClient.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Failed to download floor image: " + floor.imageUrl, e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful() || body == null) {
                        return;
                    }
                    byte[] bytes = body.bytes();
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap == null) {
                        return;
                    }
                    BitmapDescriptor descriptor = BitmapDescriptorFactory.fromBitmap(bitmap);
                    floorImageCache.put(floor.imageUrl, descriptor);
                    mainHandler.post(() -> {
                        if (requestToken != floorImageToken) {
                            return;
                        }
                        VenueModel latest = getSelectedVenue();
                        if (latest == null || latest.floors.isEmpty()) {
                            return;
                        }
                        if (currentFloor < 0 || currentFloor >= latest.floors.size()) {
                            return;
                        }
                        FloorModel current = latest.floors.get(currentFloor);
                        if (!floor.imageUrl.equals(current.imageUrl)) {
                            return;
                        }
                        setGroundOverlay(descriptor, current.bounds);
                    });
                }
            }
        });
    }

    private void setGroundOverlay(@NonNull BitmapDescriptor descriptor, @NonNull LatLngBounds bounds) {
        removeGroundOverlay();
        groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                .image(descriptor)
                .positionFromBounds(bounds)
                .zIndex(10f));
        isIndoorMapSet = groundOverlay != null;
    }

    private void removeGroundOverlay() {
        if (groundOverlay != null) {
            groundOverlay.remove();
            groundOverlay = null;
        }
        isIndoorMapSet = false;
    }

    private void applyNearbyVenues(@NonNull List<VenueModel> venues) {
        venuesById.clear();
        for (VenueModel venue : venues) {
            venuesById.put(venue.id, venue);
        }

        for (Polygon polygon : polygonsByVenueId.values()) {
            polygon.remove();
        }
        polygonsByVenueId.clear();

        for (VenueModel venue : venues) {
            if (venue.outline.size() < 3) {
                continue;
            }
            Polygon polygon = gMap.addPolygon(new PolygonOptions()
                    .addAll(venue.outline)
                    .strokeColor(VENUE_STROKE)
                    .fillColor(VENUE_FILL)
                    .strokeWidth(4f)
                    .clickable(true)
                    .zIndex(5f));
            polygon.setTag(venue.id);
            polygonsByVenueId.put(venue.id, polygon);
        }
        updatePolygonStyle();

        if (!TextUtils.isEmpty(selectedVenueId) && venuesById.containsKey(selectedVenueId)) {
            selectVenue(selectedVenueId);
        } else {
            selectedVenueId = null;
            currentFloor = 0;
            floorHeight = DEFAULT_FLOOR_HEIGHT_M;
            removeGroundOverlay();
            if (venueSelectionListener != null) {
                venueSelectionListener.onVenueSelected(null, null);
            }
        }
    }

    private void updatePolygonStyle() {
        for (Map.Entry<String, Polygon> entry : polygonsByVenueId.entrySet()) {
            boolean selected = entry.getKey().equals(selectedVenueId);
            Polygon polygon = entry.getValue();
            polygon.setStrokeColor(selected ? SELECTED_STROKE : VENUE_STROKE);
            polygon.setFillColor(selected ? SELECTED_FILL : VENUE_FILL);
            polygon.setStrokeWidth(selected ? 7f : 4f);
        }
    }

    @Nullable
    private VenueModel getSelectedVenue() {
        if (TextUtils.isEmpty(selectedVenueId)) {
            return null;
        }
        return venuesById.get(selectedVenueId);
    }

    @NonNull
    private JSONObject buildRequestPayload(@NonNull LatLng location, @Nullable List<Wifi> observedAps) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("lat", location.latitude);
        root.put("lon", location.longitude);
        root.put("latitude", location.latitude);
        root.put("longitude", location.longitude);

        JSONObject wf = new JSONObject();
        JSONArray aps = new JSONArray();
        if (observedAps != null) {
            for (Wifi wifi : observedAps) {
                String mac = String.valueOf(wifi.getBssid());
                wf.put(mac, wifi.getLevel());
                JSONObject ap = new JSONObject();
                ap.put("bssid", mac);
                ap.put("rssi", wifi.getLevel());
                ap.put("frequency", wifi.getFrequency());
                String ssid = wifi.getSsid();
                if (!TextUtils.isEmpty(ssid)) {
                    ap.put("ssid", ssid);
                }
                aps.put(ap);
            }
        }
        root.put("wf", wf);
        root.put("aps", aps);
        return root;
    }

    @NonNull
    private List<VenueModel> parseVenueResponse(@NonNull String payload) throws JSONException {
        String trimmed = payload.trim();
        if (trimmed.isEmpty()) {
            return Collections.emptyList();
        }

        JSONArray venueArray = null;
        if (trimmed.startsWith("[")) {
            venueArray = new JSONArray(trimmed);
        } else {
            JSONObject root = new JSONObject(trimmed);
            String[] candidateKeys = new String[]{"venues", "results", "data", "maps", "floorplans"};
            for (String key : candidateKeys) {
                venueArray = root.optJSONArray(key);
                if (venueArray != null) {
                    break;
                }
            }
            if (venueArray == null && looksLikeVenue(root)) {
                venueArray = new JSONArray().put(root);
            }
        }
        if (venueArray == null) {
            return Collections.emptyList();
        }

        List<VenueModel> venues = new ArrayList<>();
        for (int i = 0; i < venueArray.length(); i++) {
            JSONObject v = venueArray.optJSONObject(i);
            if (v == null) {
                continue;
            }
            VenueModel parsed = parseVenue(v, i);
            if (parsed != null) {
                venues.add(parsed);
            }
        }
        return venues;
    }

    private boolean looksLikeVenue(@NonNull JSONObject obj) {
        return obj.has("polygon") || obj.has("outline") || obj.has("geometry") || obj.has("floors");
    }

    @Nullable
    private VenueModel parseVenue(@NonNull JSONObject obj, int index) {
        String id = firstNonEmpty(obj, "venue_id", "venueId", "id", "slug", "name");
        if (TextUtils.isEmpty(id)) {
            id = "venue_" + index;
        }
        String name = firstNonEmpty(obj, "name", "venue_name", "title", "building");
        if (TextUtils.isEmpty(name)) {
            name = id;
        }

        List<LatLng> outline = parseOutline(obj);
        LatLngBounds bounds = parseBounds(obj);
        if ((outline == null || outline.size() < 3) && bounds != null) {
            outline = boundsToPolygon(bounds);
        }
        if (outline == null || outline.size() < 3) {
            return null;
        }
        if (bounds == null) {
            bounds = calculateBounds(outline);
        }
        if (bounds == null) {
            return null;
        }

        List<FloorModel> floors = parseFloors(obj, bounds);
        float parsedFloorHeight = (float) optDouble(obj, DEFAULT_FLOOR_HEIGHT_M, "floor_height", "floorHeight");
        return new VenueModel(id, name, outline, floors, parsedFloorHeight);
    }

    @NonNull
    private List<FloorModel> parseFloors(@NonNull JSONObject venue, @NonNull LatLngBounds fallbackBounds) {
        JSONArray arr = null;
        String[] keys = new String[]{"floors", "floorplans", "maps", "levels"};
        for (String key : keys) {
            arr = venue.optJSONArray(key);
            if (arr != null) {
                break;
            }
        }
        if (arr == null) {
            return Collections.emptyList();
        }

        List<FloorModel> floors = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject f = arr.optJSONObject(i);
            if (f == null) {
                continue;
            }
            String imageUrl = firstNonEmpty(f, "image_url", "imageUrl", "url", "floorplan_url", "map_url");
            if (TextUtils.isEmpty(imageUrl)) {
                JSONObject imageObj = f.optJSONObject("image");
                if (imageObj != null) {
                    imageUrl = firstNonEmpty(imageObj, "url", "src", "href");
                }
            }
            imageUrl = normalizeUrl(imageUrl);
            if (TextUtils.isEmpty(imageUrl)) {
                continue;
            }

            int idx = (int) optDouble(f, i, "floor", "index", "level", "floor_index");
            String floorName = firstNonEmpty(f, "name", "label", "title");
            if (TextUtils.isEmpty(floorName)) {
                floorName = String.format(Locale.US, "Floor %d", idx);
            }
            LatLngBounds bounds = parseBounds(f);
            if (bounds == null) {
                bounds = fallbackBounds;
            }
            floors.add(new FloorModel(idx, floorName, imageUrl, bounds));
        }
        floors.sort((a, b) -> Integer.compare(a.floorIndex, b.floorIndex));
        return floors;
    }

    @Nullable
    private String normalizeUrl(@Nullable String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("/")) {
            return "https://openpositioning.org" + url;
        }
        return url;
    }

    @Nullable
    private List<LatLng> parseOutline(@NonNull JSONObject obj) {
        JSONArray arr = obj.optJSONArray("outline");
        if (arr == null) {
            arr = obj.optJSONArray("polygon");
        }
        if (arr == null) {
            arr = obj.optJSONArray("coordinates");
        }
        if (arr == null) {
            JSONObject geometry = obj.optJSONObject("geometry");
            if (geometry != null) {
                arr = geometry.optJSONArray("coordinates");
            }
        }
        if (arr == null) {
            return null;
        }
        return parsePointArray(arr);
    }

    @NonNull
    private List<LatLng> parsePointArray(@NonNull JSONArray arr) {
        if (arr.length() == 0) {
            return Collections.emptyList();
        }
        Object first = arr.opt(0);
        if (first instanceof JSONArray) {
            Object nested = ((JSONArray) first).opt(0);
            if (nested instanceof JSONArray) {
                arr = (JSONArray) first;
            }
        }

        List<LatLng> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            Object p = arr.opt(i);
            LatLng point = null;
            if (p instanceof JSONArray) {
                point = parsePoint((JSONArray) p);
            } else if (p instanceof JSONObject) {
                point = parsePoint((JSONObject) p);
            }
            if (point != null) {
                out.add(point);
            }
        }
        return out;
    }

    @Nullable
    private LatLng parsePoint(@Nullable JSONObject obj) {
        if (obj == null) {
            return null;
        }
        double lat = optDouble(obj, Double.NaN, "lat", "latitude", "y");
        double lon = optDouble(obj, Double.NaN, "lon", "lng", "longitude", "x");
        if (!Double.isNaN(lat) && !Double.isNaN(lon) && isValidCoord(lat, lon)) {
            return new LatLng(lat, lon);
        }
        return null;
    }

    @Nullable
    private LatLng parsePoint(@NonNull JSONArray arr) {
        if (arr.length() < 2) {
            return null;
        }
        double a = arr.optDouble(0, Double.NaN);
        double b = arr.optDouble(1, Double.NaN);
        return parseCoordinatePair(a, b);
    }

    @Nullable
    private LatLng parseCoordinatePair(double a, double b) {
        if (!Double.isFinite(a) || !Double.isFinite(b)) {
            return null;
        }
        LatLng latLon = new LatLng(a, b);
        LatLng lonLat = new LatLng(b, a);
        boolean latLonOk = isValidCoord(latLon.latitude, latLon.longitude);
        boolean lonLatOk = isValidCoord(lonLat.latitude, lonLat.longitude);
        if (latLonOk && !lonLatOk) {
            return latLon;
        }
        if (!latLonOk && lonLatOk) {
            return lonLat;
        }
        if (!latLonOk) {
            return null;
        }
        if (currentLocation == null) {
            return latLon;
        }
        return distanceMeters(currentLocation, latLon) <= distanceMeters(currentLocation, lonLat)
                ? latLon : lonLat;
    }

    @Nullable
    private LatLngBounds parseBounds(@NonNull JSONObject obj) {
        JSONArray bbox = obj.optJSONArray("bbox");
        if (bbox != null && bbox.length() >= 4) {
            double b0 = bbox.optDouble(0, Double.NaN);
            double b1 = bbox.optDouble(1, Double.NaN);
            double b2 = bbox.optDouble(2, Double.NaN);
            double b3 = bbox.optDouble(3, Double.NaN);
            LatLng sw = parseCoordinatePair(b0, b1);
            LatLng ne = parseCoordinatePair(b2, b3);
            if (sw != null && ne != null) {
                return new LatLngBounds(
                        new LatLng(Math.min(sw.latitude, ne.latitude), Math.min(sw.longitude, ne.longitude)),
                        new LatLng(Math.max(sw.latitude, ne.latitude), Math.max(sw.longitude, ne.longitude)));
            }
        }

        JSONObject bounds = obj.optJSONObject("bounds");
        if (bounds == null) {
            return null;
        }
        LatLng sw = parsePoint(bounds.optJSONObject("sw"));
        if (sw == null) {
            sw = parsePoint(bounds.optJSONObject("southWest"));
        }
        if (sw == null) {
            sw = parsePoint(bounds.optJSONObject("south_west"));
        }
        LatLng ne = parsePoint(bounds.optJSONObject("ne"));
        if (ne == null) {
            ne = parsePoint(bounds.optJSONObject("northEast"));
        }
        if (ne == null) {
            ne = parsePoint(bounds.optJSONObject("north_east"));
        }
        if (sw != null && ne != null) {
            return new LatLngBounds(sw, ne);
        }
        return null;
    }

    @Nullable
    private LatLngBounds calculateBounds(@NonNull List<LatLng> points) {
        if (points.isEmpty()) {
            return null;
        }
        double south = Double.POSITIVE_INFINITY;
        double west = Double.POSITIVE_INFINITY;
        double north = Double.NEGATIVE_INFINITY;
        double east = Double.NEGATIVE_INFINITY;
        for (LatLng p : points) {
            south = Math.min(south, p.latitude);
            west = Math.min(west, p.longitude);
            north = Math.max(north, p.latitude);
            east = Math.max(east, p.longitude);
        }
        return new LatLngBounds(new LatLng(south, west), new LatLng(north, east));
    }

    @NonNull
    private List<LatLng> boundsToPolygon(@NonNull LatLngBounds bounds) {
        List<LatLng> points = new ArrayList<>(4);
        points.add(new LatLng(bounds.southwest.latitude, bounds.southwest.longitude));
        points.add(new LatLng(bounds.southwest.latitude, bounds.northeast.longitude));
        points.add(new LatLng(bounds.northeast.latitude, bounds.northeast.longitude));
        points.add(new LatLng(bounds.northeast.latitude, bounds.southwest.longitude));
        return points;
    }

    @Nullable
    private String firstNonEmpty(@NonNull JSONObject obj, @NonNull String... keys) {
        for (String key : keys) {
            String value = obj.optString(key, null);
            if (!TextUtils.isEmpty(value) && !"null".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return null;
    }

    private double optDouble(@NonNull JSONObject obj, double fallback, @NonNull String... keys) {
        for (String key : keys) {
            if (!obj.has(key)) {
                continue;
            }
            Object raw = obj.opt(key);
            if (raw instanceof Number) {
                return ((Number) raw).doubleValue();
            }
            if (raw instanceof String) {
                try {
                    return Double.parseDouble((String) raw);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return fallback;
    }

    private boolean isValidCoord(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private float distanceMeters(@NonNull LatLng a, @NonNull LatLng b) {
        float[] result = new float[1];
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result);
        return result[0];
    }

    private static final class VenueModel {
        final String id;
        final String name;
        final List<LatLng> outline;
        final List<FloorModel> floors;
        final float floorHeight;

        VenueModel(String id, String name, List<LatLng> outline, List<FloorModel> floors, float floorHeight) {
            this.id = id;
            this.name = name;
            this.outline = outline;
            this.floors = floors;
            this.floorHeight = floorHeight;
        }
    }

    private static final class FloorModel {
        final int floorIndex;
        final String floorName;
        final String imageUrl;
        final LatLngBounds bounds;

        FloorModel(int floorIndex, String floorName, String imageUrl, LatLngBounds bounds) {
            this.floorIndex = floorIndex;
            this.floorName = floorName;
            this.imageUrl = imageUrl;
            this.bounds = bounds;
        }
    }
}
