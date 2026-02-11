package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
import androidx.core.content.ContextCompat;
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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

//Indoormapping Imports
import java.io.IOException;
import com.openpositioning.PositionMe.data.remote.IndoorMapsParser;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

import androidx.fragment.app.FragmentActivity;
import org.json.JSONObject;
import org.json.JSONArray;





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


    // UI
    private Spinner switchMapSpinner;

    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;

    private volatile boolean hasRotationFix = false;

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private Polygon buildingPolygon;

    //Indoor Mapping
    private final List<com.google.android.gms.maps.model.Polygon> apiVenueOutlines = new ArrayList<>();
    @Nullable
    private JSONObject lastVenueJson = null;

    private final List<FloorLayer> mapShapeFloors = new ArrayList<>();
    private int currentMapShapeFloor = 0;
    // When true, keep current map_shapes visible and ignore new incoming indoor map responses
    private boolean mapShapesLocked = false;

    private final List<Polygon> hardcodedPolygons = new ArrayList<>();



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

                    gMap.setOnPolygonClickListener(polygon -> {
                        Object tag = polygon.getTag();
                        if (tag instanceof JSONObject) {
                            handleVenueClick((JSONObject) tag);
                        }
                    });

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
            if (hasActiveMapShapes()) {
                changeMapShapeFloor(1);
            } else if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
            }
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (hasActiveMapShapes()) {
                changeMapShapeFloor(-1);
            } else if (indoorMapManager != null) {
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

        //Indoormapping requesting
        maybeRequestIndoorMaps(newLocation);
        //Indoormapping requesting end

        // Update indoor map overlay
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);
        }

        updateFloorControlVisibility();
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

    private void updateFloorControlVisibility() {
        if (hasActiveMapShapes()) {
            setFloorControlsVisibility(View.VISIBLE);
        } else if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
            setFloorControlsVisibility(View.VISIBLE);
        } else {
            setFloorControlsVisibility(View.GONE);
        }
    }

    private boolean hasActiveMapShapes() {
        return !mapShapeFloors.isEmpty();
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


    /**
     * Clear any existing venue outline polygons added from the API before drawing new ones.
     */
    private void clearApiVenueOutlines() {
        for (com.google.android.gms.maps.model.Polygon p : apiVenueOutlines) p.remove();
        apiVenueOutlines.clear();
    }


    /**
     * Request indoor map data from the API for the given location,
     * but only if user is not currently viewing map_shapes from a previously selected venue.
     */
    private void maybeRequestIndoorMaps(@NonNull LatLng loc) {
        // Do not fetch new indoor data while user is viewing a selected venue's floors.
        if (mapShapesLocked) return;
        requestIndoorMapsApiThenFallback(loc);
    }


    /**
     * Request indoor map data from the API for the given location,
     * then fall back to hardcoded polygons on failure.
     */
    private void requestIndoorMapsApiThenFallback(@NonNull LatLng loc) {
        // Empty list passed for case of MACs
        List<String> macs = new ArrayList<>();

        new ServerCommunications(requireContext())
                .requestNearbyIndoorMaps(loc.latitude, loc.longitude, macs, new Callback() {

                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        FragmentActivity act = getActivity();
                        if (act == null) return;
                        act.runOnUiThread(() -> showHardcodedFallback(loc));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        if (!response.isSuccessful() || response.body() == null) {
                            FragmentActivity act = getActivity();
                            if (act == null) return;
                            act.runOnUiThread(() -> showHardcodedFallback(loc));
                            return;
                        }

                        String json = response.body().string();
                        android.util.Log.d("INDOOR_JSON", json);

                        IndoorMapsParser.Venue v = null;
                        try {
                            v = IndoorMapsParser.parseFirstVenue(json);
                        } catch (Exception ignored) { }

                        final IndoorMapsParser.Venue finalV = v;

                        FragmentActivity act = getActivity();
                        if (act == null) return;

                        act.runOnUiThread(() -> {
                            // If user is viewing map_shapes, do not override until they tap a new venue
                            if (mapShapesLocked) return;

                            clearApiVenueOutlines();
                            clearMapShapeFloors();
                            updateFloorControlVisibility();

                            if (finalV == null || finalV.outline == null) {
                                showHardcodedFallback(loc);
                                return;
                            }

                            setHardcodedPolygonsVisible(false);
                            lastVenueJson = finalV.raw;

                            drawOutlinePolygon(finalV.outline);

                        });
                    }
                });
    }


    /**
     * Draw a polygon on the map from the given GeoJSON outline
     */
    private void drawOutlinePolygon(@NonNull JSONObject outline) {
        if (gMap == null) return;

        JSONObject geometry = extractGeometry(outline);
        if (geometry == null) return;

        String type = geometry.optString("type", "");

        if ("Polygon".equals(type)) {
            Polygon p = addPolygonFromGeoJson(geometry);
            if (p != null) {
                p.setClickable(true);
                p.setTag(lastVenueJson);
                apiVenueOutlines.add(p);
            }
        } else if ("MultiPolygon".equals(type)) {
            JSONArray coords = geometry.optJSONArray("coordinates");
            if (coords == null || coords.length() == 0) return;

            JSONArray firstPoly = coords.optJSONArray(0);
            if (firstPoly == null) return;

            JSONObject fake = new JSONObject();
            try {
                fake.put("type", "Polygon");
                fake.put("coordinates", firstPoly);
            } catch (Exception ignored) {}

            Polygon p = addPolygonFromGeoJson(fake);
            if (p != null) {
                p.setClickable(true);
                p.setTag(lastVenueJson);
                apiVenueOutlines.add(p);
            }
        }
    }


    /**
     * Show hardcoded fallback polygons (e.g., Nucleus/Murchison),
     * when indoor map API fails or returns no data.
     */
    private void showHardcodedFallback(@NonNull LatLng loc) {
        clearMapShapeFloors();
        mapShapesLocked = false;

        setHardcodedPolygonsVisible(true);
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(loc);
        }
        updateFloorControlVisibility();
    }


    private void drawApiOutline(@NonNull org.json.JSONObject outline, @NonNull org.json.JSONObject venueTag) {
        if (gMap == null) return;

        org.json.JSONObject geometry = extractGeometry(outline);
        if (geometry == null) return;

        String type = geometry.optString("type", "");
        if ("Polygon".equals(type)) {
            com.google.android.gms.maps.model.Polygon p = addPolygonFromGeoJson(geometry);
            if (p != null) {
                p.setClickable(true);
                p.setTag(venueTag);
                apiVenueOutlines.add(p);
            }
        }
    }



    private org.json.JSONObject extractGeometry(@NonNull org.json.JSONObject geo) {
        String t = geo.optString("type", "");
        if ("FeatureCollection".equals(t)) {
            org.json.JSONArray features = geo.optJSONArray("features");
            if (features == null || features.length() == 0) return null;
            org.json.JSONObject f0 = features.optJSONObject(0);
            return f0 == null ? null : f0.optJSONObject("geometry");
        } else if ("Feature".equals(t)) {
            return geo.optJSONObject("geometry");
        }
        return geo; // sometimes already geometry
    }

    private com.google.android.gms.maps.model.Polygon addPolygonFromGeoJson(@NonNull org.json.JSONObject polyGeom) {
        org.json.JSONArray coords = polyGeom.optJSONArray("coordinates");
        if (coords == null || coords.length() == 0) return null;

        org.json.JSONArray ring = coords.optJSONArray(0);
        if (ring == null) return null;

        java.util.List<com.google.android.gms.maps.model.LatLng> pts = new java.util.ArrayList<>();
        for (int i = 0; i < ring.length(); i++) {
            org.json.JSONArray p = ring.optJSONArray(i);
            if (p == null || p.length() < 2) continue;
            double lon = p.optDouble(0);
            double lat = p.optDouble(1);
            pts.add(new com.google.android.gms.maps.model.LatLng(lat, lon));
        }
        if (pts.size() < 3) return null;

        return gMap.addPolygon(new com.google.android.gms.maps.model.PolygonOptions().addAll(pts));
    }

    private void setHardcodedPolygonsVisible(boolean visible) {
        for (Polygon p : hardcodedPolygons) {
            p.setVisible(visible);
        }
    }


    /**
     * Handle a click on a venue polygon by parsing its map shapes and displaying them as floor layers.
     */
    private void handleVenueClick(@NonNull JSONObject venueJson) {
        JSONArray mapShapesArray = optJsonArrayFlexible(venueJson, "map_shapes");
        JSONObject mapShapesObject = optJsonObjectFlexible(venueJson, "map_shapes");

        clearMapShapeFloors();
        mapShapesLocked = true;

        List<FloorLayer> layers = new ArrayList<>();
        if (mapShapesArray != null) {
            layers.addAll(buildFloorsFromArray(mapShapesArray));
        }
        if (mapShapesObject != null) {
            layers.addAll(buildFloorsFromObject(mapShapesObject));
        }

        if (layers.isEmpty()) {
            updateFloorControlVisibility();
            return;
        }

        layers.sort((a, b) -> Integer.compare(a.floorIndex, b.floorIndex));

        mapShapeFloors.addAll(layers);
        currentMapShapeFloor = 0;
        showMapShapeFloor(currentMapShapeFloor);
        setHardcodedPolygonsVisible(false);
        updateFloorControlVisibility();
    }

    private List<FloorLayer> buildFloorsFromArray(@NonNull JSONArray arr) {
        List<FloorLayer> layers = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;

            int floorIndex = extractFloorIndex(item, i);
            String label = extractFloorLabel(item, floorIndex);
            FloorLayer layer = findOrCreateLayer(layers, floorIndex, label);

            boolean added = addShapesToLayer(item, layer, layers);
            if (!added) {
                // Some APIs may nest shapes under "geojson" or "geometry" directly on the item
                JSONObject geom = extractGeometryFromShape(item);
                if (geom != null) {
                    addGeometryToLayer(geom, layer, layers);
                }
            }
        }
        return layers;
    }

    private List<FloorLayer> buildFloorsFromObject(@NonNull JSONObject obj) {
        List<FloorLayer> layers = new ArrayList<>();
        int fallbackIndex = 0;
        for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONObject shape = obj.optJSONObject(key);
            if (shape == null) continue;

            int parsedFromKey = deriveFloorIndexFromLabel(key, fallbackIndex);
            int floorIndex = extractFloorIndex(shape, parsedFromKey);
            String label = extractFloorLabel(shape, floorIndex);
            if (label.isEmpty()) label = key; // keep the key (e.g., "B1") as the label when provided

            FloorLayer layer = findOrCreateLayer(layers, floorIndex, label);

            boolean added = addShapesToLayer(shape, layer, layers);
            if (!added) {
                JSONObject geom = extractGeometryFromShape(shape);
                if (geom != null) {
                    addGeometryToLayer(geom, layer, layers);
                }
            }

            fallbackIndex++;
        }
        return layers;
    }

    private FloorLayer findOrCreateLayer(@NonNull List<FloorLayer> layers,
                                         int floorIndex,
                                         @NonNull String label) {
        for (FloorLayer l : layers) {
            if (l.floorIndex == floorIndex) {
                return l;
            }
        }
        FloorLayer created = new FloorLayer(floorIndex, label);
        layers.add(created);
        return created;
    }


    /**
     * Add shapes from the given floor entry to the specified layer
     */
    private boolean addShapesToLayer(@NonNull JSONObject floorEntry,
                                     @NonNull FloorLayer layer,
                                     @NonNull List<FloorLayer> layers) {
        JSONArray shapes = floorEntry.optJSONArray("shapes");
        if (shapes == null || shapes.length() == 0) return false;

        boolean added = false;
        for (int i = 0; i < shapes.length(); i++) {
            JSONObject shape = shapes.optJSONObject(i);
            if (shape == null) continue;
            JSONObject geom = extractGeometryFromShape(shape);
            if (geom != null) {
                addGeometryToLayer(geom, layer, layers);
                added = true;
            }
        }
        return added;
    }


    /**
     * Recursively add geometry to the specified floor layer
     */
    private void addGeometryToLayer(@NonNull JSONObject geometry,
                                    @NonNull FloorLayer layer,
                                    @NonNull List<FloorLayer> layers) {
        String type = geometry.optString("type", "");
        if ("FeatureCollection".equals(type)) {
            JSONArray features = geometry.optJSONArray("features");
            if (features != null) {
                for (int i = 0; i < features.length(); i++) {
                    JSONObject f = features.optJSONObject(i);
                    if (f == null) continue;

                    // Prefer floor info on the feature or its properties; fall back to the current layer.
                    JSONObject props = f.optJSONObject("properties");
                    int featureFloor = extractFloorIndex(f, layer.floorIndex);
                    String featureLabel = extractFloorLabel(f, featureFloor);
                    if (props != null) {
                        featureFloor = extractFloorIndex(props, featureFloor);
                        String labelFromProps = extractFloorLabel(props, featureFloor);
                        if (!labelFromProps.isEmpty()) featureLabel = labelFromProps;
                    }

                    FloorLayer target = findOrCreateLayer(layers, featureFloor,
                            featureLabel.isEmpty() ? layer.label : featureLabel);

                    JSONObject g = f.optJSONObject("geometry");
                    if (g != null) addGeometryToLayer(g, target, layers);
                }
            }
        } else if ("Feature".equals(type)) {
            JSONObject props = geometry.optJSONObject("properties");
            int featureFloor = extractFloorIndex(geometry, layer.floorIndex);
            String featureLabel = extractFloorLabel(geometry, featureFloor);
            if (props != null) {
                featureFloor = extractFloorIndex(props, featureFloor);
                String labelFromProps = extractFloorLabel(props, featureFloor);
                if (!labelFromProps.isEmpty()) featureLabel = labelFromProps;
            }

            FloorLayer target = findOrCreateLayer(layers, featureFloor,
                    featureLabel.isEmpty() ? layer.label : featureLabel);

            JSONObject g = geometry.optJSONObject("geometry");
            if (g != null) addGeometryToLayer(g, target, layers);
        } else if ("Polygon".equals(type)) {
            Polygon p = addPolygonFromGeoJson(geometry);
            if (p != null) {
                styleMapShapePolygon(p);
                layer.polygons.add(p);
            }
        } else if ("MultiPolygon".equals(type)) {
            JSONArray coords = geometry.optJSONArray("coordinates");
            if (coords == null) return;
            for (int i = 0; i < coords.length(); i++) {
                JSONArray poly = coords.optJSONArray(i);
                if (poly == null) continue;
                JSONObject fake = new JSONObject();
                try {
                    fake.put("type", "Polygon");
                    fake.put("coordinates", poly);
                    Polygon p = addPolygonFromGeoJson(fake);
                    if (p != null) {
                        styleMapShapePolygon(p);
                        layer.polygons.add(p);
                    }
                } catch (Exception ignored) { }
            }
        }
    }

    private void styleMapShapePolygon(@NonNull Polygon p) {
        p.setStrokeColor(Color.MAGENTA);
        p.setStrokeWidth(6f);
        p.setFillColor(Color.argb(40, 156, 39, 176));
        p.setClickable(false);
        p.setVisible(false);
        p.setZIndex(3f);
    }


    /**
     * Extract geometry from a shape object,
     * trying multiple common patterns to be flexible with different APIs or data formats.
     */
    private JSONObject extractGeometryFromShape(@NonNull JSONObject shape) {
        JSONObject geometry = shape.optJSONObject("geometry");
        if (geometry != null) return extractGeometry(geometry);

        JSONObject geojson = shape.optJSONObject("geojson");
        if (geojson != null) return extractGeometry(geojson);

        String geomStr = shape.optString("geometry", null);
        if (geomStr != null) {
            String trimmed = geomStr.trim();
            try {
                if (trimmed.startsWith("{")) {
                    return extractGeometry(new JSONObject(trimmed));
                }
            } catch (Exception ignored) { }
        }

        // If the object already looks like geometry (has type), return it directly
        if (shape.has("type")) return extractGeometry(shape);

        return null;
    }

    private JSONArray optJsonArrayFlexible(@NonNull JSONObject obj, @NonNull String key) {
        Object raw = obj.opt(key);
        if (raw instanceof JSONArray) return (JSONArray) raw;
        if (raw instanceof String) {
            String s = sanitizeJsonString((String) raw);
            try {
                if (s.startsWith("[")) return new JSONArray(s);
            } catch (Exception ignored) { }
        }
        return null;
    }

    private JSONObject optJsonObjectFlexible(@NonNull JSONObject obj, @NonNull String key) {
        Object raw = obj.opt(key);
        if (raw instanceof JSONObject) return (JSONObject) raw;
        if (raw instanceof String) {
            String s = sanitizeJsonString((String) raw);
            try {
                if (s.startsWith("{")) return new JSONObject(s);
            } catch (Exception ignored) { }
        }
        return null;
    }

    private String sanitizeJsonString(@NonNull String s) {
        String trimmed = s.trim();
        // Handle cases like ""{...}"" or '"{...}' where JSON is wrapped twice
        while ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            if (trimmed.length() <= 2) break;
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed.trim();
    }


    /**
     * Derive a floor index from a label string,
     * with some common heuristics (e.g., "B1" -> -1, "F1" -> 1, "G" -> 0).
     */
    private int deriveFloorIndexFromLabel(@NonNull String label, int fallbackIndex) {
        String t = label.trim().toUpperCase(Locale.US);
        if (t.isEmpty()) return fallbackIndex;

        if ("GF".equals(t) || "G".equals(t) || "GROUND".equals(t)) return 0;

        if (t.startsWith("B") && t.length() > 1) {
            try {
                int n = Integer.parseInt(t.substring(1));
                return -n; // B1 -> -1
            } catch (NumberFormatException ignored) { }
        }

        if (t.startsWith("F") && t.length() > 1) {
            try {
                return Integer.parseInt(t.substring(1)); // F1 -> 1
            } catch (NumberFormatException ignored) { }
        }

        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException ignored) { }

        return fallbackIndex;
    }

    private int extractFloorIndex(@NonNull JSONObject obj, int defaultIndex) {
        if (obj.has("floor_index")) return obj.optInt("floor_index", defaultIndex);
        if (obj.has("floor")) return obj.optInt("floor", defaultIndex);
        if (obj.has("level")) return obj.optInt("level", defaultIndex);
        if (obj.has("z_index")) return obj.optInt("z_index", defaultIndex);
        if (obj.has("z")) return obj.optInt("z", defaultIndex);
        return defaultIndex;
    }

    private String extractFloorLabel(@NonNull JSONObject obj, int floorIndex) {
        String label = obj.optString("label", "");
        if (label.isEmpty()) label = obj.optString("name", "");
        if (label.isEmpty()) label = obj.optString("title", "");
        if (label.isEmpty()) label = "Floor " + floorIndex;
        return label;
    }

    private void clearMapShapeFloors() {
        for (FloorLayer layer : mapShapeFloors) {
            for (Polygon p : layer.polygons) {
                if (p != null) p.remove();
            }
        }
        mapShapeFloors.clear();
        currentMapShapeFloor = 0;
        mapShapesLocked = false;
    }

    private void showMapShapeFloor(int floorIndex) {
        if (mapShapeFloors.isEmpty()) return;
        if (floorIndex < 0 || floorIndex >= mapShapeFloors.size()) return;

        for (int i = 0; i < mapShapeFloors.size(); i++) {
            boolean visible = i == floorIndex;
            for (Polygon p : mapShapeFloors.get(i).polygons) {
                if (p != null) p.setVisible(visible);
            }
        }
        currentMapShapeFloor = floorIndex;
    }

    private void changeMapShapeFloor(int delta) {
        if (!hasActiveMapShapes()) return;
        int next = currentMapShapeFloor + delta;
        if (next < 0 || next >= mapShapeFloors.size()) return;
        showMapShapeFloor(next);
    }

    private static final class FloorLayer {
        final int floorIndex;
        final String label;
        final List<Polygon> polygons = new ArrayList<>();

        FloorLayer(int floorIndex, @NonNull String label) {
            this.floorIndex = floorIndex;
            this.label = label;
        }
    }



    private void drawBuildingPolygon() {
        if (gMap == null) {
            Log.e("TrajectoryMapFragment", "GoogleMap is not ready");
            return;
        }

        // NUCLEUS venue polygon vertices
        LatLng nucleus1 = new LatLng(55.92279538827796, -3.174612147506538);
        LatLng nucleus2 = new LatLng(55.92278121423647, -3.174107900816096);
        LatLng nucleus3 = new LatLng(55.92288405733954, -3.173843694667146);
        LatLng nucleus4 = new LatLng(55.92331786793876, -3.173832892645086);
        LatLng nucleus5 = new LatLng(55.923337194112555, -3.1746284301397387);


        // NKML venue polygon vertices
        LatLng nkml1 = new LatLng(55.9230343434213, -3.1751847990731954);
        LatLng nkml2 = new LatLng(55.923032840563366, -3.174777103346131);
        LatLng nkml4 = new LatLng(55.92280139974615, -3.175195527934348);
        LatLng nkml3 = new LatLng(55.922793885410734, -3.1747958788136867);

        // FJB venue polygon vertices
        LatLng fjb1 = new LatLng(55.92269205199916, -3.1729563477188774);//left top
        LatLng fjb2 = new LatLng(55.922822801570994, -3.172594249522305);
        LatLng fjb3 = new LatLng(55.92223512226413, -3.171921917547244);
        LatLng fjb4 = new LatLng(55.9221071265519, -3.1722813131202097);

        // FARADAY venue polygon vertices
        LatLng faraday1 = new LatLng(55.92242866264128, -3.1719553662011815);
        LatLng faraday2 = new LatLng(55.9224966752294, -3.1717846714743474);
        LatLng faraday3 = new LatLng(55.922271383074154, -3.1715191463437162);
        LatLng faraday4 = new LatLng(55.92220124468304, -3.171705013935158);


        /**
         * Venue outline polygon properties: different colors for different venues
         * Red outline for Nucleus, blue for NKML, green for FJB, yellow for Faraday
         */
        PolygonOptions buildingPolygonOptions = new PolygonOptions()
                .add(nucleus1, nucleus2, nucleus3, nucleus4, nucleus5)
                .strokeColor(Color.RED)
                .strokeWidth(10f)
                .zIndex(1);

        PolygonOptions buildingPolygonOptions2 = new PolygonOptions()
                .add(nkml1, nkml2, nkml3, nkml4, nkml1)
                .strokeColor(Color.BLUE)
                .strokeWidth(10f)
                .zIndex(1);

        PolygonOptions buildingPolygonOptions3 = new PolygonOptions()
                .add(fjb1, fjb2, fjb3, fjb4, fjb1)
                .strokeColor(Color.GREEN)
                .strokeWidth(10f)
                .zIndex(1);

        PolygonOptions buildingPolygonOptions4 = new PolygonOptions()
                .add(faraday1, faraday2, faraday3, faraday4, faraday1)
                .strokeColor(Color.YELLOW)
                .strokeWidth(10f)
                .zIndex(1);


        // Remove the old polygon if it exists
        for (Polygon p : hardcodedPolygons) {
            if (p != null) p.remove();
        }
        hardcodedPolygons.clear();
        buildingPolygon = null;

        // Add the polygon to the map
        Polygon p1 = gMap.addPolygon(buildingPolygonOptions);
        Polygon p2 = gMap.addPolygon(buildingPolygonOptions2);
        Polygon p3 = gMap.addPolygon(buildingPolygonOptions3);
        Polygon p4 = gMap.addPolygon(buildingPolygonOptions4);

        buildingPolygon = p1;

        // Store them so we can hide/show later
        hardcodedPolygons.add(p1);
        hardcodedPolygons.add(p2);
        hardcodedPolygons.add(p3);
        hardcodedPolygons.add(p4);

        // TESTING: Log the number of vertices in the venue building polygons
        Log.d("TrajectoryMapFragment",
                "Hardcoded polygons added. nucleus vertex count: " + (p1 != null ? p1.getPoints().size() : -1));

        Log.d("TrajectoryMapFragment",
                "Hardcoded polygons added. NKML vertex count: " + (p2 != null ? p2.getPoints().size() : -1));

        Log.d("TrajectoryMapFragment",
                "Hardcoded polygons added. FJB vertex count: " + (p3 != null ? p3.getPoints().size() : -1));

        Log.d("TrajectoryMapFragment",
                "Hardcoded polygons added. FARADAY vertex count: " + (p4 != null ? p4.getPoints().size() : -1));
    }


    /**
     * Defines numbered test point marker to be added to the map
     *
     * Anchored to improve visualisation of user current position during recording.
     */
    public void addTestPointMarker(@NonNull LatLng currentPosition, int markerCount) {
        if (gMap == null) return;

        gMap.addMarker(new MarkerOptions()
                .position(currentPosition)
                .icon(markerNumber(markerCount)) // Applies defined custom icon to marker
                .anchor(0.5f, 0.94f)  // Centre marker on current location
                .zIndex(10f) // Top overlay priority above other map elements
                .title("Test Point " + markerCount)
        );
    }

    /**
     * Defines custom map marker icon:
     * - Applies scaled red maps marker drawable
     * - Defines white circular background for number placement
     * - Defines marker number centred on the white circle
     */
    private BitmapDescriptor markerNumber(int markerCount) {
        // Loads created red marker drawable
        Drawable pinDrawable = ContextCompat.getDrawable(getContext(), R.drawable.baseline_location_on_24_red);

        // Scale factor to control marker size
        float scale = 1.2f;

        // Define bitmap dimensions based on intrinsic size and scaling factor
        int width = (int) (pinDrawable.getIntrinsicWidth() * scale);
        int height = (int) (pinDrawable.getIntrinsicHeight() * scale);

        // Define empty bitmap and canvas to draw marker elements
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Draw the red marker
        pinDrawable.setBounds(0, 0, width, height);
        pinDrawable.draw(canvas);

        // Draw white number container
        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.WHITE);

        // Centre on red marker
        float cx = width / 2f;
        float cy = height * 0.42f;
        float radius = width * 0.22f;

        canvas.drawCircle(cx, cy, radius, circlePaint);

        // Add black number text
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(width * 0.35f);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = cy - (fm.ascent + fm.descent) / 2f; // Y position for text vertical alignment with white circle background

        // Draw marker number to centre of white circle
        canvas.drawText(String.valueOf(markerCount), cx, textY, textPaint);

        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

}