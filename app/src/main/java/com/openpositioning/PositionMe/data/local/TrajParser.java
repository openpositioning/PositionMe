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
import com.openpositioning.PositionMe.Traj;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class TrajParser {

    private static final String TAG = "TrajParser";

    public static class ReplayPoint {
        public LatLng pdrLocation;
        public LatLng gnssLocation;

        public float orientation;
        public float speed;
        public long timestamp;

        public List<Traj.WiFi_Sample> wifiSamples = new ArrayList<>();
        public LatLng cachedWiFiLocation = null;
        public TrajParser.TagPoint tagPoint;

        public ReplayPoint(LatLng pdrLocation, LatLng gnssLocation, float orientation,
                           float speed, long timestamp, List<Traj.WiFi_Sample> wifiSamples,
                           TrajParser.TagPoint tagPoint) {
            this.pdrLocation = pdrLocation;
            this.gnssLocation = gnssLocation;
            this.orientation = orientation;
            this.speed = speed;
            this.timestamp = timestamp;
            this.wifiSamples = wifiSamples != null ? wifiSamples : new ArrayList<>();
            this.cachedWiFiLocation = null; // Start with no cached result
            this.tagPoint = tagPoint;
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
        public double latitude, longitude;
        public double altitude;
        public String provider;
    }

    public static class TagPoint {
        public String label;
        public LatLng location;

        public TagPoint(String label, LatLng location) {
            this.label = label;
            this.location = location;
        }
    }

    public static List<TrajParser.TagPoint> tagPoints = new ArrayList<>();
    public static List<ReplayPoint> replayData = new ArrayList<>();

    public static List<ReplayPoint> parseTrajectoryData(String filePath, Context context,
                                                        double originLat, double originLng) {
        List<ReplayPoint> result = new ArrayList<>();
        List<GnssRecord> gnssList = new ArrayList<>();
        tagPoints.clear(); // Clear any previous tags



        try {
            File file = new File(filePath);
            if (!file.exists() || !file.canRead()) {
                Log.e(TAG, "File does NOT exist or is NOT readable: " + filePath);
                return result;
            }

            BufferedReader br = new BufferedReader(new FileReader(file));
            JsonObject root = new JsonParser().parse(br).getAsJsonObject();
            br.close();

            long startTimestamp = root.has("startTimestamp") ? root.get("startTimestamp").getAsLong() : 0;

            List<ImuRecord> imuList = parseImuData(root.getAsJsonArray("imuData"));
            List<PdrRecord> pdrList = parsePdrData(root.getAsJsonArray("pdrData"));
            gnssList = parseGnssData(root.getAsJsonArray("gnssData"));

            Log.i(TAG, "Parsed data - IMU: " + imuList.size() + " PDR: " + pdrList.size() + " GNSS: " + gnssList.size());

            // Extract tags from GNSS with provider "fusion"
            if (root.has("gnssData")) {
                JsonArray gnssArray = root.getAsJsonArray("gnssData");
                for (JsonElement elem : gnssArray) {
                    JsonObject obj = elem.getAsJsonObject();
                    //Log.d("ParseGnss", "GNSS Element: " + elem);
                    //Log.d("Condition", "Is fusion here?: " + (obj.has("provider") && "fusion".equalsIgnoreCase(obj.get("provider").getAsString())));
                    if (obj.has("provider") && "fusion".equalsIgnoreCase(obj.get("provider").getAsString())) {
                        long timestamp = obj.get("relativeTimestamp").getAsLong();
                        double lat = obj.get("latitude").getAsDouble();
                        double lon = obj.get("longitude").getAsDouble();
                        double alt = obj.has("altitude") ? obj.get("altitude").getAsDouble() : 0.0;
                        Log.d("NewTag", "Fusion Data: " + lat + " " + lon);
                        String label = String.format("Tag @ %.5f, %.5f\nAlt: %.1f", lat, lon, alt);
                        tagPoints.add(new TagPoint(label, new LatLng(lat, lon)));
                    }

                }

            }
            for (int i = 0; i < tagPoints.size(); i++) {
                if (tagPoints.get(i) != null) Log.d("Tags", "Tags: " + tagPoints.get(i));
            }
            //Log.d("SizeComp", "Tags List Size: " + tagPoints.size() + "PDR List Size: " + pdrList.size());


            for (int i = 0; i < pdrList.size(); i++) {
                PdrRecord pdr = pdrList.get(i);

                ImuRecord closestImu = findClosestImuRecord(imuList, pdr.relativeTimestamp);
                float orientationDeg = closestImu != null ? computeOrientationFromRotationVector(
                        closestImu.rotationVectorX, closestImu.rotationVectorY,
                        closestImu.rotationVectorZ, closestImu.rotationVectorW, context
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

                List<Traj.WiFi_Sample> wifiSamples = new ArrayList<>();

                if (root.has("wifiData")) {
                    JsonArray wifiArray = root.getAsJsonArray("wifiData");
                    for (JsonElement wifiElement : wifiArray) {
                        JsonObject wifiObject = wifiElement.getAsJsonObject();
                        long timestamp = wifiObject.has("relativeTimestamp") && !wifiObject.get("relativeTimestamp").isJsonNull()
                                ? wifiObject.get("relativeTimestamp").getAsLong() : 0;

                        if (Math.abs(timestamp - pdr.relativeTimestamp) < 500) {
                            if (wifiObject.has("macScans") && !wifiObject.get("macScans").isJsonNull()) {
                                JsonArray macScansArray = wifiObject.getAsJsonArray("macScans");
                                for (JsonElement scanElement : macScansArray) {
                                    JsonObject scanObject = scanElement.getAsJsonObject();
                                    long mac = scanObject.has("mac") && !scanObject.get("mac").isJsonNull()
                                            ? scanObject.get("mac").getAsLong() : 0;
                                    int rssi = scanObject.has("rssi") && !scanObject.get("rssi").isJsonNull()
                                            ? scanObject.get("rssi").getAsInt() : -100;

                                    if (mac != 0) {
                                        Traj.WiFi_Sample.Builder builder = Traj.WiFi_Sample.newBuilder()
                                                .setRelativeTimestamp(timestamp)
                                                .addMacScans(Traj.Mac_Scan.newBuilder()
                                                        .setMac(mac)
                                                        .setRssi(rssi)
                                                        .setRelativeTimestamp(timestamp));
                                        wifiSamples.add(builder.build());
                                    }
                                }
                            }
                        }
                    }
                }
                if (i < tagPoints.size()) {
                    result.add(new ReplayPoint(pdrLocation, gnssLocation, orientationDeg,
                            speed, pdr.relativeTimestamp, wifiSamples, tagPoints.get(i)));
                }else{
                    result.add(new ReplayPoint(pdrLocation, gnssLocation, orientationDeg,
                            speed, pdr.relativeTimestamp, wifiSamples, null));
                }
            }

            Collections.sort(result, Comparator.comparingLong(r -> r.timestamp));
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
        for (JsonElement elem : imuArray) {
            imuList.add(gson.fromJson(elem, ImuRecord.class));
        }
        return imuList;
    }

    private static List<PdrRecord> parsePdrData(JsonArray pdrArray) {
        List<PdrRecord> pdrList = new ArrayList<>();
        if (pdrArray == null) return pdrList;
        Gson gson = new Gson();
        for (JsonElement elem : pdrArray) {
            pdrList.add(gson.fromJson(elem, PdrRecord.class));
        }
        return pdrList;
    }

    private static List<GnssRecord> parseGnssData(JsonArray gnssArray) {
        List<GnssRecord> gnssList = new ArrayList<>();
        if (gnssArray == null) return gnssList;
        for (JsonElement elem : gnssArray) {
            JsonObject obj = elem.getAsJsonObject();
            GnssRecord record = new GnssRecord();
            record.relativeTimestamp = obj.get("relativeTimestamp").getAsLong();
            record.latitude = obj.get("latitude").getAsDouble();
            record.longitude = obj.get("longitude").getAsDouble();
            record.altitude = obj.has("altitude") ? obj.get("altitude").getAsDouble() : 0.0;
            record.provider = obj.has("provider") ? obj.get("provider").getAsString() : "";
            gnssList.add(record);
        }
        return gnssList;
    }

    private static ImuRecord findClosestImuRecord(List<ImuRecord> imuList, long targetTimestamp) {
        return imuList.stream()
                .min(Comparator.comparingLong(i -> Math.abs(i.relativeTimestamp - targetTimestamp)))
                .orElse(null);
    }

    private static GnssRecord findClosestGnssRecord(List<GnssRecord> gnssList, long targetTimestamp) {
        return gnssList.stream()
                .min(Comparator.comparingLong(g -> Math.abs(g.relativeTimestamp - targetTimestamp)))
                .orElse(null);
    }

    // code by Jamie Arnott
    /**
     * Method to extract the Gnss records (if any) that contains tagged records
     * with "fusion" providers
     *
     * @param gnsslist
     * @return List of (@link GnssRecord) which contains the records with tags
     */
    private static List<GnssRecord> findTaggedRecords(List<GnssRecord> gnsslist){
        List<GnssRecord> tagRecords = new ArrayList<>();
        for (int i = 0; i < gnsslist.size(); i++){
            Log.d("NewTag", "gnss provider " + gnsslist.get(i).provider);
            if (Objects.equals(gnsslist.get(i).provider, "fusion")) tagRecords.add(gnsslist.get(i));
        }
        return tagRecords;
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
}
