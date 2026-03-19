package com.openpositioning.PositionMe.presentation.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;
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
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.presentation.fragment.HomeFragment;
import com.openpositioning.PositionMe.presentation.fragment.SettingsFragment;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.PermissionManager;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Main Activity of the application, handling setup, permissions and starting all other
 * fragments and processes. The Main Activity takes care of most essential tasks before the app can
 * run. Such as setting up the views, and enforcing light mode so the colour scheme is consistent.
 * It initialises the various fragments and the navigation between them, getting the Navigation
 * controller. It also loads the custom action bar with the set theme and icons, and enables
 * back-navigation. The shared preferences are also loaded.
 *
 * <p>The most important task of the main activity is check and asking for the necessary permissions
 * to enable the application to use the required hardware devices. This is done through a number of
 * functions that call the OS, as well as pop-up messages warning the user if permissions are
 * denied.
 *
 * <p>Once all permissions are granted, the Main Activity obtains the Sensor Fusion instance and
 * sets the context, enabling the Fragments to interact with the class without setting it up again.
 *
 * @see HomeFragment the initial fragment displayed.
 * @see com.openpositioning.PositionMe.R.navigation the navigation graph.
 * @see SensorFusion the singletion data processing class.
 * @author Mate Stodulka
 * @author Virginia Cangelosi
 */
public class MainActivity extends AppCompatActivity implements Observer {
    private static final String TAG = "MainActivity";

    private NavController navController;

    private SharedPreferences settings;
    private SensorFusion sensorFusion;
    private Handler httpResponseHandler;
    private String httpFailureMessage = "";

    private PermissionManager permissionManager;

    /**
     * {@inheritDoc} Forces light mode, sets up the navigation graph, initialises the toolbar with
     * back action on the nav controller, loads the shared preferences and checks for all
     * permissions necessary. Sets up a Handler for displaying messages from other classes.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_NO) {
            Log.d(TAG, "Forcing light mode");
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
        setContentView(R.layout.activity_main);

        // Set up navigation and fragments
        NavHostFragment navHostFragment =
                (NavHostFragment)
                        getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        navController = Objects.requireNonNull(navHostFragment).getNavController();

        // Set action bar
        Toolbar toolbar = findViewById(R.id.main_toolbar);
        setSupportActionBar(toolbar);
        toolbar.showOverflowMenu();
        toolbar.setBackgroundColor(
                ContextCompat.getColor(getApplicationContext(), R.color.md_theme_light_surface));
        toolbar.setTitleTextColor(ContextCompat.getColor(getApplicationContext(), R.color.black));
        toolbar.setNavigationIcon(R.drawable.ic_baseline_back_arrow);

        // Set up back action with NavigationUI
        AppBarConfiguration appBarConfiguration =
                new AppBarConfiguration.Builder(navController.getGraph()).build();
        NavigationUI.setupWithNavController(toolbar, navController, appBarConfiguration);

        // Get handle for settings
        this.settings = PreferenceManager.getDefaultSharedPreferences(this);
        settings.edit().putBoolean("permanentDeny", false).apply();

        // Initialize SensorFusion early so that its context is set
        this.sensorFusion = SensorFusion.getInstance();
        this.sensorFusion.setContext(getApplicationContext());

        permissionManager =
                new PermissionManager(
                        this,
                        this,
                        ((allPermissionsGranted, deniedPermissions) -> {
                            if (isActivityVisible()) {
                                if (allPermissionsGranted) {
                                    allPermissionsObtained();
                                } else {
                                    Log.d(TAG, "Missing permissions!");
                                    permissionManager.confirmDeniedPermissions(deniedPermissions);
                                }
                            } else {
                                Log.d(TAG, "Activity not visible; skipping permission check");
                            }
                        }));
        permissionManager.checkAndRequestPermissions();

        // Handler for global toasts and popups from other classes
        this.httpResponseHandler = new Handler();
    }

    /** {@inheritDoc} */
    @Override
    public void onPause() {
        super.onPause();

        // Ensure sensorFusion has been initialised before unregistering listeners
        if (sensorFusion != null) {
            //            sensorFusion.stopListening();
        }
    }

    /**
     * {@inheritDoc} Checks for activities in case the app was closed without granting them, or if
     * they were granted through the settings page. Repeats the startup checks done in {@link
     * MainActivity#onCreate(Bundle)}. Starts listening in the SensorFusion class.
     *
     * @see SensorFusion the main data processing class.
     */
    @Override
    public void onResume() {
        super.onResume();

        if (getSupportActionBar() != null) {
            getSupportActionBar().show();
        }

        permissionManager.checkMissingPermissions();

        if (sensorFusion != null) {
            sensorFusion.resumeListening();
        }
    }

    private boolean isActivityVisible() {
        return !isFinishing() && !isDestroyed();
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
            //            sensorFusion.stopListening(); // suspended due to the need to record data
            // with
            //                                             a locked screen or cross activity
        }
        Log.d(TAG, "MainActivity being destroyed");
        super.onDestroy();
    }

    /**
     * Prepares global resources when all permissions are granted. Resets the permissions tracking
     * boolean in shared preferences, and initialises the {@link SensorFusion} class with the
     * application context, and registers the main activity to listen for server responses that
     * SensorFusion receives.
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

    /**
     * {@inheritDoc} Sets desired animations and navigates to {@link SettingsFragment} when the
     * settings wheel in the action bar is clicked.
     */
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (Objects.requireNonNull(navController.getCurrentDestination()).getId()
                == item.getItemId()) return super.onOptionsItemSelected(item);
        else {
            NavOptions options =
                    new NavOptions.Builder()
                            .setLaunchSingleTop(true)
                            .setEnterAnim(R.anim.slide_in_bottom)
                            .setExitAnim(R.anim.slide_out_top)
                            .setPopEnterAnim(R.anim.slide_in_top)
                            .setPopExitAnim(R.anim.slide_out_bottom)
                            .build();
            navController.navigate(R.id.action_global_settingsFragment, null, options);
            return true;
        }
    }

    /** {@inheritDoc} Enables navigating back between fragments. */
    @Override
    public boolean onSupportNavigateUp() {
        return navController.navigateUp() || super.onSupportNavigateUp();
    }

    /**
     * {@inheritDoc} Inflate the designed menu view.
     *
     * @see com.openpositioning.PositionMe.R.menu for the xml file.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_items, menu);
        return true;
    }

    /**
     * {@inheritDoc} Handles the back button press. If the current fragment is the LoginFragment, a
     * dialog is displayed to confirm the exit. If not, the default back navigation is performed.
     */
    @Override
    public void onBackPressed() {
        // Check if the current destination is LoginFragment (assumed to be the root)
        if (navController.getCurrentDestination() != null
                && navController.getCurrentDestination().getId() == R.id.homeFragment) {
            new AlertDialog.Builder(this)
                    .setTitle("Confirm Exit")
                    .setMessage("Are you sure you want to log out?")
                    .setPositiveButton(
                            "Yes",
                            (dialog, which) -> {
                                dialog.dismiss();
                                Intent intent = new Intent(this, LoginActivity.class);
                                startActivity(intent);
                                finish();
                            })
                    .setNegativeButton("No", (dialog, which) -> dialog.dismiss())
                    .create()
                    .show();
        } else {
            // If not on the root destination, perform the default back navigation.
            super.onBackPressed();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Calls the corresponding handler that runs a toast on the Main UI thread.
     */
    @Override
    public void update(Object[] objList) {
        boolean success = (boolean) objList[0];
        String response = objList[1].toString();
        Log.i(TAG, response);

        if (success) {
            httpResponseHandler.post(displayToastTaskSuccess);
        } else {
            try {
                String errorCode = response.split(":", 2)[0];
                if (errorCode.equals(getString(R.string.errorCodeNoServerResponse))) return;

                String errorMessage = response.split(":", 2)[1];
                if (errorMessage.contains("{")) {
                    JSONObject jsonObject = new JSONObject(errorMessage);

                    String cause = jsonObject.getString("detail");
                    String[] causeElements = cause.split(":", 2);
                    String causeSource = causeElements[0].strip();
                    String causeMessage = causeElements[1].strip();

                    new Handler(Looper.getMainLooper())
                            .post(
                                    () ->
                                            new AlertDialog.Builder(this)
                                                    .setTitle("Trajectory Upload Failed")
                                                    .setMessage(
                                                            "The server has declined this"
                                                                    + " trajectory. The response is"
                                                                    + " shown below:\n\n"
                                                                    + causeSource
                                                                    + "\n"
                                                                    + causeMessage)
                                                    .setPositiveButton(
                                                            "Okay",
                                                            (dialog, which) -> {
                                                                dialog.dismiss();
                                                            })
                                                    .create()
                                                    .show());
                } else {
                    httpFailureMessage = errorMessage;
                    httpResponseHandler.post(displayToastTaskFailure);
                }
            } catch (JSONException e) {
                Log.w(TAG, e.getMessage());
                httpFailureMessage = response.split(":", 2)[1];
                httpResponseHandler.post(displayToastTaskFailure);
            }
        }
    }

    /**
     * Task that displays positive toast on the main UI thread. Called when {@link
     * ServerCommunications} successfully uploads a trajectory.
     */
    private final Runnable displayToastTaskSuccess =
            () ->
                    Toast.makeText(MainActivity.this, "Trajectory uploaded", Toast.LENGTH_SHORT)
                            .show();

    /**
     * Task that displays negative toast on the main UI thread. Called when {@link
     * ServerCommunications} fails to upload a trajectory.
     */
    private final Runnable displayToastTaskFailure =
            () -> {
                Toast.makeText(MainActivity.this, httpFailureMessage, Toast.LENGTH_SHORT).show();
            };
}
