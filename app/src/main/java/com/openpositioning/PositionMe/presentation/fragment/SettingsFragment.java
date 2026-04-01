package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;

import androidx.annotation.StringRes;
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
        bindPositiveDecimalPreference(weibergK, R.string.settings_value_positive_number);
        elevationSeconds = findPreference("elevation_seconds");
        bindPositiveIntegerPreference(elevationSeconds, R.string.settings_value_positive_integer);
        accelSamples = findPreference("accel_samples");
        bindPositiveIntegerPreference(accelSamples, R.string.settings_value_positive_integer);
        epsilon = findPreference("epsilon");
        bindNonNegativeDecimalPreference(epsilon, R.string.settings_value_non_negative_number);
        accelFilter = findPreference("accel_filter");
        bindUnitIntervalPreference(accelFilter, R.string.settings_value_unit_interval);
        wifiInterval = findPreference("wifi_interval");
        bindPositiveIntegerPreference(wifiInterval, R.string.settings_value_positive_integer);
    }

    private void bindPositiveIntegerPreference(EditTextPreference preference, @StringRes int errorResId) {
        if (preference == null) return;
        preference.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER));
        preference.setOnPreferenceChangeListener((pref, newValue) ->
                validatePositiveInteger(newValue, errorResId));
    }

    private void bindPositiveDecimalPreference(EditTextPreference preference, @StringRes int errorResId) {
        if (preference == null) return;
        preference.setOnBindEditTextListener(editText -> editText.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
        preference.setOnPreferenceChangeListener((pref, newValue) ->
                validatePositiveDecimal(newValue, errorResId));
    }

    private void bindNonNegativeDecimalPreference(EditTextPreference preference, @StringRes int errorResId) {
        if (preference == null) return;
        preference.setOnBindEditTextListener(editText -> editText.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
        preference.setOnPreferenceChangeListener((pref, newValue) ->
                validateNonNegativeDecimal(newValue, errorResId));
    }

    private void bindUnitIntervalPreference(EditTextPreference preference, @StringRes int errorResId) {
        if (preference == null) return;
        preference.setOnBindEditTextListener(editText -> editText.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
        preference.setOnPreferenceChangeListener((pref, newValue) ->
                validateUnitInterval(newValue, errorResId));
    }

    private boolean validatePositiveInteger(Object newValue, @StringRes int errorResId) {
        try {
            return Integer.parseInt(String.valueOf(newValue).trim()) > 0 || rejectValue(errorResId);
        } catch (RuntimeException e) {
            return rejectValue(errorResId);
        }
    }

    private boolean validatePositiveDecimal(Object newValue, @StringRes int errorResId) {
        try {
            return Float.parseFloat(String.valueOf(newValue).trim()) > 0f || rejectValue(errorResId);
        } catch (RuntimeException e) {
            return rejectValue(errorResId);
        }
    }

    private boolean validateNonNegativeDecimal(Object newValue, @StringRes int errorResId) {
        try {
            return Float.parseFloat(String.valueOf(newValue).trim()) >= 0f || rejectValue(errorResId);
        } catch (RuntimeException e) {
            return rejectValue(errorResId);
        }
    }

    private boolean validateUnitInterval(Object newValue, @StringRes int errorResId) {
        try {
            float value = Float.parseFloat(String.valueOf(newValue).trim());
            return (value >= 0f && value <= 1f) || rejectValue(errorResId);
        } catch (RuntimeException e) {
            return rejectValue(errorResId);
        }
    }

    private boolean rejectValue(@StringRes int errorResId) {
        Toast.makeText(requireContext(), getString(errorResId), Toast.LENGTH_SHORT).show();
        return false;
    }
}
