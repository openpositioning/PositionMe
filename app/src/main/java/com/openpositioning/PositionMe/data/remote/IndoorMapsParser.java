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
        @NonNull public final JSONObject raw;      // full venue JSON
        @NonNull public final String name;         // venue name (if present)
        @NonNull public final JSONObject outline;  // parsed GeoJSON outline object

        public Venue(@NonNull JSONObject raw,
                     @NonNull String name,
                     @NonNull JSONObject outline) {
            this.raw = raw;
            this.name = name;
            this.outline = outline;
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

        JSONObject outlineObj = venueObj.optJSONObject("outline");
        if (outlineObj == null) {
            // sometimes it's a string containing JSON
            String outlineStr = venueObj.optString("outline", null);
            if (outlineStr != null) {
                String o = outlineStr.trim();
                if (o.startsWith("{")) {
                    outlineObj = new JSONObject(o);
                }
            }
        }

        if (outlineObj == null) return null;

        return new Venue(venueObj, name, outlineObj);
    }
}
