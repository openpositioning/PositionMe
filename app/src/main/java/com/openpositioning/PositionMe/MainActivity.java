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
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavOptions;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.openpositioning.PositionMe.fragments.AccountFragment;
import com.openpositioning.PositionMe.fragments.FilesFragment;
import com.openpositioning.PositionMe.fragments.HomeFragment;
import com.openpositioning.PositionMe.fragments.PositionFragment;
import com.openpositioning.PositionMe.sensors.SensorFusion;

public class MainActivity extends AppCompatActivity {
    public OnBackPressedCallback onBackPressedCallback;

    private SharedPreferences settings;

    //region Static variables
    // Static IDs for permission responses.
    private static final int REQUEST_ID_WIFI_PERMISSION = 99;
    private static final int REQUEST_ID_LOCATION_PERMISSION = 98;
    private static final int REQUEST_ID_READ_WRITE_PERMISSION = 97;
    private static final int REQUEST_ID_ACTIVITY_PERMISSION = 96;
    //endregion

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);

        // Load HomeFragment by default
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, new HomeFragment())
                .commit();

        onBackPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 默认不执行任何返回操作
            }
        };
        getOnBackPressedDispatcher().addCallback(this, onBackPressedCallback);


        bottomNavigationView.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;

            if (item.getItemId() == R.id.nav_home) {
                selectedFragment = new HomeFragment();
            } else if (item.getItemId() == R.id.nav_position) {
                selectedFragment = new PositionFragment();
            } else if (item.getItemId() == R.id.nav_files) {
                selectedFragment = new FilesFragment();
            } else if (item.getItemId() == R.id.nav_account) {
                selectedFragment = new AccountFragment();
            }

            if (selectedFragment != null) {
                FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
                transaction.setCustomAnimations(
                        R.anim.slide_in_right,      // 新 Fragment 进入时的动画
                        R.anim.slide_out_right,    // 当前 Fragment 退出时的动画
                        R.anim.slide_in_right,  // 按返回键时，新 Fragment 的进入动画（可选）
                        R.anim.slide_out_right    // 按返回键时，当前 Fragment 的退出动画（可选）
                );
                transaction.replace(R.id.fragment_container, selectedFragment);
                transaction.commit();
            }

            return true;
        });

        // Get handle for settings
        this.settings = PreferenceManager.getDefaultSharedPreferences(this);
        settings.edit().putBoolean("permanentDeny", false).apply();

        //Check Permissions
        if(ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED){
            askLocationPermissions();
        }
    }

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
                android.Manifest.permission.ACCESS_COARSE_LOCATION);
        int fineLocationPermission = ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION);
        int internetPermission = ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.INTERNET);

        // Request if not present
        if(coarseLocationPermission != PackageManager.PERMISSION_GRANTED ||
                fineLocationPermission != PackageManager.PERMISSION_GRANTED ||
                internetPermission != PackageManager.PERMISSION_GRANTED) {
            this.requestPermissions(
                    new String[]{
                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.INTERNET},
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
                android.Manifest.permission.ACCESS_WIFI_STATE);
        int wifiChangePermission = ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.CHANGE_WIFI_STATE);

        // Request if not present
        if(wifiAccessPermission != PackageManager.PERMISSION_GRANTED ||
                wifiChangePermission != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.ACCESS_WIFI_STATE,
                            android.Manifest.permission.CHANGE_WIFI_STATE},
                    REQUEST_ID_WIFI_PERMISSION
            );
        }
        else{
            // Determine next step based on android version
            // if android ver is lower than 13, check for storage permissions
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                askStoragePermission();
            } else { // else skip storage permission check
                askMotionPermissions();
            }
//            askStoragePermission();
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
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        int readStoragePermission = ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE);
        // Request if not present
        if(writeStoragePermission != PackageManager.PERMISSION_GRANTED ||
                readStoragePermission != PackageManager.PERMISSION_GRANTED) {
            this.requestPermissions(
                    new String[]{
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            android.Manifest.permission.READ_EXTERNAL_STORAGE},
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
                    android.Manifest.permission.ACTIVITY_RECOGNITION);
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
        switch (requestCode) {
            case REQUEST_ID_LOCATION_PERMISSION: { // Location permissions
                // If request is cancelled results are empty
                if (grantResults.length > 1 &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                        grantResults[1] == PackageManager.PERMISSION_GRANTED &&
                        grantResults[2] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Location permissions granted!", Toast.LENGTH_SHORT).show();
                    this.settings.edit().putBoolean("gps", true).apply();
                    askWifiPermissions();
                }
                else {
                    if(!settings.getBoolean("permanentDeny", false)) {
                        permissionsDeniedFirst();
                    }
                    else permissionsDeniedPermanent();
                    Toast.makeText(this, "Location permissions denied!", Toast.LENGTH_SHORT).show();
                    // Unset setting
                    this.settings.edit().putBoolean("gps", false).apply();
                }
                break;

            }
            case REQUEST_ID_WIFI_PERMISSION: { // Wifi permissions
                // If request is cancelled results are empty
                if (grantResults.length > 1 &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                        grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show();
                    this.settings.edit().putBoolean("wifi", true).apply();
                    // Check for storage permissions if android ver lower than 13
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        askStoragePermission();
                    }
//                    askStoragePermission();
                }
                else {
                    if(!settings.getBoolean("permanentDeny", false)) {
                        permissionsDeniedFirst();
                    }
                    else permissionsDeniedPermanent();
                    Toast.makeText(this, "Wifi permissions denied!", Toast.LENGTH_SHORT).show();
                    // Unset setting
                    this.settings.edit().putBoolean("wifi", false).apply();
                }
                break;
            }
            case REQUEST_ID_READ_WRITE_PERMISSION: { // Read write permissions
                // If request is cancelled results are empty
                if (grantResults.length > 1 &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                        grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show();
                    askMotionPermissions();
                }
                else {
                    if(!settings.getBoolean("permanentDeny", false)) {
                        permissionsDeniedFirst();
                    }
                    else permissionsDeniedPermanent();
                    Toast.makeText(this, "Storage permissions denied!", Toast.LENGTH_SHORT).show();
                }
                break;
            }
            case REQUEST_ID_ACTIVITY_PERMISSION: { // Activity permissions
                // If request is cancelled results are empty
                if (grantResults.length >= 1 &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show();
                    allPermissionsObtained();
                }
                else {
                    if(!settings.getBoolean("permanentDeny", false)) {
                        permissionsDeniedFirst();
                    }
                    else permissionsDeniedPermanent();
                    Toast.makeText(this, "Activity permissions denied!", Toast.LENGTH_SHORT).show();
                }
                break;
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
                .setIcon(R.drawable.new_icon)
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
                .setIcon(R.drawable.new_icon)
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

    }

    //endregion

//
//
//    //region Global toasts
//
//    /**
//     * {@inheritDoc}
//     * Calls the corresponding handler that runs a toast on the Main UI thread.
//     */
//    @Override
//    public void update(Object[] objList) {
//        assert objList[0] instanceof Boolean;
//        if((Boolean) objList[0]) {
//            this.httpResponseHandler.post(displayToastTaskSuccess);
//        }
//        else {
//            this.httpResponseHandler.post(displayToastTaskFailure);
//        }
//    }
//
//    /**
//     * Task that displays positive toast on the main UI thread.
//     * Called when {@link ServerCommunications} successfully uploads a trajectory.
//     */
//    private final Runnable displayToastTaskSuccess = new Runnable() {
//        @Override
//        public void run() {
//            Toast.makeText(MainActivity.this, "Trajectory uploaded", Toast.LENGTH_SHORT).show();
//        }
//    };
//
//    /**
//     * Task that displays negative toast on the main UI thread.
//     * Called when {@link ServerCommunications} fails to upload a trajectory.
//     */
//    private final Runnable displayToastTaskFailure = new Runnable() {
//        @Override
//        public void run() {
////            Toast.makeText(MainActivity.this, "Failed to complete trajectory upload", Toast.LENGTH_SHORT).show();
//        }
//    };
}
