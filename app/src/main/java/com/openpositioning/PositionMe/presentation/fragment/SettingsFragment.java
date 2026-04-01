package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.text.InputType;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.LoginManager;

/**
 * SettingsFragment that inflates and displays the preferences (settings). Sets type for numeric
 * only fields.
 *
 * @see HomeFragment the return fragment when leaving the settings.
 * @author Mate Stodulka
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    // EditTextPreference fields with numeric only inputs accepted.
    private EditTextPreference weibergK;
    private EditTextPreference elevationSeconds;
    private EditTextPreference accelSamples;
    private EditTextPreference epsilon;
    private EditTextPreference kalmanPredictedNoise;
    private EditTextPreference kalmanBiasNoise;
    private EditTextPreference kalmanMeasurementNoise;
    private EditTextPreference wifiInterval;
    private LoginManager loginManager;

    /**
     * {@inheritDoc} Sets the relevant numeric type for the preferences that should not take string
     * values.
     */
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);
        loginManager = LoginManager.getInstance();
        getActivity().setTitle("Settings - " + loginManager.getUsername());

        weibergK = findPreference("weiberg_k");
        weibergK.setOnBindEditTextListener(
                editText ->
                        editText.setInputType(
                                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));

        elevationSeconds = findPreference("elevation_seconds");
        elevationSeconds.setOnBindEditTextListener(
                editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER));

        accelSamples = findPreference("accel_samples");
        accelSamples.setOnBindEditTextListener(
                editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER));

        epsilon = findPreference("epsilon");
        epsilon.setOnBindEditTextListener(
                editText ->
                        editText.setInputType(
                                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));

        wifiInterval = findPreference("wifi_interval");
        wifiInterval.setOnBindEditTextListener(
                editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER));

        kalmanPredictedNoise = findPreference("kalman_pred_noise_std_dev");
        kalmanPredictedNoise.setOnBindEditTextListener(
                editText ->
                        editText.setInputType(
                                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));

        kalmanBiasNoise = findPreference("kalman_pred_bias_std_dev");
        kalmanBiasNoise.setOnBindEditTextListener(
                editText ->
                        editText.setInputType(
                                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));

        kalmanMeasurementNoise = findPreference("kalman_noise");
        kalmanMeasurementNoise.setOnBindEditTextListener(
                editText ->
                        editText.setInputType(
                                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
    }
}
