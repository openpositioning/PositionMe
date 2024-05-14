package com.openpositioning.PositionMe.fragments;

import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.GoogleMap;
import com.openpositioning.PositionMe.sensors.SensorFusion;

/**
 * A simple {@link Fragment} subclass. The startLocation fragment is displayed before the trajectory
 * recording starts. This fragment displays a map in which the user can adjust their location to
 * correct the PDR when it is complete
 *
 * @author Apoorv Tewari
 * @see HomeFragment the previous fragment in the nav graph.
 * @see RecordingFragment the next fragment in the nav graph.
 * @see SensorFusion the class containing sensors and recording.
 */

public class GlobalVariables {
    // Singleton instance
    private static final GlobalVariables instance = new GlobalVariables();

    // Map type variable
    private static int mapType = GoogleMap.MAP_TYPE_NORMAL; // Default map type

    // Private constructor to prevent instantiation
    private GlobalVariables() {
    }

    // Get instance method
    public static GlobalVariables getInstance() {
        return instance;
    }

    // Getter and Setter for mapType
    public static int getMapType() {
        return mapType;
    }

    public static void setMapType(int mapType) {
        GlobalVariables.mapType = mapType;
    }
}
