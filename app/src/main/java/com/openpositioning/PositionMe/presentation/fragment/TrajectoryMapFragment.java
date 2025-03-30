package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import com.google.android.material.switchmaterial.SwitchMaterial;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.OnMapReadyCallback;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.*;

import com.google.maps.android.SphericalUtil;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * A fragment responsible for displaying a trajectory map using Google Maps.
 * <p>
 * The TrajectoryMapFragment provides a map interface for visualizing movement trajectories,
 * GNSS tracking, and indoor mapping. It manages map settings, user interactions, and real-time
 * updates to user location and GNSS markers.
 * <p>
 * Key Features:
 * - Displays a Google Map with support for different map types (Hybrid, Normal, Satellite).
 * - Tracks and visualizes user movement using polylines.
 * - Supports GNSS position updates and visual representation.
 * - Includes indoor mapping with floor selection and auto-floor adjustments.
 * - Allows user interaction through map controls and UI elements.
 *
 * @see com.openpositioning.PositionMe.presentation.activity.RecordingActivity The activity hosting this fragment.
 * @see com.openpositioning.PositionMe.utils.IndoorMapManager Utility for managing indoor map overlays.
 * @see com.openpositioning.PositionMe.utils.UtilFunctions Utility functions for UI and graphics handling.
 *
 * @author Mate Stodulka
 * @author Laura Maryakhina
 * @author Kalliopi Vakali
 */

public class TrajectoryMapFragment extends Fragment {

    private GoogleMap gMap; // Google Maps instance
    private LatLng currentLocation; // Stores the user's current location
    private Marker orientationMarker; // Marker representing user's heading
    private Marker gnssMarker; // GNSS position marker
    private Polyline polyline; // Polyline representing user's movement path
    private boolean isRed = true; // Tracks whether the polyline color is red
    private boolean isGnssOn = true; // Tracks if GNSS tracking is enabled

    private Polyline gnssPolyline; // Polyline for GNSS path
    private LatLng lastGnssLocation = null; // Stores the last GNSS location

    private Polyline fusionPolyline; // Polyline for the fusion path
    private LatLng lastFusionLocation = null; // Last fusion position
    private Marker fusionMarker; // Marker for current fusion position
    private Marker pdrMarker;
    private Polyline pdrPolyline; // Polyline representing PDR trajectory
    private boolean isPdrOn = false; // Tracks if PDR trajectory is enabled

    private SwitchMaterial pdrSwitch; // PDR switch control


    private Marker wifiMarker;  // WiFi Position Marker
    private Polyline wifiPolyline;  // WiFi Position Path
    private LatLng lastWifiLocation = null; // Last WiFi position
    private boolean isWifiOn = false; // Toggle for WiFi tracking



    private LatLng pendingCameraPosition = null; // Stores pending camera movement
    private boolean hasPendingCameraMove = false; // Tracks if camera needs to move

    private IndoorMapManager indoorMapManager; // Manages indoor mapping
    private SensorFusion sensorFusion;


    // UI
    private Spinner switchMapSpinner;

    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;
    private SwitchMaterial wifiSwitch;
    private static final double MAX_DISTANCE_THRESHOLD = 100; //wifi max distance between readings to detect outliers


    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private Polygon buildingPolygon;


    public TrajectoryMapFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate the separate layout containing map + map-related UI
        return inflater.inflate(R.layout.fragment_trajectory_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Grab references to UI controls
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch      = view.findViewById(R.id.gnssSwitch);
        wifiSwitch = view.findViewById(R.id.wifiSwitch);
        pdrSwitch = view.findViewById(R.id.pdrSwitch);


        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);

        gnssSwitch.setChecked(true);

        // Setup floor up/down UI hidden initially until we know there's an indoor map
        setFloorControlsVisibility(View.GONE);

        // Initialize the map asynchronously
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(@NonNull GoogleMap googleMap) {
                    // Assign the provided googleMap to your field variable
                    gMap = googleMap;
                    // Initialize map settings with the now non-null gMap
                    initMapSettings(gMap);

                    // If we had a pending camera move, apply it now
                    if (hasPendingCameraMove && pendingCameraPosition != null) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }

                    drawBuildingPolygon();

                    Log.d("TrajectoryMapFragment", "onMapReady: Map is ready!");


                }
            });
        }

        // Map type spinner setup
        initMapTypeSpinner();

        // GNSS Switch
        gnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isGnssOn = isChecked;
            if (!isChecked && gnssMarker != null) {
                gnssMarker.remove();
                gnssMarker = null;
            }
            if (gnssPolyline != null) {
                gnssPolyline.setPoints(new ArrayList<>()); // Clear the polyline
            }
        });


        //wifi switch
        wifiSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isWifiOn = isChecked;
            if (!isChecked && wifiMarker != null) {
                wifiMarker.remove();
                wifiMarker = null;
            }
            if (wifiPolyline != null) {
                wifiPolyline.setPoints(new ArrayList<>()); // Clear the polyline
            }
        });

        pdrSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isPdrOn = isChecked;

            // Clear PDR trajectory if toggled off
            if (!isChecked && pdrPolyline != null) {
                pdrPolyline.setPoints(new ArrayList<>()); // Clear the polyline
            }
            // Remove the PDR marker if it exists
            if (pdrMarker != null) {
                pdrMarker.remove();
                pdrMarker = null;
            }
        });




        // Color switch
        switchColorButton.setOnClickListener(v -> {
            if (polyline != null) {
                if (isRed) {
                    switchColorButton.setBackgroundColor(Color.BLACK);
                    polyline.setColor(Color.BLACK);
                    isRed = false;
                } else {
                    switchColorButton.setBackgroundColor(Color.RED);
                    polyline.setColor(Color.RED);
                    isRed = true;
                }
            }
        });

        // Floor up/down logic
        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {

            //TODO - fix the sensor fusion method to get the elevation (cannot get it from the current method)
//            float elevationVal = sensorFusion.getElevation();
//            indoorMapManager.setCurrentFloor((int)(elevationVal/indoorMapManager.getFloorHeight())
//                    ,true);
        });

        floorUpButton.setOnClickListener(v -> {
            // If user manually changes floor, turn off auto floor
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
            }
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
            }
        });
    }



    /**
     * Initialize the map settings with the provided GoogleMap instance.
     * <p>
     *     The method sets basic map settings, initializes the indoor map manager,
     *     and creates an empty polyline for user movement tracking.
     *     The method also initializes the GNSS polyline for tracking GNSS path.
     *     The method sets the map type to Hybrid and initializes the map with these settings.
     *
     * @param map
     */

    // Add this method to update the fusion position on the map
    /**
     * Updates the fusion position on the map with the latest estimate.
     *
     * @param fusionLocation The latest fusion position estimate
     */
    // 2. In the updateFusionPosition method, add style enhancements:
    public void updateFusionPosition(@NonNull LatLng fusionLocation, float orientation) {
        if (gMap == null || fusionLocation == null) {
            Log.e("TrajectoryMapFragment", "Cannot update fusion: gMap or fusionLocation is null");
            return;
        }

        Log.d("TrajectoryMapFragment", "updateFusionPosition called with: " +
                fusionLocation.latitude + ", " + fusionLocation.longitude);

        // If fusion marker doesn't exist, create it
        if (fusionMarker == null) {
            fusionMarker = gMap.addMarker(new MarkerOptions()
                    .position(fusionLocation)
                    .title("Fusion Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24))));
         gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(fusionLocation, 19f));
        } else {
            // Update marker position + orientation
            fusionMarker.setPosition(fusionLocation);
            fusionMarker.setRotation(orientation);
            // Move camera a bit
            gMap.moveCamera(CameraUpdateFactory.newLatLng(fusionLocation));
        }

        // Check if fusionPolyline is null and create it if needed
        if (fusionPolyline == null) {
            fusionPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.GREEN)
                    .width(8f)
                    .zIndex(100)
                    .add(fusionLocation));
            Log.d("TrajectoryMapFragment", "Created new fusion polyline");
        } else {
            // Add new point to fusion path
            List<LatLng> fusionPoints = new ArrayList<>(fusionPolyline.getPoints());
            fusionPoints.add(fusionLocation);
            fusionPolyline.setPoints(fusionPoints);
            Log.d("TrajectoryMapFragment", "Added point to fusion polyline, total points: " + fusionPoints.size());
        }
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(fusionLocation);
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Checks if the main polyline is empty (has no points)
     * @return true if polyline has no points, false otherwise
     */
    public boolean isPolylineEmpty() {
        return polyline == null || polyline.getPoints().isEmpty();
    }

    /**
     * Force updates the polyline with a given location even if no movement was detected
     * @param location The location to add to the polyline
     */
    public void forcePolylineUpdate(LatLng location) {
        if (gMap == null || location == null) return;

        Log.d("TrajectoryMapFragment", "Forcing polyline update with: " +
                location.latitude + ", " + location.longitude);

        if (polyline == null) {
            // Initialize polyline if it doesn't exist
            polyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.RED)
                    .width(6f)
                    .zIndex(1)
                    .add(location)); // add the location immediately
        } else {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            points.add(location);
            polyline.setPoints(points);
        }
    }

    private void initMapSettings(GoogleMap map) {
        // Basic map settings
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        // Initialize indoor manager
        indoorMapManager = new IndoorMapManager(map);

        // Initialize the main PDR polyline (red)
        polyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(6f)
                .add() // start empty
                .zIndex(1000)
        );

        // Initialize the GNSS polyline (blue)
        gnssPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .width(6f)
                .add() // start empty
                .zIndex(1000)
        );


        // Initialize the fusion polyline (green) - make it prominent
        fusionPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.GREEN)
                .width(8f)  // Slightly thicker for visibility
                .zIndex(100)
                .add());    // start empty

        Log.d("TrajectoryMapFragment", "Map initialized with fusion polyline");

        // WiFi path in green
        wifiPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.MAGENTA)
                .width(5f)
                .add() // start empty
                .zIndex(1000)
        );
        // PDR path in red
        pdrPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED) // Use a distinct color for PDR
                .width(5f)
                .add() // Start empty
                .zIndex(1000)
        );



    }


    /**
     * Initialize the map type spinner with the available map types.
     * <p>
     *     The spinner allows the user to switch between different map types
     *     (e.g. Hybrid, Normal, Satellite) to customize their map view.
     *     The spinner is populated with the available map types and listens
     *     for user selection to update the map accordingly.
     *     The map type is updated directly on the GoogleMap instance.
     *     <p>
     *         Note: The spinner is initialized with the default map type (Hybrid).
     *         The map type is updated on user selection.
     *     </p>
     * </p>
     *     @see com.google.android.gms.maps.GoogleMap The GoogleMap instance to update map type.
     */
    private void initMapTypeSpinner() {
        if (switchMapSpinner == null) return;
        String[] maps = new String[]{
                getString(R.string.hybrid),
                getString(R.string.normal),
                getString(R.string.satellite)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                maps
        );
        switchMapSpinner.setAdapter(adapter);

        switchMapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                if (gMap == null) return;
                switch (position){
                    case 0:
                        gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                    case 1:
                        gMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                        break;
                    case 2:
                        gMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                        break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /**
     * Update the user's current location on the map, create or move orientation marker,
     * and append to polyline if the user actually moved.
     *
     * @param newLocation The new location to plot.
     * @param orientation The user’s heading (e.g. from sensor fusion).
     */
    public void pdrLocation(@NonNull LatLng newLocation) {
        if (gMap == null) return;
        if (!isPdrOn) return;

        // Keep track of current location
        LatLng oldLocation = this.currentLocation;
        this.currentLocation = newLocation;

        if (pdrMarker == null) {
            pdrMarker = gMap.addMarker(new MarkerOptions()
                    .position(newLocation)
                    .flat(true)
                    .title("PDR Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            );
        } else {
            pdrMarker.setPosition(newLocation);
        }

        if (oldLocation != null && !oldLocation.equals(newLocation) && pdrPolyline != null) {
            List<LatLng> points = new ArrayList<>(pdrPolyline.getPoints());
            points.add(newLocation);
            pdrPolyline.setPoints(points);
        }

        // Update indoor map overlay
//        if (indoorMapManager != null) {
//            indoorMapManager.setCurrentLocation(newLocation);
//            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
//        }
    }

    /**
     * Remove PDR trajectory if the user toggles it off.
     */
    public void clearPdrTrajectory() {
        if (pdrPolyline != null) {
            pdrPolyline.setPoints(new ArrayList<>()); // Clear the polyline
        }
    }

    /**
     * Set the initial camera position for the map.
     * <p>
     *     The method sets the initial camera position for the map when it is first loaded.
     *     If the map is already ready, the camera is moved immediately.
     *     If the map is not ready, the camera position is stored until the map is ready.
     *     The method also tracks if there is a pending camera move.
     * </p>
     * @param startLocation The initial camera position to set.
     */
    public void setInitialCameraPosition(@NonNull LatLng startLocation) {
        // If the map is already ready, move camera immediately
        if (gMap != null) {
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLocation, 19f));
        } else {
            // Otherwise, store it until onMapReady
            pendingCameraPosition = startLocation;
            hasPendingCameraMove = true;
        }
    }


    /**
     * Get the current user location on the map.
     * @return The current user location as a LatLng object.
     */
    public LatLng getCurrentLocation() {
        return currentLocation;
    }

    /**
     * Called when we want to set or update the GNSS marker position
     */
    public void updateGNSS(@NonNull LatLng gnssLocation) {
        if (gMap == null) return;
        if (!isGnssOn) return;

        if (gnssMarker == null) {
            // Create the GNSS marker for the first time with enhanced visibility
            gnssMarker = gMap.addMarker(new MarkerOptions()
                    .position(gnssLocation)
                    .title("GNSS Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    .zIndex(8f)  // High z-index, but below fusion marker
            );
            lastGnssLocation = gnssLocation;
        } else {
            // Move existing GNSS marker
            gnssMarker.setPosition(gnssLocation);
        }

        // Add a segment to the blue GNSS line, with force update if empty
        if (lastGnssLocation != null &&
                (!lastGnssLocation.equals(gnssLocation) || gnssPolyline.getPoints().isEmpty())) {

            List<LatLng> gnssPoints = new ArrayList<>(gnssPolyline.getPoints());
            gnssPoints.add(gnssLocation);
            gnssPolyline.setPoints(gnssPoints);
            gnssPolyline.setColor(Color.rgb(0, 0, 255));  // Bright blue for visibility
            gnssPolyline.setWidth(8f);  // Ensure width is set
            gnssPolyline.setZIndex(2);  // Ensure z-index is set

            Log.d("TrajectoryMapFragment", "GNSS polyline updated, total points: " + gnssPoints.size());
        }
        lastGnssLocation = gnssLocation;
    }


    /**
     * Remove GNSS marker if user toggles it off
     */
    public void clearGNSS() {
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
    }
    /**
     * Updates the WiFi marker and polyline on the map based on the provided location.
     *
     * This method ensures that the WiFi marker is either created or updated at the given location,
     * only if the user is inside a location with Wifi coverage. It also adds a segment to the polyline
     * representing the WiFi path if the new location is different from the previous one. Updates are
     * ignored if the Wifi location is an outlier or if WiFi tracking is disabled.
     *
     *
     * @param wifiLocation
     */
    public void updateWiFi(@NonNull LatLng wifiLocation) {
        if (gMap == null) return;
        if (!isWifiOn) return; // Ignore updates if WiFi is turned off

        // Check if the new wifi location is an outlier
        if (isOutlier(lastWifiLocation, wifiLocation)) {
            Log.w("OutlierDetection", "Rejected outlier WiFi location: " + wifiLocation.toString());
            // Add a red marker for the outlier for testing
//            gMap.addMarker(new MarkerOptions()
//                    .position(wifiLocation)
//                    .title("Outlier Position")
//                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
            return; // Skip updating the marker
        }

        boolean isInsideWifiBounds = indoorMapManager.getIsIndoorMapSet(); // Check bounds
        if (!isInsideWifiBounds) {
            // WiFi readings are not available; show a message
            showNoWiFiCoverageMessage();
            clearWiFi(); // Clear the WiFi marker and polyline if any
            wifiSwitch.setChecked(false);
            return;
        }

        if (wifiMarker == null) {
            // Create the WiFi marker for the first time
            wifiMarker = gMap.addMarker(new MarkerOptions()
                    .position(wifiLocation)
                    .title("WiFi Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_MAGENTA)));
            lastWifiLocation = wifiLocation;
        } else {
            // Move existing WiFi marker
            wifiMarker.setPosition(wifiLocation);

            // Add a segment to the WiFi polyline if this is a new location
            if (lastWifiLocation != null && !lastWifiLocation.equals(wifiLocation)) {
                List<LatLng> wifiPoints = new ArrayList<>(wifiPolyline.getPoints());
                wifiPoints.add(wifiLocation);
                wifiPolyline.setPoints(wifiPoints);
            }
            lastWifiLocation = wifiLocation;
        }
    }

    /**
     * Clears Wifi marker so only one is present on the map at a time
     */
    public void clearWiFi() {
        if (wifiMarker != null) {
            wifiMarker.remove();
            wifiMarker = null;
        }
    }

    /**
     * Displays a dialog to notify the user that there is no WiFi coverage.
     */
    private void showNoWiFiCoverageMessage() {
        Toast.makeText(requireContext(), "No WiFi coverage available in this area.", Toast.LENGTH_SHORT).show();
    }

    /**
     * Method to check if a Wifi location is an outlier
     * @param lastLocation - last wifi location
     * @param newLocation - current wifi location
     * @return boolean - True if new wifi location is too far from last location
     */
    private boolean isOutlier(LatLng lastLocation, LatLng newLocation) {
        if (lastLocation == null || newLocation == null) return false; // No comparison possible
        double distance = SphericalUtil.computeDistanceBetween(lastLocation, newLocation);
        return distance > MAX_DISTANCE_THRESHOLD; // True if the new location is too far
    }


    /**
     * Whether user is currently showing GNSS or not
     */
    public boolean isGnssEnabled() {
        return isGnssOn;
    }
    /**
     * Whether user is currently showing Wifi or not
     */
    public boolean isWifiEnabled() {
        return isWifiOn;
    }

    public boolean isPdrEnabled() {
        return isPdrOn;
    }



    private void setFloorControlsVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        autoFloorSwitch.setVisibility(visibility);
    }

    // In TrajectoryMapFragment.java, update the clearMapAndReset method:

    public void clearMapAndReset() {
        if (gMap == null) {
            Log.e("TrajectoryMapFragment", "Cannot reset map, gMap is null");
            return;
        }

        // Clear all polylines
        //if (polyline != null) polyline.remove();
        if (gnssPolyline != null) gnssPolyline.remove();
        if (fusionPolyline != null) fusionPolyline.remove();
        if (wifiPolyline != null) wifiPolyline.remove();
        if (pdrMarker != null) pdrPolyline.remove();

        // Clear all markers
        //if (orientationMarker != null) orientationMarker.remove();
        if (gnssMarker != null) gnssMarker.remove();
        if (fusionMarker != null) fusionMarker.remove();
        if (wifiMarker != null) wifiMarker.remove();
        if (pdrMarker != null) pdrMarker.remove();


        // Reset state variables

        lastWifiLocation = null;
        lastGnssLocation = null;
        currentLocation = null;
        fusionMarker = null;
        pdrMarker = null;


        // Create new polylines
//        polyline = gMap.addPolyline(new PolylineOptions()
//                .color(Color.RED)
//                .width(6f)
//                .add());

        gnssPolyline = gMap.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .width(6f)
                .add());

        fusionPolyline = gMap.addPolyline(new PolylineOptions()
                .color(Color.GREEN)
                .width(8f)
                .zIndex(100)
                .add());

        wifiPolyline = gMap.addPolyline(new PolylineOptions()
                .color(Color.MAGENTA)
                .width(5f)
                .add());

        pdrPolyline = gMap.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(5f)
                .add());

        Log.d("TrajectoryMapFragment", "Map cleared and reset");

    }

    /**
     * Draw the building polygon on the map
     * <p>
     *     The method draws a polygon representing the building on the map.
     *     The polygon is drawn with specific vertices and colors to represent
     *     different buildings or areas on the map.
     *     The method removes the old polygon if it exists and adds the new polygon
     *     to the map with the specified options.
     *     The method logs the number of vertices in the polygon for debugging.
     *     <p>
     *
     *    Note: The method uses hard-coded vertices for the building polygon.
     *
     *    </p>
     *
     *    See: {@link com.google.android.gms.maps.model.PolygonOptions} The options for the new polygon.
     */
    private void drawBuildingPolygon() {
        if (gMap == null) {
            Log.e("TrajectoryMapFragment", "GoogleMap is not ready");
            return;
        }

        // nuclear building polygon vertices
        LatLng nucleus1 = new LatLng(55.92279538827796, -3.174612147506538);
        LatLng nucleus2 = new LatLng(55.92278121423647, -3.174107900816096);
        LatLng nucleus3 = new LatLng(55.92288405733954, -3.173843694667146);
        LatLng nucleus4 = new LatLng(55.92331786793876, -3.173832892645086);
        LatLng nucleus5 = new LatLng(55.923337194112555, -3.1746284301397387);


        // nkml building polygon vertices
        LatLng nkml1 = new LatLng(55.9230343434213, -3.1751847990731954);
        LatLng nkml2 = new LatLng(55.923032840563366, -3.174777103346131);
        LatLng nkml4 = new LatLng(55.92280139974615, -3.175195527934348);
        LatLng nkml3 = new LatLng(55.922793885410734, -3.1747958788136867);

        LatLng fjb1 = new LatLng(55.92269205199916, -3.1729563477188774);//left top
        LatLng fjb2 = new LatLng(55.922822801570994, -3.172594249522305);
        LatLng fjb3 = new LatLng(55.92223512226413, -3.171921917547244);
        LatLng fjb4 = new LatLng(55.9221071265519, -3.1722813131202097);

        LatLng faraday1 = new LatLng(55.92242866264128, -3.1719553662011815);
        LatLng faraday2 = new LatLng(55.9224966752294, -3.1717846714743474);
        LatLng faraday3 = new LatLng(55.922271383074154, -3.1715191463437162);
        LatLng faraday4 = new LatLng(55.92220124468304, -3.171705013935158);



        PolygonOptions buildingPolygonOptions = new PolygonOptions()
                .add(nucleus1, nucleus2, nucleus3, nucleus4, nucleus5)
                .strokeColor(Color.RED)    // Red border
                .strokeWidth(10f)           // Border width
                //.fillColor(Color.argb(50, 255, 0, 0)) // Semi-transparent red fill
                .zIndex(1);                // Set a higher zIndex to ensure it appears above other overlays

        // Options for the new polygon
        PolygonOptions buildingPolygonOptions2 = new PolygonOptions()
                .add(nkml1, nkml2, nkml3, nkml4, nkml1)
                .strokeColor(Color.BLUE)    // Blue border
                .strokeWidth(10f)           // Border width
               // .fillColor(Color.argb(50, 0, 0, 255)) // Semi-transparent blue fill
                .zIndex(1);                // Set a higher zIndex to ensure it appears above other overlays

        PolygonOptions buildingPolygonOptions3 = new PolygonOptions()
                .add(fjb1, fjb2, fjb3, fjb4, fjb1)
                .strokeColor(Color.GREEN)    // Green border
                .strokeWidth(10f)           // Border width
                //.fillColor(Color.argb(50, 0, 255, 0)) // Semi-transparent green fill
                .zIndex(1);                // Set a higher zIndex to ensure it appears above other overlays

        PolygonOptions buildingPolygonOptions4 = new PolygonOptions()
                .add(faraday1, faraday2, faraday3, faraday4, faraday1)
                .strokeColor(Color.YELLOW)    // Yellow border
                .strokeWidth(10f)           // Border width
                //.fillColor(Color.argb(50, 255, 255, 0)) // Semi-transparent yellow fill
                .zIndex(1);                // Set a higher zIndex to ensure it appears above other overlays


        // Remove the old polygon if it exists
        if (buildingPolygon != null) {
            buildingPolygon.remove();
        }

        // Add the polygon to the map
        buildingPolygon = gMap.addPolygon(buildingPolygonOptions);
        gMap.addPolygon(buildingPolygonOptions2);
        gMap.addPolygon(buildingPolygonOptions3);
        gMap.addPolygon(buildingPolygonOptions4);
        Log.d("TrajectoryMapFragment", "Building polygon added, vertex count: " + buildingPolygon.getPoints().size());
    }


}
