package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

public final class ThemePreferences {

    public static final String KEY_THEME_MODE = "theme_mode";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_SYSTEM = "system";

    private ThemePreferences() {
        // Utility class
    }

    public static void applyThemeFromPreferences(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String themeMode;
        if (prefs.contains(KEY_THEME_MODE)) {
            themeMode = prefs.getString(KEY_THEME_MODE, THEME_SYSTEM);
        } else if (prefs.contains("dark_mode")) {
            // Backward compatibility: migrate old boolean dark_mode if present.
            boolean darkModeEnabled = prefs.getBoolean("dark_mode", false);
            themeMode = darkModeEnabled ? THEME_DARK : THEME_LIGHT;
            prefs.edit().putString(KEY_THEME_MODE, themeMode).apply();
        } else {
            // Fresh install or unset preference: follow system by default.
            themeMode = THEME_SYSTEM;
            prefs.edit().putString(KEY_THEME_MODE, themeMode).apply();
        }

        applyThemeMode(themeMode);
    }

    public static void applyThemeMode(String themeMode) {
        if (THEME_DARK.equals(themeMode)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else if (THEME_LIGHT.equals(themeMode)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }
}
