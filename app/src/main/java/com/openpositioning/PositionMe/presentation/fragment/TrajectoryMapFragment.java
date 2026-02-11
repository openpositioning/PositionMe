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
//added imports
import com.openpositioning.PositionMe.data.remote.IndoorMapsParser;
import org.json.JSONArray;
import org.json.JSONObject;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.IndoorMapsParser;
import org.json.JSONException;

import java.util.Iterator;
import java.util.Collections;



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

    private boolean indoorMapsRequested = false;

    // UI
    private Spinner switchMapSpinner;

    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private Polygon buildingPolygon;

    //indoormapping polygons
    private com.google.android.gms.maps.model.Polygon lastIndoorPolygon = null;
    private org.json.JSONObject lastVenueJson = null;

    // Stores polygons drawn for indoor floors so we can remove them when switching floors
    private final List<com.google.android.gms.maps.model.Polygon> indoorPolygons = new ArrayList<>();


    private LatLng lastIndoorRequestLoc = null;

    // --- indoor floors state ---
    private org.json.JSONArray floorsArray = null;
    private int currentFloorIndex = 0;

    private java.util.List<String> lastFloorNames = new java.util.ArrayList<>();



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
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);

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
//added indoormaps
                    gMap.setOnPolygonClickListener(polygon -> {
                        Log.d("IndoorMaps", "Polygon clicked!");

                        Object tag = polygon.getTag();
                        if (tag instanceof org.json.JSONObject) {
                            org.json.JSONObject venue = (org.json.JSONObject) tag;
                            Log.d("IndoorMaps", "Clicked venue=" + venue.optString("name"));
                            onVenueSelected(venue);
                        } else {
                            Log.d("IndoorMaps", "Clicked polygon has no venue tag");
                        }
                    });


                    // If we had a pending camera move, apply it now
                    if (hasPendingCameraMove && pendingCameraPosition != null) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }

                  //  drawBuildingPolygon();

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

            //TODO - fix the sensor fusion method to get the elevation (cannot get it from the current method)
//            float elevationVal = sensorFusion.getElevation();
//            indoorMapManager.setCurrentFloor((int)(elevationVal/indoorMapManager.getFloorHeight())
//                    ,true);
        });

        floorUpButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            Log.d("IndoorMaps", "UP pressed current=" + currentFloorIndex + " floors=" + lastFloorNames);

            if (lastFloorNames == null || lastFloorNames.isEmpty()) return;

            int next = currentFloorIndex + 1;
            if (next < lastFloorNames.size()) {
                currentFloorIndex = next;
                showFloor(currentFloorIndex);
            }
        });



        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            Log.d("IndoorMaps", "DOWN pressed current=" + currentFloorIndex + " floors=" + lastFloorNames);

            if (lastFloorNames == null || lastFloorNames.isEmpty()) return;

            int next = currentFloorIndex - 1;
            if (next >= 0) {
                currentFloorIndex = next;
                showFloor(currentFloorIndex);
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
        map.setMapType(GoogleMap.MAP_TYPE_NORMAL);

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
        if (gMap == null) return;

        // Keep track of current location
        LatLng oldLocation = this.currentLocation;
        this.currentLocation = newLocation;

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

        // Request nearby indoor maps ONCE, when first location arrives
        if (!indoorMapsRequested) {
            indoorMapsRequested = true;
            requestNearbyIndoorMaps(newLocation);
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

    //Indoor maps retrieving long and lat

    private void requestNearbyIndoorMaps(@NonNull LatLng loc) {

        List<String> macs = new ArrayList<>();
        List<com.openpositioning.PositionMe.sensors.Wifi> wifiList =
                SensorFusion.getInstance().getWifiList();

        if (wifiList != null) {
            for (com.openpositioning.PositionMe.sensors.Wifi w : wifiList) {
                macs.add(String.valueOf(w.getBssid()));
            }
        }

        Log.d(
                "IndoorMaps",
                "lat=" + loc.latitude
                        + " lon=" + loc.longitude
                        + " macsCount=" + macs.size()
                        + " firstMac=" + (macs.isEmpty() ? "none" : macs.get(0))
        );

        lastIndoorRequestLoc = loc;


        new com.openpositioning.PositionMe.data.remote.ServerCommunications(getContext())
                .requestNearbyIndoorMaps(
                        loc.latitude,
                        loc.longitude,
                        macs,
                        new okhttp3.Callback() {
                            @Override
                            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                                Log.e("IndoorMaps", "Indoor map request failed", e);
                            }

                            @Override
                            public void onResponse(okhttp3.Call call, okhttp3.Response response)
                                    throws java.io.IOException {

                                okhttp3.ResponseBody rb = response.body();
                                if (rb == null) {
                                    Log.e("IndoorMaps", "Response body is null. HTTP=" + response.code());
                                    return;
                                }

                                String json = rb.string();
                                Log.d("IndoorMaps", "HTTP=" + response.code() + " Nearby indoor maps JSON: " + json);

                                try {
                                    IndoorMapsParser.Venue venue = IndoorMapsParser.parseFirstVenue(json);

                                    if (venue == null) {
                                        Log.d("IndoorMaps", "No venue found in response");
                                        return;
                                    }

                                    lastVenueJson = venue.raw;
                                    Log.d("IndoorMaps", "venue=" + venue.name);

                                    requireActivity().runOnUiThread(() -> drawOutlinePolygon(venue.outline));

                                } catch (JSONException e) {
                                    Log.e("IndoorMaps", "JSON parse error", e);
                                }


                            }

                        }
                );
    }
//indoormaps code
    private void drawGeoJsonPolygon(@NonNull JSONObject polygon, boolean isIndoor) {
        JSONArray coords = polygon.optJSONArray("coordinates");
        if (coords == null || coords.length() == 0) return;

        JSONArray ring = coords.optJSONArray(0);
        if (ring == null || ring.length() < 3) return;

        List<LatLng> points = new ArrayList<>();
        for (int i = 0; i < ring.length(); i++) {
            JSONArray p = ring.optJSONArray(i);
            if (p == null || p.length() < 2) continue;

            double lon = p.optDouble(0);
            double lat = p.optDouble(1);
            points.add(new LatLng(lat, lon));


        }

        if (points.size() < 3) return;

        com.google.android.gms.maps.model.Polygon poly =
                gMap.addPolygon(new PolygonOptions().addAll(points).clickable(true));

        poly.setTag(lastVenueJson);   // <-- ADD THIS LINE (tags polygon with venue JSON)
        lastIndoorPolygon = poly;


        // --- DEBUG: show 3 reference points from the outline ---
        gMap.addMarker(new MarkerOptions().position(points.get(0)).title("Outline P0"));
        gMap.addMarker(new MarkerOptions().position(points.get(points.size() / 2)).title("Outline Pmid"));
        gMap.addMarker(new MarkerOptions().position(points.get(points.size() - 1)).title("Outline Plast"));


        LatLngBounds.Builder b = new LatLngBounds.Builder();
        for (LatLng pnt : points) b.include(pnt);
        gMap.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 80));


        Log.d("IndoorMaps", "Polygon drawn with " + points.size() + " points");
        double latSum = 0, lonSum = 0;
        for (LatLng p : points) {
            latSum += p.latitude;
            lonSum += p.longitude;
        }
        LatLng centroid = new LatLng(latSum / points.size(), lonSum / points.size());

        if (lastIndoorRequestLoc != null) {
            double d = haversineMeters(
                    lastIndoorRequestLoc.latitude, lastIndoorRequestLoc.longitude,
                    centroid.latitude, centroid.longitude
            );
            Log.d("IndoorMaps", "request->centroid offset(m)=" + d);
        }

    }

    private void drawGeoJsonMultiPolygon(@NonNull JSONObject multiPolygon, boolean isIndoor) {
        JSONArray coords = multiPolygon.optJSONArray("coordinates");
        if (coords == null || coords.length() == 0) return;

        // MultiPolygon: coordinates = [ [ [ [lon,lat], ... ] ], [ ... ] ]
        // We'll draw the first polygon's outer ring
        JSONArray firstPoly = coords.optJSONArray(0);
        if (firstPoly == null || firstPoly.length() == 0) return;

        JSONArray ring = firstPoly.optJSONArray(0);
        if (ring == null || ring.length() < 3) return;

        List<LatLng> points = new ArrayList<>();
        for (int i = 0; i < ring.length(); i++) {
            JSONArray p = ring.optJSONArray(i);
            if (p == null || p.length() < 2) continue;

            double lon = p.optDouble(0);
            double lat = p.optDouble(1);
            points.add(new LatLng(lat, lon));
        }

        if (points.size() < 3) return;

        com.google.android.gms.maps.model.Polygon poly =
                gMap.addPolygon(new PolygonOptions().addAll(points).clickable(true));

        poly.setTag(lastVenueJson);   // <-- ADD THIS LINE (tags polygon with venue JSON)
        lastIndoorPolygon = poly;

        gMap.addMarker(new MarkerOptions().position(points.get(0)).title("Outline P0"));
        gMap.addMarker(new MarkerOptions().position(points.get(points.size() / 2)).title("Outline Pmid"));
        gMap.addMarker(new MarkerOptions().position(points.get(points.size() - 1)).title("Outline Plast"));

        Log.d("IndoorMaps", "MultiPolygon drawn with " + points.size() + " points");

        double latSum = 0, lonSum = 0;
        for (LatLng p : points) {
            latSum += p.latitude;
            lonSum += p.longitude;
        }
        LatLng centroid = new LatLng(latSum / points.size(), lonSum / points.size());

        if (lastIndoorRequestLoc != null) {
            double d = haversineMeters(
                    lastIndoorRequestLoc.latitude, lastIndoorRequestLoc.longitude,
                    centroid.latitude, centroid.longitude
            );
            Log.d("IndoorMaps", "request->centroid offset(m)=" + d);
        }
    }


    private void onVenueSelected(@NonNull org.json.JSONObject venue) {
        Log.d("IndoorMaps", "Selected venue name=" + venue.optString("name"));
        Log.d("IndoorMaps", "Venue keys=" + venue.names());

        // Common possibilities: "floors", "floorplans", "levels"
// --- Try common keys first ---
        floorsArray = venue.optJSONArray("floors");
        if (floorsArray == null) floorsArray = venue.optJSONArray("floorplans");
        if (floorsArray == null) floorsArray = venue.optJSONArray("levels");

// --- Now handle map_shapes robustly (could be JSONArray / JSONObject / String) ---
        if (floorsArray == null) {
            Object ms = venue.opt("map_shapes");
            Log.d("IndoorMaps", "map_shapes raw type=" + (ms == null ? "null" : ms.getClass().getSimpleName()));

            try {
                if (ms instanceof org.json.JSONArray) {
                    floorsArray = (org.json.JSONArray) ms;
                } else if (ms instanceof org.json.JSONObject) {
                    // Sometimes map_shapes is a single object – wrap it in an array
                    floorsArray = new org.json.JSONArray();
                    floorsArray.put(ms);
                } else if (ms instanceof String) {
                    // Sometimes map_shapes is a JSON string
                    String s = (String) ms;
                    s = s.trim();
                    if (s.startsWith("[")) {
                        floorsArray = new org.json.JSONArray(s);
                    } else if (s.startsWith("{")) {
                        floorsArray = new org.json.JSONArray();
                        floorsArray.put(new org.json.JSONObject(s));
                    }
                }
            } catch (org.json.JSONException e) {
                Log.e("IndoorMaps", "Failed to parse map_shapes", e);
            }
        }

// --- Debug what we actually got ---
        if (floorsArray != null && floorsArray.length() > 0) {
            Object first = floorsArray.opt(0);
            Log.d("IndoorMaps", "floorsArray[0] type=" + (first == null ? "null" : first.getClass().getSimpleName()));
            Log.d("IndoorMaps", "floorsArray[0]=" + String.valueOf(first));
        }

        if (floorsArray == null || floorsArray.length() == 0) {
            Log.d("IndoorMaps", "No floors data found (floors/floorplans/levels/map_shapes). Full venue=" + venue);
            setFloorControlsVisibility(View.GONE);
            return;
        }

// Show UI now that we know floors exist
        setFloorControlsVisibility(View.VISIBLE);

        currentFloorIndex = 0;
        showFloor(currentFloorIndex);

    }

    private void showFloor(int index) {
        if (floorsArray == null) return;
        if (index < 0 || index >= floorsArray.length()) return;

        JSONObject floorObj = floorsArray.optJSONObject(index);
        if (floorObj == null) return;



        // Get floor keys (e.g. "B1", "GF")
        Iterator<String> keys = floorObj.keys();
        List<String> floorNames = new ArrayList<>();
        while (keys.hasNext()) floorNames.add(keys.next());

        Collections.sort(floorNames);
        Log.d("IndoorMaps", "Available floors=" + floorNames + " currentFloorIndex=" + index);
        lastFloorNames = floorNames;

        if (floorNames.isEmpty()) return;

        // Clamp index in case
        int safeIndex = Math.min(index, floorNames.size() - 1);
        String floorKey = floorNames.get(safeIndex);

        Log.d("IndoorMaps", "Rendering floor=" + floorKey);

        JSONObject geoJson = floorObj.optJSONObject(floorKey);
        if (geoJson == null) return;

        // Draw the FeatureCollection for this floor


        // Optional: update UI label if you have one
        // floorLabel.setText("Floor: " + floorKey);
    }


    private void clearIndoorMapShapes() {
        for (com.google.android.gms.maps.model.Polygon p : indoorPolygons) {
            p.remove();
        }
        indoorPolygons.clear();
    }

    private void drawIndoorFeatureCollection(@NonNull JSONObject featureCollection) {
        JSONArray features = featureCollection.optJSONArray("features");
        if (features == null) return;

        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.optJSONObject(i);
            if (feature == null) continue;

            JSONObject geometry = feature.optJSONObject("geometry");
            if (geometry == null) continue;

            String type = geometry.optString("type");

            if ("Polygon".equals(type)) {
                drawGeoJsonPolygon(geometry, true);
            } else if ("MultiPolygon".equals(type)) {
                drawGeoJsonMultiPolygon(geometry, true);
            }
        }
    }





    private void drawOutlinePolygon(@NonNull JSONObject outline) {
        if (gMap == null) return;

        String type = outline.optString("type", "");
        JSONObject geometry = null;

        if ("FeatureCollection".equals(type)) {
            // outline = { "type":"FeatureCollection", "features":[ { "geometry": {...} } ] }
            JSONArray features = outline.optJSONArray("features");
            if (features == null || features.length() == 0) return;

            JSONObject firstFeature = features.optJSONObject(0);
            if (firstFeature == null) return;

            geometry = firstFeature.optJSONObject("geometry");
            if (geometry == null) return;

        } else if ("Feature".equals(type)) {
            geometry = outline.optJSONObject("geometry");
            if (geometry == null) return;

        } else {
            // outline might already be a geometry object
            geometry = outline;
        }

        String geomType = geometry.optString("type", "");
        if ("Polygon".equals(geomType)) {
            drawGeoJsonPolygon(geometry, false);
        } else if ("MultiPolygon".equals(geomType)) {
            drawGeoJsonMultiPolygon(geometry, false);
        } else {
            Log.d("IndoorMaps", "Unsupported geometry type: " + geomType);
        }
    }

    private double haversineMeters(
            double lat1, double lon1,
            double lat2, double lon2
    ) {
        double R = 6371000; // metres
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }


    //indoormaps code ends
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
