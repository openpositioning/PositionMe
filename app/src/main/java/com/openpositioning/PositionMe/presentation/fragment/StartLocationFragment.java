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
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.NucleusBuildingManager;

/**
 * A simple {@link Fragment} subclass. The startLocation fragment is displayed before the trajectory
 * recording starts. This fragment displays a map in which the user can adjust their location to
 * correct the PDR when it is complete
 *
 * @author Virginia Cangelosi
 * @see HomeFragment the previous fragment in the nav graph.
 * @see RecordingFragment the next fragment in the nav graph.
 * @see SensorFusion the class containing sensors and recording.
 */
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
    // Instance for managing indoor building overlays (if any)
    private NucleusBuildingManager nucleusBuildingManager;
    // Dummy variable for floor index
    private int FloorNK;

    /**
     * Public Constructor for the class.
     * Left empty as not required
     */
    public StartLocationFragment() {
        // Required empty public constructor
    }

    /**
     * {@inheritDoc}
     * The map is loaded and configured so that it displays a draggable marker for the start location
     */
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

        supportMapFragment.getMapAsync(new OnMapReadyCallback() {
            /**
             * {@inheritDoc}
             * Controls to allow scrolling, tilting, rotating and a compass view of the
             * map are enabled. A marker is added to the map with the start position and a marker
             * drag listener is generated to detect when the marker has moved to obtain the new
             * location.
             */
            @Override
            public void onMapReady(GoogleMap mMap) {
                // Set map type and UI settings
                mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                mMap.getUiSettings().setCompassEnabled(true);
                mMap.getUiSettings().setTiltGesturesEnabled(true);
                mMap.getUiSettings().setRotateGesturesEnabled(true);
                mMap.getUiSettings().setScrollGesturesEnabled(true);

                // *** FIX: Clear any existing markers so the start marker isn’t duplicated ***
                mMap.clear();

                // Create NucleusBuildingManager instance (if needed)
                nucleusBuildingManager = new NucleusBuildingManager(mMap);
                nucleusBuildingManager.getIndoorMapManager().hideMap();

                // Add a marker at the current GPS location and move the camera
                position = new LatLng(startPosition[0], startPosition[1]);
                Marker startMarker = mMap.addMarker(new MarkerOptions()
                        .position(position)
                        .title("Start Position")
                        .draggable(true));
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, zoom));

                // Drag listener for the marker to update the start position when dragged
                mMap.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
                    /**
                     * {@inheritDoc}
                     */
                    @Override
                    public void onMarkerDragStart(Marker marker) {}

                    /**
                     * {@inheritDoc}
                     * Updates the start position of the user.
                     */
                    @Override
                    public void onMarkerDragEnd(Marker marker) {
                        startPosition[0] = (float) marker.getPosition().latitude;
                        startPosition[1] = (float) marker.getPosition().longitude;
                    }

                    /**
                     * {@inheritDoc}
                     */
                    @Override
                    public void onMarkerDrag(Marker marker) {}
                });
            }
        });

        return rootView;
    }

    /**
     * {@inheritDoc}
     * Button onClick listener enabled to detect when to go to next fragment and start PDR recording.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.button = view.findViewById(R.id.startLocationDone);
        this.button.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * When button clicked the PDR recording can start and the start position is stored for
             * the {@link CorrectionFragment} to display. The {@link RecordingFragment} is loaded.
             */
            @Override
            public void onClick(View view) {
                float chosenLat = startPosition[0];
                float chosenLon = startPosition[1];

                // If the Activity is RecordingActivity
                if (requireActivity() instanceof RecordingActivity) {
                    // Start sensor recording + set the start location
                    sensorFusion.startRecording();
                    sensorFusion.setStartGNSSLatitude(startPosition);

                    // Now switch to the recording screen
                    ((RecordingActivity) requireActivity()).showRecordingScreen();

                    // If the Activity is ReplayActivity
                } else if (requireActivity() instanceof ReplayActivity) {
                    // *Do not* cast to RecordingActivity here
                    // Just call the Replay method
                    ((ReplayActivity) requireActivity()).onStartLocationChosen(chosenLat, chosenLon);

                    // Otherwise (unexpected host)
                } else {
                    // Optional: log or handle error
                    // Log.e("StartLocationFragment", "Unknown host Activity: " + requireActivity());
                }
            }
        });
    }

    /**
     * Switches the indoor map to the specified floor.
     *
     * @param floorIndex the index of the floor to switch to
     */
    private void switchFloorNU(int floorIndex) {
        FloorNK = floorIndex; // Set the current floor index
        if (nucleusBuildingManager != null) {
            // Call the switchFloor method of the IndoorMapManager to switch to the specified floor
            nucleusBuildingManager.getIndoorMapManager().switchFloor(floorIndex);
        }
    }
}
