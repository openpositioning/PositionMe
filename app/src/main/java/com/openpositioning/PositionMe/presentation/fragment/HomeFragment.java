package com.openpositioning.PositionMe.presentation.fragment;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
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

import com.google.android.material.button.MaterialButton;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;

/**
 * HomeFragment - Main screen with venue selection display
 *
 * Features:
 * - Display current selected venue for data collection
 * - Navigate to other fragments
 * - Show map with current location
 *
 * Integration with venue selection:
 * - Displays venue selected in MapsFragment
 * - Updates automatically when venue changes
 *
 * @author Mate Stodulka
 * @author Your Team (venue display integration)
 */
public class HomeFragment extends Fragment implements OnMapReadyCallback {

    // Interactive UI elements
    private MaterialButton goToInfo;
    private Button start;
    private Button measurements;
    private Button files;
    private Button indoorButton;
    private TextView gnssStatusTextView;

    // Venue display elements
    private androidx.cardview.widget.CardView venueCard;
    private TextView venueNameTextView;
    private TextView venueFloorTextView;
    private MaterialButton changeVenueButton;
    private MaterialButton clearVenueButton;

    // Map components
    private GoogleMap mMap;
    private SupportMapFragment mapFragment;

    public HomeFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    /**
     * {@inheritDoc}
     * Ensure the action bar is shown at the top of the screen.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        ((AppCompatActivity) getActivity()).getSupportActionBar().show();
        View rootView = inflater.inflate(R.layout.fragment_home, container, false);
        getActivity().setTitle("Home");
        return rootView;
    }

    /**
     * Initialize UI elements and set onClick actions
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize existing buttons
        setupNavigationButtons(view);

        // Initialize venue display elements
        setupVenueDisplay(view);

        // Initialize map
        setupMap(view);
    }

    /**
     * Setup navigation buttons
     */
    private void setupNavigationButtons(View view) {
        // Sensor Info button
        goToInfo = view.findViewById(R.id.sensorInfoButton);
        goToInfo.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToInfoFragment();
            Navigation.findNavController(v).navigate(action);
        });

        // Start/Stop Recording button
        start = view.findViewById(R.id.startStopButton);
        start.setEnabled(!PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean("permanentDeny", false));
        start.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), RecordingActivity.class);
            startActivity(intent);
            ((AppCompatActivity) getActivity()).getSupportActionBar().hide();
        });

        // Measurements button
        measurements = view.findViewById(R.id.measurementButton);
        measurements.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToMeasurementsFragment();
            Navigation.findNavController(v).navigate(action);
        });

        // Files button
        files = view.findViewById(R.id.filesButton);
        files.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToFilesFragment();
            Navigation.findNavController(v).navigate(action);
        });

        // Indoor positioning button
        indoorButton = view.findViewById(R.id.indoorButton);
        if (indoorButton != null) {
            indoorButton.setOnClickListener(v -> {
                Navigation.findNavController(v).navigate(R.id.action_homeFragment_to_mapsFragment);
            });
        }

        // GNSS status text
        gnssStatusTextView = view.findViewById(R.id.gnssStatusTextView);
    }

    /**
     * Initializes venue widgets.
     */
    private void setupVenueDisplay(View view) {
        venueCard = view.findViewById(R.id.venueCard);
        venueNameTextView = view.findViewById(R.id.venueNameTextView);
        venueFloorTextView = view.findViewById(R.id.venueFloorTextView);
        changeVenueButton = view.findViewById(R.id.changeVenueButton);
        clearVenueButton = view.findViewById(R.id.clearVenueButton);

        // Setup change venue button
        if (changeVenueButton != null) {
            changeVenueButton.setOnClickListener(v -> {
                // Navigate to MapsFragment to select a different venue
                Navigation.findNavController(v).navigate(R.id.action_homeFragment_to_mapsFragment);
            });
        }

        // Setup clear venue button
        if (clearVenueButton != null) {
            clearVenueButton.setOnClickListener(v -> {
                VenueManager.getInstance(requireContext()).clearVenueSelection();
                updateVenueDisplay();
                android.widget.Toast.makeText(getContext(),
                        "Venue selection cleared",
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        }

        // Update venue display with current selection
        updateVenueDisplay();
    }

    /**
     * Refreshes the venue widgets based on current selection.
     */
    private void updateVenueDisplay() {
        VenueManager venueManager = VenueManager.getInstance(requireContext());

        if (venueManager.hasSelectedVenue()) {
            // Show venue information
            if (venueCard != null) {
                venueCard.setVisibility(View.VISIBLE);
            }

            if (venueNameTextView != null) {
                venueNameTextView.setText(venueManager.getSelectedVenueName());
            }

            if (venueFloorTextView != null) {
                String floor = venueManager.getSelectedFloor();
                if (!floor.isEmpty()) {
                    venueFloorTextView.setText("Floor: " + floor);
                    venueFloorTextView.setVisibility(View.VISIBLE);
                } else {
                    venueFloorTextView.setVisibility(View.GONE);
                }
            }

            if (changeVenueButton != null) {
                changeVenueButton.setVisibility(View.VISIBLE);
            }

            if (clearVenueButton != null) {
                clearVenueButton.setVisibility(View.VISIBLE);
            }
        } else {
            // No venue selected - hide the card or show "no venue" message
            if (venueCard != null) {
                venueCard.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Setup map fragment
     */
    private void setupMap(View view) {
        mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.mapFragmentContainer);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
    }

    /**
     * Callback triggered when the Google Map is ready
     */
    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        checkAndUpdatePermissions();
    }

    @Override
    public void onResume() {
        super.onResume();
        checkAndUpdatePermissions();

        // Refresh venue display when returning to this fragment
        updateVenueDisplay();
    }

    /**
     * Check if GNSS/Location is enabled on the device
     */
    private boolean isGnssEnabled() {
        LocationManager locationManager =
                (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        return (gpsEnabled || networkEnabled);
    }

    /**
     * Move the map to the University of Edinburgh and display a message
     */
    private void showEdinburghAndMessage(String message) {
        gnssStatusTextView.setText(message);
        gnssStatusTextView.setVisibility(View.VISIBLE);

        LatLng edinburghLatLng = new LatLng(55.944425, -3.188396);
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(edinburghLatLng, 15f));
        mMap.addMarker(new MarkerOptions()
                .position(edinburghLatLng)
                .title("University of Edinburgh"));
    }

    /**
     * Check and update location permissions
     */
    private void checkAndUpdatePermissions() {
        if (mMap == null) {
            return;
        }

        boolean gnssEnabled = isGnssEnabled();
        if (gnssEnabled) {
            gnssStatusTextView.setVisibility(View.GONE);

            if (ActivityCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(
                            requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED) {

                mMap.setMyLocationEnabled(true);
            } else {
                showEdinburghAndMessage("Permission not granted. Please enable in settings.");
            }
        } else {
            showEdinburghAndMessage("GNSS is disabled. Please enable in settings.");
        }
    }
}
