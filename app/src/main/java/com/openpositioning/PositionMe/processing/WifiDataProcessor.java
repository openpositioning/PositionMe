package com.openpositioning.PositionMe.processing;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.SensorData.WiFiData;
import com.openpositioning.PositionMe.sensors.SensorHub;
import com.openpositioning.PositionMe.sensors.SensorModule;
import com.openpositioning.PositionMe.sensors.StreamSensor;
import com.openpositioning.PositionMe.sensors.Wifi;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The WifiDataProcessor class is the Wi-Fi data gathering and processing class of the application.
 * It is initialised by SensorHub when any device requests Wi-Fi data.
 * It implements the wifi scanning and broadcasting design to identify a list of nearby Wi-Fis as
 * well as collecting information about the current Wi-Fi connection.
 * The class ensures all required permissions are granted before enabling the Wi-Fi. The class will
 * periodically start a wifi scan with the rate given by constants
 * SCAN_RATE_WITH or WITHOUT_THROTTLING.
 * When a broadcast is received it will collect a list of nearby Wi-Fi networks and their
 * associated signal strength (RSSI). This list is immediately passed to the WiFiPositioning API to
 * obtain a location estimate. Results are used to construct a WiFiData object which is passed
 * to SensorHub to coordinate updates.
 * The {@link WifiDataProcessor#getCurrentWifiData()} function will return information about the
 * current wifi connection.
 * Initial Authors:
 * @author Mate Stodulka
 * @author Virginia Cangelosi
 * Updated by:
 * @author Philip Heptonstall, to extend the SensorModule class and integrate with SensorHub
 */
public class WifiDataProcessor extends SensorModule<WiFiData> {
  private static final long SCAN_RATE_WITH_THROTTLING = 5000; // ms
  //
  private static final long SCAN_RATE_WITHOUT_THROTTLING = 500; // ms
  //Time over which a new scan will be initiated
  private static long scanInterval = SCAN_RATE_WITH_THROTTLING;

  // String for creating WiFi fingerprint JSON object
  private static final String WIFI_FINGERPRINT = "wf";

  // Application context for handling permissions and WifiManager instances
  private final Context context;
  // Locations manager to enable access to Wifi data via the android system
  private final WifiManager wifiManager;

  private WiFiPositioning wifiPositioning;

  private LatLng currentWifiLocation;

  private int currentWifiLevel;



  //List of nearby networks
  private Wifi[] wifiData;

  // Timer object
  private Timer scanWifiDataTimer;


  /**
   * Constructor for WifiDataProcessor. The constructor will check for permissions and start a
   * timer to scan for wifi data every SCAN_INTERVAL seconds.
   *
   * @param context Application Context to be used for permissions and device accesses.
   * @param sensorHub SensorHub instance to register the processor with.
   */
  public WifiDataProcessor(Context context, SensorHub sensorHub) {
    super(sensorHub, StreamSensor.WIFI);
    this.context = context;
    // Check for permissions
    this.wifiPositioning = new WiFiPositioning(context);
    boolean permissionsGranted = checkWifiPermissions();
    this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    this.scanWifiDataTimer = new Timer();

    //Inform the user if wifi throttling is enabled on their device
    checkWifiThrottling();

    // Start wifi scan and return results via broadcast
    if (permissionsGranted) {
      this.scanWifiDataTimer.schedule(new ScheduledWifiScan(), 0, scanInterval);
    }
  }

  /**
   * Get the most recent wifi location from WifiPositioning API
   *
   * @return LatLng of current latitude and longitude (WGS84) null if there have been no successful
   * readings.
   */
  public LatLng getCurrentWifiLocation() {
    return currentWifiLocation;
  }

  /**
   * Get the most recent floor estimate from WifiPositioning API
   *
   * @return int of current floor level, -1 if there have been no successful readings.
   */
  public int getCurrentWifiLevel() {
    return currentWifiLevel;
  }

  /**
   * Obtains required information about wifi in which the device is currently connected.
   * <p>
   * A connectivity manager is used to obtain information about the current network. If the device
   * is connected to a network its ssid, mac address and frequency is stored to a Wifi object so
   * that it can be accessed by the caller of the method
   *
   * @return wifi object containing the currently connected wifi's ssid, mac address and frequency
   */
  public Wifi getCurrentWifiData() {
    //Set up a connectivity manager to get information about the wifi
    ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService
        (Context.CONNECTIVITY_SERVICE);
    //Set up a network info object to store information about the current network
    NetworkInfo networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

    //Only obtain wifi data if the device is connected
    //Wifi in which the device is currently connected to
    Wifi currentWifi = new Wifi();
    if (networkInfo.isConnected()) {
      //Store the ssid, mac address and frequency of the current wifi
      currentWifi.setSsid(wifiManager.getConnectionInfo().getSSID());
      String wifiMacAddress = wifiManager.getConnectionInfo().getBSSID();
      long intMacAddress = convertBssidToLong(wifiMacAddress);
      currentWifi.setBssid(intMacAddress);
      currentWifi.setFrequency(wifiManager.getConnectionInfo().getFrequency());
    } else {
      //Store standard information if not connected
      currentWifi.setSsid("Not connected");
      currentWifi.setBssid(0);
      currentWifi.setFrequency(0);
    }
    return currentWifi;
  }

  public Wifi[] getWifiData() {
    return wifiData;
  }

  /**
   * Initiate scans for nearby networks every SCAN_INTERVAL seconds. The method declares a new timer
   * instance to schedule a scan for nearby wifis every SCAN_INTERVAL seconds.
   */
  @Override
  public void start() {
    this.scanWifiDataTimer = new Timer();
    this.scanWifiDataTimer.schedule(new ScheduledWifiScan(), 0, scanInterval);
  }

  /**
   * Cancel wifi scans. The method unregisters the broadcast receiver associated with the wifi scans
   * and cancels the timer so that new scans are not initiated.
   */
  @Override
  public void stop() {
    context.unregisterReceiver(wifiScanReceiver);
    this.scanWifiDataTimer.cancel();
  }

  /**
   * Broadcast receiver to receive updates from the wifi manager. Receives updates when a wifi scan
   * is complete. Observers are notified when the broadcast is received to update the list of wifis
   */
  BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
    /**
     * Updates the list of nearby wifis when the broadcast is received.
     * Ensures wifi scans are not enabled if permissions are not granted. The list of wifis is
     * then passed to store the Mac Address and strength and observers of the WifiDataProcessor
     * class are notified of the updated wifi list.
     *
     * @param context           Application Context to be used for permissions and device accesses.
     * @param intent
     */
    @Override
    public void onReceive(Context context, Intent intent) {
      if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
          != PackageManager.PERMISSION_GRANTED) {
        // Unregister this listener
        stop();
        return;
      }
      //Collect the list of nearby wifis
      List<ScanResult> wifiScanList = wifiManager.getScanResults();
      //Stop receiver as scan is complete
      context.unregisterReceiver(this);

      //Loop though each item in wifi list
      wifiData = new Wifi[wifiScanList.size()];
      for (int i = 0; i < wifiScanList.size(); i++) {
        wifiData[i] = new Wifi();
        //Convert String mac address to an integer
        String wifiMacAddress = wifiScanList.get(i).BSSID;
        long intMacAddress = convertBssidToLong(wifiMacAddress);
        //store mac address and rssi of wifi
        wifiData[i].setBssid(intMacAddress);
        wifiData[i].setLevel(wifiScanList.get(i).level);
      }

      //Notify observers of change in wifiData variable
      List<Wifi> newData = Stream.of(wifiData).collect(Collectors.toList());
      createWifiPositionRequestCallback(newData);
    }
  };

  /**
   * Function to create a request to obtain a wifi location for the obtained wifi fingerprint Handle
   * response with a volley callback
   */
  private void createWifiPositionRequestCallback(List<Wifi> wifiScanResult) {
    try {
      // Creating a JSON object to store the WiFi access points
      JSONObject wifiAccessPoints = new JSONObject();
      for (Wifi data : wifiScanResult) {
        wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
      }
      // Creating POST Request
      JSONObject wifiFingerPrint = new JSONObject();
      wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
      // Making a request to the WiFiPositioning API
      this.wifiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
        /**
         * Callback method to handle a successful response from the WiFiPositioning API
         *
         * @param wifiLocation The location obtained from the API
         * @param floor       The floor level obtained from the API
         */
        @Override
        public void onSuccess(LatLng wifiLocation, int floor) {
          // Handle the success response
          if (wifiLocation != currentWifiLocation) {
            currentWifiLocation = wifiLocation;
            currentWifiLevel = floor;
            // Notify listeners of a new wifi location, level received.
            WifiDataProcessor.super.notifyListeners(new WiFiData(wifiScanResult, currentWifiLocation,
                currentWifiLevel));
          }
        }
        /**
         * Callback method to handle an error response from the WiFiPositioning API
         *
         * @param message The error message received from the API
         */
        @Override
        public void onError(String message) {
          // Handle the error response
          Log.e("SensorFusion.WifiPositioning", "Wifi Positioning request" +
              "returned an error! " + message);

          // In an error, LatLng is set to null and floor level is set to -1.
          WifiDataProcessor.super.notifyListeners(new WiFiData(wifiScanResult, null,
              -1));
        }
      });
    } catch (JSONException e) {
      // Catching error while making JSON object, to prevent crashes
      // Error log to keep record of errors (for secure programming and maintainability)
      Log.e("jsonErrors", "Error creating json object" + e.toString());
    }

  }

  /**
   * Converts mac address from string to integer. Removes semicolons from mac address and converts
   * each hex byte to a hex integer.
   *
   * @param wifiMacAddress String Mac Address received from WifiManager containing colons
   * @return Long variable with decimal conversion of the mac address
   */
  private long convertBssidToLong(String wifiMacAddress) {
    long intMacAddress = 0;
    int colonCount = 5;
    //Loop through each character
    for (int j = 0; j < 17; j++) {
      //Identify character
      char macByte = wifiMacAddress.charAt(j);
      //convert string hex mac address with colons to decimal long integer
      if (macByte != ':') {
        //For characters 0-9 subtract 48 from ASCII code and multiply by 16^position
        if ((int) macByte >= 48 && (int) macByte <= 57) {
          intMacAddress =
              intMacAddress + (((int) macByte - 48) * ((long) Math.pow(16,
                  16 - j - colonCount)));
        } else if ((int) macByte >= 97 && (int) macByte <= 102) {
          //For characters a-f subtract 87 (=97-10) from ASCII code and multiply by 16^index
          intMacAddress = intMacAddress
              + (((int) macByte - 87) * ((long) Math.pow(16, 16 - j - colonCount)));
        }
      } else {
        //coloncount is used to obtain the index of each character
        colonCount--;
      }
    }
    return intMacAddress;
  }

  /**
   * Checks if the user authorised all permissions necessary for accessing wifi data. Explicit user
   * permissions must be granted for android sdk version 23 and above. This function checks which
   * permissions are granted, and returns their conjunction.
   *
   * @return boolean true if all permissions are granted for wifi access, false otherwise.
   */
  private boolean checkWifiPermissions() {
    int wifiAccessPermission = ActivityCompat.checkSelfPermission(this.context,
        Manifest.permission.ACCESS_WIFI_STATE);
    int wifiChangePermission = ActivityCompat.checkSelfPermission(this.context,
        Manifest.permission.CHANGE_WIFI_STATE);
    int coarseLocationPermission = ActivityCompat.checkSelfPermission(this.context,
        Manifest.permission.ACCESS_COARSE_LOCATION);
    int fineLocationPermission = ActivityCompat.checkSelfPermission(this.context,
        Manifest.permission.ACCESS_FINE_LOCATION);

    // Return missing permissions
    return wifiAccessPermission == PackageManager.PERMISSION_GRANTED &&
        wifiChangePermission == PackageManager.PERMISSION_GRANTED &&
        coarseLocationPermission == PackageManager.PERMISSION_GRANTED &&
        fineLocationPermission == PackageManager.PERMISSION_GRANTED;
  }

  /**
   * Scan for nearby networks. The method checks for permissions again, and then requests a scan of
   * nearby wifis. A broadcast receiver is registered to be called when the scan is complete.
   */
  private void startWifiScan() {
    //Check settings for wifi permissions
    if (checkWifiPermissions()) {
      //if(sharedPreferences.getBoolean("wifi", false)) {
      //Register broadcast receiver for wifi scans
      context.registerReceiver(wifiScanReceiver,
          new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
      wifiManager.startScan();
      //}
    }
  }

  /**
   * Inform user if throttling is resent on their device. If the device supports wifi throttling
   * check if it is enabled and instruct the user to disable it.
   */
  public void checkWifiThrottling() {
    if (checkWifiPermissions()) {
      // If the device does not support wifi throttling an exception is thrown
      if (this.wifiManager.isScanThrottleEnabled()) {
        //Inform user to disable wifi throttling
        Toast.makeText(context, "Disable Wi-Fi Throttling", Toast.LENGTH_SHORT).show();
      } else {
        scanInterval = SCAN_RATE_WITHOUT_THROTTLING;
      }
    }
  }

  /**
   * Class to schedule wifi scans.
   * <p>
   * Implements default method in {@link TimerTask} class which it implements. It begins to start
   * calling wifi scans every SCAN_RATE_WITH/OUT_THROTTLING seconds.
   */
  private class ScheduledWifiScan extends TimerTask {

    @Override
    public void run() {
      startWifiScan();
    }
  }


}
