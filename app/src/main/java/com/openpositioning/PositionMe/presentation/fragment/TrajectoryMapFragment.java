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
//琛
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.model.LatLngBounds;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import android.os.Handler;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.model.LatLng;
import android.content.Context;
import com.openpositioning.PositionMe.presentation.fragment.RecordingFragment;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import android.view.View;





//end

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
//琛
import java.util.Arrays;
import android.widget.Toast;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
//end


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

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    //琛
    private com.google.android.material.floatingactionbutton.FloatingActionButton exitIndoorButton;
    private ServerCommunications serverCommunications;

    private final Handler indoorHandler = new Handler();
    private Runnable indoorTask;

    private float indoorPrevPosX = 0f;
    private float indoorPrevPosY = 0f;
    private boolean indoorRunning = false;

    // ===== Floorplan request timing control =====
    private boolean hasReceivedFloorplan = false;
    private long lastFloorplanRequestTime = 0L;

    // 间隔（毫秒）
    private static final long FLOORPLAN_FAST_INTERVAL_MS = 5_000;   // 5 秒
    private static final long FLOORPLAN_SLOW_INTERVAL_MS = 30_000;  // 30 秒

    //end
    private Button switchColorButton;
    private Polygon buildingPolygon;
    //琛
    // ===== Remote floorplan drawing state =====
    private static class NearbyVenue {
        final String name;
        final String outlineGeoJson;   // FeatureCollection string
        final String mapShapesJson;    // JSONObject string: { "B1": {...}, "GF": {...} }

        NearbyVenue(String name, String outlineGeoJson, String mapShapesJson) {
            this.name = name;
            this.outlineGeoJson = outlineGeoJson;
            this.mapShapesJson = mapShapesJson;
        }
    }

    private final List<Polygon> nearbyVenuePolygons = new ArrayList<>();
    private final List<Polyline> indoorShapeLines = new ArrayList<>();

    private NearbyVenue selectedVenue = null;
    private final List<String> availableFloors = new ArrayList<>();
    private int currentFloorIdx = 0;

    //end

    public TrajectoryMapFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        //琛
        android.util.Log.e("Floorplan", "TrajectoryMapFragment onCreateView");
        //end
        // Inflate the separate layout containing map + map-related UI
        return inflater.inflate(R.layout.fragment_trajectory_map, container, false);
    }
    //琛
    @Override
    public void onCreate(@androidx.annotation.Nullable android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.util.Log.e("Floorplan", "TrajectoryMapFragment onCreate");
    }
    //end
    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        //琛
        int fine = androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION);
        Log.e("IndoorDebug", "fine permission=" + fine);

        android.util.Log.e("Floorplan", "TrajectoryMapFragment onViewCreated");
        //end
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch      = view.findViewById(R.id.gnssSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);

        // Setup floor up/down UI hidden initially until we know there's an indoor map
        setFloorControlsVisibility(View.GONE);
        Log.e("INDOOR", "HIDE floor controls called, selectedVenue=start" );

        //琛
        exitIndoorButton = view.findViewById(R.id.exitIndoorButton);
        exitIndoorButton.setVisibility(View.GONE);

        //end
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

                    //琛
                    setupBuildingPolygonClicks();
                    //end

                    Log.d("TrajectoryMapFragment", "onMapReady: Map is ready!");


                    //琛
//                    drawFakeVenueOutline();
                    //end



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

//        floorUpButton.setOnClickListener(v -> {
//            // If user manually changes floor, turn off auto floor
//            autoFloorSwitch.setChecked(false);
//            if (indoorMapManager != null) {
//                indoorMapManager.increaseFloor();
//            }
//        });
        //琛
        floorUpButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);

            // ✅ 优先：如果正在看 API venue 的室内 shapes，就切 map_shapes 楼层
            if (selectedVenue != null && !availableFloors.isEmpty()) {
                currentFloorIdx = Math.min(currentFloorIdx + 1, availableFloors.size() - 1);
                drawIndoorShapesForFloor(selectedVenue, availableFloors.get(currentFloorIdx));
                return;
            }

            // ⬇️ 否则走你原来的本地 overlay indoor（可保留）
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
            }
        });


//        floorDownButton.setOnClickListener(v -> {
//            autoFloorSwitch.setChecked(false);
//            if (indoorMapManager != null) {
//                indoorMapManager.decreaseFloor();
//            }
//        });
        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);

            if (selectedVenue != null && !availableFloors.isEmpty()) {
                currentFloorIdx = Math.max(currentFloorIdx - 1, 0);
                drawIndoorShapesForFloor(selectedVenue, availableFloors.get(currentFloorIdx));
                return;
            }

            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
            }
        });

        exitIndoorButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);

            // ✅ 退出 API indoor（清掉 shapes）
            selectedVenue = null;

            availableFloors.clear();
            currentFloorIdx = 0;
            clearIndoorShapes();

            // （可选）如果你希望退出时也清掉本地 overlay indoor，就保留这段
            if (indoorMapManager != null) {
                indoorMapManager.clearManualModeAndRemoveOverlay();
            }

            // 隐藏按钮（按你原来的 UI 方法）
            if (selectedVenue == null /* && 你的旧逻辑条件 */) {
                setFloorControlsVisibility(View.GONE);
                exitIndoorButton.setVisibility(View.GONE);
            }

            Log.e("INDOOR", "HIDE floor controls called, selectedVenue=" + (selectedVenue == null ? "null" : selectedVenue.name));


        });

        //end
        //琛
//        exitIndoorButton.setOnClickListener(v -> {
//            autoFloorSwitch.setChecked(false);
//
//            if (indoorMapManager != null) {
//                indoorMapManager.clearManualModeAndRemoveOverlay(); // 下面我告诉你这个方法怎么加
//            }
//
//            setFloorControlsVisibility(View.GONE);
//            exitIndoorButton.setVisibility(View.GONE);
//        });
//        android.util.Log.e("Floorplan", "🚨 HIT in onViewCreated (test)");
//        exitIndoorButton.setOnClickListener(v -> {
//            autoFloorSwitch.setChecked(false);
//
//            // 退出 API indoor
//            selectedVenue = null;
//            availableFloors.clear();
//            currentFloorIdx = 0;
//            clearIndoorShapes(); // 你第5/6步里写的清线函数
//
//            // 如果你还希望同时退出本地 overlay indoor（保留也行）
//            if (indoorMapManager != null) {
//                indoorMapManager.clearManualModeAndRemoveOverlay();
//            }
//
//            // UI 隐藏（按你项目原来的方法）
//            setFloorControlsVisibility(View.GONE);
//            Log.e("INDOOR", "HIDE floor controls called, selectedVenue=" + (selectedVenue == null ? "null" : selectedVenue.name));
//
//            exitIndoorButton.setVisibility(View.GONE);
//        });


//        requestFloorplanOnceWhenLocationReady();


        //end
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

    //琛
//    private Polygon fakeVenuePolygon;
//
//    private void drawFakeVenueOutline() {
//        if (gMap == null) return;
//
//        // 1) 定义一个假的 venue 轮廓（你可以换成你测试地点附近的坐标）
//        List<LatLng> pts = Arrays.asList(
//                new LatLng(55.9440, -3.1880),
//                new LatLng(55.9440, -3.1875),
//                new LatLng(55.9436, -3.1875),
//                new LatLng(55.9436, -3.1880)
//        );
//
//        // 2) 画 polygon（记得 clickable）
//        PolygonOptions opts = new PolygonOptions()
//                .addAll(pts)
//                .clickable(true)
//                .strokeWidth(6f);
//
//        // 如果你重复进入/重复调用，先清理旧的
//        if (fakeVenuePolygon != null) {
//            fakeVenuePolygon.remove();
//        }
//
//        fakeVenuePolygon = gMap.addPolygon(opts);
//        fakeVenuePolygon.setTag("FAKE_VENUE_001");
//
//        // 3) 设置点击事件（Toast）
//        gMap.setOnPolygonClickListener(polygon -> {
//            Object tag = polygon.getTag();
//            Toast.makeText(requireContext(),
//                    "Selected venue: " + (tag == null ? "unknown" : tag.toString()),
//                    Toast.LENGTH_SHORT).show();
//        });
//
//        // 4) 自动对焦 + 放大（推荐：根据范围自动缩放，永远能看到）
//        LatLngBounds.Builder builder = new LatLngBounds.Builder();
//        for (LatLng p : pts) {
//            builder.include(p);
//        }
//        LatLngBounds bounds = builder.build();
//
//        // padding：边缘留白（像素），数值越大越“松”
//        int paddingPx = 120;
//
//        // 用 post 是为了确保地图 view 已经布局完成，避免 newLatLngBounds 抛异常
//        requireView().post(() -> gMap.animateCamera(
//                CameraUpdateFactory.newLatLngBounds(bounds, paddingPx)
//        ));
//    }


    //end



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
        if (gMap == null) {
            Log.e("Floorplan", "updateUserLocation entered but gMap==null, returning");
            return;
        }   //琛
        Log.e("Floorplan", "updateUserLocation CALLED newLocation=" + newLocation);//琛

        // Keep track of current location
        LatLng oldLocation = this.currentLocation;
        this.currentLocation = newLocation;

        //琛
        if (serverCommunications == null) {
            Log.e("Floorplan", "serverCommunications is null");
            return;
        }

        int wifiCount = (sensorFusion != null && sensorFusion.getWifiList() != null) ? sensorFusion.getWifiList().size() : 0;        Log.d("Floorplan", "sending floorplan request lat=" + currentLocation.latitude
                + " lon=" + currentLocation.longitude
                + " wifiCount=" + wifiCount);

        Log.e("Floorplan", "🚨 HIT FLOORPLAN CALL SITE");

//        serverCommunications.requestFloorplans(
//                currentLocation.latitude,
//                currentLocation.longitude,
//                sensorFusion.getWifiList(),
//                new ServerCommunications.FloorplanCallback() {
//                    @Override
//                    public void onSuccess(org.json.JSONObject response) {
//                        Log.d("Floorplan", "✅ success! keys=" + response.names());
//                        Log.d("Floorplan", "response=" + response.toString());
//                    }
//
//                    @Override
//                    public void onError(String error) {
//                        Log.e("Floorplan", "❌ error=" + error);
//
//                    }
//                }
//        );
        //end

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
            boolean apiIndoorActive = (selectedVenue != null && !availableFloors.isEmpty());  // 你点 polygon 后会满足
            boolean overlayIndoorActive = indoorMapManager.getIsIndoorMapSet();               // 旧 overlay 模式

            setFloorControlsVisibility((apiIndoorActive || overlayIndoorActive) ? View.VISIBLE : View.GONE);
            if (exitIndoorButton != null) {
                exitIndoorButton.setVisibility((apiIndoorActive || overlayIndoorActive) ? View.VISIBLE : View.GONE);
            }

        }
    }

//琛
    private long lastFloorplanRequestMs = 0L;
    private boolean floorplanInFlight = false;
    private static final long FLOORPLAN_MIN_GAP_MS = 15_000; // 15s

    public void requestFloorplansIfNeeded(@NonNull LatLng location) {
        Log.e("Floorplan", "TrajectoryMapFragment.requestFloorplansIfNeeded called");

        if (serverCommunications == null) {
            Log.e("Floorplan", "serverCommunications is null");
            return;
        }
        if (sensorFusion == null) {
            Log.e("Floorplan", "sensorFusion is null");
            return;
        }

        long now = System.currentTimeMillis();

        // 节流：15秒内不重复请求
        // ① 有请求在飞就不发
        if (floorplanInFlight) return;

// ② 根据是否已经成功过，决定间隔：未成功 5s；成功后 30s
        long requiredIntervalMs = hasReceivedFloorplan ? 30_000L : 5_000L;

// ③ 时间未到不发
        if (now - lastFloorplanRequestMs < requiredIntervalMs) return;

// ④ 通过检查，准备发请求（先锁住，避免并发）
        lastFloorplanRequestMs = now;
        floorplanInFlight = true;

        int wifiCount = (sensorFusion.getWifiList() == null) ? 0 : sensorFusion.getWifiList().size();
        Log.e("Floorplan", "🚨 requestFloorplansIfNeeded lat=" + location.latitude
                + " lon=" + location.longitude
                + " wifiCount=" + wifiCount);

        serverCommunications.requestFloorplans(
                location.latitude,
                location.longitude,
                sensorFusion.getWifiList(),
                new ServerCommunications.FloorplanCallback() {
                    @Override
                    public void onSuccess(org.json.JSONObject response) {
                        floorplanInFlight = false;
                        hasReceivedFloorplan = true; // ✅ 成功一次，切到 30 秒刷新模式

                        Log.d("Floorplan", "Floorplan success, switch to slow refresh");
                        Log.e("Floorplan", "✅ SUCCESS keys=" + response.names());
                        Log.e("Floorplan", "response=" + response.toString());
                        // 下一步：在这里解析 venues 并画 polygon
                        renderNearbyFloorplans(response);

                    }

                    @Override
                    public void onError(String error) {
                        floorplanInFlight = false;
                        Log.e("Floorplan", "❌ ERROR " + error);
                    }
                }
        );
    }
    //end

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

    // ===== Test Point marker (Part C) =====
    public void addTestPointMarker(@NonNull LatLng pos, int index) {
        if (gMap == null) return;

        gMap.addMarker(new MarkerOptions()
                .position(pos)
                .title("TP " + index)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
        );
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
//        buildingPolygon = gMap.addPolygon(buildingPolygonOptions);
//        gMap.addPolygon(buildingPolygonOptions2);
//        gMap.addPolygon(buildingPolygonOptions3);
//        gMap.addPolygon(buildingPolygonOptions4);



        //琛
        // Nucleus
        buildingPolygon = gMap.addPolygon(buildingPolygonOptions);
        buildingPolygon.setClickable(true);
        buildingPolygon.setTag("NUCLEUS");
        focusCameraOnPolygon(buildingPolygonOptions.getPoints(), 120);

// NKML（目前没室内图，就先不 clickable 或不加 tag）
        Polygon nkmlPolygon = gMap.addPolygon(buildingPolygonOptions2);
// nkmlPolygon.setClickable(true); nkmlPolygon.setTag("NKML");

// FJB（同上）
        Polygon fjbPolygon = gMap.addPolygon(buildingPolygonOptions3);

// Faraday（同上）
        Polygon faradayPolygon = gMap.addPolygon(buildingPolygonOptions4);

        //end


        Log.d("TrajectoryMapFragment", "Building polygon added, vertex count: " + buildingPolygon.getPoints().size());
    }

    //琛
    private void setupBuildingPolygonClicks() {
        if (gMap == null) return;

        gMap.setOnPolygonClickListener(polygon -> {
            Object tagObj = polygon.getTag();
            Log.e("POLY", "clicked polygon tag=" + (tagObj == null ? "null" : tagObj.getClass().getName()));
            if (tagObj instanceof NearbyVenue) {

                onVenueSelected((NearbyVenue) tagObj);
                return;
            }
            Log.e("POLY", "clicked polygon but tag is not NearbyVenue: " + tagObj);
            String tag = tagObj == null ? "" : tagObj.toString();
            if ("NUCLEUS".equals(tag)) {
                indoorMapManager.forceShowNucleus();
                setFloorControlsVisibility(View.VISIBLE);
                exitIndoorButton.setVisibility(View.VISIBLE);
            }
        });
    }


    private void focusCameraOnPolygon(List<LatLng> points, int paddingPx) {
        if (gMap == null || points == null || points.isEmpty()) return;

        LatLngBounds.Builder builder = new LatLngBounds.Builder();
        for (LatLng p : points) {
            builder.include(p);
        }
        LatLngBounds bounds = builder.build();

        // 避免地图还没 layout 完导致 newLatLngBounds 抛异常
        requireView().post(() -> {
            try {
                gMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx));
            } catch (Exception e) {
                // 兜底：如果 bounds 动画失败，就用中心点 + zoom
                LatLng center = bounds.getCenter();
                gMap.animateCamera(CameraUpdateFactory.newLatLngZoom(center, 19f));
            }
        });
    }

    private boolean isInsideRecordingFragment() {
        return getParentFragment() instanceof RecordingFragment;
    }

    private void tickIndoorPositioning() {
        Log.e("IndoorDebug", "tickIndoorPositioning called");

        if (sensorFusion == null) {
            Log.e("IndoorDebug", "sensorFusion == null");
            return;
        }

        // 只用 GNSS
        float[] latLngArray = sensorFusion.getGNSSLatitude(false);
        if (latLngArray == null) {
            Log.e("IndoorDebug", "latLngArray == null (GNSS not ready)");
            return;
        }

        double lat = latLngArray[0];
        double lon = latLngArray[1];

        // 防止 0,0 假定位
        if (Math.abs(lat) < 1e-6 && Math.abs(lon) < 1e-6) {
            Log.e("IndoorDebug", "GNSS is (0,0), waiting emulator location...");
            return;
        }

        LatLng newLocation = new LatLng(lat, lon);

        // orientation 你可以先随便给 0 或继续用 sensorFusion 的方向
        float orientation = 0f;
        try {
            orientation = (float) Math.toDegrees(sensorFusion.passOrientation());
        } catch (Exception ignored) {}

        Log.e("IndoorDebug", "GNSS newLocation=" + newLocation.latitude + "," + newLocation.longitude);

        // 更新地图
        updateUserLocation(newLocation, orientation);

        // 触发 floorplan request（你之前加过节流的）
        requestFloorplansIfNeeded(newLocation);
    }


    @Override
    public void onResume() {
        super.onResume();

        // 如果是 RecordingFragment 的子地图，就不要在这里启动（避免重复）
        if (isInsideRecordingFragment()) return;

        indoorRunning = true;

        indoorTask = new Runnable() {
            @Override
            public void run() {
                if (!indoorRunning) return;
                tickIndoorPositioning();
                indoorHandler.postDelayed(this, 200);
            }
        };
        indoorHandler.post(indoorTask);
    }

    @Override
    public void onPause() {
        super.onPause();

        if (isInsideRecordingFragment()) return;

        indoorRunning = false;
        indoorHandler.removeCallbacksAndMessages(null);
    }
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof MainActivity) {
            MainActivity act = (MainActivity) context;

            sensorFusion = act.getSensorFusion();
            Log.e("IndoorDebug", "TrajectoryMapFragment got sensorFusion=" + sensorFusion);
        }

        serverCommunications = new ServerCommunications(context.getApplicationContext());
        Log.e("Floorplan", "TrajectoryMapFragment serverCommunications=" + serverCommunications);


        Log.e("Floorplan", "TrajectoryMapFragment serverCommunications=" + serverCommunications);
        if (context instanceof com.openpositioning.PositionMe.presentation.activity.MainActivity) {
            com.openpositioning.PositionMe.presentation.activity.MainActivity act =
                    (com.openpositioning.PositionMe.presentation.activity.MainActivity) context;
            sensorFusion = act.getSensorFusion();
            Log.e("IndoorDebug", "TrajectoryMapFragment got sensorFusion=" + sensorFusion);
        } else {
            Log.e("IndoorDebug", "TrajectoryMapFragment: host activity is not MainActivity");
        }
    }

    private void renderNearbyFloorplans(@NonNull org.json.JSONObject wrapper) {
        if (gMap == null) return;

        // 1) 清掉旧的 venues polygon（避免越画越多）
        clearNearbyVenuePolygons();

        try {
            org.json.JSONArray results = wrapper.optJSONArray("results");
            if (results == null || results.length() == 0) {
                Log.e("Floorplan", "results empty");
                return;
            }

            for (int i = 0; i < results.length(); i++) {
                org.json.JSONObject item = results.getJSONObject(i);

                String name = item.optString("name", "unknown");
                String outline = item.optString("outline", "");
                String mapShapes = item.optJSONObject("map_shapes") != null
                        ? item.getJSONObject("map_shapes").toString()
                        : item.optString("map_shapes", "");

                if (outline == null || outline.isEmpty()) continue;

                NearbyVenue v = new NearbyVenue(name, outline, mapShapes);

                // 2) 画 outline（MultiPolygon）
                List<Polygon> polys = drawOutlineMultiPolygon(v);
                nearbyVenuePolygons.addAll(polys);
            }

            Log.e("Floorplan", "drawn venue polygons=" + nearbyVenuePolygons.size());
        } catch (Exception e) {
            Log.e("Floorplan", "renderNearbyFloorplans error: " + e.getMessage());
        }
    }

    private List<Polygon> drawOutlineMultiPolygon(@NonNull NearbyVenue venue) throws Exception {
        List<Polygon> out = new ArrayList<>();

        org.json.JSONObject fc = new org.json.JSONObject(venue.outlineGeoJson);
        org.json.JSONArray features = fc.getJSONArray("features");

        for (int i = 0; i < features.length(); i++) {
            org.json.JSONObject geom = features.getJSONObject(i).getJSONObject("geometry");
            String type = geom.optString("type", "");

            if (!"MultiPolygon".equals(type)) continue;

            // coordinates: [ [ [ [lon,lat]... ] ] , ... ]
            org.json.JSONArray multiPoly = geom.getJSONArray("coordinates");

            for (int p = 0; p < multiPoly.length(); p++) {
                org.json.JSONArray polygon = multiPoly.getJSONArray(p);
                if (polygon.length() == 0) continue;

                // 只取 exterior ring（polygon[0]）
                org.json.JSONArray ring = polygon.getJSONArray(0);

                PolygonOptions po = new PolygonOptions()
                        .clickable(true)
                        .strokeWidth(10f)
                        .strokeColor(0xFFFF0000)   // 红色描边 ARGB
                        .fillColor(0x33FF0000);    // 半透明红色填充（0x33 透明度）

                for (int pt = 0; pt < ring.length(); pt++) {
                    org.json.JSONArray xy = ring.getJSONArray(pt);
                    double lon = xy.getDouble(0);
                    double lat = xy.getDouble(1);
                    po.add(new LatLng(lat, lon)); // 注意：GeoJSON 是 lon,lat
                }

                Polygon poly = gMap.addPolygon(po);
                poly.setTag(venue); // ✅ 关键：点击 polygon 就能拿到 venue
                out.add(poly);
            }
        }

        return out;
    }

    private void clearNearbyVenuePolygons() {
        for (Polygon p : nearbyVenuePolygons) {
            try { p.remove(); } catch (Exception ignored) {}
        }
        nearbyVenuePolygons.clear();
    }

    private void onVenueSelected(@NonNull NearbyVenue v) {
        selectedVenue = v;

        // 清掉旧 indoor 线条
        clearIndoorShapes();

        // 解析可用楼层 keys
        availableFloors.clear();
        try {
            JSONObject mapShapes = new JSONObject(v.mapShapesJson);
            Iterator<String> it = mapShapes.keys();
            while (it.hasNext()) {
                availableFloors.add(it.next());
            }
        } catch (Exception e) {
            Log.e("Floorplan", "map_shapes parse error: " + e.getMessage());
        }
        Log.e("INDOOR", "availableFloors=" + availableFloors);

        // ✅ 只排序，不解析
        sortFloorKeys(availableFloors);

        // 默认 GF 优先，否则 0
        currentFloorIdx = availableFloors.indexOf("GF") >= 0
                ? availableFloors.indexOf("GF")
                : 0;

        // 显示楼层按钮 + 退出按钮（你原来的方法/变量）
        setFloorControlsVisibility(View.VISIBLE);
        if (exitIndoorButton != null) exitIndoorButton.setVisibility(View.VISIBLE);

        // 画当前楼层
        if (selectedVenue != null && !availableFloors.isEmpty()) {
            drawIndoorShapesForFloor(selectedVenue, availableFloors.get(currentFloorIdx));
        }
    }

    private void clearIndoorShapes() {
        for (Polyline l : indoorShapeLines) {
            try { l.remove(); } catch (Exception ignored) {}
        }
        indoorShapeLines.clear();
    }


    private void drawIndoorShapesForFloor(@NonNull NearbyVenue v, @NonNull String floorKey) {
        clearIndoorShapes();

        try {
            JSONObject mapShapes = new JSONObject(v.mapShapesJson);
            boolean has = mapShapes.has(floorKey);
            Log.e("INDOOR", "drawIndoorShapesForFloor floor=" + floorKey + " hasKey=" + has);
            if (!has) return;

            JSONObject fc = mapShapes.getJSONObject(floorKey);
            JSONArray features = fc.optJSONArray("features");
            if (features == null) {
                Log.e("INDOOR", "features is null");
                return;
            }

            int added = 0;

            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.getJSONObject(i);
                JSONObject geom = feature.optJSONObject("geometry");
                if (geom == null) continue;

                String type = geom.optString("type", "");
                Log.e("INDOOR", "feature[" + i + "] type=" + type);

                switch (type) {
                    case "MultiLineString":
                        added += drawMultiLineString(geom.getJSONArray("coordinates"));
                        break;

                    case "LineString":
                        added += drawLineString(geom.getJSONArray("coordinates"));
                        break;

                    case "Polygon":
                        // 可选：如果你也想画 polygon 的面/轮廓
                        added += drawPolygonAsLines(geom.getJSONArray("coordinates"));
                        break;

                    case "MultiPolygon":
                        added += drawMultiPolygonAsLines(geom.getJSONArray("coordinates"));
                        break;

                    default:
                        // 先忽略其他类型，但会在 log 里看到
                        break;
                }
            }

            Log.e("INDOOR", "indoor drawn floor=" + floorKey + " linesAdded=" + added);

        } catch (Exception e) {
            Log.e("INDOOR", "drawIndoorShapesForFloor error: " + e.getMessage());
        }
    }

    private int drawLineString(@NonNull JSONArray coords) throws Exception {
        PolylineOptions plo = new PolylineOptions()
                .width(8f)
                .color(0xFFFF0000);

        for (int pt = 0; pt < coords.length(); pt++) {
            JSONArray xy = coords.getJSONArray(pt);
            double lon = xy.getDouble(0);
            double lat = xy.getDouble(1);
            plo.add(new LatLng(lat, lon));
        }

        indoorShapeLines.add(gMap.addPolyline(plo));
        return 1;
    }

    private int drawMultiLineString(@NonNull JSONArray lines) throws Exception {
        int count = 0;
        for (int li = 0; li < lines.length(); li++) {
            JSONArray line = lines.getJSONArray(li);
            count += drawLineString(line);
        }
        return count;
    }

    // Polygon coordinates: [ ring1, ring2(hole)... ], ring: [ [lon,lat], ... ]
    private int drawPolygonAsLines(@NonNull JSONArray polygonCoords) throws Exception {
        int count = 0;
        for (int r = 0; r < polygonCoords.length(); r++) {
            JSONArray ring = polygonCoords.getJSONArray(r);
            count += drawLineString(ring);
        }
        return count;
    }

    // MultiPolygon coordinates: [ polygon1, polygon2... ], polygon: [ ring1, ring2... ]
    private int drawMultiPolygonAsLines(@NonNull JSONArray multiPoly) throws Exception {
        int count = 0;
        for (int p = 0; p < multiPoly.length(); p++) {
            JSONArray polygon = multiPoly.getJSONArray(p);
            count += drawPolygonAsLines(polygon);
        }
        return count;
    }


    private void sortFloorKeys(@NonNull List<String> floors) {
        floors.sort((a, b) -> Integer.compare(floorOrder(a), floorOrder(b)));
    }

    private int floorOrder(String key) {
        if (key == null) return 999;
        key = key.trim().toUpperCase();

        // 地下层：B2,B1...
        if (key.startsWith("B")) {
            try { return -100 + Integer.parseInt(key.substring(1)); }
            catch (Exception ignored) { return -50; }
        }

        // 地面层
        if (key.equals("LG")) return -1;
        if (key.equals("GF") || key.equals("G")) return 0;

        // 数字楼层
        try { return Integer.parseInt(key); }
        catch (Exception ignored) { }

        return 500;
    }

//    private void requestFloorplanOnceWhenLocationReady() {
//        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
//
//        final Runnable[] r = new Runnable[1];
//        r[0] = () -> {
//            if (currentLocation == null) {
//                int fine = androidx.core.content.ContextCompat.checkSelfPermission(
//                        requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION);
//                int coarse = androidx.core.content.ContextCompat.checkSelfPermission(
//                        requireContext(), android.Manifest.permission.ACCESS_COARSE_LOCATION);
//                android.util.Log.e("Floorplan", "waiting location... currentLocation=null fine=" + fine + " coarse=" + coarse);
//                h.postDelayed(r[0], 1000); // 1秒后再试
//                return;
//            }
//
//            android.util.Log.e("Floorplan", "✅ location ready, sending floorplan request lat="
//                    + currentLocation.latitude + " lon=" + currentLocation.longitude);
//
//            int wifiCount = (sensorFusion.getWifiList() == null) ? 0 : sensorFusion.getWifiList().size();
//            android.util.Log.e("Floorplan", "wifiCount=" + wifiCount);
//
//            serverCommunications.requestFloorplans(
//                    currentLocation.latitude,
//                    currentLocation.longitude,
//                    sensorFusion.getWifiList(),
//                    new ServerCommunications.FloorplanCallback() {
//                        @Override
//                        public void onSuccess(org.json.JSONObject response) {
//                            android.util.Log.e("Floorplan", "✅ SUCCESS keys=" + response.names());
//                            android.util.Log.e("Floorplan", "response=" + response.toString());
//                        }
//
//                        @Override
//                        public void onError(String error) {
//                            android.util.Log.e("Floorplan", "❌ ERROR " + error);
//                        }
//                    }
//            );
//        };
//
//        h.post(r[0]);
//    }

//end

}
