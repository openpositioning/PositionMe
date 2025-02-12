package com.openpositioning.PositionMe.utils;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.openpositioning.PositionMe.R;

import java.util.ArrayList;
import java.util.List;

/**
 * A helper class responsible for checking and requesting all dangerous permissions
 * that the application needs in order to function.
 *
 * This class:
 *  - Manages the permissions list.
 *  - Checks if all permissions are granted.
 *  - Requests missing permissions.
 *  - Handles both the first-time and permanent denial scenarios.
 *
 * Usage from MainActivity:
 *   PermissionManager permissionManager = new PermissionManager(MainActivity.this, new PermissionManager.PermissionCallback() {
 *       @Override
 *       public void onAllPermissionsGranted() {
 *           // e.g. call allPermissionsObtained() in MainActivity
 *           allPermissionsObtained();
 *       }
 *   });
 *   permissionManager.checkAndRequestPermissions();
 */
public class PermissionManager {

    private static final int ALL_PERMISSIONS_REQUEST = 100;

    private final Activity activity;
    private final PermissionCallback callback;

    // The list of dangerous permissions needed by this app.
    private final List<String> requiredPermissions = new ArrayList<>();

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public PermissionManager(Activity activity, PermissionCallback callback) {
        this.activity = activity;
        this.callback = callback;

        // Populate required permissions
        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        requiredPermissions.add(Manifest.permission.ACCESS_WIFI_STATE);
        requiredPermissions.add(Manifest.permission.CHANGE_WIFI_STATE);
        // For API < 29, also request broad storage permissions
        // For API >= 29, also request ACTIVITY_RECOGNITION
        // (We can do the check here or just always add them; the OS will skip as needed.)
        requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        requiredPermissions.add(Manifest.permission.ACTIVITY_RECOGNITION);
    }

    /**
     * Checks if all required permissions are already granted; if not, requests them.
     */
    public void checkAndRequestPermissions() {
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(
                    activity,
                    requiredPermissions.toArray(new String[0]),
                    ALL_PERMISSIONS_REQUEST
            );
        } else {
            // Already granted
            callback.onAllPermissionsGranted();
        }
    }

    /**
     * Must be called from the Activity's onRequestPermissionsResult:
     *
     *   @Override
     *   public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
     *       super.onRequestPermissionsResult(requestCode, permissions, grantResults);
     *       permissionManager.handleRequestPermissionsResult(requestCode, permissions, grantResults);
     *   }
     */
    public void handleRequestPermissionsResult(int requestCode,
                                               String[] permissions,
                                               int[] grantResults) {
        if (requestCode == ALL_PERMISSIONS_REQUEST) {
            boolean allGranted = true;
            List<String> deniedPermissions = new ArrayList<>();

            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    deniedPermissions.add(permissions[i]);
                }
            }

            if (allGranted) {
                Toast.makeText(activity, "All permissions granted!", Toast.LENGTH_SHORT).show();
                callback.onAllPermissionsGranted();
            } else {
                // Check if any denied permission is permanently denied.
                boolean permanentlyDenied = false;
                for (String perm : deniedPermissions) {
                    // If shouldShowRequestPermissionRationale returns false => permanently denied
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)) {
                        permanentlyDenied = true;
                        break;
                    }
                }
                if (permanentlyDenied) {
                    showPermanentDenialDialog();
                } else {
                    showFirstDenialDialog();
                }
            }
        }
    }

    /**
     * Checks if the app has all the required permissions granted.
     */
    private boolean hasAllPermissions() {
        for (String perm : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Shows an AlertDialog if the user has denied permissions for the first time.
     */
    private void showFirstDenialDialog() {
        if (!activity.isFinishing()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Permissions Denied")
                    .setMessage("Certain permissions are essential for this app to function.\n" +
                            "Tap GRANT to try again or EXIT to close the app.")
                    .setCancelable(false)
                    .setPositiveButton("Grant", (dialog, which) -> checkAndRequestPermissions())
                    .setNegativeButton("Exit", (dialog, which) -> activity.finish())
                    .show();
        }
    }

    /**
     * Shows an AlertDialog if the user has permanently denied the permissions.
     */
    private void showPermanentDenialDialog() {
        if (!activity.isFinishing()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Permission Permanently Denied")
                    .setMessage("Some permissions have been permanently denied. " +
                            "Please go to Settings to enable them manually.")
                    .setCancelable(false)
                    .setPositiveButton("Settings", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
                        intent.setData(uri);
                        activity.startActivity(intent);
                    })
                    .setNegativeButton("Exit", (dialog, which) -> activity.finish())
                    .show();
        }
    }

    /**
     * Callback to notify the calling Activity when all permissions have been granted.
     */
    public interface PermissionCallback {
        void onAllPermissionsGranted();
    }
}
