package com.openpositioning.PositionMe;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * The TrajectoryPlayer class manages the visualization of trajectory data
 * on a Google Map. It provides functionality to draw the path based on
 * recorded coordinates, allowing users to view their movement history effectively.
 */

public class TrajectoryPlayer {
    private GoogleMap map; // Google Map instance for displaying the trajectory.
    private Polyline polyline; // Polyline for drawing the trajectory path on the map.

    /**
     * Constructor for the TrajectoryPlayer class.
     * Initializes the class with a Google Map instance to manage trajectory playback.
     *
     * @param map The GoogleMap instance where the trajectory will be displayed.
     */
    public TrajectoryPlayer(GoogleMap map) {
        this.map = map; // Assign the provided GoogleMap instance to the class variable.
    }

    /**
     * Plays the trajectory by drawing it on the map.
     * This method takes a list of coordinate points, converts them to LatLng objects,
     * and stores them in a list for visualization on the map.
     *
     * @param coordinateList A list of Coordinate objects representing the trajectory points.
     */
    public void playTrajectory(List<Coordinate> coordinateList) {
        // Convert the list of Coordinate objects to a list of LatLng objects for mapping.
        List<LatLng> latLngList = new ArrayList<>();
        for (Coordinate coordinate : coordinateList) {
            LatLng latLng = new LatLng(coordinate.latitude, coordinate.longitude);
            latLngList.add(latLng);
        }

        // Iterate through each Coordinate, creating a corresponding LatLng object.
        PolylineOptions polylineOptions = new PolylineOptions()
                .color(android.graphics.Color.BLUE) // Create a LatLng object.
                .width(5f); // Add the LatLng object to the list.

        // Add the list of LatLng objects to the PolylineOptions for drawing the trajectory.
        polylineOptions.addAll(latLngList);

        // Add the Polyline to the map, visualizing the trajectory path based on the coordinates.
        polyline = map.addPolyline(polylineOptions); // Creates and displays the polyline on the map.
    }

    /**
     * Coordinate class represents a geographical point with latitude and longitude.
     * This class can be used to structure coordinate data for trajectories being replayed.
     */
    public static class Coordinate {
        public double latitude; // The latitude of the coordinate.
        public double longitude; // The longitude of the coordinate.

        /**
         * Constructor for the Coordinate class.
         * Initializes a new Coordinate instance with the specified latitude and longitude values.
         *
         * @param latitude   The latitude of the coordinate.
         * @param longitude  The longitude of the coordinate.
         */
        public Coordinate(double latitude, double longitude) {
            this.latitude = latitude; // Assign the latitude value.
            this.longitude = longitude; // Assign the longitude value.
        }
    }
}