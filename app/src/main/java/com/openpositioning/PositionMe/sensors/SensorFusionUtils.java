package com.openpositioning.PositionMe.sensors;

import android.os.Build;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.data.remote.WiFiPositioning;

import org.json.JSONObject;

import java.util.List;

public class SensorFusionUtils {
    // but now a static helper taking exactly what it needs.
    public static Traj.Sensor_Info.Builder createInfoBuilder(MovementSensor sensor) {
        return Traj.Sensor_Info.newBuilder()
                .setName(sensor.sensorInfo.getName())
                .setVendor(sensor.sensorInfo.getVendor())
                .setResolution(sensor.sensorInfo.getResolution())
                .setPower(sensor.sensorInfo.getPower())
                .setVersion(sensor.sensorInfo.getVersion())
                .setType(sensor.sensorInfo.getType());
    }

    // Move your old getAllSensorData code here.
    // Notice we pass `sensorFusion` in so we can
    // read needed fields (acc, orientation, wifi, etc.).
    public static JSONObject getAllSensorData(SensorFusion sensorFusion, WiFiPositioning wiFiPositioning) {
        JSONObject record = new JSONObject();
        try {
            // 1) Basic timing info
            long now = System.currentTimeMillis();
            record.put("timestamp", now);

            // 2) GNSS (Lat/Lon)
            float[] gnss = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
            if (gnss != null) {
                record.put("gnssLat", gnss[0]);
                record.put("gnssLon", gnss[1]);
            }

            // 3) PDR estimation
            float[] pdr = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
            if (pdr != null) {
                record.put("pdrX", pdr[0]);
                record.put("pdrY", pdr[1]);
            }

            // 4) Accelerometer
            float[] accel = sensorFusion.getSensorValueMap().get(SensorTypes.ACCELEROMETER);
            if (accel != null) {
                record.put("accX", accel[0]);
                record.put("accY", accel[1]);
                record.put("accZ", accel[2]);
            }

            // 5) Linear acceleration (filtered)
            record.put("filteredAccX", sensorFusion.filteredAcc[0]);
            record.put("filteredAccY", sensorFusion.filteredAcc[1]);
            record.put("filteredAccZ", sensorFusion.filteredAcc[2]);

            // 6) Gyroscope
            float[] gyro = sensorFusion.getSensorValueMap().get(SensorTypes.GYRO);
            if (gyro != null) {
                record.put("gyroX", gyro[0]);
                record.put("gyroY", gyro[1]);
                record.put("gyroZ", gyro[2]);
            }

            // 7) Magnetometer
            float[] mag = sensorFusion.getSensorValueMap().get(SensorTypes.MAGNETICFIELD);
            if (mag != null) {
                record.put("magX", mag[0]);
                record.put("magY", mag[1]);
                record.put("magZ", mag[2]);
            }

            // 8) Gravity
            float[] grav = sensorFusion.getSensorValueMap().get(SensorTypes.GRAVITY);
            if (grav != null) {
                record.put("gravX", grav[0]);
                record.put("gravY", grav[1]);
                record.put("gravZ", grav[2]);
            }

            // 9) Rotation Vector
            record.put("rotationX", sensorFusion.rotation[0]);
            record.put("rotationY", sensorFusion.rotation[1]);
            record.put("rotationZ", sensorFusion.rotation[2]);
            record.put("rotationW", sensorFusion.rotation[3]);

            // 10) Orientation (azimuth, pitch, roll)
            record.put("orientationAzim", sensorFusion.orientation[0]);
            record.put("orientationPitch", sensorFusion.orientation[1]);
            record.put("orientationRoll", sensorFusion.orientation[2]);
            record.put("orientationHeading", computeHeadingFromOrientation(sensorFusion.orientation));

            // 11) Step Count
            record.put("stepCounter", sensorFusion.stepCounter);

            // 12) Environmental sensors
            record.put("light", sensorFusion.light);
            record.put("proximity", sensorFusion.proximity);
            record.put("pressure", sensorFusion.pressure);

            // 13) Elevation & elevator flag
            record.put("elevation", sensorFusion.getElevation());
            record.put("isElevator", sensorFusion.getElevator());

            // 14) Hold mode (in hand / by ear)
            record.put("holdMode", sensorFusion.getHoldMode());

            // 15) WiFi readings
            List<Wifi> wifiList = sensorFusion.getWifiList();
            if (wifiList != null && !wifiList.isEmpty()) {
                org.json.JSONArray wifiArray = new org.json.JSONArray();
                for (Wifi w : wifiList) {
                    JSONObject wObj = new JSONObject();
                    wObj.put("bssid", w.getBssid());
                    wObj.put("rssi", w.getLevel());
                    wifiArray.put(wObj);
                }
                record.put("wifiList", wifiArray);
            }

            // 16) WiFi positioning (if available)
            LatLng wifiPos = wiFiPositioning.getWifiLocation();
            if (wifiPos != null) {
                record.put("wifiLat", wifiPos.latitude);
                record.put("wifiLon", wifiPos.longitude);
                record.put("wifiFloor", wiFiPositioning.getFloor());
            }
            else{
                record.put("wifiLat", JSONObject.NULL);
                record.put("wifiLon", JSONObject.NULL);
                record.put("wifiFloor", JSONObject.NULL);
            }
            record.put("deviceModel", Build.MODEL);


        } catch (Exception e) {
            Log.e("getAllSensorData", "Error building JSON: " + e.getMessage());
        }

        return record;
    }

    private static float computeHeadingFromOrientation(float[] orientation) {
        if (orientation == null || orientation.length < 1) {
            return -1f; // invalid
        }

        // Convert radians to degrees
        float azimuthRad = orientation[0];
        float azimuthDeg = (float) Math.toDegrees(azimuthRad);

        // Normalize to [0, 360)
        float heading = (azimuthDeg + 360f) % 360f;

        return heading;
    }


    /**
     * Method used for converting an array of orientation angles into a rotation matrix.
     *
     * @param o An array containing orientation angles in radians
     * @return resultMatrix representing the orientation angles
     */
    private float[] getRotationMatrixFromOrientation(float[] o) {
        float[] xM = new float[9];
        float[] yM = new float[9];
        float[] zM = new float[9];

        float sinX = (float)Math.sin(o[1]);
        float cosX = (float)Math.cos(o[1]);
        float sinY = (float)Math.sin(o[2]);
        float cosY = (float)Math.cos(o[2]);
        float sinZ = (float)Math.sin(o[0]);
        float cosZ = (float)Math.cos(o[0]);

        // rotation about x-axis (pitch)
        xM[0] = 1.0f; xM[1] = 0.0f; xM[2] = 0.0f;
        xM[3] = 0.0f; xM[4] = cosX; xM[5] = sinX;
        xM[6] = 0.0f; xM[7] = -sinX; xM[8] = cosX;

        // rotation about y-axis (roll)
        yM[0] = cosY; yM[1] = 0.0f; yM[2] = sinY;
        yM[3] = 0.0f; yM[4] = 1.0f; yM[5] = 0.0f;
        yM[6] = -sinY; yM[7] = 0.0f; yM[8] = cosY;

        // rotation about z-axis (azimuth)
        zM[0] = cosZ; zM[1] = sinZ; zM[2] = 0.0f;
        zM[3] = -sinZ; zM[4] = cosZ; zM[5] = 0.0f;
        zM[6] = 0.0f; zM[7] = 0.0f; zM[8] = 1.0f;

        // rotation order is y, x, z (roll, pitch, azimuth)
        float[] resultMatrix = matrixMultiplication(xM, yM);
        resultMatrix = matrixMultiplication(zM, resultMatrix);
        return resultMatrix;
    }

    /**
     * Performs matrix multiplication of two 3x3 matrices.
     *
     * @param A An array representing a 3x3 matrix
     * @param B An array representing a 3x3 matrix
     * @return result representing the product of A and B
     */
    private float[] matrixMultiplication(float[] A, float[] B) {
        float[] result = new float[9];

        result[0] = A[0] * B[0] + A[1] * B[3] + A[2] * B[6];
        result[1] = A[0] * B[1] + A[1] * B[4] + A[2] * B[7];
        result[2] = A[0] * B[2] + A[1] * B[5] + A[2] * B[8];

        result[3] = A[3] * B[0] + A[4] * B[3] + A[5] * B[6];
        result[4] = A[3] * B[1] + A[4] * B[4] + A[5] * B[7];
        result[5] = A[3] * B[2] + A[4] * B[5] + A[5] * B[8];

        result[6] = A[6] * B[0] + A[7] * B[3] + A[8] * B[6];
        result[7] = A[6] * B[1] + A[7] * B[4] + A[8] * B[7];
        result[8] = A[6] * B[2] + A[7] * B[5] + A[8] * B[8];

        return result;
    }
}
