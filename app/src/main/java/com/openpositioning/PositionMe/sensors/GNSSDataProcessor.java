package com.openpositioning.PositionMe.sensors;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

/**
 * Class for handling and recording location data.
 *
 * The class is responsibly for handling location data from GNSS and cellular sources using the
 * Android LocationManager class.
 *
 * @author Virginia Cangelosi
 * @author Mate Stodulka
 */
public class GNSSDataProcessor {
    // Application context for handling permissions and locationManager instances
    private final Context context;
    // Locations manager to enable access to GNSS and cellular location data via the android system
    private LocationManager locationManager;
    // Location listener to receive the location data broadcast by the system
    private LocationListener locationListener;


    /**
     * Public default constructor of the GNSSDataProcessor class.
     *
     * The constructor saves the context, checks for permissions to use the location services,
     * creates an instance of the shared preferences to access settings using the context,
     * initialises the location manager, and the location listener that will receive the data in the
     * class the called the constructor. It checks if GPS and cellular networks are available and
     * notifies the user via toasts if they need to be turned on. If permissions are granted it
     * starts the location information gathering process.
     *
     * @param context           Application Context to be used for permissions and device accesses.
     * @param locationListener  Location listener that will receive the location information from
     *                          the device broadcasts.
     *
     * @see SensorFusion the intended parent class.
     */
    public GNSSDataProcessor(Context context, LocationListener locationListener) {
        this.context = context;

        // Check for permissions
        boolean permissionsGranted = checkLocationPermissions();

        //Location manager and listener
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.locationListener = locationListener;

        // Turn on gps if it is currently disabled
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(context, "Open GPS", Toast.LENGTH_SHORT).show();
        }
        if (!locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            Toast.makeText(context, "Enable Cellular", Toast.LENGTH_SHORT).show();
        }
        // Start location updates
        if (permissionsGranted) {
            startLocationUpdates();
        }
    }

    /**
     * Checks if the user authorised all permissions necessary for accessing location data.
     *
     * Explicit user permissions must be granted for android sdk version 23 and above. This
     * function checks which permissions are granted, and returns their conjunction.
     *
     * @return  boolean true if all permissions are granted for location access, false otherwise.
     */
    private boolean checkLocationPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {

            int coarseLocationPermission = ActivityCompat.checkSelfPermission(this.context,
                    Manifest.permission.ACCESS_COARSE_LOCATION);
            int fineLocationPermission = ActivityCompat.checkSelfPermission(this.context,
                    Manifest.permission.ACCESS_FINE_LOCATION);
            int internetPermission = ActivityCompat.checkSelfPermission(this.context,
                    Manifest.permission.INTERNET);

            // Return missing permissions
            return coarseLocationPermission == PackageManager.PERMISSION_GRANTED &&
                    fineLocationPermission == PackageManager.PERMISSION_GRANTED &&
                    internetPermission == PackageManager.PERMISSION_GRANTED;
        } else {
            // Permissions are granted by default
            return true;
        }
    }

    /**
     * Request location updates via the GNSS and Cellular networks.
     *
     * The function checks for permissions again, and then requests updates via the location
     * manager to the location listener. If permissions are granted but the GPS and cellular
     * networks are disabled it reminds the user via toasts to turn them on.
     */
    @SuppressLint("MissingPermission")
    public void startLocationUpdates() {
        //if (sharedPreferences.getBoolean("location", true)) {
        boolean permissionGranted = checkLocationPermissions();
        if (permissionGranted && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){

            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListener);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, locationListener);
        }
        else if(permissionGranted && !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)){
            Toast.makeText(context, "Open GPS", Toast.LENGTH_LONG).show();
        }
        else if(permissionGranted && !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
            Toast.makeText(context, "Turn on WiFi", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Stops updates to the location listener via the location manager.
     */
    public void stopUpdating() {
        locationManager.removeUpdates(locationListener);
    }

}
