package com.openpositioning.PositionMe.fragments;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.button.MaterialButton;
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
public class HomeFragment extends Fragment implements OnMapReadyCallback {

    // Interactive UI elements to navigate to other fragments
    private MaterialButton goToInfo;
    private MaterialButton start;
    private MaterialButton measurements;
    private MaterialButton files;

    private MaterialButton dataDisplay;

    private TextView gnssStatusTextView;

    // For the map
    private GoogleMap mMap;
    private SupportMapFragment mapFragment;

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

        // Button to navigate to the Data Display fragment
        this.dataDisplay = getView().findViewById(R.id.indoorButton);
        this.dataDisplay.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * Navigate to the {@link DataDisplay} using AndroidX Jetpack.
             */
            @Override
            public void onClick(View view) {
                NavDirections action = HomeFragmentDirections.actionHomeFragmentToDataDisplay();
                Navigation.findNavController(view).navigate(action);
            }
        });

        // TextView to display GNSS disabled message
        gnssStatusTextView = view.findViewById(R.id.gnssStatusTextView);

        // Locate the MapFragment nested in this fragment
        mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.mapFragmentContainer);
        if (mapFragment != null) {
            // Asynchronously initialize the map
            mapFragment.getMapAsync(this);
        }
    }
    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        checkAndUpdatePermissions();
    }

    @Override
    public void onResume() {
        super.onResume();
        checkAndUpdatePermissions();
    }

    private boolean isGnssEnabled() {
        LocationManager locationManager =
                (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
        // Checks both GPS and network provider. Adjust as needed.
        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        return (gpsEnabled || networkEnabled);
    }
    private void showEdinburghAndMessage(String message) {
        gnssStatusTextView.setText(message);
        gnssStatusTextView.setVisibility(View.VISIBLE);

        LatLng edinburghLatLng = new LatLng(55.944425, -3.188396);
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(edinburghLatLng, 15f));
        mMap.addMarker(new MarkerOptions()
                .position(edinburghLatLng)
                .title("University of Edinburgh"));
    }

    private void checkAndUpdatePermissions() {

        if (mMap == null) {
            return;
        }

        // Check if GNSS/Location is enabled
        boolean gnssEnabled = isGnssEnabled();
        if (gnssEnabled) {
            // Hide the "GNSS Disabled" message
            gnssStatusTextView.setVisibility(View.GONE);

            // Check runtime permissions for location
            if (ActivityCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(
                            requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED) {

                // Enable the MyLocation layer of Google Map
                mMap.setMyLocationEnabled(true);

                // Optionally move the camera to last known or default location:
                //   (You could retrieve it from FusedLocationProvider or similar).
                // Here, just leaving it on default.
                // If you want to center on the user as soon as it loads, do something like:
                /*
                FusedLocationProviderClient fusedLocationClient =
                    LocationServices.getFusedLocationProviderClient(requireContext());
                fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                    if (location != null) {
                        LatLng currentLatLng = new LatLng(location.getLatitude(), location.getLongitude());
                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f));
                    }
                });
                */
            } else {
                // If no permission, simply show a default location or prompt for permissions
                showEdinburghAndMessage("Permission not granted. Please enable in settings.");
            }
        } else {
            // If GNSS is disabled, show University of Edinburgh + message
            showEdinburghAndMessage("GNSS is disabled. Please enable in settings.");
        }


    }
}