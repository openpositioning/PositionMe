package com.openpositioning.PositionMe.fragments;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
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

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;

/**
 * Home Fragment displays the main buttons to navigate through the app.
 * The fragment has 4 buttons to:
 * 1) Start the recording process
 * 2) Navigate to the sensor information screen to have more detail
 * 3) Navigate to the measurements screen to check values in real time
 * 4) Navigate to the files page to upload trajectories and download from the cloud.
 *
 * @see FilesFragment The Files Fragment
 * @see InfoFragment Sensor information Fragment
 * @see MeasurementsFragment The measurements Fragment
 * @see StartLocationFragment The First fragment to start recording
 *
 * @author Michal Dvorak, Virginia Cangelosi
 */
public class HomeFragment extends Fragment {

    private Button startStopButton;
    private Button sensorInfoButton;
    private Button measurementButton;
    private Button filesButton;
    private SensorFusion sensorFusion;
    private static final String TAG = "HomeFragment";

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
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    /**
     * {@inheritDoc}
     * Initialise UI elements and set onClick actions for the buttons.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        sensorFusion = SensorFusion.getInstance();
        
        startStopButton = view.findViewById(R.id.startStopButton);
        sensorInfoButton = view.findViewById(R.id.sensorInfoButton);
        measurementButton = view.findViewById(R.id.measurementButton);
        filesButton = view.findViewById(R.id.filesButton);
        
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
        
        startStopButton.setEnabled(!PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean("permanentDeny", false));
    }
}