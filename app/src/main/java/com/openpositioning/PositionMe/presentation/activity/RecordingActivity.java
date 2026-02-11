package com.openpositioning.PositionMe.presentation.activity;

import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.StartLocationFragment;
import com.openpositioning.PositionMe.presentation.fragment.RecordingFragment;
import com.openpositioning.PositionMe.presentation.fragment.CorrectionFragment;
import com.openpositioning.PositionMe.presentation.fragment.VenueManager;  // 🆕 导入


/**
 * The RecordingActivity manages the recording flow of the application, guiding the user through a sequence
 * of screens for location selection, recording, and correction before finalizing the process.
 * <p>
 * This activity follows a structured workflow:
 * <ol>
 *     <li>StartLocationFragment - Allows users to select their starting location.</li>
 *     <li>RecordingFragment - Handles the recording process and contains a TrajectoryMapFragment.</li>
 *     <li>CorrectionFragment - Enables users to review and correct recorded data before completion.</li>
 * </ol>
 * <p>
 * The activity ensures that the screen remains on during the recording process to prevent interruptions.
 * It also provides fragment transactions for seamless navigation between different stages of the workflow.
 * <p>
 * 🆕 UPDATE: Now supports indoor venue tracking. If a venue is selected in HomeFragment,
 * this activity will pass venue information (venue_id, venue_name, floor) through all fragments
 * to ensure trajectory data includes indoor location context.
 * <p>
 * This class is referenced in various fragments such as HomeFragment, StartLocationFragment,
 * RecordingFragment, and CorrectionFragment to control navigation through the recording flow.
 *
 * @see StartLocationFragment The first step in the recording process where users select their starting location.
 * @see RecordingFragment Handles data recording and map visualization.
 * @see CorrectionFragment Allows users to review and make corrections before finalizing the process.
 * @see VenueManager Manages selected venue information across the app.
 * @see com.openpositioning.PositionMe.R.layout#activity_recording The associated layout for this activity.
 *
 * @author ShuGu
 * @author Your Team (venue support)
 */

public class RecordingActivity extends AppCompatActivity {

    private static final String TAG = "RecordingActivity";

    // 🆕 Venue manager for indoor location tracking
    private VenueManager venueManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recording);

        // 🆕 Initialize VenueManager
        venueManager = VenueManager.getInstance(this);

        // 🆕 Log venue information
        if (venueManager.hasSelectedVenue()) {
            Log.d(TAG, "========================================");
            Log.d(TAG, "🏢 Recording with venue information:");
            Log.d(TAG, "   Venue ID: " + venueManager.getSelectedVenueId());
            Log.d(TAG, "   Venue Name: " + venueManager.getSelectedVenueName());
            Log.d(TAG, "   Floor: " + venueManager.getSelectedFloor());
            Log.d(TAG, "========================================");
        } else {
            Log.d(TAG, "📍 Recording outdoors (no venue selected)");
        }

        if (savedInstanceState == null) {
            showStartLocationScreen(); // Start with the user selecting the start location
        }

        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /**
     * Show the StartLocationFragment (beginning of flow).
     * 🆕 Now passes venue information via Bundle arguments.
     */
    public void showStartLocationScreen() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        // 🆕 Create fragment with venue information
        StartLocationFragment fragment = new StartLocationFragment();
        Bundle args = createVenueBundle();
        fragment.setArguments(args);

        ft.replace(R.id.mainFragmentContainer, fragment);
        ft.commit();
    }

    /**
     * Show the RecordingFragment, which contains the TrajectoryMapFragment internally.
     * 🆕 Now passes venue information via Bundle arguments.
     */
    public void showRecordingScreen() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        // 🆕 Create fragment with venue information
        RecordingFragment fragment = new RecordingFragment();
        Bundle args = createVenueBundle();
        fragment.setArguments(args);

        ft.replace(R.id.mainFragmentContainer, fragment);
        ft.addToBackStack(null);
        ft.commit();
    }

    /**
     * Show the CorrectionFragment after the user stops recording.
     * 🆕 Now passes venue information via Bundle arguments.
     */
    public void showCorrectionScreen() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        // 🆕 Create fragment with venue information
        CorrectionFragment fragment = new CorrectionFragment();
        Bundle args = createVenueBundle();
        fragment.setArguments(args);

        ft.replace(R.id.mainFragmentContainer, fragment);
        ft.addToBackStack(null);
        ft.commit();
    }

    /**
     * Finish the Activity (or do any final steps) once corrections are done.
     */
    public void finishFlow() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        finish();
    }

    // 🆕 ========== VENUE SUPPORT METHODS ==========

    /**
     * Create a Bundle containing venue information to pass to fragments.
     * @return Bundle with venue_id, venue_name, venue_floor, and has_venue
     */
    private Bundle createVenueBundle() {
        Bundle args = new Bundle();
        args.putBoolean("has_venue", venueManager.hasSelectedVenue());
        args.putString("venue_id", venueManager.getSelectedVenueId());
        args.putString("venue_name", venueManager.getSelectedVenueName());
        args.putString("venue_floor", venueManager.getSelectedFloor());
        return args;
    }

    /**
     * Get venue ID for fragments that need it.
     * @return Venue ID or empty string if no venue selected
     */
    public String getSelectedVenueId() {
        return venueManager.getSelectedVenueId();
    }

    /**
     * Get venue name for fragments that need it.
     * @return Venue name or "No Venue Selected"
     */
    public String getSelectedVenueName() {
        return venueManager.getSelectedVenueName();
    }

    /**
     * Get selected floor for fragments that need it.
     * @return Floor name or empty string if no venue selected
     */
    public String getSelectedFloor() {
        return venueManager.getSelectedFloor();
    }

    /**
     * Check if a venue is selected.
     * @return true if venue is selected, false otherwise
     */
    public boolean hasSelectedVenue() {
        return venueManager.hasSelectedVenue();
    }

    /**
     * Get venue manager instance.
     * @return VenueManager instance
     */
    public VenueManager getVenueManager() {
        return venueManager;
    }
}