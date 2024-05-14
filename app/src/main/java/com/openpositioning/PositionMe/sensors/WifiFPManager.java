package com.openpositioning.PositionMe.sensors;

import android.util.Log;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * Manages the creation of Wi-Fi fingerprints for the application. This class is responsible
 * for extracting Wi-Fi data from the {@link SensorFusion} instance and compiling it into
 * a Wi-Fi fingerprint JSON structure. The fingerprint includes the BSSID (Basic Service
 * Set Identifier) and RSSI (Received Signal Strength Indicator) of each detected Wi-Fi
 * network.
 *
 * The Wi-Fi list, obtained from {@link SensorFusion}, is transformed into a dictionary
 * mapping BSSID to RSSI values. This dictionary is then serialized to JSON format, creating
 * a Wi-Fi fingerprint that can be used for location identification and other purposes within
 * the application.
 *
 * The class follows a singleton pattern to ensure that only one instance manages Wi-Fi
 * fingerprint creation throughout the application lifecycle.
 *
 * Usage of this class involves invoking the {@code createWifiFingerprintJson} method to
 * retrieve the current Wi-Fi fingerprint as a JSON string.
 *
 * Exception handling within the method ensures graceful degradation in case of errors
 * during the Wi-Fi fingerprint creation process.
 *
 * @author Michalis Voudaskas
 */
public class WifiFPManager {
    private SensorFusion sensorFusion;

    public WifiFPManager(SensorFusion sensorFusion) {
        this.sensorFusion = sensorFusion;
    }

    private static WifiFPManager instance;

    private WifiFPManager() {
        // private constructor
    }

    public static WifiFPManager getInstance() {
        if (instance == null) {
            instance = new WifiFPManager(SensorFusion.getInstance());
        }
        return instance;
    }


    public String createWifiFingerprintJson() {
        try {
            List<Wifi> wifiList = sensorFusion.getWifiList();
            if (wifiList != null && !wifiList.isEmpty()) {
                Map<String, Integer> wifiReadings = new HashMap<>();

                for (Wifi wifi : wifiList) {
                    String bssidAsString = Long.toString(wifi.getBssid());
                    wifiReadings.put(bssidAsString, wifi.getLevel());
                }

                // Wrapping the dictionary in another map with key "wf"
                Map<String, Map<String, Integer>> finalStructure = new HashMap<>();
                finalStructure.put("wf", wifiReadings);

                Gson gson = new Gson();
                return gson.toJson(finalStructure);
            }
        } catch (Exception e) {
          // Log.e("WifiFPManager", "Error creating JSON", e);
        }
        return "{}";
    }
}