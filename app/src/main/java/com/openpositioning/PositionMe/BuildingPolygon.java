package com.openpositioning.PositionMe;


import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
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

    /**
     * Adding in the coordinates for the 4 corners of Fleeming Jenkin, Sanderson, and
     * Hudson Beare
     * Author - Marco Bancalari
     */
    public static final LatLng FLEEMING_NE=new LatLng(55.922795644237574, -3.172590999121778);
    public static final LatLng FLEEMING_SW=new LatLng(55.92217867969299, -3.1723778181203066);
    public static final LatLng FLEEMING_NW=new LatLng(55.92267120276125, -3.1729395780918668);
    public static final LatLng FLEEMING_SE=new LatLng(55.922289704728435, -3.1720198754618534);
    public static final LatLng HUDSON_NE=new LatLng(55.92249233774948, -3.171660730901822);
    public static final LatLng HUDSON_SE=new LatLng(55.92222123124437, -3.1712962191735006);
    public static final LatLng HUDSON_NW=new LatLng(55.92268084126103, -3.1711342135685694);
    public static final LatLng HUDSON_SW=new LatLng(55.92240649428076, -3.1707803099666454);
    public static final LatLng SANDERSON_NE=new LatLng(55.923341373371784, -3.171908560007919);
    public static final LatLng SANDERSON_SE=new LatLng(55.92288116339978, -3.1713849360942903);
    public static final LatLng SANDERSON_NW=new LatLng(55.92310586595678, -3.1725266865044666);
    public static final LatLng SANDERSON_SW=new LatLng(55.92266024166355, -3.1720001697597033);
    // Boundary coordinates of the Nucleus building (clockwise)

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
     * Polygons for Fleeming Jenkin, Sanderson and Hudson Beare
     * Author - Marco Bancalari
     */
    public static final List<LatLng> FLEEMING_POLYGON = new ArrayList<LatLng>() {{
        add(BuildingPolygon.FLEEMING_NE);
        add(BuildingPolygon.FLEEMING_SE);//(South-East)
        add(BuildingPolygon.FLEEMING_SW);
        add(BuildingPolygon.FLEEMING_NW);//(North-West)
    }};

    public static final List<LatLng> HUDSON_POLYGON = new ArrayList<LatLng>() {{
        add(BuildingPolygon.HUDSON_NE);
        add(BuildingPolygon.HUDSON_SE);//(South-East)
        add(BuildingPolygon.HUDSON_SW);
        add(BuildingPolygon.HUDSON_NW);//(North-West)
    }};

    public static final List<LatLng> SANDERSON_POLYGON = new ArrayList<LatLng>() {{
        add(BuildingPolygon.SANDERSON_NE);
        add(BuildingPolygon.SANDERSON_SE);//(South-East)
        add(BuildingPolygon.SANDERSON_SW);
        add(BuildingPolygon.SANDERSON_NW);//(North-West)
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
    /**
     * Function to check if a point is in the Fleeming Jenkins Building
     * @param point the point which is checked if inside the building
     * @return True if point is in Fleeming jenkins building else False
     */
    public static boolean inFleeming(LatLng point){
        return (pointInPolygon(point,FLEEMING_POLYGON));
    }

    public static boolean inHudson(LatLng point){
        return (pointInPolygon(point,HUDSON_POLYGON));
    }

    public static boolean inSanderson(LatLng point){
        return (pointInPolygon(point,SANDERSON_POLYGON));
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
