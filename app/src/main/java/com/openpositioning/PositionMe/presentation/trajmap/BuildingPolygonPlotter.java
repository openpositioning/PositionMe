package com.openpositioning.PositionMe.presentation.trajmap;

import android.graphics.Color;
import android.util.Log;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;

/**
 *  This class is responsible for plotting building polygons on a Google Map.
 *  It takes a GoogleMap object in its constructor and provides a method to draw
 *  predefined building polygons (e.g., Nuclear, NKML, FJB, Faraday) with specific
 *  styling (stroke color, width, z-index).
 *
 * @author Lai Gan
 */
public class BuildingPolygonPlotter {

    private GoogleMap map;

    public BuildingPolygonPlotter(GoogleMap map) {
        this.map = map;
    }

    /**
     * Draws building polygons on the provided GoogleMap.
     */
    public void drawBuildingPolygons() {
        if (map == null) {
            Log.e("BuildingPolygonDrawer", "GoogleMap is null.");
            return;
        }

        // Example: Nuclear building polygon vertices.
        LatLng nucleus1 = new LatLng(55.92279538827796, -3.174612147506538);
        LatLng nucleus2 = new LatLng(55.92278121423647, -3.174107900816096);
        LatLng nucleus3 = new LatLng(55.92288405733954, -3.173843694667146);
        LatLng nucleus4 = new LatLng(55.92331786793876, -3.173832892645086);
        LatLng nucleus5 = new LatLng(55.923337194112555, -3.1746284301397387);

        PolygonOptions nucleusOptions = new PolygonOptions()
                .add(nucleus1, nucleus2, nucleus3, nucleus4, nucleus5)
                .strokeColor(Color.RED)
                .strokeWidth(10f)
                .zIndex(1);
        map.addPolygon(nucleusOptions);

        // Similarly add other polygons (nkml, fjb, faraday) by extracting the coordinates
        // and configuring the PolygonOptions accordingly.
        // For example:
        LatLng nkml1 = new LatLng(55.9230343434213, -3.1751847990731954);
        LatLng nkml2 = new LatLng(55.923032840563366, -3.174777103346131);
        LatLng nkml3 = new LatLng(55.922793885410734, -3.1747958788136867);
        LatLng nkml4 = new LatLng(55.92280139974615, -3.175195527934348);
        PolygonOptions nkmlOptions = new PolygonOptions()
                .add(nkml1, nkml2, nkml3, nkml4, nkml1)
                .strokeColor(Color.BLUE)
                .strokeWidth(10f)
                .zIndex(1);
        map.addPolygon(nkmlOptions);

        // Continue similarly for the other two building sets...
        Log.d("BuildingPolygonDrawer", "Building polygons added.");
    }
}
