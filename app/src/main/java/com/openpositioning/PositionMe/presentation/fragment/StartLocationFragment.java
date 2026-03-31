package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.google.android.gms.maps.model.Polygon;
import android.widget.Toast;

// A simple {@link Fragment} subclass. The startLocation fragment is displayed before the trajectory
// recording starts. This fragment displays a map in which the user can adjust their location to
// correct the PDR when it is complete.
// @author Virginia Cangelosi
// Related: HomeFragment the previous fragment in the nav graph.
// Related: RecordingFragment the next fragment in the nav graph.
// Related: SensorFusion the class containing sensors and recording.
public class StartLocationFragment extends Fragment {

    // Button to go to next fragment and save the location
    private Button button;
    // Singleton SensorFusion class which stores data from all sensors
    private SensorFusion sensorFusion = SensorFusion.getInstance();
    // Google maps LatLng object to pass location to the map
    private LatLng position;
    // Start position of the user to be stored
    private float[] startPosition = new float[2];
    // Zoom level for the Google map
    private float zoom = 19f;
    // Instance for managing indoor building overlays using new API-based system
    private IndoorMapManager indoorMapManager;
    // Google map instance
    private GoogleMap googleMap;

    // Public Constructor for the class.
    // Left empty as not required
    public StartLocationFragment() {
        // Required empty public constructor
    }

    // {@inheritDoc}
    // The map is loaded and configured so that it displays a draggable marker for the start location
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null && activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().hide();
        }
        View rootView = inflater.inflate(R.layout.fragment_startlocation, container, false);

        // Obtain the start position from the GPS data from the SensorFusion class
        startPosition = sensorFusion.getGNSSLatitude(false);
        // If no location found, zoom the map out
        if (startPosition[0] == 0 && startPosition[1] == 0) {
            zoom = 1f;
        } else {
            zoom = 19f;
        }

        // Initialize map fragment
        SupportMapFragment supportMapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.startMap);

        if (supportMapFragment != null) {
            supportMapFragment.getMapAsync(new OnMapReadyCallback() {
                // {@inheritDoc}
                // Controls to allow scrolling, tilting, rotating and a compass view of the
                // map are enabled. A marker is added to the map with the start position and a marker
                // drag listener is generated to detect when the marker has moved to obtain the new
                // location.
                @Override
                public void onMapReady(GoogleMap mMap) {
                    googleMap = mMap;
                    
                    // Set map type and UI settings
                    mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                    mMap.getUiSettings().setCompassEnabled(true);
                    mMap.getUiSettings().setTiltGesturesEnabled(true);
                    mMap.getUiSettings().setRotateGesturesEnabled(true);
                    mMap.getUiSettings().setScrollGesturesEnabled(true);

                    // Clear any existing markers
                    mMap.clear();

                    // Initialize new IndoorMapManager (supports all buildings via API)
                    indoorMapManager = new IndoorMapManager(mMap, requireContext());
                    
                    // Load all building outlines (Nucleus, Library, FJB, Murchison)
                    indoorMapManager.addFallbackBuildings();
                    
                    // Try to fetch building data from API
                    LatLng kbCampus = new LatLng(55.9230, -3.1750);
                    indoorMapManager.fetchBuildingsFromApi(kbCampus);
                    
                    // Set up building polygon click listener
                    mMap.setOnPolygonClickListener(new GoogleMap.OnPolygonClickListener() {
                        @Override
                        public void onPolygonClick(@NonNull Polygon polygon) {
                            if (indoorMapManager != null) {
                                boolean handled = indoorMapManager.onPolygonClick(polygon);
                                if (handled) {
                                    String buildingName = indoorMapManager.getSelectedBuildingName();
                                    Toast.makeText(getContext(), "Selected: " + buildingName, Toast.LENGTH_SHORT).show();
                                }
                            }
                        }
                    });

                    // Add a marker at the current GPS location and move the camera
                    position = new LatLng(startPosition[0], startPosition[1]);
                    mMap.addMarker(new MarkerOptions()
                            .position(position)
                            .title("Start Position"));
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, zoom));
                }
            });
        }

        return rootView;
    }

    // {@inheritDoc}
    // Button onClick listener enabled to detect when to go to next fragment.
    // NOTE: Actual recording start is now deferred to RecordingFragment.
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.button = view.findViewById(R.id.startLocationDone);
        this.button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                float chosenLat = startPosition[0];
                float chosenLon = startPosition[1];

                // If the Activity is RecordingActivity
                if (requireActivity() instanceof RecordingActivity) {
                    // Clear manual start so recording can choose WiFi/GNSS dynamically.
                    sensorFusion.setStartGNSSLatitude(new float[]{0f, 0f});

                    // Navigate to the Recording Screen where user will enter ID and click Start
                    ((RecordingActivity) requireActivity()).showRecordingScreen();

                } else if (requireActivity() instanceof ReplayActivity) {
                    // Just call the Replay method
                    ((ReplayActivity) requireActivity()).onStartLocationChosen(chosenLat, chosenLon);
                }
            }
        });
    }
}


