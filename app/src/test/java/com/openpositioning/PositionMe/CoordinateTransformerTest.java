package com.openpositioning.PositionMe;

import static org.junit.Assert.assertEquals;
import com.openpositioning.PositionMe.utils.CoordinateTransformer;
import org.junit.Test;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * Unit tests for the CoordinateTransformer class.

 * This test suite verifies:
 * 1. That the class correctly selects OSGB36 or UTM based on location.
 * 2. That WGS84 coordinates correctly convert to Easting/Northing in the chosen system.
 * 3. That displacements are correctly applied in the chosen CRS and then converted back.

 * The tests include:
 * - London, UK (OS GB36)
 * - New York, USA (UTM Zone 18N)
 * - Sydney, Australia (UTM Zone 56S)

 * It should be noted that a Python model has been used as ground truth, which has the same
 * functionality as the CoordinateTransformer class.
 *
 * @author Alexandros Zoupos
 * @version 2.0
 */

public class CoordinateTransformerTest {

  // Displacement in meters (1m east, 1m north)
  private final double deltaEasting = 1;
  private final double deltaNorthing = 1;

  // Test 1: For London.
  @Test
  public void testCorrectSystemSelection_London() {

    // Test Locations (Latitude, Longitude)
    double latLondon = 51.5074;
    double lonLondon = -0.1278;

    // Initialise transformer with London's coordinates.
    CoordinateTransformer transformer = new CoordinateTransformer(latLondon, lonLondon);

    // Get the easting and northing in OS GB36.
    ProjCoordinate result = transformer.convertWGS84ToTarget(latLondon, lonLondon);

    // Print the results.
    System.out.println("London (OSGB36) - Easting: " + result.x + ", Northing: " + result.y);

    // Apply displacement and convert back to WGS84.
    ProjCoordinate newCoord = transformer.applyDisplacementAndConvert(latLondon, lonLondon, deltaEasting, deltaNorthing);
    System.out.println("London After Displacement - Latitude: " + newCoord.y + ", Longitude: " + newCoord.x);

    // Expected Easting/Northing values
    double expectedEastingLondon = 530028.747;
    assertEquals(expectedEastingLondon, result.x, 1.0);
    double expectedNorthingLondon = 180380.094;
    assertEquals(expectedNorthingLondon, result.y, 1.0);

    // Expected displaced coordinates in WGS84
    double expectedLatLondonAfterDisplacement = 51.507409;
    assertEquals(expectedLatLondonAfterDisplacement, newCoord.y, 0.0001);
    double expectedLonLondonAfterDisplacement = -0.127785;
    assertEquals(expectedLonLondonAfterDisplacement, newCoord.x, 0.0001);
  }

  // Test 2: For New York.
  @Test
  public void testCorrectSystemSelection_NewYork() {

    // Test Locations (Latitude, Longitude)
    double latNewYork = 40.775602;
    double lonNewYork = -73.970561;

    // Initialise transformer with New York's coordinates.
    CoordinateTransformer transformer = new CoordinateTransformer(latNewYork, lonNewYork);

    // Get the easting and northing in UTM.
    ProjCoordinate result = transformer.convertWGS84ToTarget(latNewYork, lonNewYork);

    // Print the results.
    System.out.println("New York (UTM Zone 18N) - Easting: " + result.x + ", Northing: " + result.y);

    // Apply displacement and convert back to WGS84.
    ProjCoordinate newCoord = transformer.applyDisplacementAndConvert(latNewYork, lonNewYork, deltaEasting, deltaNorthing);
    System.out.println("New York After Displacement - Latitude: " + newCoord.y + ", Longitude: " + newCoord.x);

    // Expected Easting/Northing values.
    double expectedEastingNewYork = 586871.055;
    assertEquals(expectedEastingNewYork, result.x, 1.0);
    double expectedNorthingNewYork = 4514356.956;
    assertEquals(expectedNorthingNewYork, result.y, 1.0);

    // Expected displaced coordinates in WGS84.
    double expectedLatNewYorkAfterDisplacement = 40.775611;
    assertEquals(expectedLatNewYorkAfterDisplacement, newCoord.y, 0.0001);
    double expectedLonNewYorkAfterDisplacement = -73.970549;
    assertEquals(expectedLonNewYorkAfterDisplacement, newCoord.x, 0.0001);
  }

  // Test 3: For Sydney
  @Test
  public void testCorrectSystemSelection_Sydney() {

    // Test Locations (Latitude, Longitude)
    double latSydney = -26.313113;
    double lonSydney = 134.23096;

    // Initialise transformer with Sydney's coordinates.
    CoordinateTransformer transformer = new CoordinateTransformer(latSydney, lonSydney);

    // Get the easting and northing in UTM.
    ProjCoordinate result = transformer.convertWGS84ToTarget(latSydney, lonSydney);

    // Print the results.
    System.out.println("Sydney (UTM Zone 56S) - Easting: " + result.x + ", Northing: " + result.y);

    // Apply displacement and convert back to WGS84.
    ProjCoordinate newCoord = transformer.applyDisplacementAndConvert(latSydney, lonSydney, deltaEasting, deltaNorthing);
    System.out.println("Sydney After Displacement - Latitude: " + newCoord.y + ", Longitude: " + newCoord.x);

    // Expected Easting/Northing values.
    double expectedEastingSydney = 423240.035;
    assertEquals(expectedEastingSydney, result.x, 1.0);
    double expectedNorthingSydney = 7089411.762;
    assertEquals(expectedNorthingSydney, result.y, 1.0);

    // Expected displaced coordinates in WGS84.
    double expectedLatSydneyAfterDisplacement = -26.313104;
    assertEquals(expectedLatSydneyAfterDisplacement, newCoord.y, 0.0001);
    double expectedLonSydneyAfterDisplacement = 134.230970;
    assertEquals(expectedLonSydneyAfterDisplacement, newCoord.x, 0.0001);
  }
}