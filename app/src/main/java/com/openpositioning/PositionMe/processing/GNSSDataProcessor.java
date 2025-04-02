package com.openpositioning.PositionMe.processing;

   import android.Manifest;
   import android.annotation.SuppressLint;
   import android.content.Context;
   import android.content.pm.PackageManager;
   import android.location.Location;
   import android.location.LocationListener;
   import android.location.LocationManager;
   import android.widget.Toast;

   import androidx.annotation.NonNull;
   import androidx.core.app.ActivityCompat;
   import com.openpositioning.PositionMe.sensors.SensorData.GNSSLocationData;
   import com.openpositioning.PositionMe.sensors.SensorHub;
   import com.openpositioning.PositionMe.sensors.SensorModule;
   import com.openpositioning.PositionMe.sensors.StreamSensor;

   /**
    * Class for handling and recording location data.
    * <p>
    * The class is responsible for handling location data from GNSS and cellular sources using the
    * Android LocationManager class.
    * </p>
    *
    * @param <GNSSLocationData> The type of sensor data this module processes.
    *
    * @see SensorModule to understand the parent class.
    *
    * @author Virginia Cangelosi
    * @author Mate Stodulka
    *
    * @Updated by Philip Heptonstall to extend SensorModule to generify, and to handle WiFi callbacks directly.
    */
   public class GNSSDataProcessor extends SensorModule<GNSSLocationData> implements LocationListener {

     // Application context for handling permissions and locationManager instances
     private final Context context;
     // Locations manager to enable access to GNSS and cellular location data via the android system
     private LocationManager locationManager;

     /**
      * Public default constructor of the GNSSDataProcessor class.
      * <p>
      * The constructor saves the context, checks for permissions to use the location services, creates
      * an instance of the shared preferences to access settings using the context, initializes the
      * location manager, and the location listener that will receive the data in the class that called
      * the constructor. It checks if GPS and cellular networks are available and notifies the user via
      * toasts if they need to be turned on. If permissions are granted it starts the location
      * information gathering process.
      * </p>
      *
      * @param context Application Context to be used for permissions and device accesses.
      * @param sensorHub The sensor hub to be used for managing sensors.
      */
     public GNSSDataProcessor(Context context, SensorHub sensorHub) {
       super(sensorHub, StreamSensor.GNSS);

       this.context = context;

       // Check for permissions
       boolean permissionsGranted = checkLocationPermissions();

       // Location manager and listener
       this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

       // Turn on GPS if it is currently disabled
       if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
         Toast.makeText(context, "Open GPS", Toast.LENGTH_SHORT).show();
       }
       if (!locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
         Toast.makeText(context, "Enable Cellular", Toast.LENGTH_SHORT).show();
       }
       // Start location updates
       if (permissionsGranted) {
         start();
       }
     }

     /**
      * Request location updates via the GNSS and Cellular networks.
      * <p>
      * The function checks for permissions again, and then requests updates via the location manager
      * to the location listener. If permissions are granted but the GPS and cellular networks are
      * disabled it reminds the user via toasts to turn them on.
      * </p>
      */
     @Override
     @SuppressLint("MissingPermission")
     public void start() {
       boolean permissionGranted = checkLocationPermissions();
       if (permissionGranted
           && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
           && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
         locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
             0, 0, this);
         locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
             0, 0, this);
       } else if (permissionGranted
           && !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
         Toast.makeText(context, "Open GPS", Toast.LENGTH_LONG).show();
       } else if (permissionGranted
           && !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
         Toast.makeText(context, "Turn on WiFi", Toast.LENGTH_LONG).show();
       }
     }

     /**
      * Stops updates to the location listener via the location manager.
      */
     @Override
     public void stop() {
       locationManager.removeUpdates(this);
     }

     /**
      * Checks if the user authorized all permissions necessary for accessing location data.
      * <p>
      * Explicit user permissions must be granted for android sdk version 23 and above. This function
      * checks which permissions are granted, and returns their conjunction.
      * </p>
      *
      * @return boolean true if all permissions are granted for location access, false otherwise.
      */
     private boolean checkLocationPermissions() {
       int coarseLocationPermission = ActivityCompat.checkSelfPermission(this.context,
           Manifest.permission.ACCESS_COARSE_LOCATION);
       int fineLocationPermission = ActivityCompat.checkSelfPermission(this.context,
           Manifest.permission.ACCESS_FINE_LOCATION);
       int internetPermission = ActivityCompat.checkSelfPermission(this.context,
           Manifest.permission.INTERNET);

       // Return missing permissions
       return coarseLocationPermission == PackageManager.PERMISSION_GRANTED
           && fineLocationPermission == PackageManager.PERMISSION_GRANTED
           && internetPermission == PackageManager.PERMISSION_GRANTED;
     }

     /**
      * Called when the location has changed.
      * <p>
      * This method is called when the location listener receives new location data.
      * </p>
      *
      * @param location The new location data.
      */
     @Override
     public void onLocationChanged(@NonNull Location location) {
       super.notifyListeners(new GNSSLocationData(location));
     }
   }