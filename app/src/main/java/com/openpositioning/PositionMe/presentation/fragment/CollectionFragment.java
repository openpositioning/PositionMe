package com.openpositioning.PositionMe.presentation.fragment;

import static android.graphics.BlendMode.COLOR;

import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.CollectionActivity;

/**
 * CollectionFragment with TrajectoryMapFragment support.
 * Allows multi-point calibration with draggable markers.
 */
public class CollectionFragment extends Fragment {

    private TrajectoryMapFragment trajectoryMapFragment;

    private Spinner floorSpinner, indoorStateSpinner;
    private EditText buildingNameEditText;
    private Button calibrationButton, finishButton, cancelButton;

    private Marker calibrationMarker;
    private boolean markerPlaced = false;

    private int selectedFloorLevel = -1;
    private int selectedIndoorState = 0;
    private String buildingName = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_collection, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Attach TrajectoryMapFragment
        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.mapContainer);

        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            FragmentTransaction ft = getChildFragmentManager().beginTransaction();
            ft.replace(R.id.mapContainer, trajectoryMapFragment).commit();
        }

        floorSpinner = view.findViewById(R.id.floorSpinner);
        indoorStateSpinner = view.findViewById(R.id.indoorStateSpinner);
        buildingNameEditText = view.findViewById(R.id.buildingNameEditText);

        calibrationButton = view.findViewById(R.id.addCalibrationMarkerButton);
        finishButton = view.findViewById(R.id.finishButton);
        cancelButton = view.findViewById(R.id.cancelButton);

        setupFloorSpinner();
        setupIndoorStateSpinner();

        calibrationButton.setText("Add Tag");
        calibrationButton.setOnClickListener(v -> {
            if (!markerPlaced) {
                placeCalibrationMarker();
                markerPlaced = true;
                // change button color
                calibrationButton.setBackgroundColor(Color.GREEN);
                calibrationButton.setText("Confirm");
                Log.d("CollectionFragment", "Calibration marker placed.");
            } else {
                confirmCalibration();
                calibrationMarker = null;
                markerPlaced = false;
                // change button color
                calibrationButton.setBackgroundColor(Color.YELLOW);
                calibrationButton.setText("Add Tag");
            }
        });

        finishButton.setOnClickListener(v -> {
            if (getActivity() != null) getActivity().finish();
        });

        cancelButton.setOnClickListener(v -> {
            if (getActivity() != null) getActivity().finish();
        });
    }

    private void setupFloorSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                requireContext(),
                R.array.floor_levels,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        floorSpinner.setAdapter(adapter);

        floorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = parent.getItemAtPosition(position).toString();
                if (selected.contains("Outdoors") || selected.startsWith("-1")) {
                    selectedFloorLevel = -1;
                } else {
                    try {
                        selectedFloorLevel = Integer.parseInt(selected.split(" ")[0]);
                    } catch (NumberFormatException e) {
                        selectedFloorLevel = -1;
                    }
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupIndoorStateSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                requireContext(),
                R.array.indoor_states,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        indoorStateSpinner.setAdapter(adapter);

        indoorStateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                try {
                    selectedIndoorState = Integer.parseInt(parent.getItemAtPosition(position).toString().substring(0, 1));
                } catch (Exception e) {
                    selectedIndoorState = 0;
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void placeCalibrationMarker() {
        GoogleMap map = trajectoryMapFragment.getGoogleMap();
        if (map == null) return;

        if (calibrationMarker != null) {
            map.animateCamera(CameraUpdateFactory.newLatLng(calibrationMarker.getPosition()));
            return;
        }

        LatLng userLocation = trajectoryMapFragment.getCurrentLocation();
        if (userLocation == null) {
            userLocation = new LatLng(55.9228, -3.1746); // fallback
        }

        calibrationMarker = map.addMarker(new MarkerOptions()
                .position(userLocation)
                .title("Calibration Marker")
                .draggable(true));

        map.animateCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 19f));

        trajectoryMapFragment.updateCalibrationPinLocation(userLocation, false);

        map.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
            @Override public void onMarkerDragStart(@NonNull Marker marker) {}
            @Override public void onMarkerDrag(@NonNull Marker marker) {}
            @Override public void onMarkerDragEnd(@NonNull Marker marker) {
                if (calibrationMarker != null) {
                    calibrationMarker.setPosition(marker.getPosition());
                    trajectoryMapFragment.updateCalibrationPinLocation(marker.getPosition(), false);
                    Log.d("currentfloor", "Building polygon added, vertex count: " + trajectoryMapFragment.getCurrentBuilding());
                    Log.d("currentfloor", "Building polygon added, vertex count: " + trajectoryMapFragment.getCurrentFloor());
                }
            }
        });
    }

    private void confirmCalibration() {
        if (calibrationMarker == null) {
            Log.e("CollectionFragment", "Calibration marker is null.");
            Toast.makeText(getContext(), "No calibration marker placed!", Toast.LENGTH_SHORT).show();
            return;
        }

        buildingName = buildingNameEditText.getText().toString();
        if (TextUtils.isEmpty(buildingName)) {
            Log.w("CollectionFragment", "Building name is empty, using default null value.");
            buildingName = null;
        }

        LatLng markerPos = calibrationMarker.getPosition();
        trajectoryMapFragment.updateCalibrationPinLocation(markerPos, true);
        double lat = markerPos.latitude;
        double lng = markerPos.longitude;

        Log.i("CollectionFragment", "Calibration confirmed at lat: " + lat +
                ", lng: " + lng + ", Indoor State: " + selectedIndoorState +
                ", Floor Level: " + selectedFloorLevel + ", Building Name: " + buildingName);

        Toast.makeText(getContext(), "Calibration confirmed at (" + lat + ", " + lng + ")",
                Toast.LENGTH_SHORT).show();

        if (getActivity() instanceof CollectionActivity) {
            ((CollectionActivity) getActivity()).onCalibrationTriggered(
                    lat,
                    lng,
                    selectedIndoorState,
                    selectedFloorLevel,
                    buildingName
            );
        }
        // clear calibration marker
        calibrationMarker.remove();
    }
}
