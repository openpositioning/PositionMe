package com.openpositioning.PositionMe.fragments;

//This fragment implements the normal Kalman Filter.
//  @author Apoorv Tewari

public class KalmanFilter {
    public static class KalmanLatLong {
        public float Q_metres_per_second;
        public long TimeStamp_milliseconds;
        public double lat;
        public double lng;
        public float variance; // P matrix. Negative means object uninitialized.

        // Method to get the current accuracy (standard deviation) of the Kalman filter's state estimate
        public float get_accuracy() {
            return (float) Math.sqrt(variance);
        }

        // Method to get the current estimated latitude
        public double get_lat() {
            return lat;
        }

        // Method to get the current estimated longitude
        public double get_lng() {
            return lng;
        }

        public KalmanLatLong(float Q_metres_per_second) {
            this.Q_metres_per_second = Q_metres_per_second;
            variance = -1; // Indicate uninitialized state
        }

        // Set state with initial position and variance (accuracy)
        public void SetState(double lat, double lng, float accuracy, long TimeStamp_milliseconds) {
            this.lat = lat;
            this.lng = lng;
            this.variance = accuracy * accuracy;
            this.TimeStamp_milliseconds = TimeStamp_milliseconds;
        }

        // Process a new measurement
        public void Process(double lat_measurement, double lng_measurement, float accuracy, long TimeStamp_milliseconds) {
            if (accuracy < 1) accuracy = 1; // Ensure minimum accuracy
            if (variance < 0) {
                // Initialize with first measurement
                SetState(lat_measurement, lng_measurement, accuracy, TimeStamp_milliseconds);
            } else {
                // Time update (prediction)
                long deltaTime = TimeStamp_milliseconds - this.TimeStamp_milliseconds;
                if (deltaTime > 0) {
                    variance += (deltaTime * Q_metres_per_second * Q_metres_per_second) / 1000;
                    this.TimeStamp_milliseconds = TimeStamp_milliseconds;
                }

                // Measurement update (correction)
                float K = variance / (variance + accuracy * accuracy); // Kalman gain
                lat += K * (lat_measurement - lat);
                lng += K * (lng_measurement - lng);
                variance = (1 - K) * variance; // Update variance
            }
        }
    }

}
