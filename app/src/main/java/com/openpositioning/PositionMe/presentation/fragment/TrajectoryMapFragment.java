package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.utils.BuildingMapController;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TrajectoryMapFragment extends Fragment implements OnMapReadyCallback {

    private GoogleMap gMap;
    private LatLng currentLocation;
    private Marker orientationMarker;
    private Marker gnssMarker;
    private Polyline polyline;
    private boolean isRed = true;
    private boolean isGnssOn = false;

    private Polyline gnssPolyline;
    private LatLng lastGnssLocation = null;

    private LatLng pendingCameraPosition = null;
    private boolean hasPendingCameraMove = false;

    private IndoorMapManager indoorMapManager;
    private BuildingMapController mapController;

    private Spinner switchMapSpinner;
    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;

    private FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private TextView currentFloorIndicator;
    private View floorControlCard;

    // --- Auto Floor Logic ---
    private boolean isAutoFloorEnabled = false;
    private int currentFloorValue = 0;
    private int manualFloorOffset = 0;
    private static final double FLOOR_HEIGHT_STEP = 4.0;


    private static final int MIN_FLOOR_VAL = -1; // BF
    private static final int MAX_FLOOR_VAL = 3;  // 3F

    private BitmapDescriptor createNumberedMarkerIcon(int number) {
        final int size = 56;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setStyle(Paint.Style.FILL);
        circlePaint.setColor(0xFF2196F3);

        float cx = size / 2f;
        float cy = size / 2f;
        float r = size / 2f;
        canvas.drawCircle(cx, cy, r, circlePaint);

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(3f);
        strokePaint.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, r - 3f, strokePaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(25f);

        String text = String.valueOf(number);
        Rect textBounds = new Rect();
        textPaint.getTextBounds(text, 0, text.length(), textBounds);
        float textY = cy + textBounds.height() / 2f;
        canvas.drawText(text, cx, textY, textPaint);

        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    public TrajectoryMapFragment() {}

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_trajectory_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch      = view.findViewById(R.id.gnssSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);
        floorControlCard = view.findViewById(R.id.floorControlCard);
        currentFloorIndicator = view.findViewById(R.id.currentFloorIndicator);

        currentFloorValue = 0;
        updateFloorIndicatorUI(0);
        setFloorControlsVisibility(View.VISIBLE);

        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        initMapTypeSpinner();

        gnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isGnssOn = isChecked;
            if (!isChecked && gnssMarker != null) {
                gnssMarker.remove();
                gnssMarker = null;
            }
        });

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

        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            isAutoFloorEnabled = isChecked;
            if (isChecked) {
                Toast.makeText(requireContext(), "Auto Floor: ON", Toast.LENGTH_SHORT).show();
                manualFloorOffset = 0;
            }
        });


        floorUpButton.setOnClickListener(v -> {
            if (mapController != null) {
                manualFloorOffset++;
                mapController.changeFloor(1);
            }
        });

        floorDownButton.setOnClickListener(v -> {
            if (mapController != null) {
                manualFloorOffset--;
                mapController.changeFloor(-1);
            }
        });
    }

    public void updateElevation(float currentElevation) {
        if (!isAutoFloorEnabled || mapController == null) {
            return;
        }

        if (currentElevation < 0 && currentElevation > -1.5) {
            currentElevation = 0;
        }

        int relativeFloor = (int) Math.floor(currentElevation / FLOOR_HEIGHT_STEP);
        int targetFloorVal = relativeFloor + manualFloorOffset;

        if (targetFloorVal < MIN_FLOOR_VAL) targetFloorVal = MIN_FLOOR_VAL;
        if (targetFloorVal > MAX_FLOOR_VAL) targetFloorVal = MAX_FLOOR_VAL;

        int currentMapFloorVal = mapController.getCurrentFloorValue();

        if (targetFloorVal != currentMapFloorVal) {
            int delta = targetFloorVal - currentMapFloorVal;
            mapController.changeFloor(delta);
        }
    }

    private String getFloorLabel(int val) {
        if (val == -1) return "BF";
        if (val == 0) return "GF";
        return val + "F";
    }

    private void updateFloorIndicatorUI(int floorVal) {
        if (currentFloorIndicator != null) {
            String label = getFloorLabel(floorVal);
            currentFloorIndicator.setText("Floor: " + label);
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        gMap = googleMap;
        initMapSettings(gMap);

        mapController = new BuildingMapController(requireContext(), gMap);

        mapController.setSelectionListener((buildingName, floorCode) -> {
            setFloorControlsVisibility(View.VISIBLE);

            int val = parseFloorCode(floorCode);
            currentFloorValue = val;
            updateFloorIndicatorUI(val);

            if (!isAutoFloorEnabled) {
                manualFloorOffset = val;
            }
        });

        gMap.setOnPolygonClickListener(polygon -> mapController.onPolygonClick(polygon));

        if (hasPendingCameraMove && pendingCameraPosition != null) {
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
            hasPendingCameraMove = false;
            if (mapController != null) {
                mapController.downloadNearbyBuildings(pendingCameraPosition);
            }
            pendingCameraPosition = null;
        } else {
            LatLng defaultLoc = new LatLng(55.924131, -3.179167);
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLoc, 18f));
            if (mapController != null) {
                mapController.downloadNearbyBuildings(defaultLoc);
            }
        }
    }

    private int parseFloorCode(String code) {
        if (code == null) return 0;
        String raw = code.toUpperCase().trim();

        if (raw.equals("BF") || raw.equals("B")) return -1;
        if (raw.equals("GF") || raw.equals("G") || raw.equals("0") || raw.contains("GROUND")) return 0;
        if (raw.contains("BASEMENT") || raw.contains("BF")) return -1;

        if (raw.startsWith("B")) {
            String digits = raw.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    return -Integer.parseInt(digits);
                } catch (Exception e) {}
            }
            return -1;
        }

        try {
            Matcher matcher = Pattern.compile("-?\\d+").matcher(raw);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group());
            }
        } catch (Exception ignored) {}

        return 0;
    }

    private void initMapSettings(GoogleMap map) {
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        indoorMapManager = new IndoorMapManager(map);


        polyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(5f)
                .zIndex(200f)
                .add()
        );

        gnssPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .width(5f)
                .zIndex(200f)
                .add()
        );
    }

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
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (gMap == null) return;
                switch (position) {
                    case 0: gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID); break;
                    case 1: gMap.setMapType(GoogleMap.MAP_TYPE_NORMAL); break;
                    case 2: gMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE); break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    public void updateUserLocation(@NonNull LatLng newLocation, float orientation) {
        if (gMap == null) return;

        LatLng oldLocation = this.currentLocation;
        this.currentLocation = newLocation;

        if (orientationMarker == null) {
            orientationMarker = gMap.addMarker(new MarkerOptions()
                    .position(newLocation)
                    .flat(true)
                    .title("Current Position")
                    .zIndex(300f)
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24)))
            );
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
        } else {
            orientationMarker.setPosition(newLocation);
            orientationMarker.setRotation(orientation);
            gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
        }

        if (oldLocation != null && !oldLocation.equals(newLocation) && polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            points.add(newLocation);
            polyline.setPoints(points);
        }

        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);
        }
    }

    public void addTagPoint(@NonNull LatLng latLng, int index) {
        if (gMap == null) return;
        gMap.addMarker(new MarkerOptions()
                .position(latLng)
                .anchor(0.5f, 0.5f)
                .zIndex(300f)
                .icon(createNumberedMarkerIcon(index)));
    }

    public void setInitialCameraPosition(@NonNull LatLng startLocation) {
        if (gMap != null) {
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLocation, 19f));
        } else {
            pendingCameraPosition = startLocation;
            hasPendingCameraMove = true;
        }
    }

    public LatLng getCurrentLocation() {
        return currentLocation;
    }

    public void updateGNSS(@NonNull LatLng gnssLocation) {
        if (gMap == null) return;
        if (!isGnssOn) return;

        if (gnssMarker == null) {
            gnssMarker = gMap.addMarker(new MarkerOptions()
                    .position(gnssLocation)
                    .title("GNSS Position")
                    .zIndex(300f)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
            lastGnssLocation = gnssLocation;
        } else {
            gnssMarker.setPosition(gnssLocation);
            if (lastGnssLocation != null && !lastGnssLocation.equals(gnssLocation)) {
                List<LatLng> gnssPoints = new ArrayList<>(gnssPolyline.getPoints());
                gnssPoints.add(gnssLocation);
                gnssPolyline.setPoints(gnssPoints);
            }
            lastGnssLocation = gnssLocation;
        }
    }

    public void clearGNSS() {
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
    }

    public boolean isGnssEnabled() {
        return isGnssOn;
    }

    private void setFloorControlsVisibility(int visibility) {
        if (floorControlCard != null) {
            floorControlCard.setVisibility(visibility);
        }
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

        isAutoFloorEnabled = false;
        currentFloorValue = 0;
        manualFloorOffset = 0;

        if (currentFloorIndicator != null) updateFloorIndicatorUI(0);

        if(autoFloorSwitch != null) autoFloorSwitch.setChecked(false);

        if (gMap != null) {

            polyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.RED)
                    .width(5f)
                    .zIndex(200f)
                    .add());
            gnssPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.BLUE)
                    .width(5f)
                    .zIndex(200f)
                    .add());
        }
    }
}