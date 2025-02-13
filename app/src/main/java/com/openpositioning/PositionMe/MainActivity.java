package com.openpositioning.PositionMe;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.preference.PreferenceManager;

import java.util.ArrayDeque;
import java.util.Deque;

import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.SensorFusion;

/**
 * The Main Activity of the application, handling setup, permissions and starting all other fragments
 * and processes.
 * The Main Activity takes care of most essential tasks before the app can run. Such as setting up
 * the views, and enforcing light mode so the colour scheme is consistent. It initialises the
 * various fragments and the navigation between them, getting the Navigation controller. It also
 * loads the custom action bar with the set theme and icons, and enables back-navigation. The shared
 * preferences are also loaded.
 * <p>
 * The most important task of the main activity is check and asking for the necessary permissions to
 * enable the application to use the required hardware devices. This is done through a number of
 * functions that call the OS, as well as pop-up messages warning the user if permissions are denied.
 * <p>
 * Once all permissions are granted, the Main Activity obtains the Sensor Fusion instance and sets
 * the context, enabling the Fragments to interact with the class without setting it up again.
 *
 * @see com.openpositioning.PositionMe.fragments.HomeFragment the initial fragment displayed.
 * @see com.openpositioning.PositionMe.R.navigation the navigation graph.
 * @see SensorFusion the singletion data processing class.
 *
 * @author Mate Stodulka
 * @author Virginia Cangelosi
 */
public class MainActivity extends AppCompatActivity implements Observer {

    //region Static variables
    // Static IDs for permission responses.
    private static final int REQUEST_ID_WIFI_PERMISSION = 99;
    private static final int REQUEST_ID_LOCATION_PERMISSION = 98;
    private static final int REQUEST_ID_READ_WRITE_PERMISSION = 97;
    private static final int REQUEST_ID_ACTIVITY_PERMISSION = 96;
    //endregion

    //region Instance variables
    private NavController navController;

    private SharedPreferences settings;
    private SensorFusion sensorFusion;
    private Handler httpResponseHandler;

    private static final int REQUEST_ID_PERMISSIONS = 100;
    private static final String[] MANDATORY_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION
    };

    private static final String[] OPTIONAL_PERMISSIONS = {
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.INTERNET,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    private final Deque<String> pendingPermissions = new ArrayDeque<>();

    //endregion

    //region Activity Lifecycle

    /**
     * {@inheritDoc}
     * Forces light mode, sets up the navigation graph, initialises the toolbar with back action on
     * the nav controller, loads the shared preferences and checks for all permissions necessary.
     * Sets up a Handler for displaying messages from other classes.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        setContentView(R.layout.activity_main);

        // Set up navigation and fragments
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        navController = navHostFragment.getNavController();

        // Set action bar
        Toolbar toolbar = findViewById(R.id.main_toolbar);
        setSupportActionBar(toolbar);
        toolbar.showOverflowMenu();
        toolbar.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.primaryBlue));
        toolbar.setTitleTextColor(ContextCompat.getColor(getApplicationContext(), R.color.white));

        // Set up back action
        AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
        NavigationUI.setupWithNavController(toolbar, navController, appBarConfiguration);

        // Get handle for settings
        this.settings = PreferenceManager.getDefaultSharedPreferences(this);
        settings.edit().putBoolean("permanentDeny", false).apply();

        //Check Permissions
        checkPermissionsPhase();

        // Handler for global toasts and popups from other classes
        this.httpResponseHandler = new Handler();
    }

    private void checkPermissionsPhase() {
        pendingPermissions.clear();

        // check mandatory permissions
        for (String perm : MANDATORY_PERMISSIONS) {
            if (!hasPermission(perm)) pendingPermissions.add(perm);
        }

        // check optional permissions
        for (String perm : OPTIONAL_PERMISSIONS) {
            if (!hasPermission(perm)) pendingPermissions.add(perm);
        }

        if (!pendingPermissions.isEmpty()) {
            requestNextPermission();
        } else {
            allPermissionsObtained();
        }
    }

    private boolean hasPermission(String permission) {
        return ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNextPermission() {
        if (pendingPermissions.isEmpty()) {
            allPermissionsObtained();
            return;
        }

        String nextPermission = pendingPermissions.pollFirst();
        ActivityCompat.requestPermissions(this, new String[]{nextPermission}, REQUEST_ID_PERMISSIONS);
    }

    private boolean isMandatoryPermission(String permission) {
        for (String p : MANDATORY_PERMISSIONS) {
            if (p.equals(permission)) return true;
        }
        return false;
    }

    private void handleMandatoryDenied(String deniedPermission) {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, deniedPermission)) {
            showRationaleDialog(deniedPermission);
        } else {
            showPermanentDenyDialog();
        }
    }

    private void showRationaleDialog(String permission) {
        new AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("This feature requires the " + permission + " permission to work properly")
                .setPositiveButton("Retry", (d, w) -> requestNextPermission())
                .setNegativeButton("Exit", (d, w) -> finishAffinity())
                .show();
    }

    private void showPermanentDenyDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("You have permanently denied required permissions. Please enable them in settings.")
                .setPositiveButton("Settings", (d, w) -> openAppSettings())
                .setNegativeButton("Exit", (d, w) -> finishAffinity())
                .show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPause() {
        super.onPause();
        //Ensure sensorFusion has been initialised before unregistering listeners
        if(sensorFusion != null) {
            sensorFusion.stopListening();
        }
    }

    /**
     * {@inheritDoc}
     * Checks for activities in case the app was closed without granting them, or if they were
     * granted through the settings page. Repeats the startup checks done in
     * {@link MainActivity#onCreate(Bundle)}. Starts listening in the SensorFusion class.
     *
     * @see SensorFusion the main data processing class.
     */
    @Override
    public void onResume() {
        super.onResume();
        //Check if permissions are granted before resuming listeners
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission
                (this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission
                (this,Manifest.permission.ACCESS_WIFI_STATE)
                != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission
                (this,Manifest.permission.CHANGE_WIFI_STATE)
                != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission
                (this,Manifest.permission.INTERNET)
                != PackageManager.PERMISSION_GRANTED){
            askLocationPermissions();
        }
        //If permissions are granted resume listeners
        else {
            if(sensorFusion == null) {
                allPermissionsObtained();
            }
            else{
                sensorFusion.resumeListening();
            }
        }
    }

    /**
     * Unregisters sensor listeners when the app closes. Not in {@link MainActivity#onPause()} to
     * enable recording data with a locked screen.
     *
     * @see SensorFusion the main data processing class.
     */
    @Override
    protected void onDestroy() {
        if(sensorFusion != null) {
            sensorFusion.stopListening();
        }
        super.onDestroy();
    }

    //endregion

    //region Permissions

    /**
     * Checks for location permissions.
     * If location permissions are not present, request the permissions through the OS.
     * If permissions are present, check for the next set of required permissions with
     * {@link MainActivity#askWifiPermissions()}
     *
     * @see MainActivity#onRequestPermissionsResult(int, String[], int[]) handling request responses.
     */
    private void askLocationPermissions() {
        // Check for location permission
        int coarseLocationPermission = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION);
        int fineLocationPermission = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION);
        int internetPermission = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.INTERNET);

        // Request if not present
        if(coarseLocationPermission != PackageManager.PERMISSION_GRANTED ||
                fineLocationPermission != PackageManager.PERMISSION_GRANTED ||
                internetPermission != PackageManager.PERMISSION_GRANTED) {
            this.requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.INTERNET},
                    REQUEST_ID_LOCATION_PERMISSION
            );
        }
        else{
            // Check other permissions if present
            askWifiPermissions();
        }
    }

    /**
     * Checks for wifi permissions.
     * If wifi permissions are not present, request the permissions through the OS.
     * If permissions are present, check for the next set of required permissions with
     * {@link MainActivity#askStoragePermission()}
     *
     * @see MainActivity#onRequestPermissionsResult(int, String[], int[]) handling request responses.
     */
    private void askWifiPermissions() {
        // Check for wifi permissions
        int wifiAccessPermission = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_WIFI_STATE);
        int wifiChangePermission = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.CHANGE_WIFI_STATE);

        // Request if not present
        if(wifiAccessPermission != PackageManager.PERMISSION_GRANTED ||
                wifiChangePermission != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_WIFI_STATE,
                            Manifest.permission.CHANGE_WIFI_STATE},
                    REQUEST_ID_WIFI_PERMISSION
            );
        }
        else{
            // Check other permissions if present
            askStoragePermission();
        }
    }

    /**
     * Checks for storage permissions.
     * If storage permissions are not present, request the permissions through the OS.
     * If permissions are present, check for the next set of required permissions with
     * {@link MainActivity#askMotionPermissions()}
     *
     * @see MainActivity#onRequestPermissionsResult(int, String[], int[]) handling request responses.
     */
    private void askStoragePermission() {
        // Check for storage permission
        int writeStoragePermission = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE);
        int readStoragePermission = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.READ_EXTERNAL_STORAGE);
        // Request if not present
        if(writeStoragePermission != PackageManager.PERMISSION_GRANTED ||
                readStoragePermission != PackageManager.PERMISSION_GRANTED) {
            this.requestPermissions(
                    new String[]{
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_ID_READ_WRITE_PERMISSION
            );
        }
        else {
            // Check other permissions if present
            askMotionPermissions();
        }
    }

    /**
     * Checks for motion activity permissions.
     * If storage permissions are not present, request the permissions through the OS.
     * If permissions are present, all permissions have been granted, move on to
     * {@link MainActivity#allPermissionsObtained()} to initialise SensorFusion.
     *
     * @see MainActivity#onRequestPermissionsResult(int, String[], int[]) handling request responses.
     */
    private void askMotionPermissions() {
        // Check for motion activity permission
        if(Build.VERSION.SDK_INT >= 29) {
            int activityPermission = ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.ACTIVITY_RECOGNITION);
            // Request if not present
            if(activityPermission != PackageManager.PERMISSION_GRANTED) {
                this.requestPermissions(
                        new String[]{
                                Manifest.permission.ACTIVITY_RECOGNITION},
                        REQUEST_ID_ACTIVITY_PERMISSION
                );
            }
            // Move to finishing function if present
            else allPermissionsObtained();
        }

        else allPermissionsObtained();
    }

    /**
     * {@inheritDoc}
     * When a new set of permissions are granted, move on to the next on in the chain of permissions.
     * Once all permissions are granted, call {@link MainActivity#allPermissionsObtained()}. If any
     * permissions are denied display 1st time warning pop-up message as the application cannot
     * function without the required permissions. If permissions are denied twice, display a new
     * pop-up message, as the OS will not ask for them again, and the user will need to enter the
     * app settings menu.
     *
     * @see MainActivity#askLocationPermissions() first permission request function in the chain.
     * @see MainActivity#askWifiPermissions() second permission request function in the chain.
     * @see MainActivity#askStoragePermission() third permission request function in the chain.
     * @see MainActivity#askMotionPermissions() last permission request function in the chain.
     * @see MainActivity#allPermissionsObtained() once all permissions are granted.
     * @see MainActivity#permissionsDeniedFirst() display first pop-up message.
     * @see MainActivity#permissionsDeniedPermanent() permissions denied twice, pop-up with link to
     * the appropiate settings menu.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_ID_PERMISSIONS) return;

        boolean isMandatory = isMandatoryPermission(permissions[0]);

        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestNextPermission();
        } else {
            if (isMandatory) {
                handleMandatoryDenied(permissions[0]);
            } else {
                // Optional permission denied - just move on
                requestNextPermission();
            }
        }
    }

    /**
     * Displays a pop-up alert the first time the permissions have been denied.
     * The pop-up explains the purpose of the application and the necessity of the permissions, and
     * displays two options. If the "Grant permissions" button is clicked, the permission request
     * chain is restarted. If the "Exit application" button is clicked, the app closes.
     *
     * @see MainActivity#askLocationPermissions() the first in the permission request chain.
     * @see MainActivity#onRequestPermissionsResult(int, String[], int[]) handling permission results.
     * @see com.openpositioning.PositionMe.R.string button text resources.
     */
    private void permissionsDeniedFirst() {
        new AlertDialog.Builder(this)
                .setTitle("Permissions denied")
                .setMessage("You have denied access to data gathering devices. The primary purpose of this application is to record data.")
                .setPositiveButton(R.string.grant, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        settings.edit().putBoolean("permanentDeny", true).apply();
                        askLocationPermissions();
                    }
                })
                .setNegativeButton(R.string.exit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        settings.edit().putBoolean("permanentDeny", true).apply();
                        finishAffinity();
                    }
                })
                .setIcon(R.mipmap.ic_launcher_simple)
                .show();
    }

    /**
     * Displays a pop-up alert when permissions have been denied twice.
     * The OS will not ask for permissions again on the application's behalf. The pop-up explains
     * the purpose of the application and the necessity of the permissions, and displays a button.
     * When the "Settings" button is clicked, the app opens the relevant settings menu where
     * permissions can be adjusted through an intent. Otherwise the app must be closed by the user
     *
     * @see com.openpositioning.PositionMe.R.string button text resources.
     */
    private void permissionsDeniedPermanent() {
        AlertDialog alertDialog = new AlertDialog.Builder(this)
                .setTitle("Permissions are denied, enable them in settings manually")
                .setMessage("You have denied necessary sensor permissions for the data recording app. You need to manually enable them in your device's settings.")
                .setCancelable(false)
                .setPositiveButton("Settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        Uri uri = Uri.fromParts("package", getPackageName(), null);
                        intent.setData(uri);
                        startActivityForResult(intent, 1000);
                    }
                })
                .setIcon(R.mipmap.ic_launcher_simple)
                .create();
        alertDialog.show();
    }

    /**
     * Prepares global resources when all permissions are granted.
     * Resets the permissions tracking boolean in shared preferences, and initialises the
     * {@link SensorFusion} class with the application context, and registers the main activity to
     * listen for server responses that SensorFusion receives.
     *
     * @see SensorFusion the main data processing class.
     * @see ServerCommunications the communication class sending and recieving data from the server.
     */
    private void allPermissionsObtained() {
        settings.edit().putBoolean("permanentDeny", false).apply();
        this.sensorFusion = SensorFusion.getInstance();
        this.sensorFusion.setContext(getApplicationContext());
        sensorFusion.registerForServerUpdate(this);
    }

    //endregion

    //region Navigation

    /**
     * {@inheritDoc}
     * Sets desired animations and navigates to {@link com.openpositioning.PositionMe.fragments.SettingsFragment}
     * when the settings wheel in the action bar is clicked.
     */
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if(navController.getCurrentDestination().getId() == item.getItemId())
            return super.onOptionsItemSelected(item);
        else {
            NavOptions options = new NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .setEnterAnim(R.anim.slide_in_bottom)
                    .setExitAnim(R.anim.slide_out_top)
                    .setPopEnterAnim(R.anim.slide_in_top)
                    .setPopExitAnim(R.anim.slide_out_bottom).build();
            navController.navigate(R.id.action_global_settingsFragment, null, options);
            return true;
        }
    }

    /**
     * {@inheritDoc}
     * Enables navigating back between fragments.
     */
    @Override
    public boolean onSupportNavigateUp() {
        return navController.navigateUp() || super.onSupportNavigateUp();
    }

    /**
     * {@inheritDoc}
     * Inflate the designed menu view.
     *
     * @see com.openpositioning.PositionMe.R.menu for the xml file.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_items, menu);
        return true;
    }

    //endregion

    //region Global toasts

    /**
     * {@inheritDoc}
     * Calls the corresponding handler that runs a toast on the Main UI thread.
     */
    @Override
    public void update(Object[] objList) {
        assert objList[0] instanceof Boolean;
        if((Boolean) objList[0]) {
            this.httpResponseHandler.post(displayToastTaskSuccess);
        }
        else {
            this.httpResponseHandler.post(displayToastTaskFailure);
        }
    }

    /**
     * Task that displays positive toast on the main UI thread.
     * Called when {@link ServerCommunications} successfully uploads a trajectory.
     */
    private final Runnable displayToastTaskSuccess = new Runnable() {
        @Override
        public void run() {
            Toast.makeText(MainActivity.this, "Trajectory uploaded", Toast.LENGTH_SHORT).show();
        }
    };

    /**
     * Task that displays negative toast on the main UI thread.
     * Called when {@link ServerCommunications} fails to upload a trajectory.
     */
    private final Runnable displayToastTaskFailure = new Runnable() {
        @Override
        public void run() {
//            Toast.makeText(MainActivity.this, "Failed to complete trajectory upload", Toast.LENGTH_SHORT).show();
        }
    };

    //endregion
}