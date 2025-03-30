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
import android.widget.ImageView;
import android.widget.Spinner;
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
 */

public class TrajectoryMapFragment extends Fragment {

    private static TrajectoryMapFragment instance;  // Static reference to store the instance of the fragment
    private GoogleMap gMap; // Google Maps instance

    private LatLng smoothedPosition; // Smoothed fused position
    private LatLng estimatedLocation;
    private LatLng fusedCurrentLocation;
    private LatLng pdrCurrentLocation;

    private float currentElevation;

    private Marker orientationMarker; // Marker representing user's heading
    private Marker gnssMarker; // GNSS position marker
    private Marker wifiMarker; // Wifi position marker
    private Marker fusedMarker; // Fused data position marker
    private Polyline polyline; // Polyline representing user's movement path
    private Polyline interpolatedPolyLine; // Polyline extending from current fusion position estimated
                                           // from PDR.
    private Polyline pdrPolyline; // Polyline for PDR trajectory with a dotted black line
    private List<LatLng> movingAverageBuffer = new ArrayList<>();
    private static final int MOVING_AVERAGE_WINDOW = 10;

    private boolean isPdrOn = false; // Tracks whether the polyline color is red
    private boolean isGnssOn = false; // Tracks if GNSS tracking is enabled
    private boolean isWifiOn = false; // Tracks if GNSS tracking is enabled

    private Polyline gnssPolyline; // Polyline for GNSS path
    private LatLng lastGnssLocation = null; // Stores the last GNSS location

    private LatLng pendingCameraPosition = null; // Stores pending camera movement
    private boolean hasPendingCameraMove = false; // Tracks if camera needs to move

    private IndoorMapManager indoorMapManager; // Manages indoor mapping
    private SensorFusion sensorFusion;


    // UI
    private Spinner switchMapSpinner;

    private SwitchMaterial gnssSwitch;
    private SwitchMaterial wifiSwitch;
    private SwitchMaterial autoFloorSwitch;
    private SwitchMaterial pdrSwitch;
    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private Polygon buildingPolygon;


    public TrajectoryMapFragment() {
        // Required empty public constructor
    }
  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    instance = this;  // Set the instance when the fragment is created
  }

  /**
   * Static method to retrieve the instance of the fragment.
   * @return The instance of the TrajectoryMapFragment.
   */
  public static TrajectoryMapFragment getInstance() {
    return instance;
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
        wifiSwitch      = view.findViewById(R.id.wifiSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        pdrSwitch       = view.findViewById(R.id.pdrSwitch);

      // NEW: Get the toggle button and controls container for collapsing/expanding
      final ImageView toggleButton = view.findViewById(R.id.toggleButton);
      final View controlsContainer = view.findViewById(R.id.controlsContainer);

      // Set initial state (expanded)
      controlsContainer.setVisibility(View.VISIBLE);
      toggleButton.setImageResource(R.drawable.ic_expand_less);

      // Set the toggle logic
      toggleButton.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          if (controlsContainer.getVisibility() == View.VISIBLE) {
            // Collapse the controls container
            controlsContainer.setVisibility(View.GONE);
            // Change icon to indicate expandable state (e.g., arrow down)
            toggleButton.setImageResource(R.drawable.ic_expand_more);
          } else {
            // Expand the controls container
            controlsContainer.setVisibility(View.VISIBLE);
            // Change icon to indicate collapse state (e.g., arrow up)
            toggleButton.setImageResource(R.drawable.ic_expand_less);
          }
        }
      });

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
                gnssPolyline.setVisible(isChecked);
            }
        });
        // Wifi Switch
        wifiSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isWifiOn = isChecked;
            if (!isChecked && wifiMarker != null) {
                wifiMarker.remove();
                wifiMarker = null;
            }
        });

        // PDR Switch
        pdrSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isPdrOn = isChecked;
            if (pdrPolyline != null) {
                pdrPolyline.setVisible(isChecked);
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

    private void initMapSettings(GoogleMap map) {
        // Basic map settings
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        // Initialize indoor manager
        indoorMapManager = new IndoorMapManager(map);

        // Initialize an empty polyline
        polyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(10f)
                .zIndex(10f)
                .add() // start empty
        );

        interpolatedPolyLine = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(8f)
                .zIndex(10f)
                .add() // start empty
                .pattern(Arrays.asList(new Dot(), new Gap(10))) // Dotted line
        );

        pdrPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.BLACK)
                .width(5f)
                .zIndex(10f)
                .add() // start empty
        );
        // ensures pdr trajectory is initialised in the invisible state
        pdrPolyline.setVisible(isPdrOn);

        // GNSS path in blue
        gnssPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .zIndex(10f)
                .width(5f)
                .add() // start empty
        );
        gnssPolyline.setVisible(isGnssOn);
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

  public void interpolateFusedLocation(@NonNull LatLng newLocation) {
    if (gMap == null) return;
    // Keep track of current location
    LatLng oldLocation = this.estimatedLocation;
    this.estimatedLocation = newLocation;

    // Extend polyline if movement occurred
    if (oldLocation != null && !oldLocation.equals(this.estimatedLocation)
            && interpolatedPolyLine != null) {
      List<LatLng> points = new ArrayList<>(interpolatedPolyLine.getPoints());
      points.add(this.estimatedLocation);
      interpolatedPolyLine.setPoints(points);
    }
  }

  /**
   * Update user's location in the map. Handles the polyline and orientation marker
   * @param newLocation New location to plot
   * @param orientation User's heading
   */
  public void updateUserLocation(LatLng newLocation, float orientation) {
    if (gMap == null) return;

    // Keep track of current location
    LatLng oldLocation = this.fusedCurrentLocation;
    this.fusedCurrentLocation = newLocation;

    // Add the new location to a moving average buffer
    movingAverageBuffer.add(newLocation);
    if (movingAverageBuffer.size() > MOVING_AVERAGE_WINDOW) {
      movingAverageBuffer.remove(0);
    }

    // Compute the average (smoothed) location from the buffer
    double sumLat = 0, sumLng = 0;
    for (LatLng point : movingAverageBuffer) {
        sumLat += point.latitude;
        sumLng += point.longitude;
    }
    LatLng oldSmoothedPosition = this.smoothedPosition;
    smoothedPosition = new LatLng(sumLat / movingAverageBuffer.size(),
            sumLng / movingAverageBuffer.size());

    // If no marker, create it
    if (orientationMarker == null) {
      orientationMarker = gMap.addMarker(new MarkerOptions()
              .position(smoothedPosition)
              .flat(true)
              .title("Current Position")
              .icon(BitmapDescriptorFactory.fromBitmap(
                      UtilFunctions.getBitmapFromVector(requireContext(),
                              R.drawable.ic_baseline_navigation_24)))
      );
      gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(smoothedPosition, 19f));
    } else {
      // Update marker position + orientation
      orientationMarker.setPosition(smoothedPosition);
      orientationMarker.setRotation(orientation);
    }

    // Extend polyline if movement occurred
    if (oldSmoothedPosition != null && !oldSmoothedPosition.equals(smoothedPosition) && polyline != null) {
      this.estimatedLocation = smoothedPosition;
      interpolatedPolyLine.setPoints(new ArrayList<>());
      List<LatLng> points = new ArrayList<>(polyline.getPoints());
      points.add(smoothedPosition);
      polyline.setPoints(points);
    }
    // Update indoor map overlay
    if (indoorMapManager != null) {
      indoorMapManager.setCurrentLocation(smoothedPosition);
      setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
    }
  }
    /**
     * Update the user's current location on the map, create or move orientation marker,
     * and append to polyline if the user actually moved.
     *
     * @param newLocation The new location to plot.
     * @param initialPosition to offset the new location if needed
     * @param orientation The user’s heading (e.g. from sensor fusion).
     */
    public void updateUserLocation(@NonNull LatLng newLocation, LatLng initialPosition,
                                   float orientation) {
      LatLng offsetNewLocation = new LatLng(newLocation.latitude + initialPosition.latitude,
              newLocation.longitude + initialPosition.longitude);
      updateUserLocation(offsetNewLocation, orientation);
    }
    public void updatePdrLocation(@NonNull LatLng newLocation, LatLng initialPosition) {
        if (gMap == null) return;
        // Keep track of current location
        LatLng oldLocation = this.pdrCurrentLocation;
        this.pdrCurrentLocation = new LatLng(newLocation.latitude + initialPosition.latitude,
                newLocation.longitude + initialPosition.longitude);
        // Extend polyline if movement occurred
        if (oldLocation != null && !oldLocation.equals(this.pdrCurrentLocation) && pdrPolyline != null) {
            List<LatLng> points = new ArrayList<>(pdrPolyline.getPoints());
            points.add(this.pdrCurrentLocation);
            while (points.size() > 50) {
                points.remove(0);
            }
            pdrPolyline.setPoints(points);
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
        return fusedCurrentLocation;
    }

    public LatLng getPdrCurrentLocation() {
        return pdrCurrentLocation;
    }

    public LatLng getEstimatedLocation() {
      return estimatedLocation;
    }
    /**
     * Called when we want to set or update the GNSS marker position
     */
    public void updateGNSS(@NonNull LatLng gnssLocation) {
        if (gMap == null) return;
        if (!isGnssOn) return;

        if (gnssMarker == null) {
            // Create the GNSS marker for the first time
            gnssMarker = gMap.addMarker(new MarkerOptions()
                    .position(gnssLocation)
                    .title("GNSS Position")
                    .icon(BitmapDescriptorFactory
                            .defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
            lastGnssLocation = gnssLocation;
        } else {
            // Move existing GNSS marker
            gnssMarker.setPosition(gnssLocation);

            // Add a segment to the blue GNSS line, if this is a new location
            if (lastGnssLocation != null && !lastGnssLocation.equals(gnssLocation)) {
                List<LatLng> gnssPoints = new ArrayList<>(gnssPolyline.getPoints());
                gnssPoints.add(gnssLocation);
                gnssPolyline.setPoints(gnssPoints);
            }
            lastGnssLocation = gnssLocation;
        }
    }

    /**
     * Update the map with a WiFi marker at the specified location.
     *
     * @param wifiLocation The location where the WiFi marker should be placed.
     */
    public void updateWifi(@NonNull LatLng wifiLocation) {
        if (gMap == null) return;
        if (!isWifiOn) return;

        if (wifiMarker == null) {
            // Create the WiFi marker if it doesn't already exist
            wifiMarker = gMap.addMarker(new MarkerOptions()
                    .position(wifiLocation)
                    .title("WiFi Access Point")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
            );
        } else {
            // Update the existing WiFi marker's position
            wifiMarker.setPosition(wifiLocation);
        }
    }

    /**
     * Update the map with a Fused marker at the specified location.
     *
     * @param fusedLocation The location where the Fused marker should be placed.
     */
    public void updateFused(@NonNull LatLng fusedLocation) {
        if (gMap == null) return;

        if (fusedMarker == null) {
            // Create the fused marker if it doesn't already exist
            fusedMarker = gMap.addMarker(new MarkerOptions()
                    .position(fusedLocation)
                    .title("Fused Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
            );
        } else {
            // Update the existing fused marker's position
            fusedMarker.setPosition(fusedLocation);
        }
    }
    public void updateFloor(int floor) {
      if(indoorMapManager != null && this.autoFloorSwitch.isChecked()) {
          indoorMapManager.setCurrentFloor(floor, true);
      }
    }

    public void updateElevation(float elevation) {

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

    public void clearWifi() {
        if (wifiMarker != null) {
            wifiMarker.remove();
            wifiMarker = null;
        }
    }

    public void clearFused() {
        if (fusedMarker != null) {
            fusedMarker.remove();
            fusedMarker = null;
        }
    }


    /**
     * Whether user is currently showing GNSS or not
     */
    public boolean isGnssEnabled() {
        return isGnssOn;
    }
    public boolean isWifiEnabled() {
        return isWifiOn;
    }

    private void setFloorControlsVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        autoFloorSwitch.setVisibility(visibility);
    }

    public void clearMapAndReset() {
        if (polyline != null) {
            polyline.remove();
            polyline = null;
        }
        if (gnssPolyline != null) {
            gnssPolyline.remove();
            gnssPolyline = null;
        }
        if (orientationMarker != null) {
            orientationMarker.remove();
            orientationMarker = null;
        }
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
        lastGnssLocation = null;

        // Re-create empty polylines with your chosen colors
        if (gMap != null) {
            polyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.RED)
                    .zIndex(10f)
                    .width(5f)
                    .add());
            gnssPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.BLUE)
                    .zIndex(10f)
                    .width(5f)
                    .add());
        }
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

  /**
   * Function to add a tag marker to the map.
   * @param tagLocation The `LatLng` coordinates where the tag should be placed
   */
  public void addTagMarker(LatLng tagLocation) {
    if (gMap != null) {
      gMap.addMarker(new MarkerOptions()
              .position(tagLocation)
              .title("Tag")
              .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
      Log.d("TrajectoryMapFragment", "Tag marker added at: " + tagLocation.toString());
    } else {
      Log.e("TrajectoryMapFragment", "Google Map is not ready yet.");
    }
  }



}
