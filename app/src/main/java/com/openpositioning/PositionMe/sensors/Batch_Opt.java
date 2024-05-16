package com.openpositioning.PositionMe.sensors;

import android.util.Log;
import com.google.android.gms.maps.model.LatLng;

public class Batch_Opt {
    private LatLng previousWifiLatLng; // Stores the previous WiFi location
    private LatLng wifiLatLng; // Current WiFi location
    private LatLng pdrLatLng; // Current PDR location
    private double[] positionCorrection;
    private double learningRateWifiPdr = 0.01; // Initial learning rate, small
    private final double maxLearningRate = 0.05; // Max learning rate
    private final double threshold = 0.0001;
    private int maxIterations = 1000; // Maximum number of iterations for the optimization

    public Batch_Opt() {
        this.positionCorrection = new double[2];
    } // Initialize correction array with 2 elements for latitude and longitude

    public void setNumbers(LatLng pdrLatLng, LatLng wifiLatLng) {
        this.pdrLatLng = pdrLatLng; // Set the PDR-determined location
        this.wifiLatLng = wifiLatLng; // Set the WiFi-determined location
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    } // Allow setting a custom number of maximum iterations

    public LatLng startOptimization() {
        return optimizePosition();
    } // Start the optimization process

    private LatLng optimizePosition() {
        LatLng correctedPosition = pdrLatLng; // Start with PDR location as the base corrected position
        int iterations = 0; // Counter for the number of iterations
        do {
            if (wifiLatLng != null && !wifiLatLng.equals(previousWifiLatLng)) {
                // Update correction vector when new WiFi data is available
                double gradientLat = pdrLatLng.latitude - wifiLatLng.latitude;
                double gradientLng = pdrLatLng.longitude - wifiLatLng.longitude;
                positionCorrection[0] -= learningRateWifiPdr * gradientLat; // Apply corrections to latitude
                positionCorrection[1] -= learningRateWifiPdr * gradientLng; // Apply corrections to longitude
                previousWifiLatLng = wifiLatLng; // Update the previous WiFi location
            }

            // Always calculate current location by combining PDR and corrected WiFi location
            correctedPosition = new LatLng(
                    pdrLatLng.latitude + positionCorrection[0],
                    pdrLatLng.longitude + positionCorrection[1]
            );

            // Dynamically adjust learning rate
            if (learningRateWifiPdr < maxLearningRate) {
                learningRateWifiPdr += 0.001; // Gradually increase the learning rate
            }

            iterations++;
        } while (iterations < maxIterations); // Continue until the max number of iterations is reached

        return correctedPosition;
    }
}

