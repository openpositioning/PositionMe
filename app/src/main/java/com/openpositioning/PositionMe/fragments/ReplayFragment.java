package com.openpositioning.PositionMe.fragments;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.R;

import java.util.ArrayList;
import java.util.List;

public class ReplayFragment extends Fragment {

    private static final String TAG = "ReplayFragment";

    private GoogleMap googleMap;
    private List<LatLng> trajectoryPoints; // Stores trajectory path (latitude, longitude)
    private Marker replayMarker; // Marker to animate during replay
    private Polyline trajectoryPolyline; // The trajectory path on map
    private Handler replayHandler = new Handler();
    private int currentPointIndex = 0;

    public ReplayFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_replay, container, false);

        // Initialize SupportMapFragment
        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager()
                .findFragmentById(R.id.replayMap);

        if (mapFragment != null) {
            mapFragment.getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(GoogleMap map) {
                    googleMap = map;
                    googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                    googleMap.getUiSettings().setZoomControlsEnabled(true);

                    // Load trajectory data from arguments
                    loadTrajectoryData();

                    // Draw trajectory if available
                    if (trajectoryPoints != null && !trajectoryPoints.isEmpty()) {
                        drawTrajectoryPath();
                        startTrajectoryReplay();
                    } else {
                        Log.e(TAG, "No trajectory data found!");
                    }
                }
            });
        }

        return view;
    }

    /**
     * Loads trajectory data from the arguments passed by FilesFragment.
     */
    private void loadTrajectoryData() {
        if (getArguments() != null) {
            trajectoryPoints = (ArrayList<LatLng>) getArguments().getSerializable("trajectoryPoints");
        }

        if (trajectoryPoints == null || trajectoryPoints.isEmpty()) {
            Log.e(TAG, "Trajectory data is empty or not received!");
        } else {
            Log.d(TAG, "Trajectory data loaded successfully! Points: " + trajectoryPoints.size());
        }
    }

    /**
     * Draws the recorded trajectory on the map using a polyline.
     */
    private void drawTrajectoryPath() {
        if (trajectoryPoints == null || trajectoryPoints.isEmpty()) {
            return;
        }

        PolylineOptions polylineOptions = new PolylineOptions()
                .addAll(trajectoryPoints)
                .width(8f)
                .color(Color.RED);

        trajectoryPolyline = googleMap.addPolyline(polylineOptions);

        // Place initial marker at the start position
        replayMarker = googleMap.addMarker(new MarkerOptions()
                .position(trajectoryPoints.get(0))
                .title("Replay Start")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));

        // Move camera to the start position
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(trajectoryPoints.get(0), 18f));
    }

    /**
     * Starts animating the marker along the trajectory path.
     */
    private void startTrajectoryReplay() {
        if (trajectoryPoints == null || trajectoryPoints.isEmpty()) {
            return;
        }

        currentPointIndex = 0;
        replayHandler.postDelayed(replayRunnable, 1000); // Start replay with delay
    }

    private final Runnable replayRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentPointIndex < trajectoryPoints.size()) {
                LatLng nextPoint = trajectoryPoints.get(currentPointIndex);

                // Move marker to next position
                if (replayMarker != null) {
                    replayMarker.setPosition(nextPoint);
                }

                // Move camera smoothly
                googleMap.animateCamera(CameraUpdateFactory.newLatLng(nextPoint));

                // Schedule the next movement
                currentPointIndex++;
                replayHandler.postDelayed(this, 1000); // Move every 1 second
            } else {
                Log.d(TAG, "Replay complete!");
            }
        }
    };

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        replayHandler.removeCallbacks(replayRunnable);
    }
}
