package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/**
 * Centralises venue selection persistence so map selection and upload stay in sync.
 */
public final class VenueSelectionHelper {

    public static final String PREF_SELECTED_BUILDING = "selected_building_name";
    public static final String PREF_CURRENT_CAMPAIGN = "current_campaign";
    public static final String DEFAULT_CAMPAIGN = "nucleus";

    // Prevents this helper class from being created.
    private VenueSelectionHelper() {
    }

    // Saves the selected building and matching campaign name.
    public static void persistSelectedBuilding(Context context, String buildingName) {
        if (context == null || buildingName == null || buildingName.trim().isEmpty()) {
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        prefs.edit()
                .putString(PREF_SELECTED_BUILDING, buildingName)
                .putString(PREF_CURRENT_CAMPAIGN, resolveCampaignName(buildingName))
                .apply();
    }

    // Returns the campaign name that should be used now.
    public static String getSelectedCampaign(Context context) {
        if (context == null) {
            return DEFAULT_CAMPAIGN;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        String campaign = prefs.getString(PREF_CURRENT_CAMPAIGN, null);
        if ((campaign == null || campaign.trim().isEmpty())) {
            String selectedBuilding = prefs.getString(PREF_SELECTED_BUILDING, null);
            if (selectedBuilding != null && !selectedBuilding.trim().isEmpty()) {
                campaign = resolveCampaignName(selectedBuilding);
            }
        }
        if (campaign == null || campaign.trim().isEmpty()) {
            return DEFAULT_CAMPAIGN;
        }
        return campaign;
    }

    // Converts a building name into a simple campaign key.
    public static String resolveCampaignName(String buildingName) {
        if (buildingName == null) {
            return DEFAULT_CAMPAIGN;
        }

        String normalized = buildingName.trim().toLowerCase();
        if (normalized.contains("nucleus")) {
            return "nucleus";
        }
        if (normalized.contains("library")) {
            return "library";
        }
        if (normalized.contains("murchison")) {
            return "murchison_house";
        }

        String slug = normalized.replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? DEFAULT_CAMPAIGN : slug;
    }
}
