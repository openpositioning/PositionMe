package com.openpositioning.PositionMe.sensors;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * Class for handling and recording location data.
 *
 * Uses Google's FusedLocationProviderClient for maximum accuracy by combining
 * GPS, WiFi, cell towers, and device sensors automatically.
 * Falls back to raw LocationManager if FusedLocation is unavailable.
 *
 * @author Virginia Cangelosi
 * @author Mate Stodulka
 */
public class GNSSDataProcessor {
    private static final String TAG = "GNSSDataProcessor";

    private final Context context;
    private LocationManager locationManager;
    private LocationListener locationListener;

    // Google Fused Location (high accuracy)
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback fusedLocationCallback;
    private boolean usingFusedLocation = false;

    /**
     * Public default constructor of the GNSSDataProcessor class.
     *
     * Uses Google FusedLocationProviderClient as primary source for maximum accuracy.
     * Falls back to raw GPS + Network providers if fused location is unavailable.
     */
    public GNSSDataProcessor(Context context, LocationListener locationListener) {
        this.context = context;
        this.locationListener = locationListener;

        boolean permissionsGranted = checkLocationPermissions();

        // Initialize LocationManager as fallback
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

        // Initialize Google Fused Location Client (primary - highest accuracy)
        try {
            this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
            this.fusedLocationCallback = new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult locationResult) {
                    if (locationResult == null) return;
                    // Use the most recent location
                    Location location = locationResult.getLastLocation();
                    if (location != null) {
                        // Forward to existing LocationListener for compatibility with SensorFusion
                        locationListener.onLocationChanged(location);
                    }
                }
            };
            Log.d(TAG, "FusedLocationProviderClient initialized");
        } catch (Exception e) {
            Log.w(TAG, "FusedLocationProvider unavailable, will use raw GPS: " + e.getMessage());
            this.fusedLocationClient = null;
        }

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(context, "Open GPS", Toast.LENGTH_SHORT).show();
        }

        if (permissionsGranted) {
            startLocationUpdates();
        }
    }

    private boolean checkLocationPermissions() {
        int coarseLocationPermission = ActivityCompat.checkSelfPermission(this.context,
                Manifest.permission.ACCESS_COARSE_LOCATION);
        int fineLocationPermission = ActivityCompat.checkSelfPermission(this.context,
                Manifest.permission.ACCESS_FINE_LOCATION);
        int internetPermission = ActivityCompat.checkSelfPermission(this.context,
                Manifest.permission.INTERNET);

        return coarseLocationPermission == PackageManager.PERMISSION_GRANTED &&
                fineLocationPermission == PackageManager.PERMISSION_GRANTED &&
                internetPermission == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Start location updates using Google Fused Location (primary) + raw GPS (fallback).
     * FusedLocation combines GPS, WiFi, cell, and sensors for best accuracy.
     */
    @SuppressLint("MissingPermission")
    public void startLocationUpdates() {
        boolean permissionGranted = checkLocationPermissions();
        if (!permissionGranted) return;

        // PRIMARY: Google Fused Location Provider - highest accuracy
        if (fusedLocationClient != null) {
            try {
                LocationRequest locationRequest = new LocationRequest.Builder(
                        Priority.PRIORITY_HIGH_ACCURACY, 300)  // 300ms interval (balanced for smoothing)
                        .setMinUpdateIntervalMillis(200)        // fastest 200ms (avoid too frequent updates)
                        .setMinUpdateDistanceMeters(0.5f)       // small distance threshold
                        .setWaitForAccurateLocation(false)      // don't wait, send immediately
                        .build();

                fusedLocationClient.requestLocationUpdates(locationRequest,
                        fusedLocationCallback, Looper.getMainLooper());
                usingFusedLocation = true;
                Log.d(TAG, "Started FusedLocation updates (HIGH_ACCURACY, 300ms, smooth mode)");
            } catch (Exception e) {
                Log.w(TAG, "FusedLocation failed, falling back to raw GPS: " + e.getMessage());
                usingFusedLocation = false;
            }
        }

        // FALLBACK/SUPPLEMENT: Raw GPS provider (always start for redundancy)
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 500, 0, locationListener);
            Log.d(TAG, "Started raw GPS updates");
        } else {
            Toast.makeText(context, "Open GPS", Toast.LENGTH_LONG).show();
        }

        // SUPPLEMENT: Network provider for faster initial fix
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 1000, 0, locationListener);
            Log.d(TAG, "Started Network location updates");
        }
    }

    /**
     * Stops all location updates.
     */
    public void stopUpdating() {
        // Stop fused location
        if (fusedLocationClient != null && fusedLocationCallback != null) {
            fusedLocationClient.removeLocationUpdates(fusedLocationCallback);
            Log.d(TAG, "Stopped FusedLocation updates");
        }
        // Stop raw GPS/Network
        locationManager.removeUpdates(locationListener);
        Log.d(TAG, "Stopped raw GPS/Network updates");
    }
}
