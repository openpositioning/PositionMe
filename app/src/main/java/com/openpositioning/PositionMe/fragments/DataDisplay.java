package com.openpositioning.PositionMe.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.TrajectoryDrawer;
import com.openpositioning.PositionMe.sensors.PositioningFusion;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.Arrays;


/**
 * Fragment responsible for displaying real-time location data on a Google Map.
 */
public class DataDisplay extends Fragment implements OnMapReadyCallback {
    // GoogleMap instance
    private GoogleMap mMap;

    // Add a field for the marker
    private Marker fusedMarker = null;
    private Marker wifiMarker = null;
    private Marker gnssMarker = null;
    private Marker pdrMarker = null;

    // Google Maps fragment
    private SupportMapFragment mapFragment;

    // Handler for periodic UI updates
    private final android.os.Handler handler = new android.os.Handler();
    private final int updateInterval = 1000; // 1 second


    // Positioning fusion system (fuses WiFi, GNSS, and PDR)
    private PositioningFusion positioningFusion = PositioningFusion.getInstance();


    // Manages indoor map display and floor info
    private IndoorMapManager indoorMapManager;


    // Spinner to select map type
    private Spinner mapTypeSpinner;


    // Utility to draw trajectory lines on the map
    TrajectoryDrawer trajectoryDrawer;

    private Marker directionMarker;


    // Runnable to periodically update WiFi-related location info on screen
    private final Runnable updateWifiLocationRunnable = new Runnable() {
        @Override
        public void run() {
            updateWifiLocationText();
            handler.postDelayed(this, updateInterval);
        }
    };


    // UI text element for status
    private TextView statusText;

    public DataDisplay() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment DataDisplay.
     */
    // TODO: Rename and change types and number of parameters
    public static DataDisplay newInstance(String param1, String param2) {
        DataDisplay fragment = new DataDisplay();
//        Bundle args = new Bundle();
//        args.putString(ARG_PARAM1, param1);
//        args.putString(ARG_PARAM2, param2);
//        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Set activity title
        getActivity().setTitle("Live Position");
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_data_display, container, false);
    }

    /**
     * Initializes the view elements once the layout is inflated.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize the SupportMapFragment and set the OnMapReadyCallback
        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager()
                .findFragmentById(R.id.mapFragmentContainer);

        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        // Reference UI elements
        statusText = view.findViewById(R.id.textView3);

        mapTypeSpinner = view.findViewById(R.id.spinner2);
        setupMapTypeSpinner();

        Log.d("Data Display", "View Created");

        // Initialize coordinate system and start fusion process
        positioningFusion.initCoordSystem();
        positioningFusion.startPeriodicFusion();
    }


    /**
     * Called when the map is ready. Starts drawing and updates.
     */
    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
//        showCurrentLocation();
        handler.post(updateWifiLocationRunnable);
        indoorMapManager = new IndoorMapManager(mMap);
        indoorMapManager.setIndicationOfIndoorMap();
        trajectoryDrawer = new TrajectoryDrawer(mMap);
    }

    /**
     * Displays the initial WiFi location on the map.
     */
    public void showCurrentLocation(){
        LatLng wifiLocation = SensorFusion.getInstance().getLatLngWifiPositioning();
        if (wifiLocation != null) {
            mMap.addMarker(new MarkerOptions()
                    .position(wifiLocation)
                    .title("WiFi Estimated Position"));
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(wifiLocation, 18f));
        }

    }


    /**
     * Updates the UI with the latest fused, WiFi, GNSS, and PDR locations.
     */
    private void updateWifiLocationText() {
        Log.d("DataDisplay", String.format("Inilization status: %s", positioningFusion.isInitialized()));
        LatLng fusedLocation = PositioningFusion.getInstance().getFusedPosition();
        int floor = SensorFusion.getInstance().getWifiFloor();
        Location locationData = SensorFusion.getInstance().getLocationData();

        Log.d("DataDisplay", "Fused Location: " + fusedLocation);

        if (fusedLocation != null) {
            trajectoryDrawer.addPoint(fusedLocation);
            float accuracy;
            if (locationData != null) {
                accuracy = locationData.getAccuracy();
            } else {
                accuracy = -1;
            }

            // Display current fused location and floor info
            String display = String.format(
                    "Location:\nLat: %.6f\nLon: %.6f\nFloor: %d\nAccuracy: %.2fm",
                    fusedLocation.latitude,
                    fusedLocation.longitude,
                    floor,
                    accuracy
            );
            statusText.setText(display);

            // Icons for fused position marker
            BitmapDescriptor blueDotIcon = vectorToBitmap(requireContext(), R.drawable.ic_blue_dot);
            BitmapDescriptor coneIcon = vectorToBitmap(requireContext(), R.drawable.ic_direction_cone);

            // Add or update fused marker
            if (fusedMarker == null) {
                // Create marker only once
                fusedMarker = mMap.addMarker(new MarkerOptions()
                        .position(fusedLocation)
                        .title("Estimated Position")
                        .icon(blueDotIcon)
                        .anchor(0.5f, 0.5f)
                        .flat(true));

                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(fusedLocation, 18f));
            } else {
                // Just move the marker
                fusedMarker.setPosition(fusedLocation);
            }

            // --- WiFi marker update ---
            LatLng wifiLocation = positioningFusion.getWifiPosition();
            if (positioningFusion.isWifiPositionSet()) {
                if (wifiMarker == null) {
                    wifiMarker = mMap.addMarker(new MarkerOptions()
                            .position(wifiLocation)
                            .title(String.format("WiFi Position %s", wifiLocation))
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)));
                } else {
                    wifiMarker.setPosition(wifiLocation);
                    wifiMarker.setTitle(String.format("WiFi Position %s", wifiLocation));
                    PositioningFusion.getInstance().wifiLocationHistory.drawOnMap(mMap, Color.YELLOW);
                }
            }

            // --- GNSS marker update ---
            LatLng gnssLocation = positioningFusion.getGnssPosition();
            if (positioningFusion.isGNSSPositionSet()) {
                if (gnssMarker == null) {
                    gnssMarker = mMap.addMarker(new MarkerOptions()
                            .position(gnssLocation)
                            .title(String.format("GNSS Position %s", gnssLocation))
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
                } else {
                    gnssMarker.setPosition(gnssLocation);
                    gnssMarker.setTitle(String.format("GNSS Position %s", gnssLocation));
                    PositioningFusion.getInstance().gnssLocationHistory.drawOnMap(mMap, Color.GREEN);
                }
            }

            // --- PDR marker update ---
            LatLng pdrLocation = positioningFusion.getPdrPosition();
            if (positioningFusion.isPDRPositionSet()) {
                if (pdrMarker == null) {
                    pdrMarker = mMap.addMarker(new MarkerOptions()
                            .position(pdrLocation)
                            .title(String.format("PDR Position: %s", Arrays.toString(positioningFusion.getPdrPositionLocal())))
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)));
                } else {
                    pdrMarker.setPosition(pdrLocation);
                    pdrMarker.setTitle(String.format("PDR Position: %s", Arrays.toString(positioningFusion.getPdrPositionLocal())));
                    PositioningFusion.getInstance().pdrLocationHistory.drawOnMap(mMap, Color.MAGENTA);
                }
            }

            // Indoor floor handling
            if (indoorMapManager != null) {
                indoorMapManager.setCurrentLocation(fusedLocation);
            }
            if (indoorMapManager.getIsIndoorMapSet()) {
                indoorMapManager.setCurrentFloor(floor, true);
            }

        } else {
            statusText.setText("Location: Unavailable");
        }



    }


    /**
     * Sets up the map type selection spinner (hybrid, normal, satellite).
     */
    private void setupMapTypeSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                requireContext(), R.array.map_types, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mapTypeSpinner.setAdapter(adapter);
        mapTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mMap == null) return;
                switch (position) {
                    case 0:
                        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                    case 1:
                        mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                        break;
                    case 2:
                        mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                        break;
                    default:
                        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                if (mMap != null) {
                    mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                }
            }
        });
    }


    /**
     * Converts a vector drawable resource to a BitmapDescriptor for map markers.
     */
    private BitmapDescriptor vectorToBitmap(Context context, @DrawableRes int vectorResId) {
        Drawable vectorDrawable = ContextCompat.getDrawable(context, vectorResId);
        vectorDrawable.setBounds(0, 0, vectorDrawable.getIntrinsicWidth(), vectorDrawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(
                vectorDrawable.getIntrinsicWidth(),
                vectorDrawable.getIntrinsicHeight(),
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(bitmap);
        vectorDrawable.draw(canvas);
        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }


    /**
     * Called when the fragment's view is destroyed; stops fusion and updates.
     */
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        positioningFusion.stopPeriodicFusion();
        handler.removeCallbacks(updateWifiLocationRunnable);
    }
}


