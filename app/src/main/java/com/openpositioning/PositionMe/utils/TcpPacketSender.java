package com.openpositioning.PositionMe.utils;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.sensors.Wifi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import com.openpositioning.PositionMe.fusion.CoordinateConverter;

/**
 * Builds newline-delimited JSON packets from the current live sensor state and
 * sends them through the existing {@link TcpClient}.
 *
 * <p>The packet is designed to match the current Python GUI server, which expects
 * "pdr", "imu", and "wifi" sections in each JSON object.</p>
 */
public class TcpPacketSender {

    private static final String TAG = "TcpPacketSender";

    private final SensorFusion sensorFusion;
    private final TcpClient tcpClient;
    private CoordinateConverter coordinateConverter;

    /**
     * Creates a TCP packet sender for live JSON streaming.
     *
     * @param sensorFusion shared sensor fusion singleton providing live sensor data
     * @param tcpClient TCP client used to send the packets
     */
    public TcpPacketSender(SensorFusion sensorFusion, TcpClient tcpClient) {
        this.sensorFusion = sensorFusion;
        this.tcpClient = tcpClient;
    }

    /**
     * Builds a JSON packet from the latest live data and sends it to the TCP server.
     *
     * <p>Each packet is newline-delimited so that the Python server can split and
     * decode it correctly.</p>
     */
    public void sendLatestPacket() {
        try {
            JSONObject packet = buildPacket();
            tcpClient.send(packet.toString() + "\n");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build TCP JSON packet", e);
        }
    }

    /**
     * Initialises the coordinate converter using the best available absolute reference.
     *
     * <p>Wi-Fi is preferred. If Wi-Fi is unavailable, GNSS is used instead.</p>
     */
    private void initialiseConverterIfNeeded() {
        if (coordinateConverter != null) {
            return;
        }

        LatLng reference = sensorFusion.getLatLngWifiPositioning();

        if (reference == null) {
            float[] gnss = sensorFusion.getGNSSLatitude(false);
            if (gnss != null && gnss.length >= 2) {
                reference = new LatLng(gnss[0], gnss[1]);
            }
        }

        if (reference != null) {
            coordinateConverter = new CoordinateConverter(reference.latitude, reference.longitude);
        }
    }

    /**
     * Builds the current JSON packet from PDR, IMU, and Wi-Fi data.
     *
     * @return packet ready to be serialized and sent
     * @throws JSONException if JSON building fails
     */
    private JSONObject buildPacket() throws JSONException {
        JSONObject packet = new JSONObject();
        packet.put("timestamp", System.currentTimeMillis());

        Map<SensorTypes, float[]> sensorValues = sensorFusion.getSensorValueMap();

        // PDR block
        float[] pdr = sensorValues.get(SensorTypes.PDR);
        JSONObject pdrObject = new JSONObject();
        pdrObject.put("x", pdr != null && pdr.length > 0 ? pdr[0] : 0.0);
        pdrObject.put("y", pdr != null && pdr.length > 1 ? pdr[1] : 0.0);
        packet.put("pdr", pdrObject);

        // IMU block: accelerometer + gyroscope
        float[] accel = sensorValues.get(SensorTypes.ACCELEROMETER);
        float[] gyro = sensorValues.get(SensorTypes.GYRO);

        JSONObject imuObject = new JSONObject();
        imuObject.put("accel_x", accel != null && accel.length > 0 ? accel[0] : 0.0);
        imuObject.put("accel_y", accel != null && accel.length > 1 ? accel[1] : 0.0);
        imuObject.put("accel_z", accel != null && accel.length > 2 ? accel[2] : 0.0);

        imuObject.put("gyro_x", gyro != null && gyro.length > 0 ? gyro[0] : 0.0);
        imuObject.put("gyro_y", gyro != null && gyro.length > 1 ? gyro[1] : 0.0);
        imuObject.put("gyro_z", gyro != null && gyro.length > 2 ? gyro[2] : 0.0);

        packet.put("imu", imuObject);

        // Wi-Fi block
        initialiseConverterIfNeeded();

        JSONObject wifiObject = new JSONObject();
        //Raw Wi-Fi AP List
        JSONArray wifiArray = new JSONArray();
        List<Wifi> wifiList = sensorFusion.getWifiList();
        if (wifiList != null) {
            for (Wifi wifi : wifiList) {
                JSONObject ap = new JSONObject();
                ap.put("bssid", wifi.getBssid());
                ap.put("rssi", wifi.getLevel());
                wifiArray.put(ap);
            }
        }
        wifiObject.put("access_points",wifiArray);

        //Wi-Fi positioning result
        LatLng wifiLatLng = sensorFusion.getLatLngWifiPositioning();
        if (wifiLatLng != null) {
            wifiObject.put("lat", wifiLatLng.latitude);
            wifiObject.put("lng", wifiLatLng.longitude);

            if (coordinateConverter != null) {
                double[] wifiLocal = coordinateConverter.latLngToLocal(wifiLatLng);
                wifiObject.put("x", wifiLocal[0]);
                wifiObject.put("y", wifiLocal[1]);
            } else {
                wifiObject.put("x", JSONObject.NULL);
                wifiObject.put("y", JSONObject.NULL);
            }
        } else {
            wifiObject.put("lat", JSONObject.NULL);
            wifiObject.put("lng", JSONObject.NULL);
            wifiObject.put("x", JSONObject.NULL);
            wifiObject.put("y", JSONObject.NULL);
        }
        wifiObject.put("floor",sensorFusion.getWifiFloor());

        packet.put("wifi", wifiObject);

        return packet;
    }
}