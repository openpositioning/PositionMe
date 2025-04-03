package com.openpositioning.PositionMe.utils;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.CoordinateTransform;
import java.util.ArrayList;
import java.util.List;

public class MapConstraint {
    private final List<double[]> polygonENU; // Each point: [x, y]
    private final double refLat, refLon, refAlt;

    public MapConstraint(double refLat, double refLon, double refAlt) {
        this.refLat = refLat;
        this.refLon = refLon;
        this.refAlt = refAlt;
        this.polygonENU = new ArrayList<>();

        // Define building corners here (Nucleus building)
        LatLng nucleus1 = new LatLng(55.92279538827796, -3.174612147506538);
        LatLng nucleus2 = new LatLng(55.92278121423647, -3.174107900816096);
        LatLng nucleus3 = new LatLng(55.92288405733954, -3.173843694667146);
        LatLng nucleus4 = new LatLng(55.92331786793876, -3.173832892645086);
        LatLng nucleus5 = new LatLng(55.923337194112555, -3.1746284301397387);

        List<LatLng> corners = List.of(nucleus1, nucleus2, nucleus3, nucleus4, nucleus5);

        for (LatLng corner : corners) {
            double[] enu = CoordinateTransform.geodeticToEnu(
                    corner.latitude, corner.longitude, 0.0,
                    refLat, refLon, refAlt
            );
            polygonENU.add(new double[]{enu[0], enu[1]});
        }

        // Define second building corners (NKML building)
        LatLng nkml1 = new LatLng(55.9230343434213, -3.1751847990731954);
        LatLng nkml2 = new LatLng(55.923032840563366, -3.174777103346131);
        LatLng nkml3 = new LatLng(55.922793885410734, -3.1747958788136867);
        LatLng nkml4 = new LatLng(55.92280139974615, -3.175195527934348);

        List<LatLng> nkmlCorners = List.of(nkml1, nkml2, nkml3, nkml4);
        for (LatLng corner : nkmlCorners) {
            double[] enu = CoordinateTransform.geodeticToEnu(
                    corner.latitude, corner.longitude, 0.0,
                    refLat, refLon, refAlt
            );
            polygonENU.add(new double[]{enu[0], enu[1]});
        }
    }

    public boolean isInside(double x, double y) {
        int crossings = 0;
        int n = polygonENU.size();
        for (int i = 0; i < n; i++) {
            double[] p1 = polygonENU.get(i);
            double[] p2 = polygonENU.get((i + 1) % n);

            if (((p1[1] > y) != (p2[1] > y)) &&
                    (x < (p2[0] - p1[0]) * (y - p1[1]) / (p2[1] - p1[1]) + p1[0])) {
                crossings++;
            }
        }
        return (crossings % 2 == 1);
    }
}
