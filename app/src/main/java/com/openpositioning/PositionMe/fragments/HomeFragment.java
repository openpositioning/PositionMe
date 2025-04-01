package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;
import com.openpositioning.PositionMe.R;

/**
 * A simple {@link Fragment} subclass. The home fragment is the start screen of the application.
 * The home fragment acts as a hub for all other fragments, with buttons and icons for navigation.
 * The default screen when opening the application
 *
 * @see RecordingFragment
 * @see FilesFragment
 * @see MeasurementsFragment
 * @see SettingsFragment
 *
 * @author Mate Stodulka
 */
public class HomeFragment extends Fragment {

    // Interactive UI elements to navigate to other fragments
    private MaterialButton sensorInfoButton;
    private Button startStopButton;
    private Button measurementButton;
    private Button filesButton;

    /**
     * Default empty constructor, unused.
     */
    public HomeFragment() {
        // Required empty public constructor
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    /**
     * {@inheritDoc}
     * Initialise UI elements and set onClick actions for the buttons.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 获取各个按钮
        startStopButton = view.findViewById(R.id.startStopButton);
        sensorInfoButton = view.findViewById(R.id.sensorInfoButton);
        measurementButton = view.findViewById(R.id.measurementButton);
        filesButton = view.findViewById(R.id.filesButton);
        
        // 设置点击监听器
        startStopButton.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToStartLocationFragment();
            Navigation.findNavController(v).navigate(action);
        });
        
        sensorInfoButton.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToInfoFragment();
            Navigation.findNavController(v).navigate(action);
        });
        
        measurementButton.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToMeasurementsFragment();
            Navigation.findNavController(v).navigate(action);
        });
        
        filesButton.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToFilesFragment();
            Navigation.findNavController(v).navigate(action);
        });
        
        // 设置开始按钮的启用状态
        startStopButton.setEnabled(!PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean("permanentDeny", false));
    }
}