package com.openpositioning.PositionMe.presentation.activity;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.preference.PreferenceManager;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.HomeFragment;
import com.openpositioning.PositionMe.presentation.fragment.SettingsFragment;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.PermissionManager;

// Import required for file saving
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;

public class MainActivity extends AppCompatActivity implements Observer {

    //region Instance variables
    private NavController navController;
    private ActivityResultLauncher<String> locationPermissionLauncher;
    private ActivityResultLauncher<String[]> multiplePermissionsLauncher;

    private SharedPreferences settings;
    private SensorFusion sensorFusion;
    private Handler httpResponseHandler;

    private PermissionManager permissionManager;

    private static final int PERMISSION_REQUEST_CODE = 100;
    //endregion

    //region Activity Lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        setContentView(R.layout.activity_main);

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        navController = Objects.requireNonNull(navHostFragment).getNavController();

        Toolbar toolbar = findViewById(R.id.main_toolbar);
        setSupportActionBar(toolbar);
        toolbar.showOverflowMenu();
        toolbar.setBackgroundColor(ContextCompat.getColor(getApplicationContext(), R.color.md_theme_light_surface));
        toolbar.setTitleTextColor(ContextCompat.getColor(getApplicationContext(), R.color.black));
        toolbar.setNavigationIcon(R.drawable.ic_baseline_back_arrow);

        AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
        NavigationUI.setupWithNavController(toolbar, navController, appBarConfiguration);

        this.settings = PreferenceManager.getDefaultSharedPreferences(this);
        settings.edit().putBoolean("permanentDeny", false).apply();

        this.sensorFusion = SensorFusion.getInstance();
        this.sensorFusion.setContext(getApplicationContext());

        multiplePermissionsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean locationGranted = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                    boolean activityGranted = result.getOrDefault(Manifest.permission.ACTIVITY_RECOGNITION, false);

                    if (locationGranted && activityGranted) {
                        allPermissionsObtained();
                    } else {
                        Toast.makeText(this,
                                "Location or Physical Activity permission denied. Some features may not work.",
                                Toast.LENGTH_LONG).show();
                    }
                }
        );

        this.httpResponseHandler = new Handler();
    }

    @Override
    public void onPause() {
        super.onPause();
        if(sensorFusion != null) {
            // sensorFusion.stopListening();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getSupportActionBar() != null) {
            getSupportActionBar().show();
        }

        new Handler().postDelayed(() -> {
            if (isActivityVisible()) {
                boolean locationGranted = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;

                boolean activityGranted = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED;

                if (!locationGranted || !activityGranted) {
                    multiplePermissionsLauncher.launch(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION
                    });
                    multiplePermissionsLauncher.launch(new String[]{
                            Manifest.permission.ACTIVITY_RECOGNITION
                    });
                } else {
                    allPermissionsObtained();
                }
            }
        }, 300);

        if (sensorFusion != null) {
            sensorFusion.resumeListening();
        }
    }

    private boolean isActivityVisible() {
        return !isFinishing() && !isDestroyed();
    }

    @Override
    protected void onDestroy() {
        if (sensorFusion != null) {
            // sensorFusion.stopListening();
        }
        super.onDestroy();
    }
    //endregion

    //region Permissions
    private void allPermissionsObtained() {
        settings.edit().putBoolean("permanentDeny", false).apply();
        if (this.sensorFusion == null) {
            this.sensorFusion = SensorFusion.getInstance();
            this.sensorFusion.setContext(getApplicationContext());
        }
        sensorFusion.registerForServerUpdate(this);
    }
    //endregion

    //region Navigation
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if(Objects.requireNonNull(navController.getCurrentDestination()).getId() == item.getItemId())
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

    @Override
    public boolean onSupportNavigateUp() {
        return navController.navigateUp() || super.onSupportNavigateUp();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_items, menu);
        return true;
    }

    @Override
    public void onBackPressed() {
        if (navController.getCurrentDestination() != null &&
                navController.getCurrentDestination().getId() == R.id.homeFragment) {
            new AlertDialog.Builder(this)
                    .setTitle("Confirm Exit")
                    .setMessage("Are you sure you want to exit the app?")
                    .setPositiveButton("Yes", (dialog, which) -> {
                        dialog.dismiss();
                        finish();
                    })
                    .setNegativeButton("No", (dialog, which) -> dialog.dismiss())
                    .create()
                    .show();
        } else {
            super.onBackPressed();
        }
    }
    //endregion

    //region Global toasts
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

    private final Runnable displayToastTaskSuccess = () -> Toast.makeText(MainActivity.this,
            "Trajectory uploaded", Toast.LENGTH_SHORT).show();

    private final Runnable displayToastTaskFailure = () -> {
        // Toast.makeText(MainActivity.this, "Failed to complete trajectory upload", Toast.LENGTH_SHORT).show();
    };
    //endregion

    // ====================================================================================
    // File Saving Logic
    // ====================================================================================

    /**
     * Stops the sensor recording and saves the trajectory data to a file.
     * The file is saved with a .protobuf extension in the app's external files directory.
     * Call this method in the Stop button of HomeFragment: ((MainActivity)getActivity()).stopRecordingAndSave();
     */
    public void stopRecordingAndSave() {
        // 1. Stop sensor collection
        if (sensorFusion != null) {
            sensorFusion.stopRecording();
        }

        // 2. Prepare to save
        try {
            // File name suffix must be .protobuf for History interface recognition
            String filename = "Traj_" + System.currentTimeMillis() + ".protobuf";

            // Get App private storage path
            File file = new File(getExternalFilesDir(null), filename);
            FileOutputStream fos = new FileOutputStream(file);

            // Use writeTo to write binary stream directly
            // Note: You need to add a public Traj.Trajectory.Builder getTrajectory() method in SensorFusion.java
            if (sensorFusion != null && sensorFusion.getTrajectory() != null) {
                sensorFusion.getTrajectory().build().writeTo(fos);
                Toast.makeText(this, "File Saved: " + filename, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Error: No data to save", Toast.LENGTH_SHORT).show();
            }

            fos.close();

        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Save Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // Helper method to start recording with a filename from a Fragment
    public void startRecording(String filename) {
        if (sensorFusion != null) {
            sensorFusion.startRecording(filename);
        }
    }
}