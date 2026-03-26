package com.openpositioning.PositionMe.data.local;

import android.content.Context;
import android.hardware.SensorManager;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openpositioning.PositionMe.presentation.fragment.ReplayFragment;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Handles parsing of trajectory data stored in JSON files, combining IMU, PDR, GNSS, and pressure data
 * to reconstruct motion paths for replay.
 */
public class TrajParser {

    private static final String TAG = "TrajParser";
    private static final double SEA_LEVEL_PRESSURE_HPA = 1013.25d;
    // 原始 replay 没有 building-specific floorHeight 时，先用保守默认值。
    // 这样法国原始轨迹至少能在 replay 里看到“不是永远锁在基层”的楼层切换。
    private static final double DEFAULT_REPLAY_FLOOR_HEIGHT_METERS = 4.0d;
    private static final double HEIGHT_CHANGE_THRESHOLD_METERS = 0.35d;

    public static class ReplayPoint {
        public LatLng pdrLocation;
        public LatLng gnssLocation;
        public float orientation;
        public float speed;
        public long timestamp;
        public Integer syntheticFloor;
        public Double currentElevation;
        public Double deltaHeight;
        public boolean heightChanged;
        public Integer initialFloor;

        public ReplayPoint(LatLng pdrLocation,
                           LatLng gnssLocation,
                           float orientation,
                           float speed,
                           long timestamp,
                           Integer syntheticFloor,
                           Double currentElevation,
                           Double deltaHeight,
                           boolean heightChanged,
                           Integer initialFloor) {
            this.pdrLocation = pdrLocation;
            this.gnssLocation = gnssLocation;
            this.orientation = orientation;
            this.speed = speed;
            this.timestamp = timestamp;
            this.syntheticFloor = syntheticFloor;
            this.currentElevation = currentElevation;
            this.deltaHeight = deltaHeight;
            this.heightChanged = heightChanged;
            this.initialFloor = initialFloor;
        }
    }

    private static class ImuRecord {
        public long relativeTimestamp;
        public float rotationVectorX;
        public float rotationVectorY;
        public float rotationVectorZ;
        public float rotationVectorW;
    }

    private static class PdrRecord {
        public long relativeTimestamp;
        public float x;
        public float y;
        public Integer syntheticFloor;
        public Double currentElevation;
        public Double deltaHeight;
        public Boolean heightChanged;
    }

    private static class PressureRecord {
        public long relativeTimestamp;
        public double pressureHpa;
    }

    private static class GnssRecord {
        public long relativeTimestamp;
        public double latitude;
        public double longitude;
    }

    private static class VerticalReplayState {
        public Integer syntheticFloor;
        public Double currentElevation;
        public Double deltaHeight;
        public boolean heightChanged;
    }

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

            JsonObject root = readJsonObject(file);
            if (root == null) {
                Log.e(TAG, "Failed to parse root JSON object for file: " + filePath);
                return result;
            }

            Integer initialFloor = root.has("initialFloor") && !root.get("initialFloor").isJsonNull()
                    ? safeGetInt(root, "initialFloor")
                    : null;

            List<ImuRecord> imuList = parseImuData(root.getAsJsonArray("imuData"));
            List<PdrRecord> pdrList = parsePdrData(root.getAsJsonArray("pdrData"));
            List<GnssRecord> gnssList = parseGnssData(root.getAsJsonArray("gnssData"));
            List<PressureRecord> pressureList = parsePressureData(root.getAsJsonArray("pressureData"));

            Log.i(TAG, "Parsed data - IMU: " + imuList.size()
                    + " records, PDR: " + pdrList.size()
                    + " records, GNSS: " + gnssList.size()
                    + " records, Pressure: " + pressureList.size() + " records");

            double referenceAltitude = Double.NaN;
            Double previousElevation = null;
            Integer previousSyntheticFloor = null;
            if (!pdrList.isEmpty() && !pressureList.isEmpty()) {
                PressureRecord basePressure = findClosestPressureRecord(pressureList, pdrList.get(0).relativeTimestamp);
                if (basePressure != null) {
                    referenceAltitude = pressureToAltitudeMeters(basePressure.pressureHpa);
                }
            }

            for (int i = 0; i < pdrList.size(); i++) {
                PdrRecord pdr = pdrList.get(i);

                ImuRecord closestImu = findClosestImuRecord(imuList, pdr.relativeTimestamp);
                float orientationDeg = closestImu != null
                        ? computeOrientationFromRotationVector(
                        closestImu.rotationVectorX,
                        closestImu.rotationVectorY,
                        closestImu.rotationVectorZ,
                        closestImu.rotationVectorW,
                        context)
                        : 0f;

                float speed = 0f;
                if (i > 0) {
                    PdrRecord prev = pdrList.get(i - 1);
                    double dt = (pdr.relativeTimestamp - prev.relativeTimestamp) / 1000.0;
                    double dx = pdr.x - prev.x;
                    double dy = pdr.y - prev.y;
                    double distance = Math.sqrt(dx * dx + dy * dy);
                    if (dt > 0) {
                        speed = (float) (distance / dt);
                    }
                }

                double lat = originLat + pdr.y * 1E-5;
                double lng = originLng + pdr.x * 1E-5;
                LatLng pdrLocation = new LatLng(lat, lng);

                GnssRecord closestGnss = findClosestGnssRecord(gnssList, pdr.relativeTimestamp);
                LatLng gnssLocation = closestGnss != null
                        ? new LatLng(closestGnss.latitude, closestGnss.longitude)
                        : null;

                VerticalReplayState verticalState;
                if (hasEmbeddedReplayVerticalFields(pdr)) {
                    verticalState = new VerticalReplayState();
                    verticalState.syntheticFloor = pdr.syntheticFloor;
                    verticalState.currentElevation = pdr.currentElevation;
                    verticalState.deltaHeight = pdr.deltaHeight;
                    verticalState.heightChanged = Boolean.TRUE.equals(pdr.heightChanged);
                } else {
                    verticalState = deriveVerticalStateFromPressure(
                            pressureList,
                            pdr.relativeTimestamp,
                            referenceAltitude,
                            previousElevation,
                            previousSyntheticFloor);
                }

                if (verticalState.currentElevation != null) {
                    previousElevation = verticalState.currentElevation;
                }
                if (verticalState.syntheticFloor != null) {
                    previousSyntheticFloor = verticalState.syntheticFloor;
                }

                result.add(new ReplayPoint(
                        pdrLocation,
                        gnssLocation,
                        orientationDeg,
                        speed,
                        pdr.relativeTimestamp,
                        verticalState.syntheticFloor,
                        verticalState.currentElevation,
                        verticalState.deltaHeight,
                        verticalState.heightChanged,
                        initialFloor
                ));
            }

            Collections.sort(result, Comparator.comparingLong(rp -> rp.timestamp));
            Log.i(TAG, "Final ReplayPoints count: " + result.size());

        } catch (Exception e) {
            Log.e(TAG, "Error parsing trajectory file!", e);
        }

        return result;
    }

    private static JsonObject readJsonObject(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        String raw = sb.toString();
        int firstBrace = raw.indexOf('{');
        if (firstBrace > 0) {
            raw = raw.substring(firstBrace);
        }
        return new JsonParser().parse(raw).getAsJsonObject();
    }

    private static List<ImuRecord> parseImuData(JsonArray imuArray) {
        List<ImuRecord> imuList = new ArrayList<>();
        if (imuArray == null) {
            return imuList;
        }

        for (JsonElement element : imuArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            ImuRecord record = new ImuRecord();
            record.relativeTimestamp = safeGetLong(obj, "relativeTimestamp");

            JsonObject rotationVector = obj.has("rotationVector") && obj.get("rotationVector").isJsonObject()
                    ? obj.getAsJsonObject("rotationVector")
                    : null;
            if (rotationVector != null) {
                record.rotationVectorX = safeGetFloat(rotationVector, "x");
                record.rotationVectorY = safeGetFloat(rotationVector, "y");
                record.rotationVectorZ = safeGetFloat(rotationVector, "z");
                record.rotationVectorW = safeGetFloat(rotationVector, "w");
            }
            imuList.add(record);
        }
        return imuList;
    }

    private static List<PdrRecord> parsePdrData(JsonArray pdrArray) {
        List<PdrRecord> pdrList = new ArrayList<>();
        if (pdrArray == null) {
            return pdrList;
        }

        for (JsonElement element : pdrArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            PdrRecord record = new PdrRecord();
            record.relativeTimestamp = safeGetLong(obj, "relativeTimestamp");
            record.x = safeGetFloat(obj, "x");
            record.y = safeGetFloat(obj, "y");
            record.syntheticFloor = obj.has("syntheticFloor") && !obj.get("syntheticFloor").isJsonNull()
                    ? safeGetInt(obj, "syntheticFloor")
                    : null;
            record.currentElevation = obj.has("currentElevation") && !obj.get("currentElevation").isJsonNull()
                    ? safeGetDouble(obj, "currentElevation")
                    : null;
            record.deltaHeight = obj.has("deltaHeight") && !obj.get("deltaHeight").isJsonNull()
                    ? safeGetDouble(obj, "deltaHeight")
                    : null;
            record.heightChanged = obj.has("heightChanged") && !obj.get("heightChanged").isJsonNull()
                    ? obj.get("heightChanged").getAsBoolean()
                    : null;
            pdrList.add(record);
        }
        return pdrList;
    }

    private static List<PressureRecord> parsePressureData(JsonArray pressureArray) {
        List<PressureRecord> pressureList = new ArrayList<>();
        if (pressureArray == null) {
            return pressureList;
        }

        for (JsonElement element : pressureArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            if (!obj.has("pressure") || obj.get("pressure").isJsonNull()) {
                continue;
            }
            PressureRecord record = new PressureRecord();
            record.relativeTimestamp = safeGetLong(obj, "relativeTimestamp");
            record.pressureHpa = safeGetDouble(obj, "pressure");
            pressureList.add(record);
        }
        return pressureList;
    }

    private static List<GnssRecord> parseGnssData(JsonArray gnssArray) {
        List<GnssRecord> gnssList = new ArrayList<>();
        if (gnssArray == null) {
            return gnssList;
        }

        for (JsonElement element : gnssArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            JsonObject position = obj.has("position") && obj.get("position").isJsonObject()
                    ? obj.getAsJsonObject("position")
                    : obj;

            if (!position.has("latitude") || !position.has("longitude")) {
                continue;
            }

            GnssRecord record = new GnssRecord();
            record.relativeTimestamp = safeGetLong(position, "relativeTimestamp");
            record.latitude = safeGetDouble(position, "latitude");
            record.longitude = safeGetDouble(position, "longitude");
            gnssList.add(record);
        }
        return gnssList;
    }

    private static boolean hasEmbeddedReplayVerticalFields(PdrRecord pdr) {
        return pdr.syntheticFloor != null
                || pdr.currentElevation != null
                || pdr.deltaHeight != null
                || Boolean.TRUE.equals(pdr.heightChanged);
    }

    private static VerticalReplayState deriveVerticalStateFromPressure(List<PressureRecord> pressureList,
                                                                       long targetTimestamp,
                                                                       double referenceAltitude,
                                                                       Double previousElevation,
                                                                       Integer previousSyntheticFloor) {
        VerticalReplayState state = new VerticalReplayState();
        if (pressureList == null || pressureList.isEmpty() || Double.isNaN(referenceAltitude)) {
            return state;
        }

        PressureRecord pressureRecord = findClosestPressureRecord(pressureList, targetTimestamp);
        if (pressureRecord == null) {
            return state;
        }

        double altitude = pressureToAltitudeMeters(pressureRecord.pressureHpa);
        double currentElevation = altitude - referenceAltitude;
        double deltaHeight = previousElevation == null ? 0d : currentElevation - previousElevation;
        int syntheticFloor = (int) Math.round(currentElevation / DEFAULT_REPLAY_FLOOR_HEIGHT_METERS);

        state.currentElevation = currentElevation;
        state.deltaHeight = deltaHeight;
        state.syntheticFloor = syntheticFloor;
        state.heightChanged = Math.abs(deltaHeight) >= HEIGHT_CHANGE_THRESHOLD_METERS
                || (previousSyntheticFloor != null && syntheticFloor != previousSyntheticFloor);
        return state;
    }

    private static double pressureToAltitudeMeters(double pressureHpa) {
        return 44330.0d * (1.0d - Math.pow(pressureHpa / SEA_LEVEL_PRESSURE_HPA, 0.1903d));
    }

    private static ImuRecord findClosestImuRecord(List<ImuRecord> imuList, long targetTimestamp) {
        if (imuList == null || imuList.isEmpty()) {
            return null;
        }
        ImuRecord closest = null;
        long bestDistance = Long.MAX_VALUE;
        for (ImuRecord imu : imuList) {
            long distance = Math.abs(imu.relativeTimestamp - targetTimestamp);
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = imu;
            }
        }
        return closest;
    }

    private static GnssRecord findClosestGnssRecord(List<GnssRecord> gnssList, long targetTimestamp) {
        if (gnssList == null || gnssList.isEmpty()) {
            return null;
        }
        GnssRecord closest = null;
        long bestDistance = Long.MAX_VALUE;
        for (GnssRecord gnss : gnssList) {
            long distance = Math.abs(gnss.relativeTimestamp - targetTimestamp);
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = gnss;
            }
        }
        return closest;
    }

    private static PressureRecord findClosestPressureRecord(List<PressureRecord> pressureList, long targetTimestamp) {
        if (pressureList == null || pressureList.isEmpty()) {
            return null;
        }
        PressureRecord closest = null;
        long bestDistance = Long.MAX_VALUE;
        for (PressureRecord record : pressureList) {
            long distance = Math.abs(record.relativeTimestamp - targetTimestamp);
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = record;
            }
        }
        return closest;
    }

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

    private static long safeGetLong(JsonObject obj, String key) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static int safeGetInt(JsonObject obj, String key) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static float safeGetFloat(JsonObject obj, String key) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsFloat() : 0f;
        } catch (Exception e) {
            return 0f;
        }
    }

    private static double safeGetDouble(JsonObject obj, String key) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsDouble() : 0d;
        } catch (Exception e) {
            return 0d;
        }
    }
}
