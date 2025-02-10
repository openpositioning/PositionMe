package com.openpositioning.PositionMe.data.remote;

import com.google.android.gms.maps.model.LatLng;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.openpositioning.PositionMe.Traj;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

import com.openpositioning.PositionMe.presentation.fragment.ReplayFragment;

public class TrajectoryFileHandler {

    /**
     * Reads a trajectory file and returns the `Traj.Trajectory` object if the timestamp matches.
     *
     * @param filename        The name of the trajectory file.
     * @param targetTimestamp The timestamp to search for.
     * @return The matching `Traj.Trajectory` object.
     * @throws IOException If file not found or reading fails.
     */
    public static Traj.Trajectory getTrajectoryByTimestamp(String filename, long targetTimestamp) throws IOException {

        StringBuilder fileContent = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = reader.readLine()) != null) {
                fileContent.append(line);
            }
        }

        Gson gson = new Gson();
        Traj.Trajectory trajectory = gson.fromJson(fileContent.toString(), Traj.Trajectory.class);

        if (trajectory.getStartTimestamp() == targetTimestamp) {
            return trajectory;
        } else {
            throw new IllegalArgumentException("Timestamp not found in file: " + targetTimestamp);
        }
    }

    /**
     * Reads a trajectory file and returns the `imuData` object matching the target timestamp.
     *
     * @param filename        Path of the trajectory file.
     * @param targetTimestamp The timestamp to search for.
     * @return The matching `imuData` object as a JsonObject.
     * @throws IOException If file reading fails.
     */
    public static ReplayFragment.ReplayPoint getReplayPointByTimestamp(String filename, String targetTimestamp) throws IOException {
        // Read file
        StringBuilder fileContent = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = reader.readLine()) != null) {
                fileContent.append(line);
            }
        }

        // Parse JSON
        Gson gson = new Gson();
        JsonObject jsonObject = gson.fromJson(fileContent.toString(), JsonObject.class);

        // Get imuData
        JsonArray imuDataArray = jsonObject.getAsJsonArray("imuData");

        // Iterate to find the target timestamp data
        for (JsonElement element : imuDataArray) {
            JsonObject imuObject = element.getAsJsonObject();
            if (imuObject.get("relativeTimestamp").getAsString().equals(targetTimestamp)) {
                // Parse timestamp
                long timestamp = imuObject.get("relativeTimestamp").getAsLong();
                float orientation = imuObject.has("orientation") ? imuObject.get("orientation").getAsFloat() : 0.0f;

                // Parse PDR location
                LatLng pdrLocation = null;
                if (imuObject.has("pdrLat") && imuObject.has("pdrLng")) {
                    double lat = imuObject.get("pdrLat").getAsDouble();
                    double lng = imuObject.get("pdrLng").getAsDouble();
                    pdrLocation = new LatLng(lat, lng);
                }

                // Parse GNSS location (optional)
                LatLng gnssLocation = null;
                if (imuObject.has("gnssLat") && imuObject.has("gnssLng")) {
                    double lat = imuObject.get("gnssLat").getAsDouble();
                    double lng = imuObject.get("gnssLng").getAsDouble();
                    gnssLocation = new LatLng(lat, lng);
                }

                // Return `ReplayPoint` object
//                return new ReplayFragment.ReplayPoint(pdrLocation, gnssLocation, orientation, timestamp);
            }
        }

        // If no matching timestamp is found, return `null`
        return null;
    }

    /**
     * Gets the time range (min and max timestamps) from a trajectory file.
     *
     * @param filename The trajectory file path.
     * @return An array with min and max timestamps.
     * @throws IOException If file reading fails.
     */
    public static long[] getTimeRange(String filename) throws IOException {

        StringBuilder fileContent = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = reader.readLine()) != null) {
                fileContent.append(line);
            }
        }

        // Parse JSON data using Gson
        Gson gson = new Gson();
        JsonObject jsonObject = gson.fromJson(fileContent.toString(), JsonObject.class);

        if (jsonObject == null || !jsonObject.has("imuData")) {
            throw new IOException("Invalid file format: missing imuData array");
        }

        // Get imuData array and determine min and max timestamps
        JsonArray imuDataArray = jsonObject.getAsJsonArray("imuData");
        long minTimestamp = Long.MAX_VALUE;
        long maxTimestamp = Long.MIN_VALUE;

        for (JsonElement element : imuDataArray) {
            JsonObject imu = element.getAsJsonObject();
            long t = imu.get("relativeTimestamp").getAsLong();
            if (t < minTimestamp) {
                minTimestamp = t;
            }
            if (t > maxTimestamp) {
                maxTimestamp = t;
            }
        }

        return new long[]{minTimestamp, maxTimestamp};
    }

    /**
     * Reads IMU data from a JSON trajectory file.
     *
     * @param filename The trajectory file path.
     * @return A list of IMU data as JsonObjects.
     * @throws IOException If file reading fails.
     */
    private static List<JsonObject> loadImuData(String filename) throws IOException {
        List<JsonObject> imuDataList = new ArrayList<>();
        StringBuilder fileContent = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = reader.readLine()) != null) {
                fileContent.append(line);
            }
        }

        Gson gson = new Gson();
        JsonObject jsonObject = gson.fromJson(fileContent.toString(), JsonObject.class);
        JsonArray imuDataArray = jsonObject.getAsJsonArray("imuData");

        for (JsonElement element : imuDataArray) {
            imuDataList.add(element.getAsJsonObject());
        }

        return imuDataList;
    }


        /**
         * Reads a trajectory file and returns the smoothed IMU data at a specific timestamp.
         *
         * @param filename        Path to the trajectory file.
         * @param targetTimestamp The desired timestamp (milliseconds).
         * @return Smoothed IMU data as a JsonObject.
         * @throws IOException If file reading fails.
         */
        public static JsonObject getSmoothedImuData(String filename, long targetTimestamp) throws IOException {
            List<JsonObject> imuDataList = loadImuData(filename);

            // 1. Find the two closest timestamps (t1, t2) around the target timestamp
            int lowerIndex = -1, upperIndex = -1;
            for (int i = 0; i < imuDataList.size() - 1; i++) {
                long t1 = imuDataList.get(i).get("relativeTimestamp").getAsLong();
                long t2 = imuDataList.get(i + 1).get("relativeTimestamp").getAsLong();

                if (t1 <= targetTimestamp && t2 >= targetTimestamp) {
                    lowerIndex = i;
                    upperIndex = i + 1;
                    break;
                }
            }

            // If no suitable interval is found, return the closest available IMU data
            if (lowerIndex == -1 || upperIndex == -1) {
                return imuDataList.get(imuDataList.size() - 1); // Return the last data point
            }

            // 2. Perform linear interpolation
            JsonObject imu1 = imuDataList.get(lowerIndex);
            JsonObject imu2 = imuDataList.get(upperIndex);
            double alpha = computeAlpha(imu1, imu2, targetTimestamp);

            JsonObject interpolatedData = interpolateImuData(imu1, imu2, alpha);
            interpolatedData.addProperty("relativeTimestamp", targetTimestamp); // Set target timestamp

            // 3. Apply sliding window smoothing
            return smoothSingleImuData(imuDataList, interpolatedData, lowerIndex, upperIndex, 5);
        }

        /**
         * Computes the linear interpolation weight α.
         *
         * @param imu1            The IMU data at the lower timestamp.
         * @param imu2            The IMU data at the upper timestamp.
         * @param targetTimestamp The desired timestamp.
         * @return Interpolation coefficient α.
         */
        private static double computeAlpha(JsonObject imu1, JsonObject imu2, long targetTimestamp) {
            long t1 = imu1.get("relativeTimestamp").getAsLong();
            long t2 = imu2.get("relativeTimestamp").getAsLong();
            return (double) (targetTimestamp - t1) / (t2 - t1);
        }

        /**
         * Performs linear interpolation between two IMU data points.
         *
         * @param imu1  The IMU data at the lower timestamp.
         * @param imu2  The IMU data at the upper timestamp.
         * @param alpha The interpolation coefficient (0 to 1).
         * @return Interpolated IMU data.
         */
        private static JsonObject interpolateImuData(JsonObject imu1, JsonObject imu2, double alpha) {
            JsonObject result = new JsonObject();

            result.addProperty("accY", interpolate(imu1.get("accY").getAsDouble(), imu2.get("accY").getAsDouble(), alpha));
            result.addProperty("accZ", interpolate(imu1.get("accZ").getAsDouble(), imu2.get("accZ").getAsDouble(), alpha));
            result.addProperty("rotationVectorX", interpolate(imu1.get("rotationVectorX").getAsDouble(), imu2.get("rotationVectorX").getAsDouble(), alpha));
            result.addProperty("rotationVectorY", interpolate(imu1.get("rotationVectorY").getAsDouble(), imu2.get("rotationVectorY").getAsDouble(), alpha));
            result.addProperty("rotationVectorZ", interpolate(imu1.get("rotationVectorZ").getAsDouble(), imu2.get("rotationVectorZ").getAsDouble(), alpha));
            result.addProperty("rotationVectorW", interpolate(imu1.get("rotationVectorW").getAsDouble(), imu2.get("rotationVectorW").getAsDouble(), alpha));

            return result;
        }

        /**
         * Performs linear interpolation between two values.
         *
         * @param v1    Start value.
         * @param v2    End value.
         * @param alpha Interpolation coefficient (0 to 1).
         * @return Interpolated value.
         */
        private static double interpolate(double v1, double v2, double alpha) {
            return v1 * (1 - alpha) + v2 * alpha;
        }

        /**
         * Applies a sliding window smoothing filter to a single interpolated IMU data point.
         *
         * @param imuDataList      The original IMU data list.
         * @param interpolatedData The interpolated IMU data point.
         * @param lowerIndex       The lower bound index for the smoothing window.
         * @param upperIndex       The upper bound index for the smoothing window.
         * @param windowSize       The size of the sliding window.
         * @return Smoothed IMU data.
         */
        private static JsonObject smoothSingleImuData(List<JsonObject> imuDataList, JsonObject interpolatedData, int lowerIndex, int upperIndex, int windowSize) {
            JsonObject smoothedData = new JsonObject();

            smoothedData.addProperty("relativeTimestamp", interpolatedData.get("relativeTimestamp").getAsLong());

            smoothedData.addProperty("accY", smoothAttribute(imuDataList, lowerIndex, upperIndex, interpolatedData, "accY", windowSize));
            smoothedData.addProperty("accZ", smoothAttribute(imuDataList, lowerIndex, upperIndex, interpolatedData, "accZ", windowSize));
            smoothedData.addProperty("rotationVectorX", smoothAttribute(imuDataList, lowerIndex, upperIndex, interpolatedData, "rotationVectorX", windowSize));
            smoothedData.addProperty("rotationVectorY", smoothAttribute(imuDataList, lowerIndex, upperIndex, interpolatedData, "rotationVectorY", windowSize));
            smoothedData.addProperty("rotationVectorZ", smoothAttribute(imuDataList, lowerIndex, upperIndex, interpolatedData, "rotationVectorZ", windowSize));
            smoothedData.addProperty("rotationVectorW", smoothAttribute(imuDataList, lowerIndex, upperIndex, interpolatedData, "rotationVectorW", windowSize));

            return smoothedData;
        }

        /**
         * Computes the smoothed value of a specific IMU attribute using a sliding window filter.
         *
         * @param imuDataList      The original IMU data list.
         * @param lowerIndex       The lower bound index for the smoothing window.
         * @param upperIndex       The upper bound index for the smoothing window.
         * @param interpolatedData The interpolated IMU data point.
         * @param key              The attribute name (e.g., "accY").
         * @param windowSize       The size of the smoothing window.
         * @return Smoothed value.
         */
        private static double smoothAttribute(List<JsonObject> imuDataList, int lowerIndex, int upperIndex, JsonObject interpolatedData, String key, int windowSize) {
            int count = 0;
            double sum = interpolatedData.get(key).getAsDouble();

            int halfWindow = windowSize / 2;
            for (int i = Math.max(0, lowerIndex - halfWindow); i <= Math.min(upperIndex + halfWindow, imuDataList.size() - 1); i++) {
                sum += imuDataList.get(i).get(key).getAsDouble();
                count++;
            }

            return sum / count;
        }

}
