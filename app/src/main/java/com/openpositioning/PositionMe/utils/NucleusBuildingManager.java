package com.openpositioning.PositionMe.utils;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.IndoorMapFragment;

import java.util.ArrayList;

public class NucleusBuildingManager {
    private IndoorMapFragment indoorMapFragment;
    private ArrayList<LatLng> buildingPolygon;

    public NucleusBuildingManager(GoogleMap map) {
        // The nuclear building has 5 floors
        indoorMapFragment = new IndoorMapFragment(map, 5);

        // southwest corner
        double N1 = 55.92279;
        double W1 = 3.174643;

        // Northeast corner
        double N2 = 55.92335;
        double W2 = 3.173829;

        // Define the full polygon with 4 vertices
        buildingPolygon = new ArrayList<>();
        buildingPolygon.add(new LatLng(N1, -W1)); // Southwest corner
        buildingPolygon.add(new LatLng(N1, -W2)); // Southeast corner
        buildingPolygon.add(new LatLng(N2, -W2)); // Northeast corner
        buildingPolygon.add(new LatLng(N2, -W1)); // Northwest corner

        // Initialize the indoor map of each layer
        indoorMapFragment.addFloor(0, R.drawable.floor_lg, new LatLngBounds(buildingPolygon.get(0), buildingPolygon.get(2)));
        indoorMapFragment.addFloor(1, R.drawable.floor_ug, new LatLngBounds(buildingPolygon.get(0), buildingPolygon.get(2)));
        indoorMapFragment.addFloor(2, R.drawable.floor_1, new LatLngBounds(buildingPolygon.get(0), buildingPolygon.get(2)));
        indoorMapFragment.addFloor(3, R.drawable.floor_2, new LatLngBounds(buildingPolygon.get(0), buildingPolygon.get(2)));
        indoorMapFragment.addFloor(4, R.drawable.floor_3, new LatLngBounds(buildingPolygon.get(0), buildingPolygon.get(2)));
    }

    public IndoorMapFragment getIndoorMapManager() {
        return indoorMapFragment;
    }

    /**
     * Determines if a given point is inside the building polygon.
     *
     * @param point the point to check
     * @return true if the point is inside the polygon, false otherwise
     */
    public boolean isPointInBuilding(LatLng point) {
        int intersectCount = 0;
        // Loop through each edge of the polygon
        for (int j = 0; j < buildingPolygon.size(); j++) {
            LatLng vertA = buildingPolygon.get(j);
            LatLng vertB = buildingPolygon.get((j + 1) % buildingPolygon.size());
            // Check if the ray from the point intersects with the edge
            if (rayCastIntersect(point, vertA, vertB)) {
                intersectCount++;
            }
        }
        // If the number of intersections is odd, the point is inside the polygon
        return ((intersectCount % 2) == 1); // odd = inside, even = outside;
    }

    /**
     * Determines if a ray from a point intersects with a given edge of the polygon.
     *
     * @param point the point from which the ray is cast
     * @param vertA the first vertex of the edge
     * @param vertB the second vertex of the edge
     * @return true if the ray intersects with the edge, false otherwise
     */
    private boolean rayCastIntersect(LatLng point, LatLng vertA, LatLng vertB) {
        double aY = vertA.latitude;
        double bY = vertB.latitude;
        double aX = vertA.longitude;
        double bX = vertB.longitude;
        double pY = point.latitude;
        double pX = point.longitude;

        // Check if the point is horizontally aligned with the edge
        if ((aY > pY && bY > pY) || (aY < pY && bY < pY) || (aX < pX && bX < pX)) {
            return false;
        }

        // Calculate the slope of the edge
        double m = (aY - bY) / (aX - bX);
        // Calculate the y-intercept of the edge
        double bee = -aX * m + aY;
        // Calculate the x-coordinate of the intersection point of the ray and the edge
        double x = (pY - bee) / m;

        // Return true if the intersection point is to the right of the point
        return x > pX;
    }
}
