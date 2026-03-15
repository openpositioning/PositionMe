package com.openpositioning.PositionMe.utils;

import android.Manifest;
import android.annotation.SuppressLint;

/**
 * Centralised definitions of 'dangerous' permissions required by PositionMe.
 *
 * <p>Note that some permissions are unavailable in certain Android SDK versions.
 *
 * @see PermissionManager
 */
public class PermissionConstants {
    public static final String[] PERMISSIONS_LOCATION =
            new String[] {
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION
            };

    @SuppressLint("InlinedApi")
    public static final String[] PERMISSIONS_API_29 =
            new String[] {Manifest.permission.ACTIVITY_RECOGNITION};

    @SuppressLint("InlinedApi")
    public static final String[] PERMISSIONS_API_33 =
            new String[] {Manifest.permission.NEARBY_WIFI_DEVICES};

    @SuppressLint("InlinedApi")
    public static final String[] PERMISSIONS_API_34 =
            new String[] {Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED};

    @SuppressLint("InlinedApi")
    public static final String[] PERMISSIONS_STORAGE_MODERN =
            new String[] {
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            };

    public static final String[] PERMISSIONS_STORAGE_LEGACY =
            new String[] {
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            };
}
