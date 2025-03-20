package com.openpositioning.PositionMe.fragments;

import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.UtilFunctions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FusionFragment extends Fragment implements OnMapReadyCallback {

    private static final String TAG = "FusionFragment";

    // Google Map and drawing variables.
    private GoogleMap mMap;
    private Marker fusionMarker;
    private Polyline fusionPolyline;
    private List<LatLng> fusionPath = new ArrayList<>();

    // Handler and runnable for live updates.
    private Handler updateHandler = new Handler(Looper.getMainLooper());
    private Runnable fusionUpdateRunnable;
    private static final long UPDATE_INTERVAL_MS = 200; // Update every 200ms

    // SensorFusion instance.
    private SensorFusion sensorFusion;

    // Indoor map manager.
    private IndoorMapManager indoorMapManager;

    // Spinner for map type.
    private Spinner mapSpinner;

    // Variables to track movement from PDR.
    private float previousPosX = 0;
    private float previousPosY = 0;
    private LatLng currentFusionLocation;

    public FusionFragment() {
        // Required empty public constructor.
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate the layout for FusionFragment (make sure fragment_fusion.xml exists)
        return inflater.inflate(R.layout.fragment_fusion, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated called");

        // Set up exit button to navigate back to HomeFragment.
        view.findViewById(R.id.exitButton_fusion).setOnClickListener(v ->
                Navigation.findNavController(v).popBackStack(R.id.homeFragment, false)
        );

        // Set up floor buttons.
        view.findViewById(R.id.floorUpButton_fusion).setOnClickListener(v -> {
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
            }
        });
        view.findViewById(R.id.floorDownButton_fusion).setOnClickListener(v -> {
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
            }
        });

        // Retrieve the map spinner.
        mapSpinner = view.findViewById(R.id.mapSwitchSpinner_fusion);

        // Get the SensorFusion instance.
        sensorFusion = SensorFusion.getInstance();

        // Define periodic updates.
        fusionUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateFusionUI();
                updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
            }
        };

        // Retrieve and initialize the map fragment.
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.fusionMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        } else {
            Log.e(TAG, "Fusion map fragment is null");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateHandler.postDelayed(fusionUpdateRunnable, UPDATE_INTERVAL_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        updateHandler.removeCallbacks(fusionUpdateRunnable);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        Log.d(TAG, "onMapReady called");
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        mMap.getUiSettings().setCompassEnabled(true);

        // Initialize IndoorMapManager.
        indoorMapManager = new IndoorMapManager(mMap);

        // For testing: use the initial GNSS position as starting point.
        float[] initGNSS = sensorFusion.getGNSSLatitude(true);
        currentFusionLocation = new LatLng(initGNSS[0], initGNSS[1]);
        // Set previous PDR values to zero.
        previousPosX = 0;
        previousPosY = 0;

        fusionMarker = mMap.addMarker(new MarkerOptions().position(currentFusionLocation)
                .title("Fusion Position"));
        fusionPath.add(currentFusionLocation);
        PolylineOptions polylineOptions = new PolylineOptions()
                .addAll(fusionPath)
                .color(getResources().getColor(R.color.pastelBlue));
        fusionPolyline = mMap.addPolyline(polylineOptions);
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentFusionLocation, 19f));

        // Update indoor map manager.
        indoorMapManager.setCurrentLocation(currentFusionLocation);
        // For now, floor remains default (0).
        indoorMapManager.setCurrentFloor(0, true);
        indoorMapManager.setIndicationOfIndoorMap();

        // Set up the spinner.
        setupMapSpinner();
    }

    /**
     * Updates the fusion UI by retrieving live sensor data from SensorFusion.
     * This method simulates live updates by reading PDR values and updating the fused position.
     */
    private void updateFusionUI() {
        // Get sensor values from SensorFusion.
        // We assume SensorTypes.PDR is a float[2] array representing movement deltas.
        // And SensorTypes.GNSSLATLONG is a float[2] array with GNSS position.
        Map<SensorTypes, float[]> sensorMap = sensorFusion.getSensorValueMap();
        float[] pdrValues = sensorMap.get(SensorTypes.PDR);
        float[] gnssValues = sensorMap.get(SensorTypes.GNSSLATLONG);

        // For this example, we use PDR values to update our fused position.
        // Calculate the net movement since the last update.
        float deltaX = pdrValues[0] - previousPosX;
        float deltaY = pdrValues[1] - previousPosY;

        // If there's significant movement, update the fused position.
        if (deltaX != 0 || deltaY != 0) {
            // Use a helper function to calculate a new LatLng based on the current position and movement delta.
            // This function should convert a movement (in meters) into a change in latitude/longitude.
            // For example, UtilFunctions.calculateNewPos(currentFusionLocation, new float[]{deltaX, deltaY});
            LatLng newFusionLocation = UtilFunctions.calculateNewPos(currentFusionLocation, new float[]{deltaX, deltaY});

            // Update the marker and polyline.
            if (fusionMarker != null) {
                fusionMarker.setPosition(newFusionLocation);
            }
            fusionPath.add(newFusionLocation);
            if (fusionPolyline != null) {
                fusionPolyline.setPoints(fusionPath);
            }
            mMap.animateCamera(CameraUpdateFactory.newLatLng(newFusionLocation));
            // Update current position.
            currentFusionLocation = newFusionLocation;

            // Save the current PDR values for the next update.
            previousPosX = pdrValues[0];
            previousPosY = pdrValues[1];
        }

        // (Optionally) you can also update fused position using GNSS data.
        // For instance, if GNSS data deviates significantly, you could incorporate it.
        // For now, this example prioritizes PDR-based updates.
    }

    /**
     * Sets up the map spinner to allow switching between different map types.
     */
    private void setupMapSpinner() {
        String[] maps = new String[]{"Hybrid", "Normal", "Satellite"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, maps);
        mapSpinner.setAdapter(adapter);
        mapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Log.d(TAG, "Spinner selected: " + maps[position]);
                if (mMap == null) return;
                switch (position) {
                    case 0:
                        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                    case 1:
                        mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                        break;
                    case 2:
                        mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                        break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                if (mMap != null) mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
            }
        });
    }
}
