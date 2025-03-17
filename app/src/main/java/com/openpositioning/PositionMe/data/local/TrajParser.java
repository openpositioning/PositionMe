package com.openpositioning.PositionMe.data.local;

import android.content.Context;
import android.hardware.SensorManager;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openpositioning.PositionMe.presentation.fragment.ReplayFragment;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
        public LatLng pdrLocation;  // PDR-derived location estimate
        public LatLng gnssLocation; // GNSS location (may be null if unavailable)
        public float orientation;   // Orientation in degrees
        public float speed;         // Speed in meters per second
        public long timestamp;      // Relative timestamp

        /**
         * Constructs a ReplayPoint.
         *
         * @param pdrLocation  The pedestrian dead reckoning (PDR) location.
         * @param gnssLocation The GNSS location, or null if unavailable.
         * @param orientation  The orientation angle in degrees.
         * @param speed        The speed in meters per second.
         * @param timestamp    The timestamp associated with this point.
         */
        public ReplayPoint(LatLng pdrLocation, LatLng gnssLocation, float orientation, float speed, long timestamp) {
            this.pdrLocation = pdrLocation;
            this.gnssLocation = gnssLocation;
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
    }

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
    public static List<ReplayPoint> parseTrajectoryData(String filePath, Context context,
                                                        LatLng initialPos) {
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
            JsonObject root = new JsonParser().parse(br).getAsJsonObject();
            br.close();

            Log.i(TAG, "Successfully read trajectory file: " + filePath);

            long startTimestamp = root.has("startTimestamp") ? root.get("startTimestamp").getAsLong() : 0;

            List<ImuRecord> imuList = parseImuData(root.getAsJsonArray("imuData"));
            List<PdrRecord> pdrList = parsePdrData(root.getAsJsonArray("pdrData"));
            List<GnssRecord> gnssList = parseGnssData(root.getAsJsonArray("gnssData"));

            Log.i(TAG, "Parsed data - IMU: " + imuList.size() + " records, PDR: "
                    + pdrList.size() + " records, GNSS: " + gnssList.size() + " records");

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

                double lat = initialPos.latitude + pdr.y * 1E-5;
                double lng = initialPos.longitude + pdr.x * 1E-5;
                LatLng pdrLocation = new LatLng(lat, lng);

                GnssRecord closestGnss = findClosestGnssRecord(gnssList, pdr.relativeTimestamp);
                LatLng gnssLocation = closestGnss != null ?
                        new LatLng(closestGnss.latitude, closestGnss.longitude) : null;

                result.add(new ReplayPoint(pdrLocation, gnssLocation, orientationDeg,
                        0f, pdr.relativeTimestamp));
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
    if (gnssArray == null) return gnssList;
    Gson gson = new Gson();
    for (int i = 0; i < gnssArray.size(); i++) {
        GnssRecord record = gson.fromJson(gnssArray.get(i), GnssRecord.class);
        gnssList.add(record);
    }
    return gnssList;
}/** Finds the closest IMU record to the given timestamp. */
private static ImuRecord findClosestImuRecord(List<ImuRecord> imuList, long targetTimestamp) {
    return imuList.stream().min(Comparator.comparingLong(imu -> Math.abs(imu.relativeTimestamp - targetTimestamp)))
            .orElse(null);

}/** Finds the closest GNSS record to the given timestamp. */
private static GnssRecord findClosestGnssRecord(List<GnssRecord> gnssList, long targetTimestamp) {
    return gnssList.stream().min(Comparator.comparingLong(gnss -> Math.abs(gnss.relativeTimestamp - targetTimestamp)))
            .orElse(null);

}/** Computes the orientation from a rotation vector. */
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