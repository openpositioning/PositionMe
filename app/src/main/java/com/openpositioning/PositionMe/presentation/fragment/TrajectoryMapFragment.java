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
import android.widget.TextView;

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
import java.util.List;
import com.openpositioning.PositionMe.data.remote.FloorPlanData;
import com.openpositioning.PositionMe.utils.VenueMapper;

import okhttp3.OkHttpClient;


import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.MarkerOptions;



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

//request nearby indoor maps
    //draw venue polygons
    //handle venue click

public class TrajectoryMapFragment extends Fragment {

    private static final boolean USE_MOCK_FLOORPLAN = true;
    private GoogleMap gMap; // Google Maps instance
    private LatLng currentLocation; // Stores the user's current location
    private Marker orientationMarker; // Marker representing user's heading
    private Marker gnssMarker; // GNSS position marker
    private Polyline polyline; // Polyline representing user's movement path
    private boolean isRed = true; // Tracks whether the polyline color is red
    private boolean isGnssOn = false; // Tracks if GNSS tracking is enabled

    private Polyline gnssPolyline; // Polyline for GNSS path
    private LatLng lastGnssLocation = null; // Stores the last GNSS location

    private LatLng pendingCameraPosition = null; // Stores pending camera movement
    private boolean hasPendingCameraMove = false; // Tracks if camera needs to move

    private IndoorMapManager indoorMapManager; // Manages indoor mapping
    private SensorFusion sensorFusion;
    private TextView floorLabel;

    private float lastElevation = Float.NaN; //To compute height change between old and current position




    // UI
    private Spinner switchMapSpinner;

    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private Polygon buildingPolygon;

    private List <IndoorMapManager.IndoorVenue> selectedVenue;
//    private IndoorMapManager.IndoorFloor selectedFloor;
    private FloorPlanData floorplanRemote;
    private long lastVenueQueryMs = 0;
    private LatLng lastVenueQueryLoc = null;

    private final Object macLock = new Object();
    private List<String> observedMacs = new ArrayList<>();

    // cache venues if they arrive before map/manager is ready (optional)
    private List<IndoorMapManager.IndoorVenue> lastFetchedVenues = null;



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
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorUpButton.setOnClickListener(v -> indoorMapManager.increaseFloor());
        floorDownButton = view.findViewById(R.id.floorDownButton);
        floorDownButton.setOnClickListener(v -> indoorMapManager.decreaseFloor());
        switchColorButton = view.findViewById(R.id.lineColorButton);
        floorLabel = view.findViewById(R.id.floorLabel);
        floorLabel.setText("Floor: -");


        // Setup floor up/down UI hidden initially until we know there's an indoor map
//        setFloorControlsVisibility(View.GONE);

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
                    floorplanRemote = new FloorPlanData(new OkHttpClient());


                    // If we had a pending camera move, apply it now
                    if (hasPendingCameraMove && pendingCameraPosition != null) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }

//                    drawBuildingPolygon();
                    indoorMapManager = new IndoorMapManager(gMap);
                    sensorFusion = SensorFusion.getInstance();
                    sensorFusion.setIndoorMapManager(indoorMapManager);
                    // 1) Handle user clicking a venue outline polygon
                    gMap.setOnPolygonClickListener(polygon -> {
                        IndoorMapManager.IndoorVenue v = indoorMapManager.getVenueForPolygon(polygon);

                        if (v != null) {
                            Log.d("IndoorDebug", "Clicked venue=" + v.name);

                            Log.d("IndoorDebug", "mapShapes length=" +
                                    (v.rawMapShapes == null ? "null" : v.rawMapShapes.length()));

                            Log.d("IndoorDebug", "mapShapes preview=" +
                                    (v.rawMapShapes == null ? "null" :
                                            v.rawMapShapes.substring(0,
                                                    Math.min(400, v.rawMapShapes.length()))));

                            indoorMapManager.selectVenue(v);

                            if(getActivity() instanceof VenueSelectionListener) {
                                ((VenueSelectionListener) getActivity()).onVenueSelected(
                                        v.venueId != null ? v.venueId : v.name
                                );
                            }
                            setFloorControlsVisibility(View.VISIBLE);

                            String fk = indoorMapManager.getCurrentFloorKey();
                            if (floorLabel != null) floorLabel.setText("Floor: " + (fk == null ? "-" : fk));

                        }
                    });

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
        });

        floorUpButton.setOnClickListener(v -> {
            // If user manually changes floor, turn off auto floor
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
                String fk = indoorMapManager.getCurrentFloorKey();
                if (floorLabel != null) floorLabel.setText("Floor: " + (fk == null ? "-" : fk));

            }

        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
                String fk = indoorMapManager.getCurrentFloorKey();
                if (floorLabel != null) floorLabel.setText("Floor: " + (fk == null ? "-" : fk));

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

    private Bitmap createNumberedMarkerBitmap(int number) {
        int size = 100; // marker size in pixels

        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint circlePaint = new Paint();
        circlePaint.setColor(Color.RED);
        circlePaint.setAntiAlias(true);

        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(40f);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // Draw circle
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, circlePaint);

        // Draw number in center
        Rect bounds = new Rect();
        String text = String.valueOf(number);
        textPaint.getTextBounds(text, 0, text.length(), bounds);

        float x = size / 2f;
        float y = size / 2f - bounds.centerY();

        canvas.drawText(text, x, y, textPaint);

        return bitmap;
    }

    public void addTestPointMarker(LatLng latLng, int number) {
        Bitmap numberedMarker = createNumberedMarkerBitmap(number);

        MarkerOptions markerOptions = new MarkerOptions()
                .position(latLng)
                .icon(BitmapDescriptorFactory.fromBitmap(numberedMarker))
                .anchor(0.5f, 0.5f)   // center anchor
                .title("Test Point " + number);

        gMap.addMarker(markerOptions);
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

        // Initialize an empty polyline
        polyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(5f)
                .add() // start empty
        );

        // GNSS path in blue
        gnssPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .width(5f)
                .add() // start empty
        );
    }

    private void maybeRequestNearbyVenues(@NonNull LatLng loc) {
        Log.d("MapDebug", "maybeRequestNearbyVenues called, timeSinceLast=" + (System.currentTimeMillis() - lastVenueQueryMs) + " loc=" + loc);
        if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) return;

        long now = System.currentTimeMillis();
        if (now - lastVenueQueryMs < 15000) return; // 15s throttle

        if (lastVenueQueryLoc != null && distanceMeters(loc, lastVenueQueryLoc) < 25) return; // 25m threshold

        lastVenueQueryMs = now;
        lastVenueQueryLoc = loc;


        List<String> macs = getObservedMacsOrEmpty();
        Log.d("TrajectoryMapFragment", "maybeRequestNearbyVenues instance=" + System.identityHashCode(this)
                + " observedMacs=" + macs.size());


        if (macs.isEmpty()) {
            Log.d("TrajectoryMapFragment", "Skipping floorplan request: no MACs yet");
            return;
        }

        Log.d("TrajectoryMapFragment", "Floorplan request @ " +
                loc.latitude + "," + loc.longitude + " macs=" + macs.size());
        if (floorplanRemote == null) {
            Log.w("TrajectoryMapFragment", "floorplanRemote not initialized");
            return;
        }

        floorplanRemote.requestNearbyVenues(
                loc.latitude, loc.longitude, macs,
                new FloorPlanData.VenueCallback() {
                    @Override public void onSuccess(List<FloorPlanData.VenueDto> dtos) {
                        requireActivity().runOnUiThread(() -> {
                            Log.d("TrajectoryMapFragment", "Floorplan response venues=" + dtos.size());

                            List<IndoorMapManager.IndoorVenue> venues = VenueMapper.toIndoorVenues(dtos);
                            Log.d("TrajectoryMapFragment", "Mapped venues=" + venues.size());

                            if (indoorMapManager != null) {
                                indoorMapManager.showVenueOutlines(venues);
                            }
                        });
                    }



                    @Override public void onError(Exception e) {
                         Log.d("TrajectoryMapFragment", "floorplan request failed", e);
                    }
                }
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
    public void updateUserLocation(@NonNull LatLng newLocation, float orientation) {
        Log.d("MapDebug", "updateUserLocation called, gMap=" + gMap + " sensorFusion=" + sensorFusion);
        if (gMap == null) return;
        if (sensorFusion == null) sensorFusion = SensorFusion.getInstance();


        // Keep track of current location
        LatLng oldLocation = this.currentLocation;
        LatLng correctedLocation = newLocation;
        float heightChange = 0f;
        if (sensorFusion != null) {
            float currentElevation = sensorFusion.getElevation();



            if (!Float.isNaN(lastElevation)) {
                heightChange = currentElevation - lastElevation;
            }


            lastElevation = currentElevation;
        }

        if (oldLocation != null && indoorMapManager != null) {
//            correctedLocation = indoorMapManager.indoorLocationCorrection(
//                    oldLocation,
//                    newLocation,
//                    heightChange
//            );

            indoorMapManager.acceptFloorChange(
                    correctedLocation,
                    oldLocation,
                    heightChange
            );
        }

        this.currentLocation = correctedLocation;
        newLocation = correctedLocation;
        Log.d("IndoorTest", "oldLocation = " + oldLocation);
        Log.d("IndoorTest", "newLocation = " + newLocation);
        Log.d("IndoorTest", "heightChange = " + heightChange);

//        if indoor map is active and current venue is known:
//        send oldLocation, newLocation, current floor, and maybe barometer info to IndoorMapManager
//        get back corrected position/floor
//        then update marker/polyline using that corrected result


        // If no marker, create it
        if (orientationMarker == null) {
            orientationMarker = gMap.addMarker(new MarkerOptions()
                    .position(newLocation)
                    .flat(true)
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24)))
            );
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
        } else {
            // Update marker position + orientation
            orientationMarker.setPosition(newLocation);
            orientationMarker.setRotation(orientation);
            // Move camera a bit
            gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
        }

        // Extend polyline if movement occurred
        if (oldLocation != null && !oldLocation.equals(newLocation) && polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            points.add(newLocation);
            polyline.setPoints(points);
        }


        // Update indoor map overlay
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
        }

        // call api
        if (floorplanRemote != null) {
            maybeRequestNearbyVenues(newLocation);
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
     * Remove GNSS marker if user toggles it off
     */
    public void clearGNSS() {
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
    }

    /**
     * Whether user is currently showing GNSS or not
     */
    public boolean isGnssEnabled() {
        return isGnssOn;
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
        currentLocation  = null;

        // Re-create empty polylines with your chosen colors
        if (gMap != null) {
            polyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.RED)
                    .width(5f)
                    .add());
            gnssPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.BLUE)
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
    public interface VenueSelectionListener {
        void onVenueSelected(String venueIdOrName);
        void onVenueCleared();
    }

    private static float distanceMeters(LatLng a, LatLng b) {
        float[] out = new float[1];
        android.location.Location.distanceBetween(
                a.latitude, a.longitude, b.latitude, b.longitude, out
        );
        return out[0];
    }
    private List<String> getObservedMacsOrEmpty() {
        return observedMacs == null ? new ArrayList<>() : new ArrayList<>(observedMacs);
    }


    public void updateObservedMacs(@NonNull List<String> macs) {
        Log.d("TrajectoryMapFragment", "Observed macs updated size=" + macs.size()+ " instance=" + System.identityHashCode(this));
        synchronized (macLock) {
            observedMacs = new ArrayList<>(macs);
        }
    }

    public IndoorMapManager getIndoorMapManager() {
        return indoorMapManager;
    }




}
