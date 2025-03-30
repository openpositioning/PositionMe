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
import com.example.ekf.EKFManager;
import com.example.ekf.GNSSProcessor;

import java.util.ArrayList;
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

    private GoogleMap gMap; // Google Maps instance
    private LatLng currentLocation; // Stores the user's current location
    private Marker orientationMarker; // Marker representing user's heading
    private Marker gnssMarker; // GNSS position marker
    private Marker ekfMarker; // EKF fusion position marker
    private Polyline polyline; // Polyline representing user's movement path
    private boolean isRed = true; // Tracks whether the polyline color is red
    private boolean isGnssOn = true; // Tracks if GNSS tracking is enabled
    private boolean isEkfOn = true; // Tracks if EKF is enabled

    private Polyline gnssPolyline; // Polyline for GNSS path
    private Polyline ekfPolyline; // Polyline for EKF fusion path
    private LatLng lastGnssLocation = null; // Stores the last GNSS location
    private LatLng lastEkfLocation = null; // Stores the last EKF location

    private LatLng pendingCameraPosition = null; // Stores pending camera movement
    private boolean hasPendingCameraMove = false; // Tracks if camera needs to move

    private IndoorMapManager indoorMapManager; // Manages indoor mapping
    private SensorFusion sensorFusion;

    private EKFManager ekfManager; // EKF manager
    
    // GNSS处理器，用于平滑GNSS数据
    private GNSSProcessor gnssProcessor;

    // UI
    private Spinner switchMapSpinner;

    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;
    private SwitchMaterial ekfSwitch; // EKF switch

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
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        ekfSwitch       = view.findViewById(R.id.EKF_Switch); // 添加对EKF开关的引用
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);

        // 设置开关的初始状态为开启
        gnssSwitch.setChecked(true);
        ekfSwitch.setChecked(true);
        
        // 初始化EKF管理器
        ekfManager = EKFManager.getInstance();
        ekfManager.setEkfEnabled(true);
        
        // 初始化GNSS处理器
        gnssProcessor = GNSSProcessor.getInstance();

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

                    // 设置默认位置和缩放级别 (如果没有特定位置，可以使用一个默认位置)
                    // 缩放级别范围通常是1-20，其中1是世界级别，20是建筑物级别
                    // 街道级别通常在15-18之间
                    float defaultZoom = 18.0f; // 街道级别的缩放
                    
                    // 如果有待处理的相机移动，使用该位置；否则使用默认位置
                    if (hasPendingCameraMove && pendingCameraPosition != null) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, defaultZoom));
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    } else {
                        // 如果没有特定位置，可以尝试使用最后一个已知位置
                        LatLng defaultPosition = (currentLocation != null) ? currentLocation : 
                                                 new LatLng(39.9042, 116.4074); // 默认位置(可以是北京或其他地点)
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultPosition, defaultZoom));
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

        // EKF Switch
        ekfSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isEkfOn = isChecked;
            ekfManager.setEkfEnabled(isChecked);
            
            if (!isChecked && ekfMarker != null) {
                ekfMarker.remove();
                ekfMarker = null;
            }
            
            if (!isChecked && ekfPolyline != null) {
                ekfPolyline.remove();
                ekfPolyline = null;
                lastEkfLocation = null;
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

        // EKF fusion path in green
        ekfPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.GREEN)
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
     * Update the user's location on the map.
     * <p>
     *     The method creates or moves a marker representing the user's current position
     *     and orientation. It also updates the camera position to follow the user's movement
     *     and adds the current position to the polyline path if the position has changed.
     *     The method handles both the creation of new markers and the updating of existing ones.
     *     The orientation is represented by the rotation of the marker.
     *
     * @param newLocation The new LatLng position of the user.
     * @param orientation The user's current orientation in degrees.
     */
    public void updateUserLocation(@NonNull LatLng newLocation, float orientation) {
        if (gMap == null) return;

        currentLocation = newLocation;

        // Angle for marker rotation (in degrees, clockwise from north)
        float markerRotation = orientation;

        // If the marker doesn't exist yet, create it
        if (orientationMarker == null) {
            MarkerOptions markerOptions = new MarkerOptions()
                    .position(newLocation)
                    .flat(true)  // Make sure the marker is flat on the map
                    .anchor(0.5f, 0.5f) // Center the icon
                    .rotation(markerRotation) // Set the rotation based on gyro/compass
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED));

            orientationMarker = gMap.addMarker(markerOptions);
            
            // 初次创建标记时，使用较高的缩放级别移动相机
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 18.0f));
        } else {
            // If it exists, update its position and rotation
            orientationMarker.setPosition(newLocation);
            orientationMarker.setRotation(markerRotation);
            
            // 移动相机跟随用户位置，但保持当前缩放级别
            // 这样用户手动缩放后不会被重置
            gMap.animateCamera(CameraUpdateFactory.newLatLng(newLocation));
        }

        // Update polyline path
        List<LatLng> points = polyline.getPoints();
        if (points.isEmpty() || !points.get(points.size() - 1).equals(newLocation)) {
            points.add(newLocation);
            polyline.setPoints(points);
        }
        
        // 更新EKF位置 - 使用当前用户位置和航向更新PDR位置
        if (isEkfOn && ekfManager != null) {
            // 将航向角转换为弧度
            float headingRadians = (float) Math.toRadians(orientation);
            
            // 更新EKF中的PDR位置
            ekfManager.updatePdrPosition(newLocation, headingRadians);
            
            // 如果EKF尚未初始化，尝试使用当前位置初始化
            if (ekfManager.getFusedPosition() == null) {
                ekfManager.initialize(newLocation, headingRadians);
            }
            
            // 获取并显示融合后的位置
            updateEKFLocation();
        }
    }



    /**
     * Set the initial camera position (before map is ready).
     * <p>
     *     If the map is already initialized, it will move to the position immediately.
     *     Otherwise, it will save the position and move there when the map becomes ready.
     *
     * @param position The LatLng position to center the map on.
     */
    public void setInitialCameraPosition(LatLng position) {
        if (position == null) return;

        // 定义街道级别的缩放值
        float streetLevelZoom = 18.0f; // 街道级别的缩放

        if (gMap != null) {
            // Map is ready, move now
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(position, streetLevelZoom));
        } else {
            // Save for later
            pendingCameraPosition = position;
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
     * Update the GNSS position on the map.
     * <p>
     *     The method creates or moves a marker representing the current GNSS position.
     *     It also adds the position to the GNSS polyline path if it has changed.
     *     The GNSS marker and path are only updated if GNSS tracking is enabled.
     *     The method handles the creation and updating of both the marker and the polyline.
     *
     * @param gnssLocation The new LatLng position reported by GNSS.
     */
    public void updateGNSS(@NonNull LatLng gnssLocation) {
        if (gMap == null) return;

        // 使用GNSS处理器处理原始GNSS位置，得到平滑后的位置
        LatLng processedGnssLocation = gnssProcessor.processGNSSPosition(gnssLocation);
        
        // 如果处理后位置为null，直接返回
        if (processedGnssLocation == null) return;

        // Only update GNSS visuals if enabled
        if (isGnssOn) {
            // GNSS marker (create or update)
            if (gnssMarker == null) {
                MarkerOptions markerOptions = new MarkerOptions()
                        .position(processedGnssLocation)
                        .title("GNSS Position")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE));

                gnssMarker = gMap.addMarker(markerOptions);
            } else {
                gnssMarker.setPosition(processedGnssLocation);
            }

            // Update GNSS polyline if position has changed
            if (lastGnssLocation == null || !lastGnssLocation.equals(processedGnssLocation)) {
                List<LatLng> points = gnssPolyline.getPoints();
                points.add(processedGnssLocation);
                gnssPolyline.setPoints(points);
                lastGnssLocation = processedGnssLocation;
            }
        }
        
        // 更新EKF的GNSS位置数据 - 使用处理后的平滑位置
        if (isEkfOn && ekfManager != null) {
            ekfManager.updateGnssPosition(processedGnssLocation);
            updateEKFLocation(); // 更新融合后的位置显示
        }
    }

    /**
     * 更新WiFi位置数据到EKF
     * @param wifiLocation WiFi定位得到的位置
     */
    public void updateWiFiLocation(@NonNull LatLng wifiLocation) {
        // 更新EKF的WiFi位置数据
        if (isEkfOn && ekfManager != null) {
            ekfManager.updateWifiPosition(wifiLocation);
            updateEKFLocation(); // 更新融合后的位置显示
        }
    }

    /**
     * Clear GNSS markers and polyline from the map.
     */
    public void clearGNSS() {
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
        if (gnssPolyline != null) {
            gnssPolyline.setPoints(new ArrayList<>());
        }
        lastGnssLocation = null;
        
        // 重置GNSS处理器状态
        if (gnssProcessor != null) {
            gnssProcessor.reset();
        }
    }
    
    /**
     * 清除EKF标记和路径
     */
    public void clearEKF() {
        if (ekfMarker != null) {
            ekfMarker.remove();
            ekfMarker = null;
        }
        if (ekfPolyline != null) {
            ekfPolyline.setPoints(new ArrayList<>());
        }
        lastEkfLocation = null;
        
        // 重置EKF管理器
        if (ekfManager != null) {
            ekfManager.reset();
        }
    }

    /**
     * Check if GNSS tracking is enabled.
     * @return True if GNSS tracking is enabled, false otherwise.
     */
    public boolean isGnssEnabled() {
        return isGnssOn;
    }
    
    /**
     * 检查EKF是否启用
     * @return EKF是否启用
     */
    public boolean isEkfEnabled() {
        return isEkfOn;
    }

    private void setFloorControlsVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        autoFloorSwitch.setVisibility(visibility);
    }

    public void clearMapAndReset() {
        if (gMap == null) return;

        // Remove markers
        if (orientationMarker != null) {
            orientationMarker.remove();
            orientationMarker = null;
        }

        // Clear GNSS data
        clearGNSS();
        
        // Clear EKF data
        clearEKF();

        // Remove polyline and recreate
        if (polyline != null) {
            polyline.remove();
        }
        polyline = gMap.addPolyline(new PolylineOptions()
                .color(isRed ? Color.RED : Color.BLACK)
                .width(5f)
                .add() // start empty
        );

        // Reset GNSS polyline
        if (gnssPolyline != null) {
            gnssPolyline.remove();
        }
        gnssPolyline = gMap.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .width(5f)
                .add() // start empty
        );
        
        // Reset EKF polyline
        if (ekfPolyline != null) {
            ekfPolyline.remove();
        }
        ekfPolyline = gMap.addPolyline(new PolylineOptions()
                .color(Color.GREEN)
                .width(5f)
                .add() // start empty
        );

        // Reset indoor map if present
        if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
            // 设置当前位置为远离任何建筑物的位置，触发清除覆盖物
            LatLng farAwayLocation = new LatLng(0, 0); // 赤道和本初子午线交点
            indoorMapManager.setCurrentLocation(farAwayLocation);
        }

        // Reset current location
        currentLocation = null;
        lastGnssLocation = null;
        lastEkfLocation = null;
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
     * 更新EKF融合位置在地图上的显示
     */
    private void updateEKFLocation() {
        if (gMap == null || !isEkfOn || ekfManager == null) return;
        
        LatLng ekfLocation = ekfManager.getFusedPosition();
        if (ekfLocation == null) return;
        
        // 更新或创建EKF位置标记
        if (ekfMarker == null) {
            MarkerOptions markerOptions = new MarkerOptions()
                    .position(ekfLocation)
                    .title("Fusion Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN));
            
            ekfMarker = gMap.addMarker(markerOptions);
        } else {
            ekfMarker.setPosition(ekfLocation);
        }
        
        // 更新EKF轨迹
        if (ekfPolyline != null) {
            List<LatLng> points = ekfPolyline.getPoints();
            if (points.isEmpty() || !points.get(points.size() - 1).equals(ekfLocation)) {
                points.add(ekfLocation);
                ekfPolyline.setPoints(points);
                lastEkfLocation = ekfLocation;
            }
        }
    }

}
