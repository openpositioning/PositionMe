package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.text.InputType;

import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

import com.openpositioning.PositionMe.R;

/**
 * SettingsFragment that inflates and displays the preferences (settings).
 * Sets type for numeric only fields.
 *
 * @see HomeFragment the return fragment when leaving the settings.
 *
 * @author Mate Stodulka
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    // EditTextPreference fields with numeric only inputs accepted.
    private EditTextPreference weibergK;
    private EditTextPreference elevationSeconds;
    private EditTextPreference accelSamples;
    private EditTextPreference epsilon;
    private EditTextPreference accelFilter;
    private EditTextPreference wifiInterval;

    /**
     * {@inheritDoc}
     * Sets the relevant numeric type for the preferences that should not take string values.
     */
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);
        getActivity().setTitle("Settings");
        weibergK = findPreference("weiberg_k");
        weibergK.setOnBindEditTextListener(editText -> editText.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
        elevationSeconds = findPreference("elevation_seconds");
        elevationSeconds.setOnBindEditTextListener(editText -> editText.setInputType(
                InputType.TYPE_CLASS_NUMBER));
        accelSamples = findPreference("accel_samples");
        accelSamples.setOnBindEditTextListener(editText -> editText.setInputType(
                InputType.TYPE_CLASS_NUMBER));
        epsilon = findPreference("epsilon");
        epsilon.setOnBindEditTextListener(editText -> editText.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
        accelFilter = findPreference("accel_filter");
        accelFilter.setOnBindEditTextListener(editText -> editText.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
        wifiInterval = findPreference("wifi_interval");
        wifiInterval.setOnBindEditTextListener(editText -> editText.setInputType(
                InputType.TYPE_CLASS_NUMBER));

    }
}