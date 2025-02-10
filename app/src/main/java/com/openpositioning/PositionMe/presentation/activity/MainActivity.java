package com.openpositioning.PositionMe.presentation.activity;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.presentation.fragment.HomeFragment;
import com.openpositioning.PositionMe.presentation.fragment.SettingsFragment;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.PermissionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 * @see HomeFragment the initial fragment displayed.
 * @see com.openpositioning.PositionMe.R.navigation the navigation graph.
 * @see SensorFusion the singletion data processing class.
 *
 * @author Mate Stodulka
 * @author Virginia Cangelosi
 */
public class MainActivity extends AppCompatActivity implements Observer {


    //region Instance variables
    private NavController navController;

    private SharedPreferences settings;
    private SensorFusion sensorFusion;
    private Handler httpResponseHandler;

    private PermissionManager permissionManager;

    private static final int PERMISSION_REQUEST_CODE = 100;

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
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().
                findFragmentById(R.id.nav_host_fragment);
        navController = Objects.requireNonNull(navHostFragment).getNavController();

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

        // Initialize SensorFusion early so that its context is set
        this.sensorFusion = SensorFusion.getInstance();
        this.sensorFusion.setContext(getApplicationContext());

        // Build the list of dangerous permissions for this device.
        permissionManager = new PermissionManager(this, new PermissionManager.PermissionCallback() {
            @Override
            public void onAllPermissionsGranted() {
                // Once all permissions are granted, complete initialization:
                allPermissionsObtained();
            }
        });
        // Check and request permissions
        checkAndRequestPermissions();

        // Handler for global toasts and popups from other classes
        this.httpResponseHandler = new Handler();
    }



    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13 and above
            List<String> permissionsNeeded = new ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
            if (!permissionsNeeded.isEmpty()) {
                ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]),
                        PERMISSION_REQUEST_CODE);
            }
        } else {
            // Below Android 13
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, proceed with accessing the storage
                } else {
                    // Permission denied, handle accordingly
                }
            }
        }
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public void onPause() {
        super.onPause();

        //Ensure sensorFusion has been initialised before unregistering listeners
        if(sensorFusion != null) {
//            sensorFusion.stopListening();
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
        new Handler().postDelayed(() -> {
            if (permissionManager != null) {
                permissionManager.checkAndRequestPermissions();
            }
        }, 5000); // 300 ms delay to ensure the Activity is fully in the foreground
        if (sensorFusion != null) {
            sensorFusion.resumeListening();
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
        if (sensorFusion != null) {
//            sensorFusion.stopListening(); // suspended due to the need to record data with
//                                             a locked screen or cross activity
        }
        super.onDestroy();
    }


    //endregion

    //region Permissions

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
        // Reset any permission denial flag in SharedPreferences if needed.
        settings.edit().putBoolean("permanentDeny", false).apply();

        // Ensure SensorFusion is initialized with a valid context.
        if (this.sensorFusion == null) {
            this.sensorFusion = SensorFusion.getInstance();
            this.sensorFusion.setContext(getApplicationContext());
        }
        sensorFusion.registerForServerUpdate(this);
    }




    //endregion

    //region Navigation

    /**
     * {@inheritDoc}
     * Sets desired animations and navigates to {@link SettingsFragment}
     * when the settings wheel in the action bar is clicked.
     */
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
    private final Runnable displayToastTaskSuccess = () -> Toast.makeText(MainActivity.this,
            "Trajectory uploaded", Toast.LENGTH_SHORT).show();

    /**
     * Task that displays negative toast on the main UI thread.
     * Called when {@link ServerCommunications} fails to upload a trajectory.
     */
    private final Runnable displayToastTaskFailure = () -> {
//            Toast.makeText(MainActivity.this, "Failed to complete trajectory upload", Toast.LENGTH_SHORT).show();
    };

    //endregion
}