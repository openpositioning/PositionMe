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
 *
 * @author Alexandros Zoupos
 */
public class CoordinateTransformer {

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

    // Define WGS84 CRS (global GPS system)
    CoordinateReferenceSystem wgs84CRS = crsFactory.createFromParameters(
            "WGS84", "+proj=longlat +datum=WGS84 +no_defs"
    );

    CoordinateReferenceSystem targetCRS;

    // If the Location is in the UK → Use OS GB36
    // Define OS GB36 CRS for UK locations
    if (isLocationInUK(latitude, longitude)) {
      targetCRS = crsFactory.createFromParameters(
              "OSGB36", "+proj=tmerc +lat_0=49 +lon_0=-2 +k=0.9996012717 "
                      + "+x_0=400000 +y_0=-100000 +ellps=airy +datum=OSGB36 +units=m +no_defs"
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
    return (latitude >= 49.0 && latitude <= 61.0) && (longitude >= -10.0 && longitude <= 2.0);
  }

  /**
   * Determines the correct UTM zone for the given longitude.
   * @param longitude in decimal.
   * @return int, UTM zone.
   */
  private int getUtmZone(double longitude) {
    return (int) Math.floor((longitude + 180) / 6) + 1;
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

}