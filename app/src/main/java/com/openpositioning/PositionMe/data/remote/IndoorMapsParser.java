package com.openpositioning.PositionMe.data.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Helper for parsing OpenPositioning "floorplan/request" responses.
 * Handles:
 *  - response being either a JSON object or a JSON array
 *  - "outline" being either a JSONObject or a String containing JSON
 */
public final class IndoorMapsParser {

    private IndoorMapsParser() {}

    public static class Venue {
        @NonNull public final JSONObject raw;      // full venue JSON (normalized; map_shapes/outline parsed when possible)
        @NonNull public final String name;         // venue name (if present)
        @NonNull public final JSONObject outline;  // parsed GeoJSON outline object
        @Nullable public final JSONArray mapShapesArray;   // optional map_shapes as array
        @Nullable public final JSONObject mapShapesObject; // optional map_shapes as object

        public Venue(@NonNull JSONObject raw,
                     @NonNull String name,
                     @NonNull JSONObject outline,
                     @Nullable JSONArray mapShapesArray,
                     @Nullable JSONObject mapShapesObject) {
            this.raw = raw;
            this.name = name;
            this.outline = outline;
            this.mapShapesArray = mapShapesArray;
            this.mapShapesObject = mapShapesObject;
        }
    }

    /**
     * Returns the first venue from the response, or null if none / not parseable.
     */
    @Nullable
    public static Venue parseFirstVenue(@NonNull String json) throws JSONException {
        String trimmed = json.trim();
        if (trimmed.isEmpty()) return null;

        JSONObject venueObj;

        // Response may be: [ {...}, {...} ] or { ... }
        if (trimmed.startsWith("[")) {
            JSONArray arr = new JSONArray(trimmed);
            if (arr.length() == 0) return null;
            venueObj = arr.getJSONObject(0);
        } else {
            venueObj = new JSONObject(trimmed);
        }

        String name = venueObj.optString("name", "unknown");

        // map_shapes can be an array, an object, or a stringified / double-wrapped JSON value
        JSONArray mapShapesArray = null;
        JSONObject mapShapesObject = null;

        ParsedJson parsedMapShapes = parseFlexibleJson(venueObj.opt("map_shapes"));
        if (parsedMapShapes.object != null) mapShapesObject = parsedMapShapes.object;
        if (parsedMapShapes.array != null) mapShapesArray = parsedMapShapes.array;

        // outline can also arrive stringified
        ParsedJson parsedOutline = parseFlexibleJson(venueObj.opt("outline"));
        JSONObject outlineObj = parsedOutline.object;
        if (outlineObj == null) return null;

        // normalize the raw venue so downstream consumers do not need to re-parse strings
        if (mapShapesObject != null) {
            venueObj.put("map_shapes", mapShapesObject);
        } else if (mapShapesArray != null) {
            venueObj.put("map_shapes", mapShapesArray);
        }
        venueObj.put("outline", outlineObj);

        return new Venue(venueObj, name, outlineObj, mapShapesArray, mapShapesObject);
    }

    private static String sanitizeJsonString(@NonNull String s) {
        String trimmed = s.trim();
        // Handle cases like ""{...}"" or '"{...}' where JSON is double-wrapped
        while ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
                (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            if (trimmed.length() <= 2) break;
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static ParsedJson parseFlexibleJson(@Nullable Object value) {
        Object current = value;
        for (int depth = 0; depth < 3 && current != null; depth++) {
            if (current instanceof JSONObject) {
                return new ParsedJson((JSONObject) current, null);
            }
            if (current instanceof JSONArray) {
                return new ParsedJson(null, (JSONArray) current);
            }
            if (current instanceof String) {
                String s = sanitizeJsonString((String) current);
                try {
                    if (s.startsWith("{")) {
                        current = new JSONObject(s);
                        continue;
                    }
                    if (s.startsWith("[")) {
                        current = new JSONArray(s);
                        continue;
                    }
                } catch (JSONException ignored) { }
                // If parsing failed, break to return empty
                break;
            } else {
                break;
            }
        }
        return new ParsedJson(null, null);
    }

    private static final class ParsedJson {
        @Nullable final JSONObject object;
        @Nullable final JSONArray array;

        ParsedJson(@Nullable JSONObject object, @Nullable JSONArray array) {
            this.object = object;
            this.array = array;
        }
    }
}
