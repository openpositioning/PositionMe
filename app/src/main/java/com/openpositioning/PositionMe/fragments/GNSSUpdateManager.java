package com.openpositioning.PositionMe.fragments;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

/**
 * Manages GNSS (Global Navigation Satellite System) location updates using Google's FusedLocationProviderClient.
 *
 *
 * @author apoorvtewari
 */
public class GNSSUpdateManager {
    private Context context; // Android context, used to access system services
    private FusedLocationProviderClient fusedLocationProviderClient; // Client for interacting with the fused location provider
    private LocationCallback locationCallback; // Callback to handle location update events
    private LocationRequest locationRequest; // Configuration object for requesting location updates

    /**
     * Constructor that initializes the location client and requests.
     *
     * @param context The application context used for getting the location service.
     */
    public GNSSUpdateManager(Context context) {
        this.context = context;
        this.fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context);
        createLocationRequest(); // Define the location request parameters
        createLocationCallback(); // Define what to do when locations are received
    }

    /**
     * Creates and configures a location request.
     */
    private void createLocationRequest() {
        locationRequest = LocationRequest.create();
        locationRequest.setInterval(10000); // Interval for active location updates (10 seconds)
        locationRequest.setFastestInterval(5000); // Fastest interval for location updates, allowing for faster updates than setInterval()
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY); // Request the most accurate locations available
    }

    /**
     * Sets up the location callback that is triggered upon receiving location updates.
     */
    private void createLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return; // If no results, exit the callback
                }
                for (Location location : locationResult.getLocations()) {
                    // Update UI or process location data here
                }
            }
        };
    }

    /**
     * Starts requesting location updates from the FusedLocationProvider.
     * Requires the ACCESS_FINE_LOCATION permission to be declared and granted.
     */
    @SuppressLint("MissingPermission")
    public void startLocationUpdates() {
        fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        // Location updates started successfully
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        // Handle the failure of starting location updates
                    }
                });
    }

    /**
     * Stops the ongoing location updates to preserve resources when not needed.
     */
    public void stopLocationUpdates() {
        fusedLocationProviderClient.removeLocationUpdates(locationCallback);
    }
}