/**
 * VenueMapper is responsible for converting raw API response objects
 * (FloorPlanData.VenueDto) into IndoorMapManager.IndoorVenue objects
 * that can be rendered and managed by the map layer.
 *
 * It parses the GeoJSON building outline into a list of LatLng points,
 * computes bounding boxes for overlay placement, and stores raw
 * floor map (mapShapes) data for later floor rendering.
 *
 * This class isolates data transformation logic from both the
 * networking layer (FloorPlanData) and the UI layer
 * (TrajectoryMapFragment / IndoorMapManager), maintaining a clear
 * separation of concerns and improving maintainability.
 */

package com.openpositioning.PositionMe.utils;


import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.openpositioning.PositionMe.data.remote.FloorPlanData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class VenueMapper {

    public static List<IndoorMapManager.IndoorVenue> toIndoorVenues(List<FloorPlanData.VenueDto> dtos) {
        List<IndoorMapManager.IndoorVenue> out = new ArrayList<>();

        for (FloorPlanData.VenueDto dto : dtos) {
            try {
                List<LatLng> outline = parseOutlineGeoJson(dto.outline);
                if (outline.isEmpty()) continue;

                IndoorMapManager.IndoorVenue v = new IndoorMapManager.IndoorVenue();
                v.name = dto.name;
                v.venueId = dto.name; // placeholder (API may provide an id field later)
                v.outline = outline;
                v.bounds = computeBounds(outline);
                v.rawMapShapes = dto.mapShapes;


                out.add(v);

            } catch (Exception e) {
                Log.e("VenueMapper", "Failed mapping venue " + dto.name, e);
            }
        }

        return out;
    }

    /**
     * dto.outline is a STRING that itself contains GeoJSON.
     * Coordinates in GeoJSON are [lon, lat].
     * We take the first polygon ring from the MultiPolygon.
     */
    private static List<LatLng> parseOutlineGeoJson(String outlineJsonString) throws Exception {
        List<LatLng> pts = new ArrayList<>();
        if (outlineJsonString == null || outlineJsonString.isEmpty()) return pts;

        JSONObject fc = new JSONObject(outlineJsonString);
        JSONArray features = fc.optJSONArray("features");
        if (features == null || features.length() == 0) return pts;

        JSONObject geom = features.getJSONObject(0).getJSONObject("geometry");
        String type = geom.optString("type", "");

        JSONArray coords = geom.getJSONArray("coordinates");

        // MultiPolygon: coordinates[ polygon ][ ring ][ point ][ lon/lat ]
        // Polygon: coordinates[ ring ][ point ][ lon/lat ]
        JSONArray ring;

        if ("MultiPolygon".equalsIgnoreCase(type)) {
            ring = coords.getJSONArray(0).getJSONArray(0);
        } else if ("Polygon".equalsIgnoreCase(type)) {
            ring = coords.getJSONArray(0);
        } else {
            return pts;
        }

        for (int i = 0; i < ring.length(); i++) {
            JSONArray p = ring.getJSONArray(i);
            double lon = p.getDouble(0);
            double lat = p.getDouble(1);
            pts.add(new LatLng(lat, lon));
        }

        return pts;
    }

    private static LatLngBounds computeBounds(List<LatLng> pts) {
        double minLat = Double.POSITIVE_INFINITY, minLon = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;

        for (LatLng p : pts) {
            minLat = Math.min(minLat, p.latitude);
            minLon = Math.min(minLon, p.longitude);
            maxLat = Math.max(maxLat, p.latitude);
            maxLon = Math.max(maxLon, p.longitude);
        }

        return new LatLngBounds(
                new LatLng(minLat, minLon),
                new LatLng(maxLat, maxLon)
        );
    }
}

