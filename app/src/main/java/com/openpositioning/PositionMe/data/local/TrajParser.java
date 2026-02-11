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
        public LatLng pdrLocation;
        public LatLng gnssLocation;
        public float orientation;
        public float speed;
        public long timestamp;
        public int testPointNumber;  // 0 = no test point, >0 = test point marker number

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
            this.testPointNumber = 0;  // Default: no test point
        }
    }

    /** Represents an IMU (Inertial Measurement Unit) data record used for orientation calculations. */
    private static class ImuRecord {
        public long relativeTimestamp;
        public float accX, accY, accZ; // Accelerometer values
        public float gyrX, gyrY, gyrZ; // Gyroscope values
        public float rotationVectorX, rotationVectorY, rotationVectorZ, rotationVectorW; // Rotation quaternion
    }

    /** Helper class matching the protobuf JSON nested structure for IMU data. */
    private static class ImuJsonRecord {
        public long relativeTimestamp;
        public Vector3Json acc;
        public Vector3Json gyr;
        public QuaternionJson rotationVector;
        public int stepCount;
    }

    private static class Vector3Json {
        public float x, y, z;
    }

    private static class QuaternionJson {
        public float x, y, z, w;
    }

    /** Helper class matching the protobuf JSON nested structure for GNSS data. */
    private static class GnssJsonRecord {
        public GnssPositionJson position;
        public float accuracy;
        public float speed;
        public float bearing;
        public String provider;
    }

    private static class GnssPositionJson {
        public long relativeTimestamp;
        public double latitude, longitude;
        public double altitude;
        public String floor;
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
        public float accuracy; // GNSS accuracy in meters
    }

    /**
     * Extracts the initial recording position from a trajectory JSON file.
     * <p>
     * This quickly reads the file to find the original recording location, using these priorities:
     * 1. {@code initialPosition} field (set when the user marked their start location during recording)
     * 2. First GNSS reading from {@code gnssData} (absolute GPS coordinates captured during recording)
     * 3. Returns {@code null} if neither is available
     * </p>
     *
     * @param filePath Path to the trajectory JSON file.
     * @return A {@link LatLng} representing the recording's initial position, or null if unavailable.
     */
    public static LatLng extractInitialPosition(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists() || !file.canRead()) {
                Log.e(TAG, "Cannot read file for initial position extraction: " + filePath);
                return null;
            }

            BufferedReader br = new BufferedReader(new FileReader(file));
            JsonObject root = new JsonParser().parse(br).getAsJsonObject();
            br.close();

            // Log what top-level fields the file contains for diagnostics
            Log.i(TAG, "extractInitialPosition: file has keys: " + root.keySet());

            // Priority 1: Try "initialPosition" field (protobuf camelCase of initial_position)
            if (root.has("initialPosition") && root.get("initialPosition").isJsonObject()) {
                JsonObject initPos = root.getAsJsonObject("initialPosition");
                double lat = initPos.has("latitude") ? initPos.get("latitude").getAsDouble() : 0.0;
                double lng = initPos.has("longitude") ? initPos.get("longitude").getAsDouble() : 0.0;
                Log.i(TAG, "extractInitialPosition: initialPosition field found: lat=" + lat + ", lng=" + lng);
                if (lat != 0.0 || lng != 0.0) {
                    return new LatLng(lat, lng);
                }
            } else {
                Log.i(TAG, "extractInitialPosition: no 'initialPosition' field in file");
            }

            // Priority 2: Try first GNSS reading from gnssData array
            if (root.has("gnssData") && root.get("gnssData").isJsonArray()) {
                JsonArray gnssArray = root.getAsJsonArray("gnssData");
                Log.i(TAG, "extractInitialPosition: gnssData array has " + gnssArray.size() + " entries");
                for (int i = 0; i < gnssArray.size(); i++) {
                    JsonObject gnssEntry = gnssArray.get(i).getAsJsonObject();
                    if (gnssEntry.has("position") && gnssEntry.get("position").isJsonObject()) {
                        JsonObject pos = gnssEntry.getAsJsonObject("position");
                        double lat = pos.has("latitude") ? pos.get("latitude").getAsDouble() : 0.0;
                        double lng = pos.has("longitude") ? pos.get("longitude").getAsDouble() : 0.0;
                        if (lat != 0.0 || lng != 0.0) {
                            Log.i(TAG, "extractInitialPosition: found GNSS at index " + i + ": " + lat + ", " + lng);
                            return new LatLng(lat, lng);
                        }
                    }
                }
                Log.w(TAG, "extractInitialPosition: gnssData exists but all entries have 0,0 coordinates");
            } else {
                Log.w(TAG, "extractInitialPosition: no 'gnssData' array in file");
            }

            // Priority 3: Try correctedPositions
            if (root.has("correctedPositions") && root.get("correctedPositions").isJsonArray()) {
                JsonArray corrected = root.getAsJsonArray("correctedPositions");
                Log.i(TAG, "extractInitialPosition: correctedPositions array has " + corrected.size() + " entries");
                if (corrected.size() > 0) {
                    JsonObject pos = corrected.get(0).getAsJsonObject();
                    double lat = pos.has("latitude") ? pos.get("latitude").getAsDouble() : 0.0;
                    double lng = pos.has("longitude") ? pos.get("longitude").getAsDouble() : 0.0;
                    if (lat != 0.0 || lng != 0.0) {
                        Log.i(TAG, "extractInitialPosition: from correctedPositions: " + lat + ", " + lng);
                        return new LatLng(lat, lng);
                    }
                }
            } else {
                Log.i(TAG, "extractInitialPosition: no 'correctedPositions' array in file");
            }

            Log.w(TAG, "No initial position found in trajectory file. " +
                    "Future recordings will save the start position automatically.");
        } catch (Exception e) {
            Log.e(TAG, "Error extracting initial position from file: " + e.getMessage(), e);
        }
        return null;
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
     * @param originLat Latitude of the reference origin.
     * @param originLng Longitude of the reference origin.
     * @return A list of parsed {@link ReplayPoint} objects.
     */
    public static List<ReplayPoint> parseTrajectoryData(String filePath, Context context,
                                                        double originLat, double originLng) {
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

// 🆕 Parse test points
            List<long[]> testPointTimestamps = new ArrayList<>();  // [timestamp, pointNumber]
            if (root.has("testPoints") && root.get("testPoints").isJsonArray()) {
                JsonArray testPointsArray = root.getAsJsonArray("testPoints");
                for (int i = 0; i < testPointsArray.size(); i++) {
                    try {
                        JsonObject tp = testPointsArray.get(i).getAsJsonObject();
                        long ts = tp.has("relativeTimestamp") ? tp.get("relativeTimestamp").getAsLong() : 0;
                        testPointTimestamps.add(new long[]{ts, i + 1});  // 1-indexed point number
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to parse test point " + i + ": " + e.getMessage());
                    }
                }
                Log.i(TAG, "Parsed " + testPointTimestamps.size() + " test points");
            }

            Log.i(TAG, "Parsed data - IMU: " + imuList.size() + " records, PDR: "
                    + pdrList.size() + " records, GNSS: " + gnssList.size() + " records");

            // ========== GNSS-PDR Fusion (matches real-time SimplePositionFusion) ==========
            // Replicate the drift correction that happens during real-time recording.
            // Without this, the replay trajectory is pure PDR which appears smaller
            // because it lacks the GNSS corrections applied in real-time.
            final double METERS_PER_DEG_LAT = 111139.0;
            final double metersPerDegLng = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(originLat));
            double correctionX = 0.0; // Accumulated east-west drift correction (meters)
            double correctionY = 0.0; // Accumulated north-south drift correction (meters)
            int lastGnssIndex = -1;   // Track which GNSS records have been applied

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

                // Apply GNSS drift correction: find new GNSS records up to this timestamp
                // and accumulate corrections, same as SimplePositionFusion.updateWithGNSS()
                for (int g = lastGnssIndex + 1; g < gnssList.size(); g++) {
                    GnssRecord gnss = gnssList.get(g);
                    if (gnss.relativeTimestamp > pdr.relativeTimestamp) break;

                    // Skip poor accuracy GNSS (same logic as SimplePositionFusion)
                    float acc = gnss.accuracy > 0 ? gnss.accuracy : 30f;
                    if (acc > 30) {
                        lastGnssIndex = g;
                        continue;
                    }

                    // Where PDR+correction thinks we are right now
                    double pdrLat = originLat + (pdr.y + correctionY) / METERS_PER_DEG_LAT;
                    double pdrLng = originLng + (pdr.x + correctionX) / metersPerDegLng;

                    // Error between GNSS and current fused position
                    double errorY = (gnss.latitude - pdrLat) * METERS_PER_DEG_LAT;
                    double errorX = (gnss.longitude - pdrLng) * metersPerDegLng;

                    // Weight: better accuracy = more trust (matches SimplePositionFusion)
                    double weight = Math.min(0.5, 2.5 / acc);

                    correctionX += errorX * weight;
                    correctionY += errorY * weight;

                    lastGnssIndex = g;
                }

                // Final position: start + PDR + GNSS correction (same as SimplePositionFusion)
                double lat = originLat + (pdr.y + correctionY) / METERS_PER_DEG_LAT;
                double lng = originLng + (pdr.x + correctionX) / metersPerDegLng;
                LatLng pdrLocation = new LatLng(lat, lng);

                GnssRecord closestGnss = findClosestGnssRecord(gnssList, pdr.relativeTimestamp);
                LatLng gnssLocation = closestGnss != null ?
                        new LatLng(closestGnss.latitude, closestGnss.longitude) : null;

                result.add(new ReplayPoint(pdrLocation, gnssLocation, orientationDeg,
                        0f, pdr.relativeTimestamp));
            }

            // 🆕 Mark test points on closest PDR points
            for (long[] testPoint : testPointTimestamps) {
                long testTimestamp = testPoint[0];
                int pointNumber = (int) testPoint[1];

                // Find the closest replay point by timestamp
                ReplayPoint closest = null;
                long minDiff = Long.MAX_VALUE;
                for (ReplayPoint rp : result) {
                    long diff = Math.abs(rp.timestamp - testTimestamp);
                    if (diff < minDiff) {
                        minDiff = diff;
                        closest = rp;
                    }
                }
                if (closest != null) {
                    closest.testPointNumber = pointNumber;
                    Log.i(TAG, "Test point #" + pointNumber + " mapped to timestamp " + closest.timestamp);
                }
            }

            Collections.sort(result, Comparator.comparingLong(rp -> rp.timestamp));

            Log.i(TAG, "Final ReplayPoints count: " + result.size());

        } catch (Exception e) {
            Log.e(TAG, "Error parsing trajectory file!", e);
        }

        return result;
    }
/** Parses IMU data from JSON - handles protobuf nested structure. */
private static List<ImuRecord> parseImuData(JsonArray imuArray) {
    List<ImuRecord> imuList = new ArrayList<>();
    if (imuArray == null) return imuList;
    Gson gson = new Gson();
    for (int i = 0; i < imuArray.size(); i++) {
        try {
            ImuJsonRecord jsonRec = gson.fromJson(imuArray.get(i), ImuJsonRecord.class);
            ImuRecord record = new ImuRecord();
            record.relativeTimestamp = jsonRec.relativeTimestamp;
            if (jsonRec.acc != null) {
                record.accX = jsonRec.acc.x;
                record.accY = jsonRec.acc.y;
                record.accZ = jsonRec.acc.z;
            }
            if (jsonRec.gyr != null) {
                record.gyrX = jsonRec.gyr.x;
                record.gyrY = jsonRec.gyr.y;
                record.gyrZ = jsonRec.gyr.z;
            }
            if (jsonRec.rotationVector != null) {
                record.rotationVectorX = jsonRec.rotationVector.x;
                record.rotationVectorY = jsonRec.rotationVector.y;
                record.rotationVectorZ = jsonRec.rotationVector.z;
                record.rotationVectorW = jsonRec.rotationVector.w;
            }
            imuList.add(record);
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse IMU record " + i + ": " + e.getMessage());
        }
    }
    return imuList;
}/** Parses PDR data from JSON. */
private static List<PdrRecord> parsePdrData(JsonArray pdrArray) {
    List<PdrRecord> pdrList = new ArrayList<>();
    if (pdrArray == null) return pdrList;
    Gson gson = new Gson();
    for (int i = 0; i < pdrArray.size(); i++) {
        try {
            PdrRecord record = gson.fromJson(pdrArray.get(i), PdrRecord.class);
            pdrList.add(record);
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse PDR record " + i + ": " + e.getMessage());
        }
    }
    return pdrList;
}/** Parses GNSS data from JSON - handles protobuf nested structure. */
private static List<GnssRecord> parseGnssData(JsonArray gnssArray) {
    List<GnssRecord> gnssList = new ArrayList<>();
    if (gnssArray == null) return gnssList;
    Gson gson = new Gson();
    for (int i = 0; i < gnssArray.size(); i++) {
        try {
            GnssJsonRecord jsonRec = gson.fromJson(gnssArray.get(i), GnssJsonRecord.class);
            GnssRecord record = new GnssRecord();
            if (jsonRec.position != null) {
                record.relativeTimestamp = jsonRec.position.relativeTimestamp;
                record.latitude = jsonRec.position.latitude;
                record.longitude = jsonRec.position.longitude;
            }
            record.accuracy = jsonRec.accuracy;
            // Only add if we have valid coordinates
            if (record.latitude != 0.0 || record.longitude != 0.0) {
                gnssList.add(record);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse GNSS record " + i + ": " + e.getMessage());
        }
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