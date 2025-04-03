package com.openpositioning.PositionMe.FusionFilter;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;
import org.json.JSONException;
import org.json.JSONObject;

public class EKFState {
    // -------------------------
    // A. Original fields: latitude/longitude + covariance, etc.
    // -------------------------
    private static double[] state;     // [latitude, longitude]
    private double[][] covariance;     // 2x2 covariance matrix

    private WiFiPositioning wiFiPositioning;
    private LatLng wifiLocation;       // WiFi positioning result

    // Latest EKF estimated position (latitude/longitude)
    private LatLng EKFPosition;

    // Index variables (can continue to be used as needed)
    private int pdrIndex = 0;
    private int gnssIndex = 0;
    private int pressureIndex = 0;
    private int wifiIndex = 0;

    // -------------------------
    // B. New fields: reference point & local plane coordinates
    // -------------------------
    // Using initialPosition as the reference latitude/longitude for the local coordinate system
    private double baseLat;
    private double baseLon;
    // Plane coordinates [x, y] (in meters) converted from (lat, lon) relative to the reference point
    private float[] localXY;

    /**
     * Constructor: Takes an initial position (usually coordinates obtained from WiFi positioning),
     * initializes the state vector, covariance matrix, and initiates a WiFi positioning request.
     * <p>
     * Also sets the initial position as the reference point (baseLat, baseLon) for the local coordinate system,
     * to automatically maintain localXY afterwards.
     *
     * @param initialPosition Initial position (LatLng)
     * @param initialVariance
     */
    public EKFState(LatLng initialPosition, double initialVariance) {
        if (initialPosition != null) {
            state = new double[]{initialPosition.latitude, initialPosition.longitude};
            EKFPosition = initialPosition;
            // Set as local coordinate reference
            baseLat = initialPosition.latitude;
            baseLon = initialPosition.longitude;
        } else {
            state = new double[]{0.0, 0.0};
            EKFPosition = new LatLng(0.0, 0.0);
            baseLat = 0.0;
            baseLon = 0.0;
        }
        // Initialize covariance matrix as identity matrix
        covariance = new double[][] {
                {1.0, 0.0},
                {0.0, 1.0}
        };
        // Calculate localXY (relative to reference point)
        localXY = latLngToXY(state[0], state[1], baseLat, baseLon);

        // Initiate WiFi positioning request to update state
        updateWithWiFiPosition();
    }

    /**
     * Initiates a WiFi positioning request and updates the state vector and estimated position in the callback
     */
    private void updateWithWiFiPosition() {
        if (wiFiPositioning == null) {
            System.err.println("WiFiPositioning instance not set, cannot initiate WiFi positioning request");
            return;
        }
        // Construct WiFi fingerprint JSON object, actual data should be generated according to specific business needs
        JSONObject wifiFingerprint = new JSONObject();
        try {
            // Example: construct a fingerprint containing a single AP data
            JSONObject wifiAccessPoints = new JSONObject();
            wifiAccessPoints.put("00:11:22:33:44:55", -45);
            wifiFingerprint.put("wf", wifiAccessPoints);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        // Call WiFi positioning service to get the latest coordinates
        wiFiPositioning.request(wifiFingerprint, new WiFiPositioning.VolleyCallback() {
            @Override
            public void onSuccess(LatLng location, int floor) {
                // Update WiFi positioning result and state vector
                wifiLocation = location;
                state[0] = location.latitude;
                state[1] = location.longitude;
                EKFPosition = location;
                // Update localXY
                localXY = latLngToXY(location.latitude, location.longitude, baseLat, baseLon);
                System.out.println("WiFi positioning update successful: " + location.latitude + ", " + location.longitude);
            }

            @Override
            public void onError(String message) {
                System.err.println("WiFi positioning error: " + message);
            }
        });
    }

    /**
     * Sets the WiFiPositioning instance used to initiate WiFi positioning requests.
     * @param wiFiPositioning WiFi positioning instance
     */
    public void setWiFiPositioning(WiFiPositioning wiFiPositioning) {
        this.wiFiPositioning = wiFiPositioning;
    }

    /**
     * Gets the current internal state vector (latitude/longitude).
     * @return double array in format [latitude, longitude]
     */
    public double[] getState() {
        return state;
    }

    /**
     * Sets a new internal state vector (lat, lon), and updates EKFPosition and localXY
     * @param newState New state vector in format [latitude, longitude]
     */
    public void setState(double[] newState) {
        state = newState;
        EKFPosition = new LatLng(newState[0], newState[1]);
        // Also update localXY
        localXY = latLngToXY(newState[0], newState[1], baseLat, baseLon);
    }

    /**
     * Gets the current state covariance matrix (2x2).
     * @return 2x2 matrix
     */
    public double[][] getCovariance() {
        return covariance;
    }

    /**
     * Sets a new state covariance matrix.
     * @param newCovariance 2x2 matrix
     */
    public void setCovariance(double[][] newCovariance) {
        covariance = newCovariance;
    }

    /**
     * Gets the current EKF estimated position (LatLng object).
     * @return Current EKF estimated position
     */
    public LatLng getEstimatedPosition() {
        return EKFPosition;
    }


    /**
     * Converts (lat, lon) to approximate plane coordinates (x, y) relative to (baseLat, baseLon), in meters.
     *
     * @param lat      Current latitude
     * @param lon      Current longitude
     * @param baseLat  Reference point latitude
     * @param baseLon  Reference point longitude
     * @return         float[] of size 2, (x, y).
     */
    private float[] latLngToXY(double lat, double lon, double baseLat, double baseLon) {
        double avgLatRad = Math.toRadians((baseLat + lat) / 2.0);
        double cosVal = Math.cos(avgLatRad);

        double deltaLon = lon - baseLon;   // degrees
        double deltaLat = lat - baseLat;   // degrees

        // 1 degree of latitude is approximately 111320 meters
        // Longitude needs to be multiplied by cos(latitude)
        float x = (float) (deltaLon * 111320.0 * cosVal);
        float y = (float) (deltaLat * 111320.0);

        return new float[]{ x, y };
    }

    public float[] getEKFPositionAsXY() {
        return latLngToXY(EKFPosition.latitude, EKFPosition.longitude, baseLat, baseLon);
    }
}




