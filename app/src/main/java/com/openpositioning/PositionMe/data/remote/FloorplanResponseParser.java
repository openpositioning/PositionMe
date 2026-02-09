package com.openpositioning.PositionMe.data.remote;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.model.FloorplanLevel;
import com.openpositioning.PositionMe.data.remote.model.FloorplanVenue;
import com.openpositioning.PositionMe.data.remote.model.MapShapeData;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Parser for floorplan API payloads.
 */
final class FloorplanResponseParser {
    private static final String TAG = "FloorplanService";

    private FloorplanResponseParser() {
    }

    static List<FloorplanVenue> parseFloorplanResponse(String responseBody) throws JSONException {
        List<FloorplanVenue> venues = new ArrayList<>();
        if (responseBody == null || responseBody.trim().isEmpty()) {
            return venues;
        }

        Object root = parsePossiblyStringifiedJson(responseBody);
        if (root instanceof JSONArray) {
            JSONArray rootArray = (JSONArray) root;
            for (int i = 0; i < rootArray.length(); i++) {
                Object item = rootArray.opt(i);
                if (item instanceof JSONObject) {
                    FloorplanVenue venue = parseVenueObject((JSONObject) item);
                    if (venue != null) {
                        venues.add(venue);
                    }
                }
            }
        } else if (root instanceof JSONObject) {
            JSONObject rootObj = (JSONObject) root;
            if (rootObj.has("data") && rootObj.opt("data") instanceof JSONArray) {
                JSONArray arr = rootObj.optJSONArray("data");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        Object item = arr.opt(i);
                        if (item instanceof JSONObject) {
                            FloorplanVenue venue = parseVenueObject((JSONObject) item);
                            if (venue != null) {
                                venues.add(venue);
                            }
                        }
                    }
                }
            } else {
                FloorplanVenue venue = parseVenueObject(rootObj);
                if (venue != null) {
                    venues.add(venue);
                }
            }
        }

        return venues;
    }

    private static FloorplanVenue parseVenueObject(JSONObject venueObj) {
        String campaign = firstNonEmpty(
                venueObj.optString("campaign", null),
                venueObj.optString("name", null),
                venueObj.optString("venue", null),
                venueObj.optString("id", null)
        );

        if (campaign == null || campaign.trim().isEmpty()) {
            return null;
        }

        List<LatLng> outlinePoints = parseOutlinePoints(venueObj.opt("outline"));
        if (outlinePoints.size() < 3) {
            Log.w(TAG, "Skipping venue without valid outline: " + campaign);
            return null;
        }

        List<FloorplanLevel> levels = parseLevels(venueObj.opt("map_shapes"));
        return new FloorplanVenue(campaign, outlinePoints, levels);
    }

    private static List<LatLng> parseOutlinePoints(Object outlineRaw) {
        try {
            Object parsed = parsePossiblyStringifiedJson(outlineRaw);
            if (parsed instanceof JSONObject) {
                JSONObject obj = (JSONObject) parsed;
                Object direct = firstNonNull(
                        obj.opt("coordinates"),
                        obj.opt("points"),
                        obj.opt("path"),
                        obj.opt("outline")
                );
                if (direct == null) {
                    // GeoJSON FeatureCollection / Feature support
                    JSONArray features = obj.optJSONArray("features");
                    if (features != null && features.length() > 0) {
                        JSONObject feature0 = features.optJSONObject(0);
                        if (feature0 != null) {
                            JSONObject geometry = feature0.optJSONObject("geometry");
                            if (geometry != null) {
                                direct = firstNonNull(
                                        geometry.opt("coordinates"),
                                        geometry.opt("points"),
                                        geometry.opt("path")
                                );
                            }
                        }
                    }
                }
                parsed = direct;
            }
            if (parsed instanceof String) {
                String text = ((String) parsed).trim();
                if (text.toUpperCase().startsWith("POLYGON")
                        || text.toUpperCase().startsWith("MULTIPOLYGON")) {
                    return parseWktOutline(text);
                }
            }
            List<LatLng> points = parseLatLngList(parsed);
            if ((points == null || points.size() < 3) && parsed != null) {
                Log.w(TAG, "Outline parsed with insufficient points. rawType="
                        + parsed.getClass().getSimpleName()
                        + ", preview=" + preview(String.valueOf(parsed), 180));
            }
            return points != null ? points : new ArrayList<>();
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse outline: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<FloorplanLevel> parseLevels(Object mapShapesRaw) {
        List<FloorplanLevel> levels = new ArrayList<>();
        try {
            Object parsed = parsePossiblyStringifiedJson(mapShapesRaw);
            if (parsed instanceof JSONObject) {
                JSONObject floorMap = (JSONObject) parsed;
                Iterator<String> keys = floorMap.keys();
                while (keys.hasNext()) {
                    String floorKey = keys.next();
                    Object floorValue = floorMap.opt(floorKey);
                    List<MapShapeData> shapes = parseShapesForFloor(floorValue);
                    levels.add(new FloorplanLevel(floorKey, shapes));
                }
            } else if (parsed instanceof JSONArray) {
                JSONArray floors = (JSONArray) parsed;
                List<MapShapeData> shapes = parseShapesForFloor(floors);
                levels.add(new FloorplanLevel("default", shapes));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse map_shapes: " + e.getMessage());
        }

        return levels;
    }

    private static List<MapShapeData> parseShapesForFloor(Object floorValue) {
        List<MapShapeData> shapes = new ArrayList<>();

        if (floorValue instanceof JSONObject) {
            JSONObject obj = (JSONObject) floorValue;
            if (looksLikeShapeObject(obj)) {
                MapShapeData shape = parseShape(obj);
                if (shape != null) {
                    shapes.add(shape);
                }
                return shapes;
            }

            JSONArray candidateArray = firstJSONArray(obj,
                    "shapes", "walls", "polygons", "lines", "objects", "features", "data");
            if (candidateArray != null) {
                return parseShapesForFloor(candidateArray);
            }

            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object nested = obj.opt(key);
                shapes.addAll(parseShapesForFloor(nested));
            }
        } else if (floorValue instanceof JSONArray) {
            JSONArray arr = (JSONArray) floorValue;
            for (int i = 0; i < arr.length(); i++) {
                Object entry = arr.opt(i);
                if (entry instanceof JSONObject) {
                    MapShapeData shape = parseShape((JSONObject) entry);
                    if (shape != null) {
                        shapes.add(shape);
                    }
                } else {
                    List<LatLng> points = parseLatLngList(entry);
                    if (points != null && points.size() >= 2) {
                        boolean isClosed = isClosed(points);
                        shapes.add(new MapShapeData(
                                isClosed ? MapShapeData.ShapeType.POLYGON : MapShapeData.ShapeType.POLYLINE,
                                points,
                                null,
                                null,
                                3f
                        ));
                    }
                }
            }
        }

        return shapes;
    }

    private static MapShapeData parseShape(JSONObject shapeObj) {
        List<LatLng> points = null;

        Object pointsCandidate = firstNonNull(
                shapeObj.opt("points"),
                shapeObj.opt("path"),
                shapeObj.opt("coordinates"),
                shapeObj.opt("vertices"),
                shapeObj.opt("polygon"),
                shapeObj.opt("polyline")
        );

        if (pointsCandidate == null) {
            JSONObject geometryObj = shapeObj.optJSONObject("geometry");
            if (geometryObj != null) {
                pointsCandidate = firstNonNull(
                        geometryObj.opt("coordinates"),
                        geometryObj.opt("points"),
                        geometryObj.opt("path")
                );
            }
        }

        if (pointsCandidate != null) {
            points = parseLatLngList(pointsCandidate);
        }

        if (points == null || points.size() < 2) {
            return null;
        }

        String typeValue = firstNonEmpty(
                shapeObj.optString("type", null),
                shapeObj.optString("shape_type", null)
        );

        boolean closed = isClosed(points)
                || shapeObj.optBoolean("closed", false)
                || (typeValue != null && typeValue.toLowerCase().contains("polygon"));

        String strokeColor = firstNonEmpty(
                shapeObj.optString("stroke_color", null),
                shapeObj.optString("line_color", null),
                shapeObj.optString("color", null)
        );
        String fillColor = firstNonEmpty(
                shapeObj.optString("fill_color", null),
                shapeObj.optString("fill", null)
        );

        float strokeWidth = 3f;
        if (shapeObj.has("stroke_width")) {
            strokeWidth = (float) shapeObj.optDouble("stroke_width", 3.0);
        } else if (shapeObj.has("line_width")) {
            strokeWidth = (float) shapeObj.optDouble("line_width", 3.0);
        } else if (shapeObj.has("width")) {
            strokeWidth = (float) shapeObj.optDouble("width", 3.0);
        }

        return new MapShapeData(
                closed ? MapShapeData.ShapeType.POLYGON : MapShapeData.ShapeType.POLYLINE,
                points,
                strokeColor,
                fillColor,
                strokeWidth
        );
    }

    private static JSONArray firstJSONArray(JSONObject obj, String... keys) {
        for (String key : keys) {
            Object value = obj.opt(key);
            if (value instanceof JSONArray) {
                return (JSONArray) value;
            }
        }
        return null;
    }

    private static boolean looksLikeShapeObject(JSONObject obj) {
        return obj.has("points")
                || obj.has("path")
                || obj.has("coordinates")
                || obj.has("vertices")
                || obj.has("polygon")
                || obj.has("polyline")
                || (obj.has("geometry") && obj.opt("geometry") instanceof JSONObject);
    }

    private static List<LatLng> parseLatLngList(Object raw) {
        try {
            Object parsed = parsePossiblyStringifiedJson(raw);
            if (!(parsed instanceof JSONArray)) {
                return null;
            }

            JSONArray arr = (JSONArray) parsed;
            // Handle nested geometry arrays, e.g. [[[[lon,lat],...]]] by descending to first ring.
            arr = unwrapToCoordinatePairArray(arr);

            List<LatLng> points = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Object point = arr.opt(i);
                LatLng parsedPoint = parsePoint(point);
                if (parsedPoint != null) {
                    points.add(parsedPoint);
                }
            }

            return points;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONArray unwrapToCoordinatePairArray(JSONArray arr) {
        JSONArray current = arr;
        while (current != null && current.length() > 0 && current.opt(0) instanceof JSONArray) {
            JSONArray first = current.optJSONArray(0);
            if (first == null) {
                break;
            }
            if (looksLikeCoordinatePair(first)) {
                break;
            }
            current = first;
        }
        return current == null ? arr : current;
    }

    private static boolean looksLikeCoordinatePair(JSONArray candidate) {
        if (candidate.length() < 2) {
            return false;
        }
        Object first = candidate.opt(0);
        Object second = candidate.opt(1);
        return isNumericLike(first) && isNumericLike(second);
    }

    private static boolean isNumericLike(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (value instanceof String) {
            try {
                Double.parseDouble((String) value);
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private static LatLng parsePoint(Object pointObj) {
        if (pointObj instanceof JSONArray) {
            JSONArray pointArr = (JSONArray) pointObj;
            if (pointArr.length() >= 2) {
                double first = pointArr.optDouble(0, Double.NaN);
                double second = pointArr.optDouble(1, Double.NaN);
                if (Double.isNaN(first) || Double.isNaN(second)) {
                    return null;
                }
                // Non-ambiguous [lon, lat].
                if (Math.abs(first) > 90 && Math.abs(first) <= 180 && Math.abs(second) <= 90) {
                    return new LatLng(second, first);
                }
                // Non-ambiguous [lat, lon].
                if (Math.abs(first) <= 90 && Math.abs(second) > 90 && Math.abs(second) <= 180) {
                    return new LatLng(first, second);
                }
                // Ambiguous when both are within [-90, 90]. Use magnitude heuristic:
                // latitude is often farther from zero than longitude in this dataset.
                double absFirst = Math.abs(first);
                double absSecond = Math.abs(second);
                if (absFirst > absSecond + 1.0) {
                    return new LatLng(first, second);
                }
                if (absSecond > absFirst + 1.0) {
                    return new LatLng(second, first);
                }
                // Final fallback to GeoJSON [lon, lat].
                if (Math.abs(first) <= 180 && Math.abs(second) <= 90) {
                    return new LatLng(second, first);
                }
                if (Math.abs(first) <= 90 && Math.abs(second) <= 180) {
                    return new LatLng(first, second);
                }
            }
            return null;
        }

        if (pointObj instanceof JSONObject) {
            JSONObject pointJson = (JSONObject) pointObj;
            Double lat = nullableDouble(
                    firstNonNull(pointJson.opt("lat"), pointJson.opt("latitude"), pointJson.opt("y"))
            );
            Double lon = nullableDouble(
                    firstNonNull(pointJson.opt("lon"), pointJson.opt("lng"), pointJson.opt("longitude"), pointJson.opt("x"))
            );

            if (lat != null && lon != null) {
                return new LatLng(lat, lon);
            }

            Object coords = pointJson.opt("coordinates");
            if (coords != null) {
                return parsePoint(coords);
            }
        }

        return null;
    }

    private static Double nullableDouble(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object parsePossiblyStringifiedJson(Object raw) throws JSONException {
        if (raw == null || raw == JSONObject.NULL) {
            return null;
        }

        if (raw instanceof JSONObject || raw instanceof JSONArray) {
            return raw;
        }

        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (text.isEmpty()) {
                return null;
            }
            if (text.startsWith("{")) {
                return new JSONObject(text);
            }
            if (text.startsWith("[")) {
                return new JSONArray(text);
            }
            return raw;
        }

        return raw;
    }

    private static List<LatLng> parseWktOutline(String wkt) {
        List<LatLng> points = new ArrayList<>();
        if (wkt == null) {
            return points;
        }

        String upper = wkt.trim().toUpperCase();
        String coordSection;
        if (upper.startsWith("MULTIPOLYGON")) {
            int start = wkt.indexOf("(((");
            int end = wkt.lastIndexOf(")))");
            if (start < 0 || end <= start) {
                return points;
            }
            coordSection = wkt.substring(start + 3, end);
            int ringSeparator = coordSection.indexOf("),(");
            if (ringSeparator > 0) {
                coordSection = coordSection.substring(0, ringSeparator);
            }
        } else if (upper.startsWith("POLYGON")) {
            int start = wkt.indexOf("((");
            int end = wkt.lastIndexOf("))");
            if (start < 0 || end <= start) {
                return points;
            }
            coordSection = wkt.substring(start + 2, end);
            int ringSeparator = coordSection.indexOf("),(");
            if (ringSeparator > 0) {
                coordSection = coordSection.substring(0, ringSeparator);
            }
        } else {
            return points;
        }

        String[] pairs = coordSection.split(",");
        for (String pair : pairs) {
            String[] values = pair.trim().split("\\s+");
            if (values.length < 2) {
                continue;
            }
            try {
                // WKT coordinates are typically lon lat
                double lon = Double.parseDouble(values[0]);
                double lat = Double.parseDouble(values[1]);
                points.add(new LatLng(lat, lon));
            } catch (NumberFormatException ignored) {
                // skip malformed pair
            }
        }
        return points;
    }

    private static String preview(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private static boolean isClosed(List<LatLng> points) {
        if (points.size() < 3) {
            return false;
        }
        LatLng first = points.get(0);
        LatLng last = points.get(points.size() - 1);
        return Math.abs(first.latitude - last.latitude) < 1e-6
                && Math.abs(first.longitude - last.longitude) < 1e-6;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim())) {
                return value;
            }
        }
        return null;
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null && value != JSONObject.NULL) {
                return value;
            }
        }
        return null;
    }
}
