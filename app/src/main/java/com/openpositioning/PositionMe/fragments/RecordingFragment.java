package com.openpositioning.PositionMe.fragments;


import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.PluralsRes;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.gms.maps.model.CircleOptions;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;


@SuppressLint("UseSwitchCompatOrMaterialCode")
public class RecordingFragment extends Fragment {

    //Button to end PDR recording
    private Button stopButton;
    private Button startButton;
    private ImageButton addTagButton;
    //Text views to display distance travelled and elevation since beginning of recording
    private TextView elevation;
    private TextView distanceTravelled;
    // Text view to show the error between current PDR and current GNSS
    private TextView gnssError;
    private static final DecimalFormat df = new DecimalFormat("#.####");

    //Singleton class to collect all sensor data
    private SensorFusion sensorFusion;
    // Responsible for updating UI in Loop
    private Handler refreshDataHandler;

    //variables to store data of the trajectory
    private float distance;
    private float previousPosX;
    private float previousPosY;

    private float fusedDistance;
    private float fusedPreviousPosX;
    private float fusedPreviousPosY;
    // Starting point coordinates
    private static LatLng start;
    // Storing the google map object
    private GoogleMap gMap;
    //Switch Map Dropdown
    private Spinner switchMapSpinner;
    //Map Marker
    private Marker orientationMarker;

    private Switch wifi;
    private Switch pdr;
    private Marker wifiPositionMarker;
    // Current Location coordinates
    private LatLng currentLocation;
    private LatLng fusedCurrentLocation;
    // Next Location coordinates
    private LatLng nextLocation;
    // Stores the polyline object for plotting path
    private Polyline polyline;

    private Polyline wifiPolyline;
    private Polyline gnssPolyline;
    private Polyline fusedPolyline;

    // Manages overlaying of the indoor maps
    public com.openpositioning.PositionMe.IndoorMapManager indoorMapManager;
    // Floor Up button
    public FloatingActionButton floorUpButton;
    // Floor Down button
    public FloatingActionButton floorDownButton;
    // GNSS Switch
    private Switch gnss;
    // GNSS marker
    private Marker gnssMarker;
    // Switch used to set auto floor
    private Switch autoFloor;
    private String zoneName;
    private double markerLatitude;
    private double markerLongitude;
    private boolean isRecording = false;
    private static final int REQUEST_ACTIVITY_RECOGNITION_PERMISSION_CODE = 1001;
    private NucleusBuildingManager nucleusBuildingManager;
    private boolean ifstart = false;

    private ImageView recIcon;
    private static final long THRESHOLD = 30 * 1000; // 30s
    private CountDownTimer timer;

    private final int MAX_POSITIONS = 6; // last MAX_POSITIONS observations
    private List<LatLng> wifiPositions = new ArrayList<>();
    private List<Marker> wifiMarkers = new LinkedList<>();
    private List<LatLng> gnssPositions = new ArrayList<>();
    private List<Marker> gnssMarkers = new LinkedList<>();


    /**
     * Public Constructor for the class.
     * Left empty as not required
     */
    public RecordingFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check Android version, only Android 10 (API 29) and above require runtime permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACTIVITY_RECOGNITION)
                    != PackageManager.PERMISSION_GRANTED) {

                // Request permissions
                requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION},
                        REQUEST_ACTIVITY_RECOGNITION_PERMISSION_CODE);
            } else {
                // Already have permission, you can continue with the subsequent operations
                Log.d("RecordingFragment", "Already have permission, you can continue with the subsequent operations");
            }
        } else {
            // No additional permissions are required for Android 9 and below
            Log.d("RecordingFragment", "No additional permissions are required for Android 9 and below");
        }


        // ✅ Make sure SensorFusion is initialized correctly
        this.sensorFusion = SensorFusion.getInstance();

        // Set up the application context
        sensorFusion.setContext(getActivity().getApplicationContext());
        if (this.sensorFusion == null) {
            Log.e("SensorFusion", "❌ SensorFusion is NULL! Retrying initialization...");
            this.sensorFusion = SensorFusion.getInstance(); // 重新获取实例 Re-obtain the instance
        }


        // ✅ Initialize `Handler` (used to update UI regularly)
        this.refreshDataHandler = new Handler();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_recording, container, false);


        //✅ **Get the passed data from the Bundle**
        if (getArguments() != null) {
            zoneName = getArguments().getString("zone_name");
            markerLatitude = getArguments().getDouble("marker_latitude", 0.0);
            markerLongitude = getArguments().getDouble("marker_longitude", 0.0);

            Log.d("RecordingFragment", "📍 Zone: " + zoneName + " | Lat: " + markerLatitude + " | Lon: " + markerLongitude);
        }


        // ✅ Get the initial GNSS position (make sure to include latitude & longitude)
        if (markerLatitude != 0.0 && markerLongitude != 0.0) {
            start = new LatLng(markerLatitude, markerLongitude);
        } else {
            start = new LatLng(55.953251, -3.188267); // 💡 默认位置（爱丁堡）Default location (Edinburgh)
        }

        float[] sendStartLocation = new float[2];
        sendStartLocation[0] = (float) start.latitude;
        sendStartLocation[1] = (float) start.longitude;
        sensorFusion.setStartGNSSLatitude(sendStartLocation);

        currentLocation = start; // 🔥 确保 currentLocation 也初始化 Make sure currentLocation is also initialized

        fusedCurrentLocation = start;
        // ✅ Initialize the map
        SupportMapFragment supportMapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.map_fragment);
        if (supportMapFragment != null) {
            supportMapFragment.getMapAsync(map -> {
                gMap = map;

                // ✅ Initialize indoor map (check if needed first)
                if (indoorMapManager == null) {
                    indoorMapManager = new com.openpositioning.PositionMe.IndoorMapManager(gMap);
                }

                // ✅ Configure Google Map UI
                map.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                map.getUiSettings().setCompassEnabled(true);
                map.getUiSettings().setTiltGesturesEnabled(true);
                map.getUiSettings().setRotateGesturesEnabled(true);
                map.getUiSettings().setScrollGesturesEnabled(true);

                // ✅ Add a starting point marker (with direction indication)
                orientationMarker = map.addMarker(new MarkerOptions()
                        .position(start)
                        .title("Current Position")
                        .flat(true)
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(requireContext(), R.drawable.ic_baseline_navigation_24)
                        )));
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(start, 19f));

                // ✅ Initialize PDR track (Polyline)

                polyline = gMap.addPolyline(new PolylineOptions()
                        .color(Color.parseColor("#FFA500"))
                        .add(currentLocation)
                        .zIndex(6));

                // initialize the fused position
                fusedPolyline = gMap.addPolyline(new PolylineOptions()
                        .color(Color.parseColor("#880000"))
                        .add(fusedCurrentLocation)
                        .zIndex(6));


                // ✅ Set up indoor maps (if applicable)
                indoorMapManager.setCurrentLocation(fusedCurrentLocation);// fusedCurrentLocation or not?
                indoorMapManager.setIndicationOfIndoorMap();
                indoorMapManager.setCurrentFloor(sensorFusion.getLastWifiFloor(), true);
            });
        } else {
            Log.e("RecordingFragment", "❌ SupportMapFragment is NULL!");
        }

        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 🛑 **Delete old Marker to avoid duplication**
        if (orientationMarker != null) {
            orientationMarker.remove();
            orientationMarker = null;
            Log.d("MarkerReset", "old Marker removed");
        }

        if (polyline != null) {
            polyline.remove();
            polyline = null;
            Log.d("PolylineReset", "old Polyline removed");
        }

        if (fusedPolyline != null) {
            fusedPolyline.remove();
            fusedPolyline = null;
        }

        // ✅ Initialize UI components (avoid multiple calls to `getView()`)
        this.elevation = view.findViewById(R.id.tv_elevation);
        this.distanceTravelled = view.findViewById(R.id.tv_distance);
        this.gnssError = view.findViewById(R.id.tv_gnss_error);
        this.startButton = view.findViewById(R.id.button_start);
        this.stopButton = view.findViewById(R.id.button_stop);
        this.addTagButton = view.findViewById(R.id.AddTagButton);

        // ✅ **Set default UI values**
        this.gnssError.setVisibility(View.GONE);
        this.elevation.setText("Elevation: 0.0 m");
        this.distanceTravelled.setText("Distance: 0.0 m");

        // ✅ **重置轨迹计算变量**
        //✅ **Reset trajectory calculation variables**
        this.distance = 0f;
        this.previousPosX = 0f;
        this.previousPosY = 0f;

        this.fusedDistance = 0f;
        this.fusedPreviousPosX = 0f;
        this.fusedPreviousPosY = 0f;

        this.recIcon = getView().findViewById(R.id.redDot);
        if (recIcon != null) {
            recIcon.setVisibility(View.GONE);
            blinkingRecording();
        }

        // ✅ **Start button (start recording)**
        this.startButton.setOnClickListener(view1 -> {
            if (!isRecording) {
                ifstart = true;
                if (recIcon != null) {
                    recIcon.setVisibility(View.VISIBLE);
                    recIcon.setColorFilter(Color.RED);
                }

                // Stop previous recording, sensor monitoring and scheduled tasks
                sensorFusion.stopRecording();
                sensorFusion.stopListening();
                refreshDataHandler.removeCallbacks(refreshDataTask);
                // The first call to resetMap() resets the map immediately
                resetMap();

                if (sensorFusion != null) {
                    sensorFusion.setContext(getActivity().getApplicationContext());
                    sensorFusion.resumeListening();  //  Register all sensor listeners
                    sensorFusion.startRecording();
                    Toast.makeText(getContext(), "Recording Started", Toast.LENGTH_SHORT).show();
                    Log.d("RecordingFragment", "🚀 SensorFusion Recording Started");
                    isRecording = true; // Mark recording
                    // Start updating the UI
                    refreshDataHandler.post(refreshDataTask);

                } else {
                    Log.e("RecordingFragment", "❌ SensorFusion not initialised！");
                }


                // check if there is an existing timer, if so, cancel the reset
                if (timer != null) {
                    timer.cancel();
                    timer = null;
                    Log.d("TimerReset", "🔥 old Timer is reset");
                }

                // new countdown button
                timer = new CountDownTimer(33000, 1000) {
                    @Override
                    public void onTick(long millisUntilFinished) {
                        // empty mission for second invoke
                    }

                    @Override
                    public void onFinish() {
                        if (recIcon != null) {
                            recIcon.setColorFilter(Color.GREEN);
                        }
                        // ensure context is not null, and handel toast message
                        Context context = getContext();
                        if (context != null) {
                            Toast.makeText(context, "Recording reached 30sec, you may stop at anytime!", Toast.LENGTH_SHORT).show();
                        }
                    }
                };
                timer.start();
            } else {
                Toast.makeText(getContext(), "Recording in Progress", Toast.LENGTH_SHORT).show();
            }
        });


        this.addTagButton.setOnClickListener(view1 -> {
            addCurrentLocationMarker();
            sensorFusion.addTag();
        });


        // **Stop button (stop recording & jump)**
        this.stopButton.setOnClickListener(view1 -> {
            if (ifstart){
                if (sensorFusion != null) {
                    sensorFusion.stopRecording();
                    sensorFusion.stopListening();
                    Toast.makeText(getContext(), "Recording Stopped", Toast.LENGTH_SHORT).show();
                    isRecording = false; // toggle recording to false
                    if (recIcon != null) {
                        recIcon.setVisibility(View.GONE);
                    }
                    if (timer != null) {
                        timer.cancel();
                        timer = null;
                        Log.d("TimerReset", "🔥 old timer is reset");
                    }
                    Log.d("RecordingFragment", "🛑 SensorFusion recording stopped");
                } else {
                    Log.e("RecordingFragment", "❌ SensorFusion is not initialised！");
                }

                // Stop UI update task
                if (refreshDataHandler != null) {
                    refreshDataHandler.removeCallbacks(refreshDataTask);
                }

                // **Jump to FilesFragment**
                if (isAdded()) {

                    //Send trajectory data to the cloud
                    sensorFusion.sendTrajectoryToCloud();

                    FragmentTransaction transaction = requireActivity().getSupportFragmentManager().beginTransaction();
                    transaction.replace(R.id.fragment_container, new PositionFragment());
                    transaction.addToBackStack(null);
                    transaction.commit();
                } else {
                    Log.w("RecordingFragment", "Fragment is destoried, unable to jump!");
                }
            }else{
                Toast.makeText(getContext(), "Recording not started yet!", Toast.LENGTH_SHORT).show();
            }
        });

        //✅ **Initialize UI components**
        this.floorUpButton = view.findViewById(R.id.floorUpButton);
        this.floorDownButton = view.findViewById(R.id.floorDownButton);
        this.autoFloor = view.findViewById(R.id.switch_auto_floor);

        //✅ **Set default state**
        autoFloor.setChecked(true); // Automatic floor is enabled by default
        setFloorButtonVisibility(View.GONE); // Initially hide the floor switch button

        //✅ **Map type switch**
        mapDropdown();
        switchMap();

        //✅ **Floor up button**
        this.floorUpButton.setOnClickListener(view1 -> {
            autoFloor.setChecked(false); // 🚀 关闭 Auto Floor Turn off Auto Floor
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
                Log.d("FloorControl", "📈 floor up");
            } else {
                Log.e("FloorControl", "indoorMapManager is empty cannot change floor");
            }
        });

        //✅ **Floor down button**
        this.floorDownButton.setOnClickListener(view1 -> {
            autoFloor.setChecked(false); // 🚀 关闭 Auto Floor Turn off Auto Floor
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
                Log.d("FloorControl", "📉 floor down");
            } else {
                Log.e("FloorControl", "indoorMapManager is empty cannot change floor");
            }
        });

        //✅ **Automatic floor switching**
        this.autoFloor.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                Log.d("FloorControl", "✅ Auto Floor is enabled");
            } else {
                Log.d("FloorControl", "⚠️ Auto Floor is disabled");
            }
        });

        //**Bind GNSS switch**
        this.gnss = view.findViewById(R.id.switch_gnss);
        //**Bind WiFi switch**
        this.wifi = view.findViewById(R.id.switch_wifi);
        //**Bind PDR position switch**
        this.pdr = view.findViewById(R.id.switch_pdr);

        this.pdr.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (!isChecked) {
                polyline.setVisible(false);
            }else{
                polyline.setVisible(true);
            }
        });
// WiFi switch listener
        this.wifi.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked) {
                // When the WiFi switch is turned ON

                // Retrieve sensor data from the sensor fusion system
                Map<SensorTypes, float[]> sensorData = sensorFusion.getSensorValueMap();
                if (sensorData == null) {
                    // If sensor data is not available, show a toast and turn the switch off
                    Toast.makeText(getContext(), "sensorData not available", Toast.LENGTH_SHORT).show();
                    wifi.setChecked(false);
                    return;
                }

                // Get the most recent WiFi-determined position from the sensor fusion module
                // LatLng wifiPosition = sensorFusion.getLatLngWifiPositioning(); // Deprecated or unused
                LatLng wifiPosition = sensorFusion.getLastWifiPos();
                if (wifiPosition == null) {
                    // If WiFi position is not available, notify the user and turn off the switch
                    Toast.makeText(getContext(), "WiFi data not available", Toast.LENGTH_SHORT).show();
                    wifi.setChecked(false);
                    return;
                }

                // Make the WiFi position marker visible on the map
                if (wifiPositionMarker != null) {
                    wifiPositionMarker.setVisible(true);
                }

                // Make the WiFi polyline (path) visible on the map
                if (wifiPolyline != null) {
                    wifiPolyline.setVisible(true);
                }

                // Make all individual WiFi markers (e.g., AP positions) visible
                for (Marker marker : wifiMarkers) {
                    marker.setVisible(true);
                }

            } else {
                // When the WiFi switch is turned OFF

                // Hide the WiFi position marker from the map
                if (wifiPositionMarker != null) {
                    wifiPositionMarker.setVisible(false);
                }

                // Hide the WiFi polyline (path) from the map
                if (wifiPolyline != null) {
                    wifiPolyline.setVisible(false);
                }

                // Hide all individual WiFi markers
                for (Marker marker : wifiMarkers) {
                    marker.setVisible(false);
                }
            }
        });


        //GNSS switch listener
        this.gnss.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked) {
                // Get all sensor data (GNSS data does not depend on pdrProcessing)
                Map<SensorTypes, float[]> sensorData = sensorFusion.getSensorValueMap();
                if (sensorData == null) {
                    Toast.makeText(getContext(), "sensorData not available", Toast.LENGTH_SHORT).show();
                    gnss.setChecked(false);
                    return;
                }

                // Get GNSS data
                float[] gnssData = sensorData.get(SensorTypes.GNSSLATLONG);
                if (gnssData == null || gnssData.length < 2) {
                    Toast.makeText(getContext(), "GNSS data not available", Toast.LENGTH_SHORT).show();
                    gnss.setChecked(false);
                    return;
                }

                // Convert GNSS data to LatLng object
                LatLng gnssLocation = new LatLng(gnssData[0], gnssData[1]);

                // Determine whether orientationMarker exists
                if (orientationMarker != null) {
                    LatLng orientationPos = orientationMarker.getPosition();
                    // Calculate the distance between orientationMarker and GNSS data (unit: meters)
                    double distance = UtilFunctions.distanceBetweenPoints(orientationPos, gnssLocation);
                    // Set a distance threshold to determine whether the two are "particularly close"
                    final double THRESHOLD_DISTANCE = 1.0; // The threshold is 1 meter and can be adjusted as needed


//                    // *****Debugging for geofence START*****
//                    List<LatLng> wallPointsLatLng = Arrays.asList(
//                            new LatLng(55.92301090863321, -3.174221045188629),
//                            new LatLng(55.92301094092557, -3.1742987516650873),
//                            new LatLng(55.92292858261526, -3.174298917609189),
//                            new LatLng(55.92292853699635, -3.174189214585424),
//                            new LatLng(55.92298698483965, -3.1741890966446484)
//                    );
//
//                    for (LatLng point : wallPointsLatLng) {
//                        gMap.addCircle(new CircleOptions()
//                                .center(point)
//                                .radius(0.5) // 单位：米，适当调整大小
//                                .strokeColor(Color.RED)
//                                .fillColor(Color.argb(100, 255, 0, 0)) // 半透明红色
//                                .zIndex(100)); // 确保在 overlay 上方
//                    }
//                    // *****Debugging for geofence END*****

                    if (distance < THRESHOLD_DISTANCE) {
                        // If the two are very close, only keep the orientationMarker,
                        // Also make sure to delete any GNSS Marker that may have existed before
                        if (gnssMarker != null) {
                            gnssMarker.remove();
                            gnssMarker = null;
                        }
                        // A prompt can be displayed on the interface to tell the user that the two are very close
                        gnssError.setVisibility(View.VISIBLE);
                        String GnssErrorRound = df.format(distance);
                        gnss.setText("GNSS error: " + GnssErrorRound + " m");
                        gnssError.setText("GNSS error: " + String.format("%.2f", distance) + " m (位置接近)");
                    } else {
                        // If the distance is greater than the threshold, display a GNSS Marker on the map,
                        // so that the user can compare the distance between the orientationMarker and the GNSS Marker
                        if (gnssMarker == null) {
//                            gnssMarker = gMap.addMarker(new MarkerOptions()
//                                    .title("GNSS Position")
//                                    .position(gnssLocation)
//                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                        } else {
                            gnssMarker.setPosition(gnssLocation);
                        }
                        gnssError.setVisibility(View.VISIBLE);
                        String GnssErrorRound = df.format(distance);
                        gnssError.setText("GNSS error: " + GnssErrorRound + " m");
                    }

                } else {
                    // If orientationMarker has not been created (this is rare), display GNSS Marker directly
                    if (gnssMarker == null) {
                        gnssMarker = gMap.addMarker(new MarkerOptions()
                                .title("GNSS Position")
                                .position(gnssLocation)
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
                    } else {
                        gnssMarker.setPosition(gnssLocation);
                    }
                    gnssError.setVisibility(View.VISIBLE);
                    gnssError.setText("GNSS error: Cannot resolve GNSS error（orientationMarker is not ready）");
                }

                if (gnssMarker != null) {
                    gnssMarker.setVisible(true);
                }
                if (gnssPolyline != null) {
                    gnssPolyline.setVisible(true);
                }
                for (Marker marker : gnssMarkers) {
                    marker.setVisible(true);
                }

            } else {
                // When GNSS is turned off, only orientationMarker is kept and GNSS Marker is removed
//                if (gnssMarker != null) {
//                    gnssMarker.remove();
//                    gnssMarker = null;
//                }
                if (gnssMarker != null) {
                    gnssMarker.setVisible(false);
                }
                if (gnssPolyline != null) {
                    gnssPolyline.setVisible(false);
                }
                for (Marker marker : gnssMarkers) {
                    marker.setVisible(false);
                }
                gnssError.setVisibility(View.GONE);
                Log.d("GNSS", "GNSS Marker 已移除，仅保留 orientationMarker");
            }
        });

    }

    /**
     * Adds a marker at the current fused location if recording is active.
     *
     * <p>If the map is currently recording, this method places a violet marker labeled "Added Tag"
     * at the current fused position and shows a confirmation toast. If not recording, it notifies the user.
     */
    private void addCurrentLocationMarker() {
        if (fusedCurrentLocation != null) {
            if (isRecording) {
                Marker tagMarker = gMap.addMarker(new MarkerOptions()
                        .position(fusedCurrentLocation)
                        .title("Added Tag")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)));
                Toast.makeText(getContext(), "Tag added successfully!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "Recording not started yet!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Resets the map to its initial state.
     *
     * <p>This method:
     * <ul>
     *     <li>Clears existing markers and polylines</li>
     *     <li>Resets the current and fused location to the starting point</li>
     *     <li>Recenters the map camera</li>
     *     <li>Reinitializes orientation and polyline markers</li>
     * </ul>
     */
    private void resetMap() {
        if (gMap != null) {
            if (orientationMarker != null) orientationMarker.remove();
            if (polyline != null) polyline.remove();
            if (fusedPolyline != null) fusedPolyline.remove();
        }

        // Reset current and fused location to start point
        currentLocation = new LatLng(start.latitude, start.longitude);
        fusedCurrentLocation = new LatLng(start.latitude, start.longitude);

        // Reset camera to initial position
        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(fusedCurrentLocation, 19f));

        // Re-add orientation marker at the starting point
        orientationMarker = gMap.addMarker(new MarkerOptions()
                .position(fusedCurrentLocation)
                .title("Current Position")
                .flat(true)
                .icon(BitmapDescriptorFactory.fromBitmap(
                        UtilFunctions.getBitmapFromVector(requireContext(), R.drawable.ic_baseline_navigation_24)
                )));

        // Recreate polyline for PDR
        polyline = gMap.addPolyline(new PolylineOptions()
                .color(Color.parseColor("#FFA500")) // Orange
                .add(currentLocation)
                .zIndex(6));
        polyline.setVisible(pdr.isChecked());

        // Recreate polyline for fused tracking
        fusedPolyline = gMap.addPolyline(new PolylineOptions()
                .color(Color.parseColor("#880000")) // Dark red
                .add(fusedCurrentLocation)
                .zIndex(6));
    }

    /**
     * Handles the result of runtime permission requests.
     *
     * @param requestCode  The request code passed in requestPermissions().
     * @param permissions  The requested permissions.
     * @param grantResults The grant results for the corresponding permissions.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_ACTIVITY_RECOGNITION_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("RecordingFragment", "Activity recognition permission granted.");
            } else {
                Log.w("RecordingFragment", "Activity recognition permission denied.");
            }
        }
    }

    /**
     * Creates and initializes the dropdown spinner for switching map types.
     *
     * <p>This method sets up a Spinner control with options such as Hybrid, Normal, and Satellite.
     * It attaches an adapter and sets the default selection to "Hybrid".
     */
    private void mapDropdown() {
        switchMapSpinner = getView().findViewById(R.id.spinner_map_type);

        if (switchMapSpinner == null) {
            Log.e("MapDropdown", "❌ Spinner is NULL! Cannot initialize dropdown.");
            return;
        }

        // Define map type options
        String[] maps = new String[]{
                "Hybrid",
                "Normal",
                "Satellite"
        };

        // Create an adapter with the map type options
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                maps
        );

        // Attach the adapter to the spinner
        switchMapSpinner.setAdapter(adapter);

        // Set the default selection to the first option (Hybrid)
        switchMapSpinner.setSelection(0);

        Log.d("MapDropdown", "✅ Map dropdown initialized with default selection: Hybrid");
    }

    /**
     * Sets a listener on the Spinner to switch the map type when the user makes a selection.
     *
     * <p>This method uses a map to translate spinner positions into corresponding
     * GoogleMap map type constants. If no item is selected, it defaults to Hybrid.
     */
    private void switchMap() {
        if (switchMapSpinner == null) {
            Log.e("MapSwitch", "❌ Spinner is NULL! Cannot set listener.");
            return;
        }

        switchMapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (gMap == null) {
                    Log.e("MapSwitch", "❌ GoogleMap is NULL! Cannot switch map type.");
                    return;
                }

                // Map spinner positions to GoogleMap map types
                Map<Integer, Integer> mapTypeMap = new HashMap<>();
                mapTypeMap.put(0, GoogleMap.MAP_TYPE_HYBRID);
                mapTypeMap.put(1, GoogleMap.MAP_TYPE_NORMAL);
                mapTypeMap.put(2, GoogleMap.MAP_TYPE_SATELLITE);

                if (mapTypeMap.containsKey(position)) {
                    gMap.setMapType(mapTypeMap.get(position));
                    Log.d("MapSwitch", "✅ Switched to: " + position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                if (gMap != null) {
                    gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                    Log.d("MapSwitch", "⚠️ No selection, defaulting to Hybrid.");
                }
            }
        });
    }


    /**
     * Runnable task for refreshing UI with live data (every 200ms)
     */
    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            if (refreshDataHandler != null) {
                updateUIandPosition();
                refreshDataHandler.postDelayed(this, 200);
            } else {
                Log.e("refreshDataTask", "❌ Handler is NULL! Stopping refresh.");
            }
        }
    };

    /**
     * Updates the position history for WiFi or GNSS markers and polylines on the map.
     *
     * <p>This method:
     * <ul>
     *     <li>Adds a new marker at the given position</li>
     *     <li>Maintains a fixed-size list of recent positions and markers (removes the oldest when max is exceeded)</li>
     *     <li>Updates or creates a polyline connecting the positions</li>
     *     <li>Sets marker visibility and transparency for visualization</li>
     *     <li>Applies titles and colors for differentiation between WiFi and GNSS</li>
     * </ul>
     *
     * @param newPosition   The new LatLng position to be added.
     * @param positionList  The list of historical LatLng positions.
     * @param markerList    The list of marker references on the map.
     * @param polyline      The existing polyline, or null if it needs to be created.
     * @param marker        A temporary marker reference (not reused in logic).
     * @param markerColor   The color of the marker (e.g., BitmapDescriptorFactory.HUE_GREEN).
     * @param lineColor     The color of the polyline (e.g., Color.GREEN).
     * @param title         If true, this is a WiFi marker; otherwise, it's GNSS.
     * @return The updated polyline.
     */
    private Polyline updatePositionHistory(LatLng newPosition,
                                           List<LatLng> positionList,
                                           List<Marker> markerList,
                                           Polyline polyline,
                                           Marker marker,
                                           float markerColor,
                                           int lineColor,
                                           boolean title) {
        if (newPosition == null) return polyline;

        // Skip if the new position is identical to the most recent one
        if (!positionList.isEmpty() && positionList.get(positionList.size() - 1).equals(newPosition)) {
            return polyline;
        }

        // Add new position and maintain size constraint
        positionList.add(newPosition);
        if (positionList.size() >= MAX_POSITIONS) {
            positionList.remove(0);
            markerList.get(0).remove();
            markerList.remove(0);
        }

        // Add marker for the new position
        marker = gMap.addMarker(new MarkerOptions()
                .position(newPosition)
                .icon(BitmapDescriptorFactory.defaultMarker(markerColor)));

        if (marker != null) {
            marker.setVisible(title ? wifi.isChecked() : gnss.isChecked());
        }
        markerList.add(marker);

        // Set fading alpha and marker title
        for (int i = 0; i < markerList.size(); i++) {
            float alpha = 0.3f + (0.7f * i / (markerList.size() - 1)); // Fading effect
            markerList.get(i).setAlpha(alpha);
            markerList.get(i).setTitle(title ? "Wifi Marker #" + (i + 1) : "GNSS Marker #" + (i + 1));
        }

        // Create or update the polyline
        if (polyline == null) {
            polyline = gMap.addPolyline(new PolylineOptions()
                    .addAll(positionList)
                    .width(8)
                    .color(lineColor)
                    .geodesic(true));
        } else {
            polyline.setPoints(positionList);
        }

        // Set polyline visibility and title
        polyline.setVisible(title ? wifi.isChecked() : gnss.isChecked());
        return polyline;
    }



    /**
     * Updates the UI and user positions using sensor fusion data.
     *
     * <p>This method:
     * <ul>
     *     <li>Processes new PDR and fused movement data</li>
     *     <li>Updates the user’s trajectory polylines</li>
     *     <li>Calculates distances traveled</li>
     *     <li>Updates WiFi and GNSS markers</li>
     *     <li>Handles indoor map floor logic and elevation</li>
     *     <li>Refreshes UI indicators such as elevation and orientation</li>
     * </ul>
     */
    private void updateUIandPosition() {
        // Get sensor data
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        float[] fusedValues = sensorFusion.getFusionLocation();

        // check for null values
        if (pdrValues == null || pdrValues.length < 2) return;
        if (fusedValues == null || fusedValues.length < 2) return;

        // Calculate raw PDR movement
        float deltaX = pdrValues[0] - previousPosX;
        float deltaY = pdrValues[1] - previousPosY;
        float stepDistance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);

        // Calculate fused movement
        float fusedDeltaX = fusedValues[0] - fusedPreviousPosX;
        float fusedDeltaY = fusedValues[1] - fusedPreviousPosY;
        float fusedStepDistance = (float) Math.sqrt(fusedDeltaX * fusedDeltaX + fusedDeltaY * fusedDeltaY);

        // Update distance and plot if step is significant
        if (stepDistance > 0.001f) {
            distance += stepDistance;
            distanceTravelled.setText("Distance: " + String.format("%.2f", distance) + " m");
            plotLines(new float[]{deltaX, deltaY}, false);
        }

        if (fusedStepDistance > 0.001f) {
            fusedDistance += fusedStepDistance;
            plotLines(new float[]{fusedDeltaX, fusedDeltaY}, true);
        }

        // Initialize indoor map manager if null
        if (indoorMapManager == null) {
            indoorMapManager = new IndoorMapManager(gMap);
        }

        // Update WiFi marker
        if (wifi != null) {
            LatLng wifiPosition = sensorFusion.getLastWifiPos();
            if (wifiPosition != null) {
                wifiPolyline = updatePositionHistory(
                        wifiPosition, wifiPositions, wifiMarkers, wifiPolyline,
                        wifiPositionMarker, BitmapDescriptorFactory.HUE_GREEN, Color.GREEN, true
                );
            }
        } else {
            for (Marker marker : wifiMarkers) {
                marker.remove();
            }
            wifiMarkers.clear();
            wifiPositions.clear();
            if (wifiPolyline != null) {
                wifiPolyline.setPoints(wifiPositions);
                wifiPolyline.remove();
                wifiPolyline = null;
            }
            if (wifiPositionMarker != null) {
                wifiPositionMarker.remove();
                wifiPositionMarker = null;
            }
        }

        // Update GNSS marker and error
        if (gnss != null) {
            float[] gnssData = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
            if (gnssData != null && gnssData.length >= 2) {
                LatLng gnssLocation = new LatLng(gnssData[0], gnssData[1]);
                double error = UtilFunctions.distanceBetweenPoints(fusedCurrentLocation, gnssLocation);
                String GnssErrorRound = df.format(error);

                if (gnss.isChecked()) {
                    gnssError.setVisibility(View.VISIBLE);
                    gnssError.setText("GNSS error: " + GnssErrorRound + " m");
                } else {
                    gnssError.setVisibility(View.GONE);
                }

                gnssPolyline = updatePositionHistory(
                        gnssLocation, gnssPositions, gnssMarkers, gnssPolyline,
                        gnssMarker, BitmapDescriptorFactory.HUE_AZURE, Color.BLUE, false
                );
            }
        } else {
            for (Marker marker : gnssMarkers) {
                marker.remove();
            }
            gnssMarkers.clear();
            gnssPositions.clear();
            if (gnssPolyline != null) {
                gnssPolyline.setPoints(gnssPositions);
                gnssPolyline.remove();
                gnssPolyline = null;
            }
            if (gnssMarker != null) {
                gnssMarker.remove();
                gnssMarker = null;
            }
            gnssError.setVisibility(View.GONE);
        }

        // Update indoor map logic
        indoorMapManager.setCurrentLocation(fusedCurrentLocation);
        float elevationVal = sensorFusion.getElevation();

        // **Check if you are in an indoor map**
        if (indoorMapManager.getIsIndoorMapSet()) {
            setFloorButtonVisibility(View.VISIBLE);

            // **Auto Floor Function**
            if (autoFloor != null && autoFloor.isChecked()) {
                int estimatedFloor;
                if (sensorFusion != null && System.currentTimeMillis() - sensorFusion.getLastWifiSuccessTime() < 6000000) {
//                    TODO: implement wifi floor estimation as a initial floor
//                          thereby if the wifi gone, barometer could still be used to estimate floor
//                          NOT implemented since the barometer gives too many uncertainties over time
//                    estimatedFloor = sensorFusion.getWifiFloor();
                    estimatedFloor = sensorFusion.getLastWifiFloor();
                } else {
                    estimatedFloor = (int) (elevationVal / indoorMapManager.getFloorHeight());
                }
                indoorMapManager.setCurrentFloor(estimatedFloor, true);
            }
        } else {
            setFloorButtonVisibility(View.GONE);
        }

        // Store previous PDR and fused positions
        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];
        fusedPreviousPosX = fusedValues[0];
        fusedPreviousPosY = fusedValues[1];

        // Update elevation display
        elevation.setText("Elevation: " + String.format("%.2f", elevationVal) + " m");

        // Update orientation marker
        if (orientationMarker != null) {
            float heading = (float) Math.toDegrees(sensorFusion.passOrientation());
            orientationMarker.setRotation(heading);
        }
    }


    /**
     * Calculates and draws the PDR or fused trajectory on the map.
     *
     * <p>This method updates the user's current position using the provided PDR movement delta,
     * adds the new point to the corresponding polyline (fused or raw PDR),
     * and updates the camera view to center on the current location.
     *
     * @param pdrMoved A float array containing movement deltas in the X and Y directions.
     * @param isFused  If true, updates the fused trajectory; otherwise, updates the raw PDR trajectory.
     */
    private void plotLines(float[] pdrMoved, boolean isFused) {
        if (pdrMoved == null || pdrMoved.length < 2) {
            Log.e("PlottingPDR", "❌ Invalid pdrMoved data!");
            return;
        }

        updatePDRandFusionPosition(pdrMoved, isFused);

        // Move camera to current fused location (assumes fusedCurrentLocation is always used for centering)
        gMap.animateCamera(CameraUpdateFactory.newLatLngZoom(fusedCurrentLocation, 19f));
    }


    /**
     * Updates the PDR or fused position and extends the corresponding polyline.
     *
     * <p>This method computes a new position by applying the movement delta (pdrMoved) to the current position.
     * It then adds this new position to the appropriate polyline (fused or normal) and updates the current location.
     * If no current location is available, it tries to initialize it using GNSS sensor data.
     *
     * @param pdrMoved The movement delta from PDR in [latitudeDelta, longitudeDelta].
     * @param isFused  Whether to update the fused position and fused polyline, or just the raw PDR one.
     */
    private void updatePDRandFusionPosition(float[] pdrMoved, boolean isFused) {
        LatLng location = isFused ? fusedCurrentLocation : currentLocation;
        Polyline targetPolyline = isFused ? fusedPolyline : polyline;

        if (location != null) {
            LatLng nextLocation = UtilFunctions.calculateNewPos(location, pdrMoved);
            if (nextLocation == null) {
                Log.e("PlottingPDR", "❌ nextLocation is NULL!");
                return;
            }

            try {
                List<LatLng> points = new ArrayList<>(targetPolyline.getPoints());
                points.add(nextLocation);
                targetPolyline.setPoints(points);
            } catch (Exception ex) {
                Log.e("PlottingPDR", "❌ Exception: " + ex.getMessage());
            }

            // Update current location
            if (isFused) {
                fusedCurrentLocation = nextLocation;
            } else {
                currentLocation = nextLocation;
            }

            // Update orientation marker position if present
            if (orientationMarker != null) {
                orientationMarker.setPosition(fusedCurrentLocation);
            }
        } else {
            // Attempt to initialize start location using GNSS sensor data
            float[] locationData = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
            if (locationData != null && locationData.length >= 2) {
                LatLng startLocation = new LatLng(locationData[0], locationData[1]);
                if (isFused) {
                    fusedCurrentLocation = startLocation;
                } else {
                    currentLocation = startLocation;
                }
            } else {
                Log.e("PlottingPDR", "❌ GNSS location unavailable!");
            }
        }
    }

    /**
     * Sets the visibility of floor control UI elements (Floor Up, Floor Down, Auto-Floor).
     *
     * @param visibility Visibility constant: View.VISIBLE, View.INVISIBLE, or View.GONE.
     */
    private void setFloorButtonVisibility(int visibility) {
        if (floorUpButton != null) {
            floorUpButton.setVisibility(visibility);
        } else {
            Log.e("UI Visibility", "floorUpButton is NULL!");
        }

        if (floorDownButton != null) {
            floorDownButton.setVisibility(visibility);
        } else {
            Log.e("UI Visibility", "floorDownButton is NULL!");
        }

        if (autoFloor != null) {
            autoFloor.setVisibility(visibility);
        } else {
            Log.e("UI Visibility", "autoFloor Switch is NULL!");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // If currently recording, resume the UI update task
        if (isRecording && refreshDataHandler != null) {
            refreshDataHandler.post(refreshDataTask);
            Log.d("RecordingFragment", "onResume: Resuming UI refresh task");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Stop UI update task to avoid background execution when leaving the page
        if (refreshDataHandler != null) {
            refreshDataHandler.removeCallbacks(refreshDataTask);
            Log.d("RecordingFragment", "onPause: Stopping UI refresh task");
        }
        if (sensorFusion != null) {
            sensorFusion.stopListening(); // Stop all sensor listeners
            sensorFusion.stopRecording();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sensorFusion != null) {
            sensorFusion.stopListening(); // Stop all sensor listeners
            sensorFusion.stopRecording();
        }
        // Clear all Handler callbacks to prevent memory leaks
        if (refreshDataHandler != null) {
            refreshDataHandler.removeCallbacksAndMessages(null);
            Log.d("RecordingFragment", "onDestroy: Cleared all Handler callbacks");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (sensorFusion != null) {
            sensorFusion.stopListening(); // Stop all sensor listeners
            sensorFusion.stopRecording();
        }
        if (timer != null) {
            timer.cancel(); // Cancel any running timers
        }
    }

    /**
     * Starts a blinking animation on the red recording icon.
     */
    private void blinkingRecording() {
        // Initialize ImageView
        this.recIcon = getView().findViewById(R.id.redDot);

        // Configure blinking animation
        Animation blinking_rec = new AlphaAnimation(1, 0);
        blinking_rec.setDuration(800);
        blinking_rec.setInterpolator(new LinearInterpolator());
        blinking_rec.setRepeatCount(Animation.INFINITE);
        blinking_rec.setRepeatMode(Animation.REVERSE);

        // Start the animation
        recIcon.startAnimation(blinking_rec);
    }


}
