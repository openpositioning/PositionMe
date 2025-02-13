package com.openpositioning.PositionMe;


import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Class used to check for a pre-defined set of coordinates if it is in a Building (Nucleus, Library)
 * (Can be used to add more buildings by adding the coordinates of the buildings and adding methods)
 * @see IndoorMapManager Used by the the IndoorFloorManager class
 * @author Arun Gopalakrishnan
 */
public class BuildingPolygon {
    // Defining the coordinates of the building boundaries (rectangular boundaries based on floor map shape)
    // North-East and South-West Coordinates for the Nucleus Building
    public static final LatLng NUCLEUS_NE=new LatLng(55.92332001571212, -3.1738768212979593);
    public static final LatLng NUCLEUS_SW=new LatLng(55.92282257022002, -3.1745956532857647);
    // North-East and South-West Coordinates for the Kenneth and Murray Library Building
    public static final LatLng LIBRARY_NE=new LatLng(55.92306692576906, -3.174771893078224);
    public static final LatLng LIBRARY_SW=new LatLng(55.92281045664704, -3.175184089079065);
    // Boundary coordinates of the Nucleus building (clockwise)
    // Library Building 的东北和西南角坐标


    // Fleeming Building 的中心点
    public static final LatLng Fleeming_CENTER = new LatLng(55.9224567, -3.1724444);

    // 定义 Fleeming Building 的 Polygon（按照 Google 地图上手动调整）
    public static final List<LatLng> Fleeming_POLYGON = Arrays.asList(
            new LatLng(55.9221059, -3.1723130), // 西南 (Southwest)
            new LatLng(55.9222226, -3.1719519), // 东南 (Southeast)
            new LatLng(55.9228053, -3.1726003), // 东北 (Northeast)
            new LatLng(55.9226930, -3.1729124), // 西北 (Northwest)
            new LatLng(55.9221059, -3.1723130)  // **闭合边界**
    );

    public static final LatLng Hudson_CENTER = new LatLng(55.9225085, -3.1713467);

    // 定义 Fleeming Building 的 Polygon（按照 Google 地图上手动调整）
    public static final List<LatLng> Hudson_POLYGON = Arrays.asList(
            new LatLng(55.9223633, -3.1715301), // 西南 (Southwest)
            new LatLng(55.9225434, -3.1710165), // 东南 (Southeast)
            new LatLng(55.9226656, -3.1711522), // 东北 (Northeast)
            new LatLng(55.9224837, -3.1716374), // 西北 (Northwest)
            new LatLng(55.9221059, -3.1723130)  // **闭合边界**
    );
    public static final List<LatLng> NUCLEUS_POLYGON = new ArrayList<LatLng>() {{
        add(BuildingPolygon.NUCLEUS_NE);
        add(new LatLng(BuildingPolygon.NUCLEUS_SW.latitude, BuildingPolygon.NUCLEUS_NE.longitude)); // South-East
        add(BuildingPolygon.NUCLEUS_SW);
        add(new LatLng(BuildingPolygon.NUCLEUS_NE.latitude, BuildingPolygon.NUCLEUS_SW.longitude)); // North-West

    }};
    //Boundary coordinates of the Library building (clockwise)
    public static final List<LatLng> LIBRARY_POLYGON = new ArrayList<LatLng>() {{
        add(BuildingPolygon.LIBRARY_NE);
        add(new LatLng(BuildingPolygon.LIBRARY_SW.latitude,BuildingPolygon.LIBRARY_NE.longitude));//(South-East)
        add(BuildingPolygon.LIBRARY_SW);
        add(new LatLng(BuildingPolygon.LIBRARY_NE.latitude,BuildingPolygon.LIBRARY_SW.longitude));//(North-West)
    }};




    /**
     * Function to check if a point is in the Nucleus Building
     * @param point the point to be checked if inside the building
     * @return True if point is in Nucleus building else False
     */
    public static boolean inNucleus(LatLng point){
        return (pointInPolygon(point,NUCLEUS_POLYGON));

    }
    /**
     * Function to check if a point is in the Library Building
     * @param point the point which is checked if inside the building
     * @return True if point is in Library building else False
     */
    public static boolean inLibrary(LatLng point){
        return (pointInPolygon(point,LIBRARY_POLYGON));
    }

    public static boolean inFleeming(LatLng point){
        return (pointInPolygon(point, Fleeming_POLYGON)); // 确保 polygon 数据正确
    }

    public static boolean inHudson(LatLng point){
        return (pointInPolygon(point, Hudson_POLYGON)); // 确保 polygon 数据正确
    }

    /**
     * Function to check if point in polygon (approximates earth to be flat)
     * Ray casting algorithm https://en.wikipedia.org/wiki/Point_in_polygon
     * @param point point to be checked if in polygon
     * @param polygon Boundaries of the building
     * @return True if point in polygon
     * False otherwise
     */
    private static boolean pointInPolygon(LatLng point, List<LatLng> polygon) {
        int numCrossings = 0;
        List<LatLng> path=polygon;
        // For each edge
        for (int i=0; i < path.size(); i++) {
            LatLng a = path.get(i);
            int j = i + 1;
            // Last edge (includes first point of Polygon)
            if (j >= path.size()) {
                j = 0;
            }
            LatLng b = path.get(j);
            if (crossingSegment(point, a, b)) {
                numCrossings++;
            }
        }

        //if odd number of numCrossings return true (point is in polygon)
        return (numCrossings % 2 == 1);
    }

    /**
     * Ray Casting algorithm for a segment joining ab
     * @param point the point we check
     * @param a the line segment's starting point
     * @param b the line segment's ending point
     * @return True if the point is
     *      1) To the left of the segment ab
     *      2) Not above nor below the segment ab
     *      Otherwise False
     */
    private static boolean crossingSegment(LatLng point, LatLng a,LatLng b) {
        double pointLng = point.longitude,
                pointLat = point.latitude,
                aLng = a.longitude,
                aLat = a.latitude,
                bLng = b.longitude,
                bLat = b.latitude;
        if (aLat > bLat) {
            aLng = b.longitude;
            aLat = b.latitude;
            bLng = a.longitude;
            bLat = a.latitude;
        }
        // Alter longitude to correct for 180 degree crossings
        if (pointLng < 0 || aLng <0 || bLng <0) { pointLng += 360; aLng+=360; bLng+=360; }
        // If point has same latitude as a or b, increase slightly pointLat
        if (pointLat == aLat || pointLat == bLat) pointLat += 0.00000001;

        //If the point is above, below or to the right of the segment,return false
        if ((pointLat > bLat || pointLat < aLat) || (pointLng > Math.max(aLng, bLng))){
            return false;
        }
        // If the point is not above, below or to the right and is to the left, return true
        else if (pointLng < Math.min(aLng, bLng)){
            return true;
        }
        // Comparing the slope of segment [a,b] (slope1)
        // and segment [a,point] (slope2) to check if to the left of segment [a,b] or not
        else {
            double slope1 = (aLng != bLng) ? ((bLat - aLat) / (bLng - aLng)) : Double.POSITIVE_INFINITY;
            double slope2 = (aLng != pointLng) ? ((pointLat - aLat) / (pointLng - aLng)) : Double.POSITIVE_INFINITY;
            return (slope2 >= slope1);
        }
    }
}
