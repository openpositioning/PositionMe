package com.openpositioning.PositionMe.fragments;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
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

import java.util.Arrays;
import java.util.List;

public class PositionFragment extends Fragment implements OnMapReadyCallback {
    private GoogleMap mMap;
    private TextView tvLatitude, tvLongitude;
    private Button setButton, resetButton;
    private LatLng initialPosition;
    private boolean isGpsInitialized = false;
    // ✅ 当前 Marker 的位置
    private LatLng currentMarkerPosition;

    // ✅ 用户固定的 Marker 位置（点击 "Set" 按钮后）
    private LatLng fixedMarkerPosition;


    // GNSS 相关
    private LocationManager locationManager;
    private LocationListener locationListener;

    // 兴趣区域
    private List<LatLng> libraryZone;
    private List<LatLng> nucleusZone;
    private Marker currentMarker;  // 🟢 存储当前用户拖动的 Marker

    private LatLng library_NE;
    private LatLng library_SW;
    private LatLng necleus_NE;
    private LatLng necleus_SW;


    // 位置权限请求
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

        // 🛑 **删除旧 Marker，避免重复**
        if (currentMarker != null) {
            currentMarker.remove();
            currentMarker = null;
            Log.d("MarkerReset", "🔥 旧 Marker 被移除");
        }

        // ✅ 初始化 LocationManager
        locationManager = (LocationManager) requireActivity().getSystemService(Context.LOCATION_SERVICE);

        // 绑定 UI 组件
        tvLatitude = view.findViewById(R.id.tv_latitude);
        tvLongitude = view.findViewById(R.id.tv_longitude);
        setButton = view.findViewById(R.id.button_set);
        resetButton = view.findViewById(R.id.button_reset);

        // 获取地图 Fragment
        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.map_fragment);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        // 🔥 只初始化兴趣区域的数据（但不画图）
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

        // 设置地图类型为卫星图
        mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);

        // ✅ 先默认设置为爱丁堡
        initialPosition = new LatLng(55.953251, -3.188267);
        fixedMarkerPosition = initialPosition;
        currentMarkerPosition = initialPosition;

        // ✅ 确保 `locationManager` 不为空
        if (locationManager == null) {
            Log.e("GNSS", "❌ LocationManager is NULL!");
            locationManager = (LocationManager) requireActivity().getSystemService(Context.LOCATION_SERVICE);
        }

        // ✅ 确保 `locationManager` 初始化成功后，尝试获取位置
        if (locationManager != null && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

            if (lastKnownLocation != null) {
                // 🔥 发现 GNSS 位置，将其设为初始点
                initialPosition = new LatLng(lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude());
                fixedMarkerPosition = initialPosition;
                currentMarkerPosition = initialPosition;
            } else {
                Log.w("GNSS", "⚠️ No last known location available, using default.");
            }
        } else {
            Log.w("GNSS", "⚠️ LocationManager unavailable or permission not granted.");
        }

        // ✅ 在地图上添加 Marker
        currentMarker = mMap.addMarker(new MarkerOptions()
                .position(initialPosition)
                .draggable(true)
                .title("Drag me"));

        // ✅ 设置相机初始位置
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialPosition, 15));

        // ✅ 初始化兴趣区域
        initializeInterestZones();

        // ✅ 添加 Marker 拖动监听器
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

        // ✅ 申请 GNSS 位置权限（确保 GNSS 监听）
        requestLocationPermission();

        // ✅ 设置 Set 按钮（固定 Marker 位置并跳转）
        setButton.setOnClickListener(v -> {
            if (currentMarker != null) {
                LatLng markerPosition = currentMarker.getPosition();

                Toast.makeText(getContext(), "Location set!", Toast.LENGTH_SHORT).show();

                // 🚀 **创建 Bundle 传递数据**
                Bundle bundle = new Bundle();
                bundle.putDouble("marker_latitude", markerPosition.latitude);
                bundle.putDouble("marker_longitude", markerPosition.longitude);

                // 关闭GNSS监听
                locationManager.removeUpdates(locationListener);

                // 🚀 **创建 RecordingFragment 并设置参数**
                RecordingFragment recordingFragment = new RecordingFragment();
                recordingFragment.setArguments(bundle);

                // 🚀 **跳转到 RecordingFragment**
                FragmentTransaction transaction = requireActivity().getSupportFragmentManager().beginTransaction();
                transaction.replace(R.id.fragment_container, recordingFragment);
                transaction.addToBackStack(null);
                transaction.commit();
            }
        });

        // ✅ 设置 Reset 按钮（恢复初始位置）
        resetButton.setOnClickListener(v -> {
            if (currentMarker != null) {
                currentMarker.setPosition(initialPosition);
                currentMarkerPosition = initialPosition;
                updateMarkerInfo(initialPosition);
            }
        });
    }

    // **初始化兴趣区域**
    private void initializeInterestZones() {
        if (libraryZone == null || nucleusZone == null) {
            Log.e("InterestZones", "❌ Interest zones data is NULL!");
            return;
        }

        // 画出兴趣区域
        drawPolygon(libraryZone, Color.BLUE);
        drawPolygon(nucleusZone, Color.GREEN);
    }



    // **在 Google Map 绘制区域**
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



    // **检查是否进入兴趣区域**
    private void checkIfInInterestZone(LatLng markerPosition) {
        if (isPointInPolygon(markerPosition, libraryZone)) {
            showZoneDialog("Library");
        } else if (isPointInPolygon(markerPosition, nucleusZone)) {
            showZoneDialog("Nucleus");
        }
    }

    private boolean isPointInPolygon(LatLng point, List<LatLng> zone) {
        if (zone == null || zone.isEmpty()) {
            Log.e("InterestZone", "❌ Zone is NULL or EMPTY!");
            return false; // 避免 NullPointerException
        }

        int intersectCount = 0;
        for (int j = 0; j < zone.size(); j++) {
            LatLng a = zone.get(j);
            LatLng b = zone.get((j + 1) % zone.size());
            if (rayCastIntersect(point, a, b)) {
                intersectCount++;
            }
        }
        return (intersectCount % 2) == 1; // 奇数交点则在区域内
    }


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

    private void showZoneDialog(String zoneName) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Entered Interest Zone")
                .setMessage("You have entered the " + zoneName + " area. What do you want to do?")
                .setPositiveButton("OK", (dialog, which) -> {
                    if (currentMarker != null) {
                        LatLng markerPosition = currentMarker.getPosition();

                        // 🚀 **创建 Bundle 传递数据**
                        Bundle bundle = new Bundle();
                        bundle.putString("zone_name", zoneName);
                        bundle.putDouble("marker_latitude", markerPosition.latitude);
                        bundle.putDouble("marker_longitude", markerPosition.longitude);

                        // 🚀 **创建 RecordingFragment 并设置参数**
                        RecordingFragment recordingFragment = new RecordingFragment();
                        recordingFragment.setArguments(bundle);

                        // 🚀 **跳转到 RecordingFragment**
                        FragmentTransaction transaction = requireActivity().getSupportFragmentManager().beginTransaction();
                        transaction.replace(R.id.fragment_container, recordingFragment);
                        transaction.addToBackStack(null);
                        transaction.commit();
                    }
                })
                .setNegativeButton("Continue", (dialog, which) -> dialog.dismiss())
                .show();
    }



    // **更新 UI 经纬度**
    private void updateMarkerInfo(LatLng position) {
        tvLatitude.setText("Lat: " + String.format("%.5f", position.latitude));
        tvLongitude.setText("Long: " + String.format("%.5f", position.longitude));
    }

    // **请求 GNSS 位置**
    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        } else {
            startGNSS();
        }
    }

    private void startGNSS() {
        locationManager = (LocationManager) requireActivity().getSystemService(Context.LOCATION_SERVICE);

        if (locationManager == null) {
            Log.e("GNSS", "LocationManager is null.");
            return;
        }

        // ✅ 确保有权限
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

                // ✅ **仅在 GNSS 位置未初始化时更新起始点**
                if (!isGpsInitialized) {
                    isGpsInitialized = true;
                    initialPosition = newLocation;
                    fixedMarkerPosition = newLocation;
                    currentMarkerPosition = newLocation;

                    // ✅ **更新 Marker 位置并移动相机**
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

        // ✅ **请求 GNSS 更新**
        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000,  // 更新间隔（毫秒）
                1,     // 移动 1m 才更新
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
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mMap != null) {
            // 🛑 **检查 fixedMarkerPosition 是否为空，避免崩溃**
            if (fixedMarkerPosition == null) {
                Log.e("MarkerReset", "⚠️ fixedMarkerPosition is NULL! Using default Edinburgh location.");
                fixedMarkerPosition = new LatLng(55.953251, -3.188267); // 💡 重新设置默认位置
            }

            Log.d("MarkerReset", "✅ 重新创建默认 Marker at " + fixedMarkerPosition);
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        // ✅ **在 Fragment 切换时停止 GNSS**
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
            Log.d("GNSS", "🔥 GNSS Listener Stopped in onPause()");
        }
    }


}

