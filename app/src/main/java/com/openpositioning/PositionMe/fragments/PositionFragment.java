package com.openpositioning.PositionMe.fragments;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.openpositioning.PositionMe.R;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolygonOptions;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.Arrays;
import java.util.List;

public class PositionFragment extends Fragment implements OnMapReadyCallback {
    private GoogleMap mMap;
    private TextView tvLatitude, tvLongitude, wifiUpdateTime;
    private Button setButton, resetButton;
    private LatLng initialPosition;
    private boolean isGpsInitialized = false;
    // current marker position
    private LatLng currentMarkerPosition;
    private LatLng currentWiFiLocation;
    private long lastWiFiUpdateTime = 0;

    // user fixed marker position
    private LatLng fixedMarkerPosition;

    private final Handler handler = new Handler();
    private final int WIFI_CHECK_INTERVAL = 1000;


    // Gnss
    private LocationManager locationManager;
    private LocationListener locationListener;

    // Interest Zones
    private List<LatLng> libraryZone;
    private List<LatLng> nucleusZone;
    private Marker currentMarker;  // marker for user dragging

    private LatLng library_NE;
    private LatLng library_SW;
    private LatLng necleus_NE;
    private LatLng necleus_SW;

    private SensorFusion sensorFusion;


    // position permission launcher
    private final ActivityResultLauncher<String> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startGNSS();
                } else {
                    Toast.makeText(getContext(), "Location permission denied.", Toast.LENGTH_SHORT).show();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_position, container, false);

        // delete the old marker
        if (currentMarker != null) {
            currentMarker.remove();
            currentMarker = null;
            Log.d("MarkerReset", "🔥 旧 Marker 被移除");
        }

        // initialise location manager
        locationManager = (LocationManager) requireActivity().getSystemService(Context.LOCATION_SERVICE);

        // ui bounding
        tvLatitude = view.findViewById(R.id.tv_latitude);
        tvLongitude = view.findViewById(R.id.tv_longitude);
        wifiUpdateTime = view.findViewById(R.id.wifi_updateTime);
        setButton = view.findViewById(R.id.button_set);
        resetButton = view.findViewById(R.id.button_reset);

        // obtain map Fragment
        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.map_fragment);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
        this.sensorFusion = SensorFusion.getInstance();
        sensorFusion.setContext(getActivity().getApplicationContext());
//        sensorFusion.resumeListening();  // 注册所有传感器监听器 Register all sensor listeners
        if (this.sensorFusion == null) {
            Log.e("SensorFusion", "❌ SensorFusion is NULL! Retrying initialization...");
            this.sensorFusion = SensorFusion.getInstance(); // 重新获取实例 Re-obtain the instance
        } else {
            sensorFusion.startWifiScanOnly();
            Log.d("SensorFusion", "✅ SensorFusion 初始化成功");
        }
//
//        if (sensorFusion != null) {
//            sensorFusion.setContext(getActivity().getApplicationContext());
//            sensorFusion.resumeListening();  // 注册所有传感器监听器 Register all sensor listeners
//            Toast.makeText(getContext(), "Recording Started", Toast.LENGTH_SHORT).show();
//            Log.d("RecordingFragment", "🚀 SensorFusion 录制已启动");
//            isRecording = true; // 标记正在录制 Mark recording
//            // 开始更新 UI
//            // Start updating the UI
//            refreshDataHandler.post(refreshDataTask);
//
//        } else {
//            Log.e("RecordingFragment", "❌ SensorFusion 未初始化！");
//        }

        // initialize interest zones
        initializeInterestZonesData();
        return view;
    }

    private void initializeInterestZonesData() {
        library_NE = new LatLng(55.92306692576906, -3.174771893078224);
        library_SW = new LatLng(55.92281045664704, -3.175184089079065);

        necleus_NE = new LatLng(55.92332001571212, -3.1738768212979593);
        necleus_SW = new LatLng(55.92282257022002, -3.1745956532857647);

        // Calculate the regin
        LatLng library_NW = new LatLng(library_NE.latitude, library_SW.longitude);
        LatLng library_SE = new LatLng(library_SW.latitude, library_NE.longitude);

        LatLng necleus_NW = new LatLng(necleus_NE.latitude, necleus_SW.longitude);
        LatLng necleus_SE = new LatLng(necleus_SW.latitude, necleus_NE.longitude);

        libraryZone = Arrays.asList(library_NW, library_NE, library_SE, library_SW);
        nucleusZone = Arrays.asList(necleus_NW, necleus_NE, necleus_SE, necleus_SW);

        Log.d("InterestZones", "✅ Library Zone Initialized: " + libraryZone.size() + " points");
        Log.d("InterestZones", "✅ Nucleus Zone Initialized: " + nucleusZone.size() + " points");
    }


    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        // set map type
        mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);

        // Default to Edinburgh
        initialPosition = new LatLng(55.953251, -3.188267);
        fixedMarkerPosition = initialPosition;
        currentMarkerPosition = initialPosition;

        // Ensure `locationManager` is initialized
        if (locationManager == null) {
            Log.e("GNSS", "❌ LocationManager is NULL!");
            locationManager = (LocationManager) requireActivity().getSystemService(Context.LOCATION_SERVICE);
        }

        // ensure permission
        if (locationManager != null && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

            if (lastKnownLocation != null) {
                // Gnss position discovered
                initialPosition = new LatLng(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude());
                fixedMarkerPosition = initialPosition;
                currentMarkerPosition = initialPosition;
            } else {
                Log.w("GNSS", "⚠️ No last known location available, using default.");
            }
        } else {
            Log.w("GNSS", "⚠️ LocationManager unavailable or permission not granted.");
        }

        // ******** new wifi initial position ********
        if (sensorFusion != null) {
            Log.e("GNSS", "Sensor Fusion Ready");
//            if (sensorFusion.getLatLngWifiPositioning() != null) {
            if (sensorFusion.getLastWifiPos() != null) {
//                initialPosition = sensorFusion.getLatLngWifiPositioning();
                initialPosition = sensorFusion.getLastWifiPos();
            } else {
                Toast.makeText(getContext(), "Can't resolve wifi position as initial position, please try again later!", Toast.LENGTH_SHORT).show();
            }
        }
        // ******** END new wifi initial position ********

        // add maker to map
        currentMarker = mMap.addMarker(new MarkerOptions()
                .position(initialPosition)
                .draggable(true)
                .title("Drag me"));

        // set initial position
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialPosition, 15));

        // initialize interest zones
        initializeInterestZones();
        startWiFiCheckLoop();

        // add marker drag listener
        mMap.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
            @Override
            public void onMarkerDragStart(Marker marker) {}

            @Override
            public void onMarkerDrag(Marker marker) {
                updateMarkerInfo(marker.getPosition());
            }

            @Override
            public void onMarkerDragEnd(Marker marker) {
                currentMarkerPosition = marker.getPosition();
                updateMarkerInfo(marker.getPosition());
                checkIfInInterestZone(marker.getPosition());
            }
        });

        // ask for location permission
        requestLocationPermission();

        // configure button
        setButton.setOnClickListener(v -> {
            if (currentMarker != null) {
                LatLng markerPosition = currentMarker.getPosition();
                sensorFusion.stopWifiScanOnly();
                Toast.makeText(getContext(), "Location set!", Toast.LENGTH_SHORT).show();

                // create bundle for RecordingFragment
                Bundle bundle = new Bundle();
                bundle.putDouble("marker_latitude", markerPosition.latitude);
                bundle.putDouble("marker_longitude", markerPosition.longitude);

                // stop GNSS listener
                locationManager.removeUpdates(locationListener);

                // Initialize RecordingFragment and set arguments
                RecordingFragment recordingFragment = new RecordingFragment();
                recordingFragment.setArguments(bundle);

                // jump to RecordingFragment
                FragmentTransaction transaction = requireActivity().getSupportFragmentManager().beginTransaction();
                transaction.replace(R.id.fragment_container, recordingFragment);
                transaction.addToBackStack(null);
                transaction.commit();
            }
        });

        // Set reset button
        resetButton.setOnClickListener(v -> {
            if (currentMarker != null) {
                this.rescanPosition();
                currentMarker.setPosition(initialPosition);
                currentMarkerPosition = initialPosition;
                updateMarkerInfo(initialPosition);
            }
        });
    }

    private void startWiFiCheckLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkWiFiState();
                handler.postDelayed(this, WIFI_CHECK_INTERVAL);
            }
        }, WIFI_CHECK_INTERVAL);
    }
    /**
     * This block check the current state of wifi position
     * if updated, updates the time it detects
     * otherwise say the position not available
     */
    private void checkWiFiState() {
        // Get the latest WiFi position from sensorFusion
        LatLng wifiPosition = sensorFusion.getLastWifiPos();
        long currentTime = System.currentTimeMillis();

        if (wifiPosition != null) {
            // If this is the first update or the position has changed, refresh the update time.
            if (currentWiFiLocation == null || !wifiPosition.equals(currentWiFiLocation)) {
                lastWiFiUpdateTime = sensorFusion.getLastWifiSuccessTime();
                currentWiFiLocation = wifiPosition;
            }
            // Calculate the elapsed time in minutes since the last update
            long timeDiff = (currentTime - lastWiFiUpdateTime) / (1000 * 60);
            Log.d("WiFi", "Time difference: " + (currentTime - lastWiFiUpdateTime));
            if (timeDiff >= 1) {
                wifiUpdateTime.setText("WiFi last updated: " + timeDiff + " min ago");
            } else {
                wifiUpdateTime.setText("WiFi last updated: Just Now");
            }
        } else {
            wifiUpdateTime.setText("WiFi last updated: Position Not Available");
        }
    }

    // initialize interest zones
    private void initializeInterestZones() {
        if (libraryZone == null || nucleusZone == null) {
            Log.e("InterestZones", "❌ Interest zones data is NULL!");
            return;
        }

        // draw interest zones
        drawPolygon(libraryZone, Color.BLUE);
        drawPolygon(nucleusZone, Color.GREEN);
    }



    // draw polygon for interest zones
    private void drawPolygon(List<LatLng> zone, int color) {
        if (mMap == null) {
            Log.e("MapError", "❌ GoogleMap is NULL! Cannot draw polygon.");
            return;
        }

        if (zone == null || zone.isEmpty()) {
            Log.e("PolygonError", "❌ Zone is NULL or EMPTY!");
            return;
        }

        PolygonOptions polygonOptions = new PolygonOptions()
                .addAll(zone)
                .strokeColor(color)
                .fillColor(Color.argb(50, Color.red(color), Color.green(color), Color.blue(color)))
                .strokeWidth(3);
        mMap.addPolygon(polygonOptions);
        Log.d("PolygonDraw", "✅ Polygon drawn with " + zone.size() + " points.");
    }


    /**
     * checkIfInInterestZone is called when the user drags the marker.
     * @param markerPosition The current position of the marker.
     */
    private void checkIfInInterestZone(LatLng markerPosition) {
        if (isPointInPolygon(markerPosition, libraryZone)) {
            showZoneDialog("Library");
        } else if (isPointInPolygon(markerPosition, nucleusZone)) {
            showZoneDialog("Nucleus");
        }
    }

    /**
     *  check if the point is in the polygon
     * @param point The position point to check
     * @param zone The polygon to check against
     * @return true if the point is in the polygon, false otherwise
     */
    private boolean isPointInPolygon(LatLng point, List<LatLng> zone) {
        if (zone == null || zone.isEmpty()) {
            Log.e("InterestZone", "❌ Zone is NULL or EMPTY!");
            return false; // avoid NullPointerException
        }

        int intersectCount = 0;
        for (int j = 0; j < zone.size(); j++) {
            LatLng a = zone.get(j);
            LatLng b = zone.get((j + 1) % zone.size());
            if (rayCastIntersect(point, a, b)) {
                intersectCount++;
            }
        }
        return (intersectCount % 2) == 1; // if odd, point is inside
    }


    /**
     *  check if the ray intersects the polygon
     * @param point The position point to check
     * @param a The first point of the ray
     * @param b The second point of the ray
     * @return true if the ray intersects the polygon, false otherwise
     */
    private boolean rayCastIntersect(LatLng point, LatLng a, LatLng b) {
        double px = point.longitude;
        double py = point.latitude;
        double ax = a.longitude;
        double ay = a.latitude;
        double bx = b.longitude;
        double by = b.latitude;

        if (ay > by) {
            ax = b.longitude;
            ay = b.latitude;
            bx = a.longitude;
            by = a.latitude;
        }

        if (py == ay || py == by) {
            py += 0.00000001;
        }

        if ((py > by || py < ay) || (px > Math.max(ax, bx))) {
            return false;
        }

        if (px < Math.min(ax, bx)) {
            return true;
        }

        double red = (px - ax) / (bx - ax);
        double blue = (py - ay) / (by - ay);
        return (red >= blue);
    }


    /**
     *  show the dialog for the zone
     * @param zoneName The name of the zone
     */
    private void showZoneDialog(String zoneName) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Entered Interest Zone")
                .setMessage("You have entered the " + zoneName + " area. Do you want to start recording?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    if (currentMarker != null) {
                        LatLng markerPosition = currentMarker.getPosition();

                        // create Bundle for the fragment
                        Bundle bundle = new Bundle();
                        bundle.putString("zone_name", zoneName);
                        bundle.putDouble("marker_latitude", markerPosition.latitude);
                        bundle.putDouble("marker_longitude", markerPosition.longitude);

                        // create RecordingFragment and set arguments
                        RecordingFragment recordingFragment = new RecordingFragment();
                        recordingFragment.setArguments(bundle);

                        // jump to RecordingFragment
                        FragmentTransaction transaction = requireActivity().getSupportFragmentManager().beginTransaction();
                        transaction.replace(R.id.fragment_container, recordingFragment);
                        transaction.addToBackStack(null);
                        transaction.commit();
                    }
                })
                .setNegativeButton("NO", (dialog, which) -> dialog.dismiss())
                .show();
    }


    /**
     * update the marker info
     * @param position The position of the marker
     */
    private void updateMarkerInfo(LatLng position) {
        tvLatitude.setText("Lat: " + String.format("%.5f", position.latitude));
        tvLongitude.setText("Long: " + String.format("%.5f", position.longitude));
    }

    /**
     *  request location permission
     */
    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        } else {
            startGNSS();
        }
    }

    /**
     *  start GNSS listener
     */
    private void startGNSS() {
        locationManager = (LocationManager) requireActivity().getSystemService(Context.LOCATION_SERVICE);

        if (locationManager == null) {
            Log.e("GNSS", "LocationManager is null.");
            return;
        }

        // ensure permission
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e("GNSS", "Permission not granted.");
            return;
        }

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                double latitude = location.getLatitude();
                double longitude = location.getLongitude();
                LatLng newLocation = new LatLng(latitude, longitude);

//                Log.d("GNSS", "Location updated: " + latitude + ", " + longitude);

                // only initialize once
                if (!isGpsInitialized) {
                    isGpsInitialized = true;
                    initialPosition = newLocation;
                    fixedMarkerPosition = newLocation;
                    currentMarkerPosition = newLocation;

                    // renew the marker
                    requireActivity().runOnUiThread(() -> {
                        if (currentMarker != null) {
                            currentMarker.setPosition(initialPosition);
                        } else {
                            currentMarker = mMap.addMarker(new MarkerOptions()
                                    .position(initialPosition)
                                    .draggable(true)
                                    .title("Drag me"));
                        }

                        updateMarkerInfo(initialPosition);
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(initialPosition, 15));
                    });
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(@NonNull String provider) {}

            @Override
            public void onProviderDisabled(@NonNull String provider) {}
        };

        // ask for location updates
        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000,  // interval (milli seconds)
                1,     // update only if distance is more than 1 meter
                locationListener
        );

        Log.d("GNSS", "GNSS Listening started!");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
            Log.d("GNSS", "GNSS Listener stopped.");
        }
//        sensorFusion.stopListening();
        sensorFusion.stopWifiScanOnly();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mMap != null) {
            // check if fixedMarkerPosition is null
            if (fixedMarkerPosition == null) {
                Log.e("MarkerReset", "⚠️ fixedMarkerPosition is NULL! Using default Edinburgh location.");
                fixedMarkerPosition = new LatLng(55.953251, -3.188267); // reset to Edinburgh
            }

            Log.d("MarkerReset", "Recreating Marker at " + fixedMarkerPosition);
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        // stop GNSS listener
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
            Log.d("GNSS", "🔥 GNSS Listener Stopped in onPause()");
        }
    }

    private void rescanPosition() {

        // Ensure `locationManager` is initialized
        if (locationManager == null) {
            Log.e("GNSS", "❌ LocationManager is NULL!");
            locationManager = (LocationManager) requireActivity().getSystemService(Context.LOCATION_SERVICE);
        }

        // ensure permission
        if (locationManager != null && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

            if (lastKnownLocation != null) {
                // Gnss position discovered
                initialPosition = new LatLng(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude());
                fixedMarkerPosition = initialPosition;
                currentMarkerPosition = initialPosition;
            } else {
                Log.w("GNSS", "⚠️ No last known location available, using default.");
            }
        } else {
            Log.w("GNSS", "⚠️ LocationManager unavailable or permission not granted.");
        }


        if (sensorFusion != null) {
            Log.e("GNSS", "Sensor Fusion Ready");
//            if (sensorFusion.getLatLngWifiPositioning() != null) {
//                initialPosition = sensorFusion.getLatLngWifiPositioning();
//            }
            if (sensorFusion.getLastWifiPos() != null) {
                initialPosition = sensorFusion.getLastWifiPos();
            } else {
                Toast.makeText(
                    getContext(),
                "Can't resolve wifi position as initial position, please try again later! Using GPS/Default position",
                    Toast.LENGTH_SHORT
                ).show();
            }
        }
        // set initial position
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialPosition, 15));
    }




}

