package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.CollectionActivity;

/**
 * Fragment specifically for data-collection workflow:
 * - Displays a nested TrajectoryMapFragment for real-time or background location updates.
 * - Provides UI to set a calibration marker, floor-level, indoorState, buildingName, etc.
 * - "Add Calibration Point" places a draggable marker.
 * - "Finish" calls the parent's onCalibrationTriggered(...) with user-labeled lat/lng/floor/etc.
 * - "Cancel" simply closes or pops the fragment/activity.
 */
public class CollectionFragment extends Fragment {

    private TrajectoryMapFragment trajectoryMapFragment;
    private Spinner floorSpinner, indoorStateSpinner;
    private EditText buildingNameEditText;
    private Button addMarkerButton, finishButton, cancelButton;

    // The draggable marker for calibration
    private Marker calibrationMarker;

    // We'll store the user’s chosen spinner selections
    private int selectedFloorLevel = -1;    // default -1 (Outdoors)
    private int selectedIndoorState = 0;    // default 0 (Unknown)
    private String buildingName = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate the UI that has the map container + spinners + buttons
        return inflater.inflate(R.layout.fragment_collection, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 1) Setup child fragment that holds the actual map (TrajectoryMapFragment)
        //    We'll place it in the FrameLayout with id=mapContainer
        FragmentTransaction ft = getChildFragmentManager().beginTransaction();
        trajectoryMapFragment = new TrajectoryMapFragment();
        ft.replace(R.id.mapContainer, trajectoryMapFragment);
        ft.commit();

        // 2) Find references to UI elements
        floorSpinner = view.findViewById(R.id.floorSpinner);
        indoorStateSpinner = view.findViewById(R.id.indoorStateSpinner);
        buildingNameEditText = view.findViewById(R.id.buildingNameEditText);

        addMarkerButton = view.findViewById(R.id.addCalibrationMarkerButton);
        finishButton = view.findViewById(R.id.finishButton);
        cancelButton = view.findViewById(R.id.cancelButton);

        // 3) Setup spinners
        setupFloorSpinner();
        setupIndoorStateSpinner();

        // 4) Button listeners
        addMarkerButton.setOnClickListener(v -> {
            // If marker doesn't exist, create one at the current location or center of the map
            addOrMoveCalibrationMarker();
        });

        finishButton.setOnClickListener(v -> {
            // Gather final marker position + user-labeled fields
            onFinishCalibration();
        });

        cancelButton.setOnClickListener(v -> {
            // Optional: simply pop the fragment or finish the activity
            if (getActivity() != null) {
                getActivity().finish();
            }
        });
    }

    /**
     * Setup the floor level spinner to track the user's selection
     */
    private void setupFloorSpinner() {
        // If you have a string-array in arrays.xml, you can attach an ArrayAdapter:
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                requireContext(),
                R.array.floor_levels,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        floorSpinner.setAdapter(adapter);

        floorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent,
                                       View view, int position, long id) {
                // Convert spinner item to int.
                // If your array item is "0", "1", etc. you can parse it.
                // Alternatively, do a direct mapping logic:
                String selectedString = parent.getItemAtPosition(position).toString();

                // Example parse logic: check if it starts with '-1' or '0'
                if (selectedString.contains("Outdoors") || selectedString.startsWith("-1")) {
                    selectedFloorLevel = -1;
                } else {
                    try {
                        // e.g. "0", "1", "2"
                        selectedFloorLevel = Integer.parseInt(selectedString.split(" ")[0]);
                    } catch (NumberFormatException e) {
                        selectedFloorLevel = -1;
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /**
     * Setup the indoorState spinner to track selection of 0=unknown,1=indoor,2=outdoor,3=transitional
     */
    private void setupIndoorStateSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                requireContext(),
                R.array.indoor_states,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        indoorStateSpinner.setAdapter(adapter);

        indoorStateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent,
                                       View view, int position, long id) {
                // e.g. "0 - Unknown", "1 - Indoor", ...
                String text = parent.getItemAtPosition(position).toString();
                try {
                    selectedIndoorState = Integer.parseInt(text.substring(0,1));
                } catch (Exception e) {
                    selectedIndoorState = 0; // fallback
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /**
     * Places or moves a single calibration marker on the map.
     * If no current marker, create one at the user's current location (or a default location).
     * Otherwise, re-center it or inform user to drag it.  Markers are draggable by default (setDraggable(true)).
     */
    private void addOrMoveCalibrationMarker() {
        GoogleMap map = trajectoryMapFragment != null
                ? trajectoryMapFragment.getGoogleMap() : null;
        if (map == null) return;

        // If we already have a marker, just center the camera on it or prompt the user
        if (calibrationMarker != null) {
            // Possibly move camera to that marker
            map.animateCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLng(calibrationMarker.getPosition()));
            return;
        }

        // Otherwise, place marker at "current user location" or some default
        LatLng userLocation = trajectoryMapFragment.getCurrentLocation();
        if (userLocation == null) {
            // fallback if userLocation is unknown
            userLocation = new LatLng(55.9228, -3.1746); // example fallback
        }

        calibrationMarker = map.addMarker(new MarkerOptions()
                .position(userLocation)
                .title("Calibration Marker")
                .draggable(true));

        map.animateCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(userLocation, 19f));

        // Optionally, listen to drag events:
        map.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
            @Override
            public void onMarkerDragStart(@NonNull Marker marker) { }
            @Override
            public void onMarkerDrag(@NonNull Marker marker) { }
            @Override
            public void onMarkerDragEnd(@NonNull Marker marker) {
                // Not strictly needed, but you can store new position if you want
                calibrationMarker.setPosition(marker.getPosition());
            }
        });
    }

    /**
     * Called when user taps "Finish". Gather the marker lat/lng, floor, indoorState, buildingName,
     * then call the parent activity's onCalibrationTriggered(...).
     * If marker is missing, possibly show a message.
     */
    private void onFinishCalibration() {
        if (calibrationMarker == null) {
            // Show a toast or some message that user needs to place a marker
            // e.g. Toast.makeText(requireContext(), "Place a calibration marker first!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Read building name from EditText
        buildingName = buildingNameEditText.getText().toString();
        if (TextUtils.isEmpty(buildingName)) {
            buildingName = null; // or you can keep an empty string
        }

        LatLng markerPos = calibrationMarker.getPosition();
        double lat = markerPos.latitude;
        double lng = markerPos.longitude;

        // Now call the parent's method
        if (getActivity() instanceof CollectionActivity) {
            ((CollectionActivity) getActivity()).onCalibrationTriggered(
                    lat,
                    lng,
                    selectedIndoorState,
                    selectedFloorLevel,
                    buildingName
            );
        }

        // Optionally close or pop this fragment
        if (getActivity() != null) {
            getActivity().finish();
        }
    }
}
