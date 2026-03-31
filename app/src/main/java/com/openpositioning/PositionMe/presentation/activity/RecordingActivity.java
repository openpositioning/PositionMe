package com.openpositioning.PositionMe.presentation.activity;

import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.CorrectionFragment;
import com.openpositioning.PositionMe.presentation.fragment.RecordingFragment;
import com.openpositioning.PositionMe.presentation.fragment.StartLocationFragment;
import com.openpositioning.PositionMe.presentation.fragment.VenueManager;

/**
 * Controls the recording flow: start location -> recording -> correction.
 */
public class RecordingActivity extends AppCompatActivity {

    private static final String TAG = "RecordingActivity";

    private VenueManager venueManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recording);

        venueManager = VenueManager.getInstance(this);

        if (venueManager.hasSelectedVenue()) {
            Log.d(TAG, "Recording with venue: id=" + venueManager.getSelectedVenueId()
                    + ", name=" + venueManager.getSelectedVenueName()
                    + ", floor=" + venueManager.getSelectedFloor());
        } else {
            Log.d(TAG, "Recording without venue context");
        }

        if (savedInstanceState == null) {
            showStartLocationScreen();
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void showStartLocationScreen() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        StartLocationFragment fragment = new StartLocationFragment();
        fragment.setArguments(createVenueBundle());

        ft.replace(R.id.mainFragmentContainer, fragment);
        ft.commit();
    }

    public void showRecordingScreen() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        RecordingFragment fragment = new RecordingFragment();
        fragment.setArguments(createVenueBundle());

        ft.replace(R.id.mainFragmentContainer, fragment);
        ft.addToBackStack(null);
        ft.commit();
    }

    public void showCorrectionScreen() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        CorrectionFragment fragment = new CorrectionFragment();
        fragment.setArguments(createVenueBundle());

        ft.replace(R.id.mainFragmentContainer, fragment);
        ft.addToBackStack(null);
        ft.commit();
    }

    public void finishFlow() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        finish();
    }

    private Bundle createVenueBundle() {
        Bundle args = new Bundle();
        args.putBoolean("has_venue", venueManager.hasSelectedVenue());
        args.putString("venue_id", venueManager.getSelectedVenueId());
        args.putString("venue_name", venueManager.getSelectedVenueName());
        args.putString("venue_floor", venueManager.getSelectedFloor());
        return args;
    }

    public String getSelectedVenueId() {
        return venueManager.getSelectedVenueId();
    }

    public String getSelectedVenueName() {
        return venueManager.getSelectedVenueName();
    }

    public String getSelectedFloor() {
        return venueManager.getSelectedFloor();
    }

    public boolean hasSelectedVenue() {
        return venueManager.hasSelectedVenue();
    }

    public VenueManager getVenueManager() {
        return venueManager;
    }
}