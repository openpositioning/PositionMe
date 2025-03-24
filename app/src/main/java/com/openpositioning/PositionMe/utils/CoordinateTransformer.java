package com.openpositioning.PositionMe.utils;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;


/**
 * This is a class that does conversions between different coordinate systems. Given an initial
 * longitude and latitude the class determines whether OS GB36 or UTM needs to be used. For cases
 * within the UK, the OSGB36 coordinate system is used. For cases outside the UK, the UTM is used.
 * Depending on the initial location, the correct UTM zone is found. This design preserves maximum
 * accuracy for UK and outside.
 * Library Used:
 * This implementation uses the Proj4J< library for coordinate transformations:
 * https://github.com/locationtech/proj4j

 * Reference:
 * - "A Guide to Coordinate Systems in Great Britain" (Ordnance Survey, 2020).
 * Available at: https://www.ordnancesurvey.co.uk/documents/resources/guide-coordinate-systems-great-britain.pdf
 *
 * @author Alexandros Zoupos
 */
public class CoordinateTransformer {

  // ==================================================
  // Define UK boundaries as constants (see reference)
  // ==================================================
  private static final double UK_MIN_LAT = 49.0;  // Southern boundary
  private static final double UK_MAX_LAT = 61.0;  // Northern boundary
  private static final double UK_MIN_LON = -10.0; // Western boundary
  private static final double UK_MAX_LON = 2;   // Eastern boundary

  // ==============================
  // UTM ZONE CALCULATION CONSTANTS
  // ==============================
  private static final double UTM_ZONE_WIDTH = 6.0;  // Each UTM zone spans 6° longitude
  private static final double UTM_REFERENCE_LONGITUDE = -180.0;  // Westernmost longitude reference for UTM zones
  private static final int UTM_ZONE_OFFSET = 1;  // UTM zone numbering starts from 1

  // Converts WGS84 → OS GB36 or UTM
  private final CoordinateTransform transformToTarget;

  // Converts OS GB36 or UTM → WGS84
  private final CoordinateTransform transformFromTarget;

  /**
   * Constructor: Decides Whether to use OSGB36 or UTM
   * @param latitude Initial latitude in decimal (WGS84)
   * @param longitude Initial longitude in decimal (WGS84)
   */
  public CoordinateTransformer(double latitude, double longitude) {

    // Used to create coordinate reference systems (CRS)
    CRSFactory crsFactory = new CRSFactory();

    /*
      This system represents geographic coordinates using latitude and longitude in degrees.
       WGS84 is the global standard used for GPS and navigation.
     */
    CoordinateReferenceSystem wgs84CRS = crsFactory.createFromParameters(
            "WGS84", // Name of the coordinate system
            "+proj=longlat " + // Projection type: "longlat" means coordinates are in degrees (not meters)
            "+datum=WGS84 " +  // Datum: WGS84 (World Geodetic System 1984), used by GPS
            "+no_defs"         // Prevents Proj4 from applying any default parameters
    );

    /*
      Define a Coordinate Reference System (CRS) variable.
      This variable will store the appropriate coordinate system for the given location.
      Depending on the latitude and longitude provided:
      - If the location is inside the UK, OSGB36 (British National Grid) is used.
      - If the location is outside the UK, the appropriate UTM (Universal Transverse Mercator)
      zone is selected. The selected `targetCRS` will then be used to transform coordinates from
       WGS84 to the target projection system.
     */
    CoordinateReferenceSystem targetCRS;

    /*
      If the location is within the UK, use the OSGB36 (Ordnance Survey Great Britain 1936)
      coordinate system. OSGB36 uses the Transverse Mercator projection to provide accurate mapping
      across Great Britain. This transformation converts geographic coordinates (latitude/longitude)
      into a Cartesian coordinate system (easting/northing in meters).
     */
    if (isLocationInUK(latitude, longitude)) {
      targetCRS = crsFactory.createFromParameters(
              "OSGB36",  // Name of the coordinate system
              "+proj=tmerc " + // Projection: Transverse Mercator, suitable for narrow regions like the UK
              "+lat_0=49 " +   // Latitude of origin (degrees), the reference point for the projection
              "+lon_0=-2 " +   // Central meridian (degrees), the longitudinal reference point
              "+k=0.9996012717 " + // Scale factor at the central meridian (helps minimize distortion)
              "+x_0=400000 " +  // False easting (meters), ensures all coordinates are positive
              "+y_0=-100000 " + // False northing (meters), shifts origin to avoid negative values
              "+ellps=airy " +  // Ellipsoid model: Airy 1830, used in the UK
              "+datum=OSGB36 " + // Geodetic datum: OSGB36, specific to Great Britain
              "+units=m " +     // Output units: meters (easting/northing instead of degrees)
              "+no_defs"        // Prevents Proj4 from applying any default parameters
      );
    }

    //  If the Location is Outside the UK → Use UTM
    else {
      int utmZone = getUtmZone(longitude);

      // Ensure the correct UTM EPSG code is used (326XX for North, 327XX for South)
      boolean isNorthernHemisphere = (latitude >= 0);
      String utmCrsCode = "+proj=utm +zone=" + utmZone + (isNorthernHemisphere ? "" : " +south") + " +datum=WGS84 +units=m +no_defs";

      // Define UTM CRS for locations outside the UK
      targetCRS = crsFactory.createFromParameters("UTM-" + utmZone, utmCrsCode);
    }

    // Create transformation objects
    CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
    transformToTarget = ctFactory.createTransform(wgs84CRS, targetCRS);
    transformFromTarget = ctFactory.createTransform(targetCRS, wgs84CRS);
  }

  /**
   * Checks if the given coordinates are within the UK.
   * @param latitude in decimal.
   * @param longitude in decimal.
   * @return boolean, if location is in UK or not.
   */
  private boolean isLocationInUK(double latitude, double longitude) {
    return (latitude >= UK_MIN_LAT && latitude <= UK_MAX_LAT) &&
            (longitude >= UK_MIN_LON && longitude <= UK_MAX_LON);
  }

  /**
   * Determines the correct UTM zone for a given longitude.
   * UTM zones are numbered from 1 to 60, covering the Earth's surface in*6° longitude strips.
   * - Zone 1 starts at 180°W.
   * - Zone numbers **increase eastward** until Zone 60 at 180°E.
   *
   * @param longitude The longitude in decimal degrees.
   * @return The UTM zone number (1 to 60).
   */
  private int getUtmZone(double longitude) {
    return (int) Math.floor((longitude - UTM_REFERENCE_LONGITUDE) / UTM_ZONE_WIDTH) + UTM_ZONE_OFFSET;
  }

  /**
   * Converts WGS84 latitude/longitude to the selected coordinate system (OS GB36 or UTM).
   * @param latitude Latitude in decimal (WGS84)
   * @param longitude Longitude in decimal (WGS84)
   * @return ProjCoordinate with x (easting) and y (northing)
   */
  public ProjCoordinate convertWGS84ToTarget(double latitude, double longitude) {
    ProjCoordinate sourceCoordinate = new ProjCoordinate(longitude, latitude);
    ProjCoordinate resultCoordinate = new ProjCoordinate();
    transformToTarget.transform(sourceCoordinate, resultCoordinate);
    return resultCoordinate;
  }

  /**
   * Converts coordinates from the selected coordinate system (OSGB36 or UTM) back to WGS84.
   * @param easting Easting value (meters)
   * @param northing Northing value (meters)
   * @return ProjCoordinate with latitude and longitude (WGS84)
   */
  public ProjCoordinate convertTargetToWGS84(double easting, double northing) {
    ProjCoordinate sourceCoordinate = new ProjCoordinate(easting, northing);
    ProjCoordinate resultCoordinate = new ProjCoordinate();
    transformFromTarget.transform(sourceCoordinate, resultCoordinate);
    return resultCoordinate;
  }

  /**
   * Applies a displacement in the selected coordinate system and converts back to WGS84.
   * @param latitude Latitude in decimal (WGS84)
   * @param longitude Longitude in decimal (WGS84)
   * @param deltaEasting Displacement in meters (easting direction)
   * @param deltaNorthing Displacement in meters (northing direction)
   * @return ProjCoordinate with new latitude and longitude (WGS84)
   */
  public ProjCoordinate applyDisplacementAndConvert(double latitude, double longitude, double deltaEasting, double deltaNorthing) {
    // Convert to the target coordinate system (OS GB36 or UTM)
    ProjCoordinate projectedCoord = convertWGS84ToTarget(latitude, longitude);

    // Apply displacement
    double newEasting = projectedCoord.x + deltaEasting;
    double newNorthing = projectedCoord.y + deltaNorthing;

    // Convert back to WGS84
    return convertTargetToWGS84(newEasting, newNorthing);
  }

  public static double[] getRelativePosition(ProjCoordinate ref, ProjCoordinate point) {
    return new double[] {
            point.x - ref.x,
            point.y - ref.y,
            point.z - ref.z
    };
  }

  /**
   *  Calculate the distance between two proj-coordinate points.
   * @param point1
   * @param point2
   * @return distance as a double.
   */
  public static double calculateDistance(ProjCoordinate point1, ProjCoordinate point2) {
    return Math.sqrt(
            Math.pow(point1.x - point2.x,2) +
            Math.pow(point1.y - point2.y,2)
//            Math.pow(point1.z - point2.z,2)
            );

  }

}