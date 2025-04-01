package com.openpositioning.PositionMe.utils;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.IndoorMapFragment;

import org.locationtech.proj4j.ProjCoordinate;

import java.util.ArrayList;

/**
 * The {@code NucleusBuildingManager} class is responsible for managing spatial
 * information related to the Nucleus building, including indoor map overlays,
 * floor management, and key infrastructure points like elevators.
 *
 * <p>Specifically, this class:
 * <ul>
 *   <li>Initializes indoor map fragments for each floor using pre-defined bounds and assets.</li>
 *   <li>Defines the building footprint using a rectangular polygon for containment checks.</li>
 *   <li>Provides functionality to determine whether a user is inside the building polygon.</li>
 *   <li>Stores fixed locations of elevator shafts and can return the nearest one to a given user position
 *       by computing distances in local projected (XY) coordinates using a {@link CoordinateTransformer}.</li>
 * </ul>
 *
 * <p>This class is primarily used for location-aware applications such as indoor navigation,
 * elevator matching, and floor-based positioning.
 *
 * @author Alexandros Zoupos (lift matching)
 */
public class NucleusBuildingManager {
    private IndoorMapFragment indoorMapFragment;
    private ArrayList<LatLng> buildingPolygon;

  // Elevator center positions (in WGS84 LatLng)
  private static final LatLng leftLift = new LatLng(55.923022, -3.17437);
  private static final LatLng centerLift = new LatLng(55.923029, -3.17433);
  private static final LatLng rightLift = new LatLng(55.923032, -3.17429);

  // Array to loop through lifts if needed
  private static final LatLng[] elevatorLifts = new LatLng[] { leftLift, centerLift, rightLift };



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

  /**
   * Determines and returns the LatLng of the closest elevator to the given user location.
   * The method converts both the user position and each elevator's position into a local
   * projected coordinate system and then computes their distances.
   *
   * @param userLocation the current user LatLng position (in WGS84)
   * @param transformer  the CoordinateTransformer to convert WGS84 coordinates into local XY space
   * @return the LatLng of the closest elevator
   */
  public static LatLng getClosestElevatorLatLng(LatLng userLocation, CoordinateTransformer transformer) {
    // Convert the user's location into projected coordinates.
    ProjCoordinate userXY = transformer.convertWGS84ToTarget(userLocation.latitude, userLocation.longitude);
    double minDistance = Double.MAX_VALUE;
    LatLng closestElevator = null;

    // Iterate through the array of predefined elevator positions.
    for (LatLng lift : elevatorLifts) {
      ProjCoordinate liftXY = transformer.convertWGS84ToTarget(lift.latitude, lift.longitude);
      double distance = CoordinateTransformer.calculateDistance(userXY, liftXY);
      if (distance < minDistance) {
        minDistance = distance;
        closestElevator = lift;
      }
    }
    return closestElevator;
  }

}
