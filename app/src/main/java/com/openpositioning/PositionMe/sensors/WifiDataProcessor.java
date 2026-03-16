package com.openpositioning.PositionMe.sensors;

import android.util.Log;
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
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.annotation.SuppressLint;
import androidx.annotation.RequiresApi;
import android.os.Build;
import java.util.concurrent.Executor;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
/**
 * The WifiDataProcessor class is the Wi-Fi data gathering and processing class of the application.
 * It implements the wifi scanning and broadcasting design to identify a list of nearby Wi-Fis as
 * well as collecting information about the current Wi-Fi connection.
 * <p>
 * The class implements {@link Observable} for informing {@link Observer} classes of updated
 * variables. As such, it implements the {@link WifiDataProcessor#notifyObservers(int idx)} function and
 * the {@link WifiDataProcessor#registerObserver(Observer o)} function to add new users which will
 * be notified of new changes.
 * <p>
 * The class ensures all required permissions are granted before enabling the Wi-Fi. The class will
 * periodically start a wifi scan as determined by {@link SensorFusion}. When a broadcast is
 * received it will collect a list of users and notify users. The
 * {@link WifiDataProcessor#getCurrentWifiData()} function will return information about the current
 * Wi-Fi when called by {@link SensorFusion}.
 *
 * @author Mate Stodulka
 * @author Virginia Cangelosi
 */
public class WifiDataProcessor implements Observable {

    //Time over which a new scan will be initiated
    private static final long scanInterval = 15000;

    // Application context for handling permissions and WifiManager instances
    private final Context context;
    // Locations manager to enable access to Wifi data via the android system
    private final WifiManager wifiManager;

    // WiFi RTT manager for distance measurement (API 28+)
    private final WifiRttManager wifiRttManager;

    // Listener to deliver RTT results back to SensorFusion
    private RttResultListener rttResultListener;

    // Interface for RTT result callbacks
    public interface RttResultListener {
    void onRttResults(List<RangingResult> results, List<ScanResult> associatedScans);
    }


    //List of nearby networks
    private Wifi[] wifiData;

    //List of observers to be notified when changes are detected
    private ArrayList<Observer> observers;

    // Timer object
    private Timer scanWifiDataTimer;

    // Raw BSSIDs from the most recent scan (colon-separated MAC strings)
    private final List<String> lastObservedBssids = new ArrayList<>();


    /**
     * Public default constructor of the WifiDataProcessor class.
     * The constructor saves the context, checks for permissions to use the location services,
     * creates an instance of the shared preferences to access settings using the context,
     * initialises the wifi manager, and creates a timer object and list of observers. It checks if
     * wifi is enabled and enables wifi scans every 5seconds. It also informs the user to disable
     * wifi throttling if the device implements it.
     *
     * @param context           Application Context to be used for permissions and device accesses.
     *
     * @see SensorFusion the intended parent class.
     *
     * @author Virginia Cangelosi
     * @author Mate Stodulka
     */
    public WifiDataProcessor(Context context) {

        Log.d("WifiDataProcessor",
                "constructed instance=" + System.identityHashCode(this));

        this.context = context.getApplicationContext();
        this.wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        this.wifiRttManager = (WifiRttManager) this.context.getSystemService(Context.WIFI_RTT_RANGING_SERVICE);
        this.scanWifiDataTimer = new Timer();
        this.observers = new ArrayList<>();
        this.wifiData = new Wifi[0];
    }

    /**
    * Set the listener that will receive WiFi RTT ranging results.
    * Called by SensorFusion to receive RTT data for trajectory recording.
    *
    * @param listener  Callback to receive RTT results
    */
   @RequiresApi(api = Build.VERSION_CODES.P)
    public void setRttResultListener(RttResultListener listener) {
        this.rttResultListener = listener;
    }

    @SuppressLint("MissingPermission")
    public void startListening() {

        if (!checkWifiPermissions()) {
            Log.w("WifiDataProcessor", "Permissions not granted, not starting WiFi scan");
            return;
        }

        // Immediately process cached results
        List<ScanResult> initialResults = wifiManager.getScanResults();
        processScanResults(initialResults);

        scanWifiDataTimer.scheduleAtFixedRate(
                new scheduledWifiScan(),
                0,
                15000
        );
    }

    private void processScanResults(List<ScanResult> wifiScanList) {

        if (wifiScanList == null) return;

        lastObservedBssids.clear();

        for (ScanResult r : wifiScanList) {
            if (r.BSSID != null && !r.BSSID.isEmpty()) {
                lastObservedBssids.add(r.BSSID.toLowerCase());
            }
        }

        wifiData = new Wifi[wifiScanList.size()];

        for (int i = 0; i < wifiScanList.size(); i++) {
            wifiData[i] = new Wifi();
            String mac = wifiScanList.get(i).BSSID;
            long intMacAddress = convertBssidToLong(mac);
            wifiData[i].setBssid(intMacAddress);
            wifiData[i].setLevel(wifiScanList.get(i).level);
        }

        Log.d("WifiDataProcessor",
                "Processed scan results, BSSIDs=" + lastObservedBssids.size());

        // Attempt RTT ranging for APs that support it
        performRttRanging(wifiScanList);

        notifyObservers(0);
    }


    /**
     * Broadcast receiver to receive updates from the wifi manager.
     * Receives updates when a wifi scan is complete. Observers are notified when the broadcast is
     * received to update the list of wifis
     */
    BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        /**
         * Updates the list of nearby wifis when the broadcast is received.
         * Ensures wifi scans are not enabled if permissions are not granted. The list of wifis is
         * then passed to store the Mac Address and strength and observers of the WifiDataProcessor
         * class are notified of the updated wifi list.
         *
         *
         * @param context           Application Context to be used for permissions and device accesses.
         * @param intent            ???.
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d("WifiDataProcessor", "Scan complete, BSSIDs=" + lastObservedBssids.size());

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Unregister this listener
                stopListening();
                return;
            }

            //Collect the list of nearby wifis
            List<ScanResult> wifiScanList = wifiManager.getScanResults();
            //Stop receiver as scan is complete
            context.unregisterReceiver(this);
            // Collect raw BSSIDs for floorplan API
            lastObservedBssids.clear();
            for (ScanResult r : wifiScanList) {
                if (r.BSSID != null && !r.BSSID.isEmpty()) {
                    lastObservedBssids.add(r.BSSID.toLowerCase());
                }
            }


            //Loop though each item in wifi list
            wifiData = new Wifi[wifiScanList.size()];
            for(int i = 0; i < wifiScanList.size(); i++) {
                wifiData[i] = new Wifi();
                //Convert String mac address to an integer
                String wifiMacAddress = wifiScanList.get(i).BSSID;
                long intMacAddress = convertBssidToLong(wifiMacAddress);
                //store mac address and rssi of wifi
                wifiData[i].setBssid(intMacAddress);
                wifiData[i].setLevel(wifiScanList.get(i).level);
            }


            //Notify observers of change in wifiData variable
            notifyObservers(0);
        }
    };

    /**
     * Converts mac address from string to integer.
     * Removes semicolons from mac address and converts each hex byte to a hex integer.
     *
     *
     * @param wifiMacAddress        String Mac Address received from WifiManager containing colons
     *
     * @return                      Long variable with decimal conversion of the mac address
     */
    private long convertBssidToLong(String wifiMacAddress){
        long intMacAddress =0;
        int colonCount =5;
        //Loop through each character
        for(int j =0; j<17; j++){
            //Identify character
            char macByte = wifiMacAddress.charAt(j);
            //convert string hex mac address with colons to decimal long integer
            if(macByte != ':'){
                //For characters 0-9 subtract 48 from ASCII code and multiply by 16^position
                if((int) macByte >= 48 && (int) macByte <= 57){
                    intMacAddress = intMacAddress + (((int)macByte-48)*((long)Math.pow(16,16-j-colonCount)));
                }

                //For characters a-f subtract 87 (=97-10) from ASCII code and multiply by 16^index
                else if ((int) macByte >= 97 && (int) macByte <= 102){
                    intMacAddress = intMacAddress + (((int)macByte-87)*((long)Math.pow(16,16-j-colonCount)));
                }
            }
            else
                //coloncount is used to obtain the index of each character
                colonCount --;
        }

        return intMacAddress;
    }

    /**
     * Checks if the user authorised all permissions necessary for accessing wifi data.
     * Explicit user permissions must be granted for android sdk version 23 and above. This
     * function checks which permissions are granted, and returns their conjunction.
     *
     * @return  boolean true if all permissions are granted for wifi access, false otherwise.
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
     * Scan for nearby networks.
     * The method checks for permissions again, and then requests a scan of nearby wifis. A
     * broadcast receiver is registered to be called when the scan is complete.
     */
    private void startWifiScan() {
        //Check settings for wifi permissions
        if(checkWifiPermissions()) {
            //if(sharedPreferences.getBoolean("wifi", false)) {
            //Register broadcast receiver for wifi scans
            context.registerReceiver(wifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            wifiManager.startScan();

            //}
        }
    }

    /**
     * Initiate scans for nearby networks every 5 seconds.
     * The method declares a new timer instance to schedule a scan for nearby wifis every 5 seconds.
     */



    /**
     * Cancel wifi scans.
     * The method unregisters the broadcast receiver associated with the wifi scans and cancels the
     * timer so that new scans are not initiated.
     */
    public void stopListening() {
        context.unregisterReceiver(wifiScanReceiver);
        this.scanWifiDataTimer.cancel();
    }

    /**
     * Inform user if throttling is resent on their device.
     * If the device supports wifi throttling check if it is enabled and instruct the user to
     * disable it.
     */
    public void checkWifiThrottling(){
        if(checkWifiPermissions()) {
            //If the device does not support wifi throttling an exception is thrown
            try {
                if(Settings.Global.getInt(context.getContentResolver(), "wifi_scan_throttle_enabled")==1) {
                    //Inform user to disable wifi throttling
                    Toast.makeText(context, "Disable Wi-Fi Throttling", Toast.LENGTH_SHORT).show();
                }
            } catch (Settings.SettingNotFoundException e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * Checks which APs in the scan list support WiFi RTT (802.11mc),
     * then initiates a ranging request for those APs.
     * Results are delivered asynchronously via RttResultListener.
     * Requires Android API 28+ and CHANGE_WIFI_STATE permission.
     *
     * @param scanResults   The current list of scanned WiFi access points
     */
    @RequiresApi(api = Build.VERSION_CODES.P)
    @SuppressLint("MissingPermission")
    private void performRttRanging(List<ScanResult> scanResults) {
        // RTT requires Android P (API 28) or higher
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        // Check device supports RTT
        if (wifiRttManager == null || !wifiRttManager.isAvailable()) {
            Log.d("WifiDataProcessor", "WiFi RTT not available on this device");
            return;
        }
        // Filter to APs that advertise 802.11mc support
        List<ScanResult> rttCapableAps = new ArrayList<>();
        for (ScanResult result : scanResults) {
            if (result.is80211mcResponder()) {
                rttCapableAps.add(result);
            }
        }
        if (rttCapableAps.isEmpty()) {
            Log.d("WifiDataProcessor", "No RTT-capable APs found in scan");
            return;
        }
        Log.d("WifiDataProcessor", "Starting RTT ranging for " + rttCapableAps.size() + " APs");
        // Build ranging request for all RTT-capable APs
        RangingRequest request = new RangingRequest.Builder()
                .addAccessPoints(rttCapableAps)
                .build();
        // Store reference for use inside callback
        final List<ScanResult> scansForCallback = rttCapableAps;
        // Execute ranging on main thread executor
        Executor executor = context.getMainExecutor();
        wifiRttManager.startRanging(request, executor, new RangingResultCallback() {
            @Override
            public void onRangingFailure(int code) {
                Log.w("WifiDataProcessor", "RTT ranging failed, code=" + code);
            }
            @Override
            public void onRangingResults(List<RangingResult> results) {
                Log.d("WifiDataProcessor", "RTT ranging results received: " + results.size());
                // Deliver results to SensorFusion via listener
                if (rttResultListener != null) {
                    rttResultListener.onRttResults(results, scansForCallback);
                }
            }
        });
    }


    /**
     * Implement default method from Observable Interface to add new observers to the class.
     *
     * @param o     Classes which implement the Observer interface to receive updates from the class.
     */
    @Override
    public void registerObserver(Observer o) {
        observers.add(o);
    }

    /**
     * Implement default method from Observable Interface to add notify observers to the class.
     * Changes to the wifiData variable are passed to observers of the class.
     * @param idx     Unused.
     */
    @Override
    public void notifyObservers(int idx) {
        for(Observer o : observers) {
            o.update(wifiData);
        }
    }

    /**
     * Class to schedule wifi scans.
     *
     * Implements default method in {@link TimerTask} class which it implements. It begins to start
     * calling wifi scans every 5 seconds.
     */
    private class scheduledWifiScan extends TimerTask {
        @SuppressLint("MissingPermission")
        @Override
        public void run() {

            if (!checkWifiPermissions()) return;

            // Always read cached results first
            List<ScanResult> cached = wifiManager.getScanResults();
            processScanResults(cached);

            // Best-effort request a new scan (maybe throttled)
            boolean ok = wifiManager.startScan();
            Log.d("WifiDataProcessor", "startScan() returned=" + ok);
        }
    }


    /**
     * Obtains required information about wifi in which the device is currently connected.
     *
     * A connectivity manager is used to obtain information about the current network. If the device
     * is connected to a network its ssid, mac address and frequency is stored to a Wifi object so
     * that it can be accessed by the caller of the method
     *
     * @return wifi object containing the currently connected wifi's ssid, mac address and frequency
     */
    public Wifi getCurrentWifiData(){
        //Set up a connectivity manager to get information about the wifi
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService
                (Context.CONNECTIVITY_SERVICE);
        //Set up a network info object to store information about the current network
        NetworkInfo networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        //Only obtain wifi data if the device is connected
        //Wifi in which the device is currently connected to
        Wifi currentWifi = new Wifi();
        if(networkInfo.isConnected()) {
            //Store the ssid, mac address and frequency of the current wifi
            currentWifi.setSsid(wifiManager.getConnectionInfo().getSSID());
            String wifiMacAddress = wifiManager.getConnectionInfo().getBSSID();
            long intMacAddress = convertBssidToLong(wifiMacAddress);
            currentWifi.setBssid(intMacAddress);
            currentWifi.setFrequency(wifiManager.getConnectionInfo().getFrequency());
        }
        else{
            //Store standard information if not connected
            currentWifi.setSsid("Not connected");
            currentWifi.setBssid(0);
            currentWifi.setFrequency(0);
        }
        return currentWifi;
    }



    public List<String> getLastObservedBssids() {
        Log.d("WifiDataProcessor", "getLastObservedBssids() size=" + lastObservedBssids.size()
                + " instance=" + System.identityHashCode(this));
        return new ArrayList<>(lastObservedBssids);
    }



}
