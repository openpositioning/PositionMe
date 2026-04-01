package com.openpositioning.PositionMe.data.local;

import android.content.Context;
import android.hardware.SensorManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.maps.model.LatLng;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.Locale;

/**
 * Handles parsing of trajectory data stored in JSON files, combining IMU, PDR, GNSS and
 * optional barometer data to reconstruct replay motion paths.
 */
public class TrajParser {

    private static final String TAG = "TrajParser";
    private static final double DEFAULT_REPLAY_FLOOR_HEIGHT_METERS = 4.0d;
    private static final double REPLAY_HEIGHT_CHANGED_THRESHOLD_METERS = 1.6d;

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
        long relativeTimestamp;
        float rotationVectorX, rotationVectorY, rotationVectorZ, rotationVectorW;
    }

    private static class PdrRecord {
        long relativeTimestamp;
        float x, y;
        Integer syntheticFloor;
        Double currentElevation;
        Double deltaHeight;
        Boolean heightChanged;
    }

    private static class GnssRecord {
        long relativeTimestamp;
        double latitude, longitude;
    }

    private static class PressureRecord {
        long relativeTimestamp;
        double pressureHpa;
    }

    private static class VerticalEstimate {
        final double currentElevation;
        final double deltaHeight;
        final boolean heightChanged;
        final int syntheticFloor;

        VerticalEstimate(double currentElevation, double deltaHeight, boolean heightChanged, int syntheticFloor) {
            this.currentElevation = currentElevation;
            this.deltaHeight = deltaHeight;
            this.heightChanged = heightChanged;
            this.syntheticFloor = syntheticFloor;
        }
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
            Log.i(TAG, "Successfully read trajectory file: " + filePath);

            Integer initialFloor = root.has("initialFloor") && !root.get("initialFloor").isJsonNull()
                    ? safeGetInt(root.get("initialFloor"), null)
                    : null;

            List<ImuRecord> imuList = parseImuData(root.getAsJsonArray("imuData"));
            List<PdrRecord> pdrList = parsePdrData(root.getAsJsonArray("pdrData"));
            List<GnssRecord> gnssList = parseGnssData(root.getAsJsonArray("gnssData"));
            List<PressureRecord> pressureList = parsePressureData(root.getAsJsonArray("pressureData"));

            Log.i(TAG, String.format(Locale.US,
                    "Parsed data - IMU=%d PDR=%d GNSS=%d Pressure=%d",
                    imuList.size(), pdrList.size(), gnssList.size(), pressureList.size()));

            if (pdrList.isEmpty()) {
                return result;
            }

            long replayReferenceTimestamp = pdrList.get(0).relativeTimestamp;
            PressureRecord replayReferencePressure = findClosestPressureRecord(pressureList, replayReferenceTimestamp);
            Double baseElevation = replayReferencePressure != null
                    ? pressureToRelativeElevationMeters(replayReferencePressure.pressureHpa,
                    replayReferencePressure.pressureHpa)
                    : null;
            double floorHeightMeters = DEFAULT_REPLAY_FLOOR_HEIGHT_METERS;

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

                Integer syntheticFloor = pdr.syntheticFloor;
                Double currentElevation = pdr.currentElevation;
                Double deltaHeight = pdr.deltaHeight;
                boolean heightChanged = Boolean.TRUE.equals(pdr.heightChanged);

                if (syntheticFloor == null || currentElevation == null || deltaHeight == null || !heightChanged) {
                    VerticalEstimate estimate = buildVerticalEstimate(
                            pressureList,
                            replayReferencePressure,
                            pdr.relativeTimestamp,
                            floorHeightMeters
                    );
                    if (syntheticFloor == null) {
                        syntheticFloor = estimate.syntheticFloor;
                    }
                    if (currentElevation == null) {
                        currentElevation = estimate.currentElevation;
                    }
                    if (deltaHeight == null) {
                        deltaHeight = estimate.deltaHeight;
                    }
                    if (!heightChanged) {
                        heightChanged = estimate.heightChanged;
                    }
                }

                result.add(new ReplayPoint(
                        pdrLocation,
                        gnssLocation,
                        orientationDeg,
                        speed,
                        pdr.relativeTimestamp,
                        syntheticFloor,
                        currentElevation,
                        deltaHeight,
                        heightChanged,
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
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private static List<ImuRecord> parseImuData(@Nullable JsonArray imuArray) {
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
            record.relativeTimestamp = safeGetLong(obj.get("relativeTimestamp"), 0L);

            JsonObject rv = obj.has("rotationVector") && obj.get("rotationVector").isJsonObject()
                    ? obj.getAsJsonObject("rotationVector") : null;
            if (rv != null) {
                record.rotationVectorX = safeGetFloat(rv.get("x"), 0f);
                record.rotationVectorY = safeGetFloat(rv.get("y"), 0f);
                record.rotationVectorZ = safeGetFloat(rv.get("z"), 0f);
                record.rotationVectorW = safeGetFloat(rv.get("w"), 1f);
            } else {
                record.rotationVectorW = 1f;
            }
            imuList.add(record);
        }
        return imuList;
    }

    private static List<PdrRecord> parsePdrData(@Nullable JsonArray pdrArray) {
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
            record.relativeTimestamp = safeGetLong(obj.get("relativeTimestamp"), 0L);
            record.x = safeGetFloat(obj.get("x"), 0f);
            record.y = safeGetFloat(obj.get("y"), 0f);
            record.syntheticFloor = safeGetInt(obj.get("syntheticFloor"), null);
            record.currentElevation = safeGetDoubleObj(obj.get("currentElevation"));
            record.deltaHeight = safeGetDoubleObj(obj.get("deltaHeight"));
            record.heightChanged = safeGetBoolean(obj.get("heightChanged"), null);
            pdrList.add(record);
        }
        return pdrList;
    }

    private static List<GnssRecord> parseGnssData(@Nullable JsonArray gnssArray) {
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
                    ? obj.getAsJsonObject("position") : obj;

            GnssRecord record = new GnssRecord();
            record.relativeTimestamp = safeGetLong(position.get("relativeTimestamp"), 0L);
            record.latitude = safeGetDouble(position.get("latitude"), 0d);
            record.longitude = safeGetDouble(position.get("longitude"), 0d);
            gnssList.add(record);
        }
        return gnssList;
    }

    private static List<PressureRecord> parsePressureData(@Nullable JsonArray pressureArray) {
        List<PressureRecord> pressureList = new ArrayList<>();
        if (pressureArray == null) {
            return pressureList;
        }

        for (JsonElement element : pressureArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            PressureRecord record = new PressureRecord();
            record.relativeTimestamp = safeGetLong(obj.get("relativeTimestamp"), 0L);
            record.pressureHpa = safeGetDouble(obj.get("pressure"), Double.NaN);
            if (!Double.isNaN(record.pressureHpa) && record.pressureHpa > 0d) {
                pressureList.add(record);
            }
        }
        return pressureList;
    }

    private static VerticalEstimate buildVerticalEstimate(List<PressureRecord> pressureList,
                                                          @Nullable PressureRecord basePressure,
                                                          long targetTimestamp,
                                                          double floorHeightMeters) {
        if (pressureList == null || pressureList.isEmpty() || basePressure == null) {
            return new VerticalEstimate(0d, 0d, false, 0);
        }

        PressureRecord closest = findClosestPressureRecord(pressureList, targetTimestamp);
        if (closest == null) {
            return new VerticalEstimate(0d, 0d, false, 0);
        }

        double currentElevation = pressureToRelativeElevationMeters(basePressure.pressureHpa, closest.pressureHpa);
        double deltaHeight = currentElevation;
        boolean heightChanged = Math.abs(deltaHeight) >= REPLAY_HEIGHT_CHANGED_THRESHOLD_METERS;
        int syntheticFloor = (int) Math.round(deltaHeight / floorHeightMeters);

        return new VerticalEstimate(currentElevation, deltaHeight, heightChanged, syntheticFloor);
    }

    private static double pressureToRelativeElevationMeters(double basePressureHpa, double currentPressureHpa) {
        return 44330.0d * (1.0d - Math.pow(currentPressureHpa / basePressureHpa, 0.1903d));
    }

    @Nullable
    private static ImuRecord findClosestImuRecord(List<ImuRecord> imuList, long targetTimestamp) {
        return imuList.stream()
                .min(Comparator.comparingLong(imu -> Math.abs(imu.relativeTimestamp - targetTimestamp)))
                .orElse(null);
    }

    @Nullable
    private static GnssRecord findClosestGnssRecord(List<GnssRecord> gnssList, long targetTimestamp) {
        return gnssList.stream()
                .min(Comparator.comparingLong(gnss -> Math.abs(gnss.relativeTimestamp - targetTimestamp)))
                .orElse(null);
    }

    @Nullable
    private static PressureRecord findClosestPressureRecord(List<PressureRecord> pressureList, long targetTimestamp) {
        return pressureList.stream()
                .min(Comparator.comparingLong(pr -> Math.abs(pr.relativeTimestamp - targetTimestamp)))
                .orElse(null);
    }

    private static float computeOrientationFromRotationVector(float rx, float ry, float rz, float rw, Context context) {
        float[] rotationVector = new float[]{rx, ry, rz, rw};
        float[] rotationMatrix = new float[9];
        float[] orientationAngles = new float[3];

        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);

        float azimuthDeg = (float) Math.toDegrees(orientationAngles[0]);
        return azimuthDeg < 0 ? azimuthDeg + 360.0f : azimuthDeg;
    }

    private static long safeGetLong(@Nullable JsonElement element, long fallback) {
        try {
            return element == null || element.isJsonNull() ? fallback : element.getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static float safeGetFloat(@Nullable JsonElement element, float fallback) {
        try {
            return element == null || element.isJsonNull() ? fallback : element.getAsFloat();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double safeGetDouble(@Nullable JsonElement element, double fallback) {
        try {
            return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Nullable
    private static Double safeGetDoubleObj(@Nullable JsonElement element) {
        try {
            return element == null || element.isJsonNull() ? null : element.getAsDouble();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static Integer safeGetInt(@Nullable JsonElement element, @Nullable Integer fallback) {
        try {
            return element == null || element.isJsonNull() ? fallback : element.getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Nullable
    private static Boolean safeGetBoolean(@Nullable JsonElement element, @Nullable Boolean fallback) {
        try {
            return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
