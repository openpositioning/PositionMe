package com.openpositioning.PositionMe.sensors;

import android.util.Log;

import org.ejml.simple.SimpleMatrix;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.ProjCoordinate;
import com.google.android.gms.maps.model.LatLng;

/**
 * Class for Extended Kalman Filter
 * Uses PDR, WIFI and GNSS data to compute the fused position estimate
 * Is instantiated in SensorFusion class
 * @author Stone Anderson
 * @author Joseph Azrak
 */
public class EKFSensorFusion {

    private SimpleMatrix x;
    private SimpleMatrix P;
    private SimpleMatrix Q;
    private SimpleMatrix R_gnss;
    private SimpleMatrix R_wifi;
    private SimpleMatrix R_wifi_adjusted;
    private SimpleMatrix R_gnss_adjusted;
    private SimpleMatrix pdrPrev;

    /**
     * Constructor to initialize the EKF with the given parameters
     * @param initialState 2x1 initial state vector [x; y]
     * @param initialCovariance 2x2 initial covariance matrix
     * @param Q Process noise covariance (2x2)
     * @param R_gnss  noise covariance for GNSS (2x2)
     * @param R_wifi  noise covariance for WiFi (2x2)
     * @author Stone Anderson
     */
    public EKFSensorFusion(SimpleMatrix initialState, SimpleMatrix initialCovariance,
                           SimpleMatrix Q,
                           SimpleMatrix R_gnss, SimpleMatrix R_wifi) {
        this.x = initialState;
        this.P = initialCovariance;
        this.Q = Q;
        this.R_gnss = R_gnss;
        this.R_wifi = R_wifi;
        this.pdrPrev = null;
    }

    /**
     * EKF Prediction step
     * Computes the PDR displacement, and if movement occurs, updates state and covariance values
     * If no movement occurs, only updates covariance values
     *
     * @param currentPdr - current PDR coordinate (2x1 SimpleMatrix)
     * @author Stone Anderson
     */
    public void predictWithPDR(SimpleMatrix currentPdr) {
        if (pdrPrev == null) {
            pdrPrev = currentPdr.copy();
        } else {
            // Compute displacement
            SimpleMatrix u = currentPdr.minus(pdrPrev);

            // Check if displacement occurs
            double threshold = 0.01;
            if (u.normF() < threshold) {
                // No movement detected -  skip state update
                P = P.plus(Q);
                return;
            }

            // update state and covariance
            x = x.plus(u);
            P = P.plus(Q);
            pdrPrev = currentPdr.copy();
        }
    }



    /**
     * Batch update - Fuses GNSS and WiFi sensor measurements into the state and covariance update.
     * @param z_gnss (2x1) GNSS coordinate vector.
     * @param z_wifi (2x1) WiFi coordinate vector
     * @author Stone Anderson
     */
    public void updateBatch(SimpleMatrix z_gnss, SimpleMatrix z_wifi) {

        // Change R_gnss based on how similar gnss is to current position
        SimpleMatrix innovation_gnss = z_gnss.minus(x);
        SimpleMatrix S_gnss = P.plus(R_gnss);
        double mahalanobis_gnss = innovation_gnss.transpose().mult(S_gnss.invert()).mult(innovation_gnss).get(0, 0);

        double thresholdHigh = 12.0;    //  unreliable measurements
        double thresholdMedium = 9.21;  // moderately off
        double thresholdLow = 5.0;      // Slightly off


        if (mahalanobis_gnss > thresholdHigh) {
            R_gnss_adjusted = R_gnss.scale(10);
        } else if (mahalanobis_gnss > thresholdMedium) {
            R_gnss_adjusted = R_gnss.scale(5);
        } else if (mahalanobis_gnss > thresholdLow) {
            R_gnss_adjusted = R_gnss.scale(2);
        } else {
            R_gnss_adjusted = R_gnss.copy();
        }


        // Change R_wifi based on how similar wifi is to current position
        SimpleMatrix innovation_wifi = z_wifi.minus(x);
        SimpleMatrix S_wifi = P.plus(R_wifi);

        double mahalanobis_wifi = innovation_wifi.transpose().mult(S_wifi.invert()).mult(innovation_wifi).get(0, 0);
        if (mahalanobis_wifi > thresholdHigh) {
            R_wifi_adjusted = R_wifi.scale(10);
        } else if (mahalanobis_wifi > thresholdMedium) {
            R_wifi_adjusted = R_wifi.scale(5);
        } else if (mahalanobis_wifi > thresholdLow) {
            R_wifi_adjusted = R_wifi.scale(2);
        } else {
            R_wifi_adjusted = R_wifi.copy();
        }


        // Stack wifi and gnss measurements into 4x1 vector
        SimpleMatrix z = new SimpleMatrix(4, 1);
        z.insertIntoThis(0, 0, z_gnss);
        z.insertIntoThis(2, 0, z_wifi);

        // matrix H (4x2)
        double[][] hData = {
                {1, 0},
                {0, 1},
                {1, 0},
                {0, 1}
        };
        SimpleMatrix H = new SimpleMatrix(hData);

        // noise covariance R (4x4) - includes R_gnss and R_wifi.
        SimpleMatrix R = SimpleMatrix.identity(4);
        R.insertIntoThis(0, 0, R_gnss_adjusted);
        R.insertIntoThis(2, 2, R_wifi_adjusted);

        // Innovation  y = z - H*x
        SimpleMatrix y = z.minus(H.mult(x));

        // Innovation covariance S = H * P * H^T + R
        SimpleMatrix S = H.mult(P).mult(H.transpose()).plus(R);

        // Kalman gain K = P * H^T * S^-1
        SimpleMatrix K = P.mult(H.transpose()).mult(S.invert());

        // Update state x = x + K*y
        x = x.plus(K.mult(y));

        // Update covariance P = (I - K*H) * P
        SimpleMatrix I = SimpleMatrix.identity(x.numRows());
        P = (I.minus(K.mult(H))).mult(P);
    }

    /**
     * Returns the current state estimate
     * @return (2x1) state vector [x; y]
     * @author Stone Anderson
     */
    public SimpleMatrix getState() {
        return x;
    }
    
    /**
     * Returns the current position accuracy estimate based on the covariance matrix.
     * The accuracy is calculated as the square root of the sum of the diagonal elements
     * of the covariance matrix, which represents the standard deviation of the position estimate.
     *
     * @return position accuracy in meters
     * @author Joseph Azrak
     */
    public double getPositionAccuracy() {
        if (P == null) return Double.NaN;
        
        // Extract diagonal elements (variances)
        double varX = P.get(0, 0);
        double varY = P.get(1, 1);
        
        // Calculate DRMS (Distance Root Mean Square) accuracy - DRMS is a common accuracy metric for 2D position
        return Math.sqrt(varX + varY);
    }

    /**
     * Transforms corodinates from LatLng WGS84 to x,y UTM.
     *
     * @param coord_location LatLng coordinate in WGS84.
     * @return A float array x,y coordinate [easting, northing].
     * @author Stone Anderson
     */
    public static float[] getTransformedCoordinate(LatLng coord_location) {
        int zone = (int) Math.floor((coord_location.longitude + 180) / 6) + 1;
        String epsgCode;
        if (coord_location.latitude >= 0) {
            epsgCode = String.format("EPSG:326%02d", zone);
        } else {
            epsgCode = String.format("EPSG:327%02d", zone);
        }

        CRSFactory factory = new CRSFactory();
        CoordinateReferenceSystem srcCrs = factory.createFromName("EPSG:4326"); // WGS84
        CoordinateReferenceSystem dstCrs = factory.createFromName(epsgCode);

        BasicCoordinateTransform transform = new BasicCoordinateTransform(srcCrs, dstCrs);

        ProjCoordinate srcCoord = new ProjCoordinate(coord_location.longitude, coord_location.latitude);
        ProjCoordinate dstCoord = new ProjCoordinate();

        transform.transform(srcCoord, dstCoord);

        return new float[] { (float) dstCoord.x, (float) dstCoord.y };
    }

    /**
     * Inverse transforms x,y UTM coordinates back to LatLng (WGS84) coordinate.
     *
     * @param easting The UTM easting.
     * @param northing The UTM northing.
     * @param zone The UTM zone.
     * @param isNorthern True if the coordinate is in the northern hemisphere.
     * @return A LatLng coordinate (latitude, longitude).
     * @author Stone Anderson
     */
    public static LatLng getInverseTransformedCoordinate(float easting, float northing, int zone, boolean isNorthern) {
        String epsgCode;
        if (isNorthern) {
            epsgCode = String.format("EPSG:326%02d", zone);
        } else {
            epsgCode = String.format("EPSG:327%02d", zone);
        }

        CRSFactory factory = new CRSFactory();
        CoordinateReferenceSystem srcCrs = factory.createFromName(epsgCode);      // UTM CRS
        CoordinateReferenceSystem dstCrs = factory.createFromName("EPSG:4326");     // WGS84

        BasicCoordinateTransform transform = new BasicCoordinateTransform(srcCrs, dstCrs);

        ProjCoordinate srcCoord = new ProjCoordinate(easting, northing);
        ProjCoordinate dstCoord = new ProjCoordinate();

        transform.transform(srcCoord, dstCoord);
        return new LatLng(dstCoord.y, dstCoord.x);
    }
}











