package com.openpositioning.PositionMe.fragments;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;

import com.openpositioning.PositionMe.R;


public class NuclearBuildingManager {
    private IndoorMapManager indoorMapManager;

    public NuclearBuildingManager(GoogleMap map) {
        // The nuclear building has 5 floors
        indoorMapManager = new IndoorMapManager(map, 5);

        // southwest corner
        double N1 = 55.92279;
        double W1 = 3.174643;

        // Northeast corner
        double N2 = 55.92335;
        double W2 = 3.173829;

        // Initialize the indoor map of each layer
        indoorMapManager.addFloor(0, R.drawable.floor_lg, new LatLngBounds(new LatLng(N1, -W1), new LatLng(N2, -W2)));
        indoorMapManager.addFloor(1, R.drawable.floor_ug, new LatLngBounds(new LatLng(N1, -W1), new LatLng(N2, -W2)));
        indoorMapManager.addFloor(2, R.drawable.floor_1, new LatLngBounds(new LatLng(N1, -W1), new LatLng(N2, -W2)));
        indoorMapManager.addFloor(3, R.drawable.floor_2, new LatLngBounds(new LatLng(N1, -W1), new LatLng(N2, -W2)));
        indoorMapManager.addFloor(4, R.drawable.floor_3, new LatLngBounds(new LatLng(N1, -W1), new LatLng(N2, -W2)));

    }
    public IndoorMapManager getIndoorMapManager() {
        return indoorMapManager;
    }
}
