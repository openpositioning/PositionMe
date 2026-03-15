package com.openpositioning.PositionMe.utils;

import static com.openpositioning.PositionMe.utils.PermissionConstants.PERMISSIONS_API_29;
import static com.openpositioning.PositionMe.utils.PermissionConstants.PERMISSIONS_API_33;
import static com.openpositioning.PositionMe.utils.PermissionConstants.PERMISSIONS_LOCATION;
import static com.openpositioning.PositionMe.utils.PermissionConstants.PERMISSIONS_STORAGE_LEGACY;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import androidx.activity.result.ActivityResultCaller;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

/**
 * A helper class responsible for checking and requesting all dangerous permissions that the
 * application needs in order to function.
 *
 * <p>This class:
 *
 * <p>- Manages the permissions list.
 *
 * <p>- Checks if all permissions are granted.
 *
 * <p>- Requests missing permissions.
 *
 * <p>- Handles both the first-time and permanent denial scenarios.
 *
 * @see MainActivity MainActivity
 */
public class PermissionManager {
    private static final String TAG = "PermissionManager";

    private final Activity activity;
    private ActivityResultLauncher<String[]> multiplePermissionsLauncher;

    boolean allPermissionsGranted = false;
    // Flag to prevent overlapping AlertBoxes
    boolean boxShowing = false;

    // The list of dangerous permissions needed by this app.
    private final List<String> requiredPermissions = new ArrayList<>();
    private List<String> missingPermissions = new ArrayList<>();

    public PermissionManager(
            Activity activity, ActivityResultCaller caller, PermissionCallback callback) {
        this.activity = activity;

        int buildSDK = Build.VERSION.SDK_INT;
        requiredPermissions.addAll(Arrays.asList(PERMISSIONS_LOCATION));
        if (buildSDK >= Build.VERSION_CODES.Q) {
            requiredPermissions.addAll(Arrays.asList(PERMISSIONS_API_29));
        } else {
            requiredPermissions.addAll(Arrays.asList(PERMISSIONS_STORAGE_LEGACY));
        }
        if (buildSDK >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.addAll(Arrays.asList(PERMISSIONS_API_33));
        }

        // Register multiple permissions launcher
        multiplePermissionsLauncher =
                caller.registerForActivityResult(
                        new ActivityResultContracts.RequestMultiplePermissions(),
                        results -> {
                            Log.d(TAG, "Now checking " + results.size() + " permissions");
                            if (results.isEmpty()) {
                                allPermissionsGranted = false;
                            } else {
                                Set<String> permissions = results.keySet();
                                allPermissionsGranted = true;
                                for (String permission : permissions) {
                                    boolean result = Boolean.TRUE.equals(results.get(permission));
                                    if (!result) {
                                        Log.w(TAG, "Permission Denied: " + permission);
                                        allPermissionsGranted = false;
                                        if (!missingPermissions.contains(permission)) {
                                            missingPermissions.add(permission);
                                        }
                                    } else if (missingPermissions.contains(permission)) {
                                        missingPermissions.remove(permission);
                                    }
                                }
                            }
                            callback.onAllPermissionsGranted(
                                    allPermissionsGranted, missingPermissions);
                        });
    }

    /** Checks if all required permissions are already granted; if not, requests them. */
    public void checkAndRequestPermissions() {
        multiplePermissionsLauncher.launch(requiredPermissions.toArray(new String[0]));
    }

    public void checkMissingPermissions() {
        Log.d(TAG, "Checking " + missingPermissions.size() + " missing permissions");
        ListIterator<String> iterator = missingPermissions.listIterator();
        while (iterator.hasNext()) {
            String permission = iterator.next();
            if (ContextCompat.checkSelfPermission(activity, permission)
                    == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Permission " + permission + " now granted!");
                iterator.remove();
            }
        }
        Log.d(TAG, "Now missing " + missingPermissions.size() + " permissions");
        confirmDeniedPermissions(missingPermissions);
    }

    /**
     * Must be called from the Activity's onRequestPermissionsResult
     *
     * <p>Override public void onRequestPermissionsResult( int requestCode, String[] permissions,
     * int[] results) {super.onRequestPermissionsResult(requestCode, permissions, results);
     * permissionManager.handleRequestPermissionsResult(requestCode, permissions, results); }
     */
    public void confirmDeniedPermissions(List<String> deniedPermissions) {
        // Check if any denied permission is permanently denied.
        ArrayList<String> permanentlyDenied = new ArrayList<>();
        ArrayList<String> tentativelyDenied = new ArrayList<>();
        for (String permission : deniedPermissions) {
            // If shouldShowRequestPermissionRationale returns false => permanently denied
            if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
                permanentlyDenied.add(permission);
            } else {
                tentativelyDenied.add(permission);
            }
        }

        if (!permanentlyDenied.isEmpty()) {
            showPermanentDenialDialog();
        }
        if (!tentativelyDenied.isEmpty()) {
            showFirstDenialDialog();
        }
    }

    /** Shows an AlertDialog if the user has denied permissions for the first time. */
    private void showFirstDenialDialog() {
        if (!activity.isFinishing() && !activity.isDestroyed()) {
            if (boxShowing) {
                Log.w(TAG, "Box already showing. Skipping new first denial box");
                return;
            }
            boxShowing = true;
            Log.i(TAG, "Displaying the first denial box");
            new AlertDialog.Builder(activity)
                    .setTitle("Permissions Denied")
                    .setMessage(
                            "Certain permissions are essential for this app to function.\n"
                                    + "Tap GRANT to try again or EXIT to close the app.")
                    .setCancelable(false)
                    .setPositiveButton(
                            "Grant",
                            (dialog, which) -> {
                                boxShowing = false;
                                checkAndRequestPermissions();
                            })
                    .setNegativeButton(
                            "Exit",
                            (dialog, which) -> {
                                boxShowing = false;
                                activity.finish();
                            })
                    .show();
        }
    }

    /** Shows an AlertDialog if the user has permanently denied the permissions. */
    private void showPermanentDenialDialog() {
        if (!activity.isFinishing() && !activity.isDestroyed()) {
            if (boxShowing) {
                Log.w(TAG, "Box already showing. Skipping new permanent denial box");
                return;
            }
            boxShowing = true;
            Log.i(TAG, "Displaying the permanent denial box");
            new AlertDialog.Builder(activity)
                    .setTitle("Permissions Permanently Denied")
                    .setMessage(
                            "Some permissions have been permanently denied. "
                                    + "Please go to Settings to enable them manually.")
                    .setCancelable(false)
                    .setPositiveButton(
                            "Settings",
                            (dialog, which) -> {
                                boxShowing = false;
                                Intent intent =
                                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
                                intent.setData(uri);
                                activity.startActivity(intent);
                            })
                    .setNegativeButton(
                            "Exit",
                            (dialog, which) -> {
                                boxShowing = false;
                                activity.finish();
                            })
                    .show();
        }
    }

    /** Callback to notify the calling Activity when all permissions have been granted. */
    public interface PermissionCallback {
        void onAllPermissionsGranted(boolean allPermissionsGranted, List<String> deniedPermissions);
    }
}
