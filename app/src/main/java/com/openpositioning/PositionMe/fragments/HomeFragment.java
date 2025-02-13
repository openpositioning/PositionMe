package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.preference.PreferenceManager;

import com.openpositioning.PositionMe.R;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

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
    private FloatingActionButton goToInfo;
    private Button start;
    private Button measurements;
    private Button files;



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
     * Ensure the action bar is shown at the top of the screen. Set the title visible to Home.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        ((AppCompatActivity)getActivity()).getSupportActionBar().show();
        View rootView =  inflater.inflate(R.layout.fragment_home, container, false);
        getActivity().setTitle("Home");
        return rootView;
    }

    /**
     * {@inheritDoc}
     * Initialise UI elements and set onClick actions for the buttons.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Button to navigate to Sensor Info display fragment
        this.goToInfo = getView().findViewById(R.id.sensorInfoButton);
        this.goToInfo.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * Navigate to the {@link InfoFragment} using AndroidX Jetpack
             */
            @Override
            public void onClick(View view) {
                NavDirections action = HomeFragmentDirections.actionHomeFragmentToInfoFragment();
                Navigation.findNavController(view).navigate(action);
            }
        });

        // Button to start a recording session. Only enable if all relevant permissions are granted.
        this.start = getView().findViewById(R.id.startStopButton);
        start.setEnabled(!PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean("permanentDeny", false));
        this.start.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * Navigate to the {@link StartLocationFragment} using AndroidX Jetpack. Hides the
             * action bar so the map appears on the full screen.
             */
            @Override
            public void onClick(View view) {
                NavDirections action = HomeFragmentDirections.actionHomeFragmentToStartLocationFragment();
                Navigation.findNavController(view).navigate(action);
                //Show action bar
                ((AppCompatActivity)getActivity()).getSupportActionBar().hide();
            }
        });

        // Button to navigate to display of current sensor recording values
        this.measurements = getView().findViewById(R.id.measurementButton);
        this.measurements.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * Navigate to the {@link MeasurementsFragment} using AndroidX Jetpack.
             */
            @Override
            public void onClick(View view) {
                NavDirections action = HomeFragmentDirections.actionHomeFragmentToMeasurementsFragment();
                Navigation.findNavController(view).navigate(action);
            }
        });

        // Button to navigate to the file system showing previous recordings
        this.files = getView().findViewById(R.id.filesButton);
        this.files.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * Navigate to the {@link FilesFragment} using AndroidX Jetpack.
             */
            @Override
            public void onClick(View view) {
                NavDirections action = HomeFragmentDirections.actionHomeFragmentToFilesFragment();
                Navigation.findNavController(view).navigate(action);
            }
        });
    }
}