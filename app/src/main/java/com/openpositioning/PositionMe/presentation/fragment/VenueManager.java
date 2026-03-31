package com.openpositioning.PositionMe.presentation.fragment;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Singleton class to manage selected venue information across the app.
 * This ensures that the selected venue persists across fragments and activities.
 *
 * Usage:
 * - VenueManager.getInstance(context).setSelectedVenue(buildingName, buildingId, floor);
 * - String venueName = VenueManager.getInstance(context).getSelectedVenueName();
 *
 * @author Your Team
 */
public class VenueManager {
    private static VenueManager instance;
    private static final String PREFS_NAME = "VenuePreferences";
    private static final String KEY_VENUE_NAME = "selected_venue_name";
    private static final String KEY_VENUE_ID = "selected_venue_id";
    private static final String KEY_VENUE_FLOOR = "selected_venue_floor";

    private SharedPreferences prefs;

    private VenueManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Get singleton instance of VenueManager
     * @param context Application context
     * @return VenueManager instance
     */
    public static synchronized VenueManager getInstance(Context context) {
        if (instance == null) {
            instance = new VenueManager(context);
        }
        return instance;
    }

    /**
     * Set the selected venue for data collection
     * @param venueName Human-readable name of the venue (e.g., "Murchison House")
     * @param venueId Unique identifier for the venue (e.g., building ID from API)
     * @param floor Current floor name (e.g., "Ground Floor", "Floor 1")
     */
    public void setSelectedVenue(String venueName, String venueId, String floor) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_VENUE_NAME, venueName);
        editor.putString(KEY_VENUE_ID, venueId);
        editor.putString(KEY_VENUE_FLOOR, floor);
        editor.apply();
    }

    /**
     * Get the selected venue name
     * @return Venue name or "No Venue Selected" if none selected
     */
    public String getSelectedVenueName() {
        return prefs.getString(KEY_VENUE_NAME, "No Venue Selected");
    }

    /**
     * Get the selected venue ID (for API submission)
     * @return Venue ID or empty string if none selected
     */
    public String getSelectedVenueId() {
        return prefs.getString(KEY_VENUE_ID, "");
    }

    /**
     * Get the current floor
     * @return Floor name or empty string if none selected
     */
    public String getSelectedFloor() {
        return prefs.getString(KEY_VENUE_FLOOR, "");
    }

    /**
     * Check if a venue has been selected
     * @return true if venue is selected, false otherwise
     */
    public boolean hasSelectedVenue() {
        return !getSelectedVenueId().isEmpty();
    }

    /**
     * Clear the selected venue (e.g., when user goes outside)
     * @deprecated Use clearVenueSelection() instead
     */
    @Deprecated
    public void clearSelectedVenue() {
        clearVenueSelection();
    }

    /**
     * Clear the selected venue (e.g., when user goes outside)
     */
    public void clearVenueSelection() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(KEY_VENUE_NAME);
        editor.remove(KEY_VENUE_ID);
        editor.remove(KEY_VENUE_FLOOR);
        editor.apply();
    }

    /**
     * Get full venue information as a formatted string
     * @return Formatted string like "Murchison House - Ground Floor" or "No Venue Selected"
     */
    public String getVenueDisplayText() {
        if (!hasSelectedVenue()) {
            return "No Venue Selected";
        }

        String name = getSelectedVenueName();
        String floor = getSelectedFloor();

        if (floor.isEmpty()) {
            return name;
        } else {
            return name + " - " + floor;
        }
    }
}