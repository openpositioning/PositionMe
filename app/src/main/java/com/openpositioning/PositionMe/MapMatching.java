package com.openpositioning.PositionMe;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.openpositioning.PositionMe.fragments.RecordingFragment;
import com.openpositioning.PositionMe.sensors.LocationResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;
/**
 * Performs map matching by locating the nearest point in a predefined dataset
 * based on the user's current fused coordinates. This class processes a JSON file
 * containing location data, which includes latitude, longitude, and floor
 * information, and parses it into a list of {@link LocationResponse} objects.
 *
 * The core functionality is provided by the {@code findNearestLocation} method,
 * which calculates the distance between the user's current location and each
 * point in the dataset using the Haversine formula. This method is known for
 * better accuracy over spherical distances compared to planar models, making it
 * well-suited for larger areas and outdoor environments. The nearest location is
 * determined by the smallest distance and a matching floor number, which ensures
 * relevance in a multi-story context.
 *
 * The {@code distanceBetweenPoints} method takes advantage of the earth's curvature
 * to calculate a more accurate distance between two geographical points than a
 * simple Euclidean (planar) distance formula would provide. This is particularly
 * useful when working with a larger scale where the curvature becomes significant.
 *
 * Data parsed from the JSON file is stored as a list of {@link LocationResponse}
 * objects, each holding the latitude, longitude, and floor information for a
 * location. When the class is instantiated, it logs the first location from the
 * dataset as a means of initial validation. In cases where no locations are
 * found, or an error occurs during parsing, appropriate log messages are
 * generated to aid in troubleshooting.
 *
 * This class is designed to facilitate the integration of map matching into
 * applications that require location-aware functionalities, with a particular
 * emphasis on providing accurate indoor navigation and location services.
 *
 * @author Michalis Voudaskas
 */
public class MapMatching {

    private List<LocationResponse> radiomapData;

    public MapMatching(Context context) {
        try {
            AssetManager assetManager = context.getAssets();
            InputStream inputStream = assetManager.open("processed_radiomap.json");
            Scanner scanner = new Scanner(inputStream).useDelimiter("\\A");
            String jsonContent = scanner.hasNext() ? scanner.next() : "";

            JSONArray radiomapArray = new JSONArray(jsonContent);
            radiomapData = new ArrayList<>();

            for (int i = 0; i < radiomapArray.length(); i++) {
                JSONObject jsonLocation = radiomapArray.getJSONObject(i);
                double lat = jsonLocation.getDouble("lat");
                double lon = jsonLocation.getDouble("lon");
                int floorId = jsonLocation.getInt("floor_id");

                LocationResponse location = new LocationResponse(lat, lon, floorId);
                radiomapData.add(location);
            }

            // Optional: Log the first location as a test
            if (!radiomapData.isEmpty()) {
                LocationResponse firstLocation = radiomapData.get(0);
                //Log.d("MapMatching", "First entry - Lat: " + firstLocation.getLatitude() + ", Lon: " + firstLocation.getLongitude() + ", Floor ID: " + firstLocation.getFloor());
            } else{
               // Log.d("MapMatching", "Empty");

            }

        } catch (Exception e) {
            e.printStackTrace();
           // Log.e("MapMatching", "Error parsing JSON", e);
        }
    }

    public LocationResponse findNearestLocation(double userLat, double userLon, int userFloor) {
        // Find the nearest location in radiomap to the user location
        LocationResponse nearestLocation = null;
        double minDistance = Double.MAX_VALUE;

        for (LocationResponse location : radiomapData) {
            if (location.getFloor() == userFloor) {
                double distance = distanceBetweenPoints(userLat, userLon, location.getLatitude(), location.getLongitude());

                if (distance < minDistance) {
                    minDistance = distance;
                    nearestLocation = location;
                }
            }
        }

        return nearestLocation;
    }

    //Finds the nearest location but with KNN - taking into account nearest neighbours
    public LocationResponse findKNNLocation(double userLat, double userLon, int userFloor, int k) {
        List<LocationResponse> filteredLocations = radiomapData.stream()
                .filter(location -> location.getFloor() == userFloor)
                .sorted(Comparator.comparingDouble(location ->
                        distanceBetweenPoints(userLat, userLon, location.getLatitude(), location.getLongitude())))
                .limit(k)
                .collect(Collectors.toList());

        double avgLat = filteredLocations.stream().mapToDouble(LocationResponse::getLatitude).average().orElse(userLat);
        double avgLon = filteredLocations.stream().mapToDouble(LocationResponse::getLongitude).average().orElse(userLon);

        return new LocationResponse(avgLat, avgLon, userFloor);
    }



    private double distanceBetweenPoints(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Radius of the earth in kilometers

        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);

        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double distance = R * c * 1000; // convert to meters

        return distance;
    }
}
