package com.openpositioning.PositionMe.presentation.fragment;

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

    //Parameters for particle filter tuning
    private EditTextPreference pfParticleCount;
    private EditTextPreference pfSigmaStep;
    private EditTextPreference pfSigmaThetaDeg;
    private EditTextPreference pfSigmaWifi;
    private EditTextPreference pfSigmaGnss;
    private EditTextPreference pfInitPosStd;
    private EditTextPreference pfInitHeadingDeg;
    private EditTextPreference pfResampleRatio;
    private EditTextPreference pfSigmaRegPos;
    private EditTextPreference pfSigmaRegThetaDeg;

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

        //Added sections for particle filter fine tuning
        pfParticleCount = findPreference("pf_particle_count");
        pfParticleCount.setOnBindEditTextListener(editText ->
                editText.setInputType(InputType.TYPE_CLASS_NUMBER));

        pfSigmaStep = findPreference("pf_sigma_step");
        pfSigmaStep.setOnBindEditTextListener(editText ->
                editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));

        pfSigmaThetaDeg = findPreference("pf_sigma_theta_deg");
        pfSigmaThetaDeg.setOnBindEditTextListener(editText ->
                editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));

        pfSigmaWifi = findPreference("pf_sigma_wifi");
        pfSigmaWifi.setOnBindEditTextListener(editText ->
                editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));

        pfSigmaGnss = findPreference("pf_sigma_gnss");
        pfSigmaGnss.setOnBindEditTextListener(editText ->
                editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));

        pfInitPosStd = findPreference("pf_init_pos_std");
        pfInitPosStd.setOnBindEditTextListener(editText ->
                editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));

        pfInitHeadingDeg = findPreference("pf_init_heading_deg");
        pfInitHeadingDeg.setOnBindEditTextListener(editText ->
                editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));

        pfResampleRatio = findPreference("pf_resample_ratio");
        pfResampleRatio.setOnBindEditTextListener(editText ->
                editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));

        pfSigmaRegPos = findPreference("pf_sigma_reg_pos");
        pfSigmaRegPos.setOnBindEditTextListener(editText ->
                editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));

        pfSigmaRegThetaDeg = findPreference("pf_sigma_reg_theta_deg");
        pfSigmaRegThetaDeg.setOnBindEditTextListener(editText ->
                editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));

    }
}