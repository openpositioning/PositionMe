package com.openpositioning.PositionMe.data.local;

import android.content.Context;
import android.hardware.SensorManager;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class TrajParser {

    private static final String TAG = "TrajParser";

    public static class ReplayPoint {
        public LatLng pdrLocation;
        public LatLng gnssLocation;
        public float orientation;
        public float speed;
        public long timestamp;

        public ReplayPoint(LatLng pdrLocation, LatLng gnssLocation, float orientation, float speed, long timestamp) {
            this.pdrLocation = pdrLocation;
            this.gnssLocation = gnssLocation;
            this.orientation = orientation;
            this.speed = speed;
            this.timestamp = timestamp;
        }
    }

    private static class ImuRecord {
        public long relativeTimestamp;
        public float accX, accY, accZ;
        public float gyrX, gyrY, gyrZ;
        public float rotationVectorX, rotationVectorY, rotationVectorZ, rotationVectorW;
    }

    private static class PdrRecord {
        public long relativeTimestamp;
        public float x, y;
    }

    private static class GnssRecord {
        public long relativeTimestamp;
        public double latitude;
        public double longitude;
    }

    public static List<ReplayPoint> parseTrajectoryData(String filePath, Context context, double originLat, double originLng) {
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

            Log.i(TAG, "Parsed data - IMU: " + imuList.size() + " records, PDR: " + pdrList.size() + " records, GNSS: " + gnssList.size() + " records");

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


                double lat = originLat + pdr.y * 1E-5;
                double lng = originLng + pdr.x * 1E-5;
                LatLng pdrLocation = new LatLng(lat, lng);

                GnssRecord closestGnss = findClosestGnssRecord(gnssList, pdr.relativeTimestamp);
                LatLng gnssLocation = closestGnss != null ? new LatLng(closestGnss.latitude, closestGnss.longitude) : null;

                ReplayPoint rp = new ReplayPoint(pdrLocation, gnssLocation, orientationDeg, speed, pdr.relativeTimestamp);
                result.add(rp);

                Log.d(TAG, "Created ReplayPoint - PDR: " + pdrLocation + ", GNSS: " + gnssLocation + ", Orientation: " + orientationDeg + ", Speed: " + speed + ", Timestamp: " + pdr.relativeTimestamp);
            }

            Collections.sort(result, Comparator.comparingLong(rp -> rp.timestamp));

            Log.i(TAG, "Final ReplayPoints count: " + result.size());

        } catch (Exception e) {
            Log.e(TAG, "Error parsing trajectory file!", e);
        }

        return result;
    }

    private static List<ImuRecord> parseImuData(JsonArray imuArray) {
        List<ImuRecord> imuList = new ArrayList<>();
        if (imuArray == null) return imuList;
        Gson gson = new Gson();
        for (int i = 0; i < imuArray.size(); i++) {
            ImuRecord record = gson.fromJson(imuArray.get(i), ImuRecord.class);
            imuList.add(record);
        }
        return imuList;
    }

    private static List<PdrRecord> parsePdrData(JsonArray pdrArray) {
        List<PdrRecord> pdrList = new ArrayList<>();
        if (pdrArray == null) return pdrList;
        Gson gson = new Gson();
        for (int i = 0; i < pdrArray.size(); i++) {
            PdrRecord record = gson.fromJson(pdrArray.get(i), PdrRecord.class);
            pdrList.add(record);
        }
        return pdrList;
    }

    private static List<GnssRecord> parseGnssData(JsonArray gnssArray) {
        List<GnssRecord> gnssList = new ArrayList<>();
        if (gnssArray == null) return gnssList;
        Gson gson = new Gson();
        for (int i = 0; i < gnssArray.size(); i++) {
            GnssRecord record = gson.fromJson(gnssArray.get(i), GnssRecord.class);
            gnssList.add(record);
        }
        return gnssList;
    }

    private static ImuRecord findClosestImuRecord(List<ImuRecord> imuList, long targetTimestamp) {
        return imuList.stream().min(Comparator.comparingLong(imu -> Math.abs(imu.relativeTimestamp - targetTimestamp))).orElse(null);
    }

    private static GnssRecord findClosestGnssRecord(List<GnssRecord> gnssList, long targetTimestamp) {
        return gnssList.stream().min(Comparator.comparingLong(gnss -> Math.abs(gnss.relativeTimestamp - targetTimestamp))).orElse(null);
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
}
