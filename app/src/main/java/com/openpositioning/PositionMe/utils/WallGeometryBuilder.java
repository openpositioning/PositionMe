package com.openpositioning.PositionMe.utils;

import android.graphics.PointF;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds meter-based wall polylines from floorplan features, relative to an origin.
 */
public final class WallGeometryBuilder {

    private WallGeometryBuilder() {}

    /**
     * Converts wall features (LineString/MultiLineString/Polygon rings) to local meter polylines.
     *
     * @param floor floor data containing features.
     * @param origin reference lat/lng (e.g., initial PDR position) for local frame.
     * @return list of wall polylines; empty if none.
     */
    public static List<List<PointF>> buildWalls(FloorplanApiClient.FloorShapes floor, LatLng origin) {
        List<List<PointF>> walls = new ArrayList<>();
        if (floor == null || origin == null) return walls;
        for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
            if (!"wall".equals(feature.getIndoorType())) continue;
            for (List<LatLng> part : feature.getParts()) {
                if (part == null || part.size() < 2) continue;
                List<PointF> line = new ArrayList<>(part.size());
                for (LatLng p : part) {
                    float dx = (float) UtilFunctions.degreesToMetersLng(p.longitude - origin.longitude, origin.latitude);
                    float dy = (float) UtilFunctions.degreesToMetersLat(p.latitude - origin.latitude);
                    line.add(new PointF(dx, dy));
                }
                // Close ring for polygons so collision sees full wall loop
                if (!line.isEmpty() && !line.get(0).equals(line.get(line.size() - 1))) {
                    line.add(new PointF(line.get(0).x, line.get(0).y));
                }
                walls.add(line);
            }
        }
        return walls;
    }
}
