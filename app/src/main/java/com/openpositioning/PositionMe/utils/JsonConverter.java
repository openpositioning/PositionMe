package com.openpositioning.PositionMe.utils;

import com.openpositioning.PositionMe.sensors.Wifi;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Utility class for converting WiFi fingerprint data to JSON format that the server expects
 *
 * @author Thomas Deppe
 * @author Alexandra Geciova
 * @author Christopher Khoo
 */
public final class JsonConverter {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private JsonConverter() {
    }

    /**
     * Converts a list of WiFi data objects to a JSON object.
     *
     * @param wifiList The list of WiFi data objects to convert.
     * @return         The JSON object representing the WiFi data.
     * @throws JSONException If an error occurs while creating the JSON object.
     */
    public static JSONObject toJson(List<Wifi> wifiList) throws JSONException {
        JSONObject json = new JSONObject();
        JSONObject fingerprint = new JSONObject();

        // Iterate over the list of WiFi data objects
        for (Wifi data : wifiList){
            String bssid = Long.toString(data.getBssid());
            fingerprint.put(bssid, data.getLevel());
        }

        // Add the WiFi fingerprint to the main JSON object
        json.put("wf", fingerprint);

        return json;
    }
}
