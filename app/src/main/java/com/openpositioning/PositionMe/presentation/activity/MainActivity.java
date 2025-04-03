package com.openpositioning.PositionMe.presentation.activity;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.Objects;

/**
 * MainActivity is the entry point of the application. It manages the navigation between different
 * fragments and handles permissions for location and activity recognition.
 * It also initializes the SensorFusion class for sensor data collection.
 * This activity uses a bottom navigation bar to switch between different fragments.
 * It implements the Observer interface to receive updates from the SensorFusion class.
 *
 * @author Shu Gu
 */

public class MainActivity extends AppCompatActivity implements Observer {

    private NavController navController;
    private ActivityResultLauncher<String[]> multiplePermissionsLauncher;

    private SensorFusion sensorFusion;
    private Handler httpResponseHandler;

    /**
     * Called when the activity is first created. Initializes the layout, navigation, permissions, and sensor fusion.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Force light mode theme
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);

        // Load main layout
        setContentView(R.layout.activity_main);

        // Initialize navigation controller for managing fragments
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        navController = Objects.requireNonNull(navHostFragment).getNavController();

        // Handle bottom navigation bar logic
        BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            int currentId = navController.getCurrentDestination() != null
                    ? navController.getCurrentDestination().getId() : -1;

            if (itemId == currentId) return true;

            if (itemId == R.id.recordingFragment) {
                // Pop the current fragment before navigating to a fresh instance of recordingFragment
                NavOptions options = new NavOptions.Builder()
                        .setPopUpTo(R.id.recordingFragment, true)
                        .build();
                navController.navigate(R.id.recordingFragment, null, options);
            } else {
                // Navigate normally to selected fragment
                navController.navigate(itemId);
            }
            return true;
        });

        // Register launcher to handle multiple permission requests
        multiplePermissionsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean locationGranted = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                    boolean activityGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                            || result.getOrDefault(Manifest.permission.ACTIVITY_RECOGNITION, false);

                    if (locationGranted && activityGranted) {
                        allPermissionsObtained();
                    } else {
                        Toast.makeText(this,
                                "Location or Physical Activity permission denied. Some features may not work.",
                                Toast.LENGTH_LONG).show();
                    }
                });

        // Initialize sensor fusion and register context
        sensorFusion = SensorFusion.getInstance();
        sensorFusion.setContext(getApplicationContext());

        // Handler to run toast notifications on the UI thread
        httpResponseHandler = new Handler();

        // Reset any saved flag for permanent deny of permissions
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("permanentDeny", false).apply();
    }

    /**
     * Called when the activity resumes. Checks and requests permissions if needed, and resumes sensor updates.
     */
    @Override
    protected void onResume() {
        super.onResume();

        new Handler(Looper.getMainLooper()).post(() -> {
            if (!isFinishing() && !isDestroyed()) {
                boolean locationGranted = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

                boolean activityGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                        || ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;

                if (!locationGranted || !activityGranted) {
                    // Launch permission request
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        multiplePermissionsLauncher.launch(new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACTIVITY_RECOGNITION
                        });
                    } else {
                        multiplePermissionsLauncher.launch(new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION
                        });
                    }
                } else {
                    allPermissionsObtained();
                }
            }
        });

        // Resume sensor data collection
        if (sensorFusion != null) {
            sensorFusion.resumeListening();
        }
    }

    /**
     * Callback invoked when all permissions are granted. Registers this activity as an observer
     * for receiving updates from the server through SensorFusion.
     */
    private void allPermissionsObtained() {
        sensorFusion.registerForServerUpdate(this);
    }

    /**
     * Optional override. Could be used to stop sensors when activity is destroyed.
     */
    @Override
    protected void onDestroy() {
        // sensorFusion.stopListening(); // Uncomment if you wish to explicitly stop sensors
        super.onDestroy();
    }

    /**
     * Override back button behavior. If user is on the recording screen, show a confirmation dialog.
     */
    @Override
    public void onBackPressed() {
        if (navController.getCurrentDestination() != null &&
                navController.getCurrentDestination().getId() == R.id.recordingFragment) {
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

    /**
     * Observer callback invoked when trajectory upload completes.
     * @param objList An object array with a Boolean indicating success (true) or failure (false).
     */
    @Override
    public void update(Object[] objList) {
        if ((Boolean) objList[0]) {
            httpResponseHandler.post(() ->
                    Toast.makeText(this, "Trajectory uploaded", Toast.LENGTH_SHORT).show());
        } else {
            httpResponseHandler.post(() ->
                    Toast.makeText(this, "Failed to complete trajectory upload", Toast.LENGTH_SHORT).show());
        }
    }
}
