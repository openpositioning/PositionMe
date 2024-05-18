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

import com.google.android.gms.maps.model.LatLngBounds;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.util.ArrayList;

/**
 * A simple {@link Fragment} subclass. The startLocation fragment is displayed before the trajectory
 * recording starts. This fragment displays a map in which the user can adjust their location to
 * correct the PDR when it is complete
 *
 * @see HomeFragment the previous fragment in the nav graph.
 * @see RecordingFragment the next fragment in the nav graph.
 * @see SensorFusion the class containing sensors and recording.
 *
 * @author Virginia Cangelosi
 */
public class StartLocationFragment extends Fragment {

    //Button to go to next fragment and save the location
    private Button button;
    //Singleton SesnorFusion class which stores data from all sensors
    private SensorFusion sensorFusion = SensorFusion.getInstance();
    //Google maps LatLong object to pass location to the map
    private LatLng position;
    //Start position of the user to be stored
    private float[] startPosition = new float[2];
    //Zoom of google maps
    private NucleusBuildingManager NucleusBuildingManager;
    private float zoom = 19f;

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
        // Inflate the layout for this fragment
        ((AppCompatActivity)getActivity()).getSupportActionBar().hide();
        View rootView = inflater.inflate(R.layout.fragment_startlocation, container, false);

        //Obtain the start position from the GPS data from the SensorFusion class
        startPosition = sensorFusion.getGNSSLatitude(false);
        //If not location found zoom the map out
        if(startPosition[0]==0 && startPosition[1]==0){
            zoom = 1f;
        }
        else {
            zoom = 19f;
        }
        // Initialize map fragment
        SupportMapFragment supportMapFragment=(SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.startMap);


        // This is just a demonstration of the automatic expansion of the indoor map.
        // Assume that we have obtained the user's position "newPosition" from the callback function. >>>

        // Predefined building polygons
        ArrayList<LatLng> buildingPolygon = new ArrayList<>();
        buildingPolygon.add(new LatLng(55.922679, -3.174672));
        buildingPolygon.add(new LatLng(55.923316, -3.173781));
        // Add additional vertices of the building if needed

        if (newPosition != null) {
            // Check if the user's position is inside the defined building polygon
            if (isPointInPolygon(newPosition, buildingPolygon)) {
                // If inside the building, make the floor buttons visible
                FloorButtons.setVisibility(View.VISIBLE);
                //Display the indoor map
                switchFloorNU(floor);
                InNu = 1; // Mark indoor map status
            } else {
                //hideAllFloors();
                NucleusBuildingManager.getIndoorMapManager().hideMap();
                FloorButtons.setVisibility(View.GONE);
                InNu = 0; // Mark indoor map status
            }
        }


        // Asynchronous map which can be configured
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
                mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                mMap.getUiSettings().setCompassEnabled(true);
                mMap.getUiSettings().setTiltGesturesEnabled(true);
                mMap.getUiSettings().setRotateGesturesEnabled(true);
                mMap.getUiSettings().setScrollGesturesEnabled(true);


                if (mMap != null) {
                    // Create NuclearBuildingManager instance
                    NucleusBuildingManager = new NucleusBuildingManager(mMap);
                    NucleusBuildingManager.getIndoorMapManager().hideMap();
                }

                // Add a marker in current GPS location and move the camera
                position = new LatLng(startPosition[0], startPosition[1]);
                mMap.addMarker(new MarkerOptions().position(position).title("Start Position")).setDraggable(true);
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, zoom ));

                //Drag listener for the marker to execute when the markers location is changed
                mMap.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener()
                {
                    /**
                     * {@inheritDoc}
                     */
                    @Override
                    public void onMarkerDragStart(Marker marker){}

                    /**
                     * {@inheritDoc}
                     * Updates the start position of the user.
                     */
                    @Override
                    public void onMarkerDragEnd(Marker marker)
                    {
                        startPosition[0] = (float) marker.getPosition().latitude;
                        startPosition[1] = (float) marker.getPosition().longitude;
                    }

                    /**
                     * {@inheritDoc}
                     */
                    @Override
                    public void onMarkerDrag(Marker marker){}
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
        // Add button to begin PDR recording and go to recording fragment.
        this.button = (Button) getView().findViewById(R.id.startLocationDone);
        this.button.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * When button clicked the PDR recording can start and the start position is stored for
             * the {@link CorrectionFragment} to display. The {@link RecordingFragment} is loaded.
             */
            @Override
            public void onClick(View view) {
                // Starts recording data from the sensor fusion
                sensorFusion.startRecording();
                // Set the start location obtained
                sensorFusion.setStartGNSSLatitude(startPosition);
                // Navigate to the RecordingFragment
                NavDirections action = StartLocationFragmentDirections.actionStartLocationFragmentToRecordingFragment();
                Navigation.findNavController(view).navigate(action);
            }
        });

    }

    /**
     * Determines if a given point is inside a polygon.
     *
     * @param point   the point to check
     * @param polygon the list of LatLng points representing the vertices of the polygon
     * @return true if the point is inside the polygon, false otherwise
     */
    private boolean isPointInPolygon(LatLng point, ArrayList<LatLng> polygon) {
        int intersectCount = 0;
        // Loop through each edge of the polygon
        for (int j = 0; j < polygon.size() - 1; j++) {
            // Check if the ray from the point intersects with the edge
            if (rayCastIntersect(point, polygon.get(j), polygon.get(j + 1))) {
                intersectCount++;
            }
        }
        // If the number of intersections is odd, the point is inside the polygon
        return ((intersectCount % 2) == 1); // odd = inside, even = outside;
    }

    /**
     * Determines if a ray from a point intersects with a given edge of the polygon.
     *
     * @param point the point from which the ray is cast
     * @param vertA the first vertex of the edge
     * @param vertB the second vertex of the edge
     * @return true if the ray intersects with the edge, false otherwise
     */
    private boolean rayCastIntersect(LatLng point, LatLng vertA, LatLng vertB) {
        double aY = vertA.latitude;
        double bY = vertB.latitude;
        double aX = vertA.longitude;
        double bX = vertB.longitude;
        double pY = point.latitude;
        double pX = point.longitude;

        // Check if the point is horizontally aligned with the edge
        if ((aY > pY && bY > pY) || (aY < pY && bY < pY) || (aX < pX && bX < pX)) {
            return false;
        }

        // Calculate the slope of the edge
        double m = (aY - bY) / (aX - bX);
        // Calculate the y-intercept of the edge
        double bee = -aX * m + aY;
        // Calculate the x-coordinate of the intersection point of the ray and the edge
        double x = (pY - bee) / m;

        // Return true if the intersection point is to the right of the point
        return x > pX;
    }

    /**
     * Switches the indoor map to the specified floor.
     *
     * @param floorIndex the index of the floor to switch to
     */
    private void switchFloorNU(int floorIndex) {
        FloorNK = floorIndex; // Set the current floor index
        if (NucleusBuildingManager != null) {
            // Call the switchFloor method of the IndoorMapManager to switch to the specified floor
            NucleusBuildingManager.getIndoorMapManager().switchFloor(floorIndex);
        }
    }

}
