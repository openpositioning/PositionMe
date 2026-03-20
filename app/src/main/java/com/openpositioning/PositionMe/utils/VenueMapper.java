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

//load venue into internal model

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.openpositioning.PositionMe.data.remote.FloorPlanData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                Log.d("VenueMapper", "mapShapes example: " + dto.mapShapes);
                v.floorFeatures = parseFloorFeatures(dto.mapShapes);

                out.add(v);

            } catch (Exception e) {
                Log.e("VenueMapper", "Failed mapping venue " + dto.name, e);
            }
        }

        return out;
    }

    /**
     * extracts the building outline coordinates from a GeoJSON string and
     * converts them into a list of LatLng points that Android/Google Maps can use
     */
    private static List<LatLng> parseOutlineGeoJson(String outlineJsonString) throws Exception {
        List<LatLng> pts = new ArrayList<>();
        if (outlineJsonString == null || outlineJsonString.isEmpty()) return pts;

        JSONObject object = new JSONObject(outlineJsonString);
        JSONArray features = object.optJSONArray("features");
        if (features == null || features.length() == 0) return pts;

        JSONObject geom = features.getJSONObject(0).getJSONObject("geometry");
        String type = geom.optString("type", "");

        JSONArray coordinates = geom.getJSONArray("coordinates");

        // MultiPolygon: coordinates[ polygon ][ ring ][ point ][ lon/lat ]
        // Polygon: coordinates[ ring ][ point ][ lon/lat ]
        JSONArray boundary;

        if ("MultiPolygon".equalsIgnoreCase(type)) {
            boundary = coordinates.getJSONArray(0).getJSONArray(0);
        } else if ("Polygon".equalsIgnoreCase(type)) {
            boundary = coordinates.getJSONArray(0);
        } else {
            return pts;
        }

        for (int i = 0; i < boundary.length(); i++) {
            JSONArray p = boundary.getJSONArray(i);
            double lon = p.getDouble(0);
            double lat = p.getDouble(1);
            pts.add(new LatLng(lat, lon));
        }

        return pts;
    }

    /**
     * takes a string of shape, gets JSON object,
     */
    private static Map<String, IndoorMapManager.IndoorVenue.FloorFeatures>
    parseFloorFeatures(String shapes) throws Exception {

        Map<String, IndoorMapManager.IndoorVenue.FloorFeatures> floors = new HashMap<>();

        if (shapes == null || shapes.isEmpty()) {
            return floors;
        }

        JSONArray shapesArray = new JSONArray(shapes);

        for (int k = 0; k < shapesArray.length(); k++) {
            JSONObject shape = shapesArray.getJSONObject(k);

            String floor = String.valueOf(shape.getInt("floor"));
            String type = shape.getString("indoor_type");

            IndoorMapManager.IndoorVenue.FloorFeatures f = floors.get(floor);
            //if floor doesn't already exist in indoorvenue.floorfeatures, add it
            if (f == null) {
                f = new IndoorMapManager.IndoorVenue.FloorFeatures();
                floors.put(floor, f);
            }

            JSONObject geometry = shape.getJSONObject("geometry");

            //if wall, parse and add it to list of walls, if stairs, add to stairs, if lift, add to lifts
            if (type.equals("wall")) {
                String geomType = geometry.optString("type", "");

                if ("Polygon".equalsIgnoreCase(geomType) ||
                        "MultiPolygon".equalsIgnoreCase(geomType)) {
                    List<LatLng> poly = parsePolygon(geometry);
                    if (!poly.isEmpty()) {
                        f.wallPolygons.add(poly);
                    }
                } else if ("LineString".equalsIgnoreCase(geomType)) {
                    List<LatLng> line = parseLineString(geometry);
                    f.wallPolylines.addAll(line);
                }

            } else if (type.equals("stairs")) {
                LatLng p = parsePointOrCenter(geometry);
                if (p != null) {
                    f.stairsCenters.add(p);
                }

            } else if (type.equals("lift")) {
                LatLng p = parsePointOrCenter(geometry);
                if (p != null) {
                    f.liftCenters.add(p);
                }
            }
        }

        return floors;
    }

    private static List<LatLng> parsePolygon(JSONObject geometry) throws Exception {
        List<LatLng> pts = new ArrayList<>();

        String type = geometry.optString("type", "");
        JSONArray coordinates = geometry.getJSONArray("coordinates");
        JSONArray ring;

        if ("MultiPolygon".equalsIgnoreCase(type)) {
            ring = coordinates.getJSONArray(0).getJSONArray(0);
        } else if ("Polygon".equalsIgnoreCase(type)) {
            ring = coordinates.getJSONArray(0);
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

    private static List<LatLng> parseLineString(JSONObject geometry) throws Exception {
        List<LatLng> pts = new ArrayList<>();

        if (!"LineString".equalsIgnoreCase(geometry.optString("type", ""))) {
            return pts;
        }

        JSONArray coordinates = geometry.getJSONArray("coordinates");
        for (int i = 0; i < coordinates.length(); i++) {
            JSONArray p = coordinates.getJSONArray(i);
            double lon = p.getDouble(0);
            double lat = p.getDouble(1);
            pts.add(new LatLng(lat, lon));
        }

        return pts;
    }

    private static LatLng parsePointOrCenter(JSONObject geometry) throws Exception {
        String type = geometry.optString("type", "");

        if ("Point".equalsIgnoreCase(type)) {
            JSONArray coordinates = geometry.getJSONArray("coordinates");
            double lon = coordinates.getDouble(0);
            double lat = coordinates.getDouble(1);
            return new LatLng(lat, lon);
        }

        if ("Polygon".equalsIgnoreCase(type) || "MultiPolygon".equalsIgnoreCase(type)) {
            List<LatLng> poly = parsePolygon(geometry);
            if (!poly.isEmpty()) {
                return computeCenter(poly);
            }
        }

        return null;
    }

    private static LatLng computeCenter(List<LatLng> pts) {
        if (pts == null || pts.isEmpty()) return null;

        double sumLat = 0.0;
        double sumLon = 0.0;

        for (LatLng p : pts) {
            sumLat += p.latitude;
            sumLon += p.longitude;
        }

        return new LatLng(sumLat / pts.size(), sumLon / pts.size());
    }

    /**
     * finds the smallest rectangle completely containing all points
     */
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

