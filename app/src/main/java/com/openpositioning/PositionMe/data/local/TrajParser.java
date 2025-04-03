package com.openpositioning.PositionMe.data.local;

import android.content.Context;
import android.hardware.SensorManager;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openpositioning.PositionMe.presentation.fragment.ReplayFragment;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Handles parsing of trajectory data stored in JSON files, combining IMU, PDR, and GNSS data
 * to reconstruct motion paths.
 *
 * <p>
 * The **TrajParser** is primarily responsible for processing recorded trajectory data and
 * reconstructing motion information, including estimated positions, GNSS coordinates, speed, and orientation.
 * It does this by reading a JSON file containing:
 * </p>
 * <ul>
 *     <li>IMU (Inertial Measurement Unit) data</li>
 *     <li>PDR (Pedestrian Dead Reckoning) position data</li>
 *     <li>GNSS (Global Navigation Satellite System) location data</li>
 * </ul>
 *
 * <p>
 * **Usage in Module 'PositionMe.app.main':**
 * </p>
 * <ul>
 *     <li>**ReplayFragment** - Calls `parseTrajectoryData()` to read recorded trajectory files and process movement.</li>
 *     <li>Stores parsed trajectory data as `ReplayPoint` objects.</li>
 *     <li>Provides data for updating map visualizations in `ReplayFragment`.</li>
 * </ul>
 *
 * @see ReplayFragment which uses parsed trajectory data for visualization.
 * @see SensorFusion for motion processing and sensor integration.
 * @see com.openpositioning.PositionMe.presentation.fragment.ReplayFragment for implementation details.
 *
 * @author Shu Gu
 * @author Lin Cheng
 */
public class TrajParser {

    private static final String TAG = "TrajParser";

    /**
     * Represents a single replay point containing estimated PDR position, GNSS location,
     * orientation, speed, and timestamp.
     */
    public static class ReplayPoint {
        public LatLng pdrLocation;
        public LatLng gnssLocation;
        public LatLng wifiLocation;
        public int wifiFloor;
        public LatLng fusedLocation;
        public LatLng initialPosition;
        public float orientation;
        public float speed;
        public long timestamp;

        /**
         * Constructs a ReplayPoint.
         *
         * @param pdrLocation   The pedestrian dead reckoning (PDR) location.
         * @param gnssLocation  The GNSS location, or null if unavailable.
         * @param wifiLocation  The WiFi location, or null if unavailable.
         * @param wifiFloor     The WiFi floor, or null if unavailable.
         * @param fusedLocation  The Fused location, or null if unavailable.
         * @param initialPosition The initial position from the trajectory file.
         * @param orientation   The orientation angle in degrees.
         * @param speed         The speed in meters per second.
         * @param timestamp     The timestamp associated with this point.
         */
        public ReplayPoint(LatLng pdrLocation, LatLng gnssLocation, LatLng wifiLocation, int wifiFloor,
                           LatLng fusedLocation, LatLng initialPosition, float orientation, float speed,
                           long timestamp) {
            this.pdrLocation = pdrLocation;
            this.gnssLocation = gnssLocation;
            this.wifiLocation = wifiLocation;
            this.wifiFloor = wifiFloor;
            this.fusedLocation = fusedLocation;
            this.initialPosition = initialPosition;
            this.orientation = orientation;
            this.speed = speed;
            this.timestamp = timestamp;
        }
    }

    /** Represents an IMU (Inertial Measurement Unit) data record used for orientation calculations. */
    private static class ImuRecord {
        public long relativeTimestamp;
        public float accX, accY, accZ; // Accelerometer values
        public float gyrX, gyrY, gyrZ; // Gyroscope values
        public float rotationVectorX, rotationVectorY, rotationVectorZ, rotationVectorW; // Rotation quaternion
    }

    /** Represents a Pedestrian Dead Reckoning (PDR) data record storing position shifts over time. */
    private static class PdrRecord {
        public long relativeTimestamp;
        public float x, y; // Position relative to the starting point
    }

    /** Represents a GNSS (Global Navigation Satellite System) data record with latitude/longitude. */
    private static class GnssRecord {
        public long relativeTimestamp;
        public double latitude, longitude; // GNSS coordinates
        public String provider;
    }

    // Semih - NEW
    /** Represents a WIFI Positioning data record with latitude/longitude/floor. */
    private static class WifiRecord {
        public long relativeTimestamp;
        public float latitude, longitude;
        public int floor;

        public WifiRecord(long relativeTimestamp, float latitude, float longitude, int floor) {
            this.relativeTimestamp = relativeTimestamp;
            this.latitude = latitude;
            this.longitude = longitude;
            this.floor = floor;
        }
    }
    /** Represents a Fused Location data record with latitude/longitude. */
    private static class FusedRecord {
        public long relativeTimestamp;
        public float latitude, longitude;
    }
    /** Represents a Tag Location data record with latitude/longitude/altitude. */
    public static class TagRecord {
        public long relativeTimestamp;
        public double latitude, longitude, altitude;
    }

    private static List<TagRecord> tagMarkers = new ArrayList<>();

    public static List<TagRecord> getTagMarkers() {
        return tagMarkers;
    }

    // Semih - END

    /**
     * Parses trajectory data from a JSON file and reconstructs a list of replay points.
     *
     * <p>
     * This method processes a trajectory log file, extracting IMU, PDR, and GNSS records,
     * and uses them to generate **ReplayPoint** objects. Each point contains:
     * </p>
     * <ul>
     *     <li>Estimated PDR-based position.</li>
     *     <li>GNSS location (if available).</li>
     *     <li>Computed orientation using rotation vectors.</li>
     *     <li>Speed estimation based on movement data.</li>
     * </ul>
     *
     * @param filePath  Path to the JSON file containing trajectory data.
     * @param context   Android application context (used for sensor processing).
     * @return A list of parsed {@link ReplayPoint} objects.
     */
    public static List<ReplayPoint> parseTrajectoryData(String filePath, Context context) {
        List<ReplayPoint> result = new ArrayList<>();

        try {
            File file = new File(filePath);
            if (!file.exists()) {
                Log.e(TAG, "File does NOT exist: " + filePath);
                return result;
            }
            if (!file.canRead()) {
                Log.e(TAG, "File is NOT readable: " + filePath);
                return result;
            }

            BufferedReader br = new BufferedReader(new FileReader(file));

            /*
            // STONE - new
            StringBuilder jsonBuilder = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                jsonBuilder.append(line).append("\n");
            }
            br.close();
            Log.d("JSON_CONTENT", jsonBuilder.toString());
            // STONE - END
            */

            /*
            // JSON LOGGER (INITIAL-GNSS-WIFI-FUSED)
            StringBuilder jsonBuilder = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                jsonBuilder.append(line).append("\n");
            }
            br.close();

            // Convert to string
            String jsonString = jsonBuilder.toString();

            // Parse JSON and extract keys
            JSONObject jsonObject = new JSONObject(jsonString);
            Iterator<String> keys = jsonObject.keys();

            StringBuilder outputBuilder = new StringBuilder("JSON Group Names:\n");

            // Print all top-level keys
            while (keys.hasNext()) {
                outputBuilder.append(keys.next()).append("\n");
            }

            // Extract and print "initialPos", "gnssData", and "wifiData" if they exist
            if (jsonObject.has("initialPos")) {
                outputBuilder.append("\nData in 'initialPos':\n");
                outputBuilder.append(jsonObject.get("initialPos").toString()).append("\n");
            } else {
                outputBuilder.append("\n'initialPos' key not found.\n");
            }

            if (jsonObject.has("gnssData")) {
                outputBuilder.append("\nData in 'gnssData':\n");
                outputBuilder.append(jsonObject.get("gnssData").toString()).append("\n");
            } else {
                outputBuilder.append("\n'gnssData' key not found.\n");
            }

            if (jsonObject.has("wifiData")) {
                outputBuilder.append("\nData in 'wifiData':\n");
                outputBuilder.append(jsonObject.get("wifiData").toString()).append("\n");
            } else {
                outputBuilder.append("\n'wifiData' key not found.\n");
            }

            if (jsonObject.has("fusedPos")) {
                outputBuilder.append("\nData in 'fusedPos':\n");
                outputBuilder.append(jsonObject.get("fusedPos").toString()).append("\n");
            } else {
                outputBuilder.append("\n'fusedPos' key not found.\n");
            }

            // Log the final output
            Log.d("JSON_OUTPUT", outputBuilder.toString());
            */

            JsonObject root = new JsonParser().parse(br).getAsJsonObject();
            br.close();

            Log.i(TAG, "Successfully read trajectory file: " + filePath);

            long startTimestamp = root.has("startTimestamp") ? root.get("startTimestamp").getAsLong() : 0;

            // Semih - ADD
            // Extract Initial Position from JSON
            LatLng initialPosition = null;

            if (root.has("initialPos")) {
                JsonObject initialPosJson = root.getAsJsonObject("initialPos");
                double initialLatitude = initialPosJson.has("initialLatitude") ?
                        initialPosJson.get("initialLatitude").getAsDouble() : 0.0;
                double initialLongitude = initialPosJson.has("initialLongitude") ?
                        initialPosJson.get("initialLongitude").getAsDouble() : 0.0;

                initialPosition = new LatLng(initialLatitude, initialLongitude);

                Log.i(TAG, "Initial Position - Latitude: " + initialLatitude + ", Longitude: " + initialLongitude);
            } else {
                Log.e(TAG, "Initial Position key NOT found in JSON!");
            }

            // Extract tag markers
            if (root.has("tags")) {
                tagMarkers = parseTagData(root.getAsJsonArray("tags"));
                Log.d(TAG, "Loaded " + tagMarkers.size() + " tag markers.");
            } else {
                Log.w(TAG, "No tag markers found in trajectory.");
            }
            // Semih - END


            List<ImuRecord> imuList = parseImuData(root.getAsJsonArray("imuData"));
            List<PdrRecord> pdrList = parsePdrData(root.getAsJsonArray("pdrData"));
            List<GnssRecord> gnssList = parseGnssData(root.getAsJsonArray("gnssData"));
            List<WifiRecord> wifiList = parseWifiData(root.getAsJsonArray("wifiData"));
            List<FusedRecord> fusedList = parseFusedData(root.getAsJsonArray("fusedPos"));

            // Parsed Data Checks
            /*
            Log.d(TAG, "Parsed GNSS Data:");
            for (GnssRecord record : gnssList) {
                Log.d(TAG, "GNSS - Timestamp: " + record.relativeTimestamp +
                        ", Latitude: " + record.latitude +
                        ", Longitude: " + record.longitude);
            }

            Log.d(TAG, "Parsed WIFI Data:");
            for (WifiRecord record : wifiList) {
                Log.d(TAG, "WIFI - Timestamp: " + record.relativeTimestamp +
                        ", Latitude: " + record.latitude +
                        ", Longitude: " + record.longitude);
            }

            Log.d(TAG, "Parsed Fused Data:");
            for (FusedRecord record : fusedList) {
                Log.d(TAG, "Fused - Timestamp: " + record.relativeTimestamp +
                        ", Latitude: " + record.latitude +
                        ", Longitude: " + record.longitude);
            }
             */


            Log.i(TAG, "Parsed data - IMU: " + imuList.size() + " records, PDR: "
                    + pdrList.size() + " records, GNSS: " + gnssList.size() + " records, Wifi: " + wifiList.size());

            Log.d(TAG, "Loaded " + tagMarkers.size() + " tag markers from fusion GNSS data.");


            for (int i = 0; i < pdrList.size(); i++) {
                PdrRecord pdr = pdrList.get(i);

                ImuRecord closestImu = findClosestImuRecord(imuList, pdr.relativeTimestamp);
                float orientationDeg = closestImu != null ? computeOrientationFromRotationVector(
                        closestImu.rotationVectorX,
                        closestImu.rotationVectorY,
                        closestImu.rotationVectorZ,
                        closestImu.rotationVectorW,
                        context
                ) : 0f;

                float speed = 0f;
                if (i > 0) {
                    PdrRecord prev = pdrList.get(i - 1);
                    double dt = (pdr.relativeTimestamp - prev.relativeTimestamp) / 1000.0;
                    double dx = pdr.x - prev.x;
                    double dy = pdr.y - prev.y;
                    double distance = Math.sqrt(dx * dx + dy * dy);
                    if (dt > 0) speed = (float) (distance / dt);
                }


                double lat = initialPosition.latitude + pdr.y * 1E-5;
                double lng = initialPosition.longitude + pdr.x * 1E-5;
                LatLng pdrLocation = new LatLng(lat, lng);

                GnssRecord closestGnss = findClosestGnssRecord(gnssList, pdr.relativeTimestamp);
                LatLng gnssLocation = closestGnss != null ?
                        new LatLng(closestGnss.latitude, closestGnss.longitude) : null;

                WifiRecord closestWifi = findClosestWifiRecord(wifiList, pdr.relativeTimestamp);
                LatLng wifiLocation = closestWifi != null ?
                        new LatLng(closestWifi.latitude, closestWifi.longitude) : null;
                int wifiFloor = closestWifi != null ? closestWifi.floor : 0;


                FusedRecord closestFused = findClosestFusedRecord(fusedList, pdr.relativeTimestamp);
                LatLng fusedLocation = closestFused != null ?
                        new LatLng(closestFused.latitude, closestFused.longitude) : null;

                result.add(new ReplayPoint(pdrLocation, gnssLocation, wifiLocation, wifiFloor,
                        fusedLocation, initialPosition, orientationDeg, speed, pdr.relativeTimestamp));


            }

            Collections.sort(result, Comparator.comparingLong(rp -> rp.timestamp));

            Log.i(TAG, "Final ReplayPoints count: " + result.size());

        } catch (Exception e) {
            Log.e(TAG, "Error parsing trajectory file!", e);
        }

        return result;
    }
/** Parses IMU data from JSON. */
private static List<ImuRecord> parseImuData(JsonArray imuArray) {
    List<ImuRecord> imuList = new ArrayList<>();
    if (imuArray == null) return imuList;
    Gson gson = new Gson();
    for (int i = 0; i < imuArray.size(); i++) {
        ImuRecord record = gson.fromJson(imuArray.get(i), ImuRecord.class);
        imuList.add(record);
    }
    return imuList;
}/** Parses PDR data from JSON. */
private static List<PdrRecord> parsePdrData(JsonArray pdrArray) {
    List<PdrRecord> pdrList = new ArrayList<>();
    if (pdrArray == null) return pdrList;
    Gson gson = new Gson();
    for (int i = 0; i < pdrArray.size(); i++) {
        PdrRecord record = gson.fromJson(pdrArray.get(i), PdrRecord.class);
        pdrList.add(record);
    }
    return pdrList;
}/** Parses GNSS data from JSON. */
private static List<GnssRecord> parseGnssData(JsonArray gnssArray) {
    List<GnssRecord> gnssList = new ArrayList<>();
    tagMarkers.clear(); // Clear old markers to prevent duplicates

    Iterator<JsonElement> iterator = gnssArray.iterator(); // Use iterator for safe removal

    while (iterator.hasNext()) {
        JsonObject obj = iterator.next().getAsJsonObject();
        GnssRecord record = new GnssRecord();

        System.out.println(obj);

        record.relativeTimestamp = obj.get("relativeTimestamp").getAsLong();
        record.latitude = obj.get("latitude").getAsDouble();
        record.longitude = obj.get("longitude").getAsDouble();
        record.provider = obj.has("provider") ? obj.get("provider").getAsString() : "unknown";

        if ("fusion".equals(record.provider)) {
            TagRecord tag = new TagRecord();
            tag.relativeTimestamp = record.relativeTimestamp;
            tag.latitude = record.latitude;
            tag.longitude = record.longitude;
            tag.altitude = obj.has("altitude") ? obj.get("altitude").getAsDouble() : 0.0;

            tagMarkers.add(tag);
        } else {
            gnssList.add(record); // Only keep non-"fusion" records
        }
    }

    Log.d(TAG, "Filtered GNSS records: " + gnssList.size());
    Log.d(TAG, "Extracted tag markers: " + tagMarkers.size());

    return gnssList;
}
/** Parses WIFI data from JSON. */
private static List<WifiRecord> parseWifiData(JsonArray wifiArray) {
    List<WifiRecord> wifiList = new ArrayList<>();
    if (wifiArray == null) return wifiList;

    for (int i = 0; i < wifiArray.size(); i++) {
        JsonObject wifiObject = wifiArray.get(i).getAsJsonObject();

        if (wifiObject.has("position")) {
            JsonObject positionObject = wifiObject.getAsJsonObject("position");

            long relativeTimestamp = positionObject.has("relativeTimestamp") ?
                    positionObject.get("relativeTimestamp").getAsLong() : 0;

            float latitude = positionObject.has("latitude") ?
                    positionObject.get("latitude").getAsFloat() : 0.0f;

            float longitude = positionObject.has("longitude") ?
                    positionObject.get("longitude").getAsFloat() : 0.0f;

            int floor = positionObject.has("floor") ?
                    positionObject.get("floor").getAsInt() : 0;

            WifiRecord record = new WifiRecord(relativeTimestamp, latitude, longitude, floor);
            wifiList.add(record);
        }
    }
    return wifiList;
}
/** Parses Fused Location data from JSON. */
private static List<FusedRecord> parseFusedData(JsonArray fusedArray){
    List<FusedRecord> fusedList = new ArrayList<>();
    if (fusedArray == null) return fusedList;
    Gson gson = new Gson();
    for (int i = 0; i < fusedArray.size(); i++) {
        FusedRecord record = gson.fromJson(fusedArray.get(i), FusedRecord.class);
        fusedList.add(record);
    }
    return fusedList;
}
/** Parses Tag Location data from JSON. */
private static List<TagRecord> parseTagData(JsonArray tagArray) {
    List<TagRecord> tagList = new ArrayList<>();
    if (tagArray == null) return tagList;

    Gson gson = new Gson();
    for (int i = 0; i < tagArray.size(); i++) {
        TagRecord record = gson.fromJson(tagArray.get(i), TagRecord.class);
        tagList.add(record);
    }
    return tagList;
}


/** Finds the closest IMU record to the given timestamp. */
private static ImuRecord findClosestImuRecord(List<ImuRecord> imuList, long targetTimestamp) {
    return imuList.stream().min(Comparator.comparingLong(imu -> Math.abs(imu.relativeTimestamp - targetTimestamp)))
            .orElse(null);

}/** Finds the closest GNSS record to the given timestamp. */
private static GnssRecord findClosestGnssRecord(List<GnssRecord> gnssList, long targetTimestamp) {
    return gnssList.stream().min(Comparator.comparingLong(gnss -> Math.abs(gnss.relativeTimestamp - targetTimestamp)))
            .orElse(null);

}

/** Finds the closest WIFI record to the given timestamp. */
private static WifiRecord findClosestWifiRecord(List<WifiRecord> wifiList, long targetTimestamp) {
    return wifiList.stream().min(Comparator.comparingLong(wifi -> Math.abs(wifi.relativeTimestamp - targetTimestamp)))
            .orElse(null);
}
/** Finds the closest Fused Location record to the given timestamp. */
private static FusedRecord findClosestFusedRecord(List<FusedRecord> fusedList, long targetTimestamp) {
    return fusedList.stream().min(Comparator.comparingLong(fused -> Math.abs(fused.relativeTimestamp - targetTimestamp)))
            .orElse(null);
}
/** Computes the orientation from a rotation vector. */
private static float computeOrientationFromRotationVector(float rx, float ry, float rz, float rw, Context context) {
    float[] rotationVector = new float[]{rx, ry, rz, rw};
    float[] rotationMatrix = new float[9];
    float[] orientationAngles = new float[3];

    SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector);
    SensorManager.getOrientation(rotationMatrix, orientationAngles);

    float azimuthDeg = (float) Math.toDegrees(orientationAngles[0]);
    return azimuthDeg < 0 ? azimuthDeg + 360.0f : azimuthDeg;
}

}