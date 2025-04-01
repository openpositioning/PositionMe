package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.*;
import com.google.android.gms.maps.model.*;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.local.TrajParser;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.text.DecimalFormat;
import java.util.List;

public class StatsFragment extends Fragment implements OnMapReadyCallback {

    private TextView distanceTextView, timeTextView, avgSpeedTextView, paceTextView, altitudeTextView;
    private Spinner trajectoryTypeSpinner;
    private GoogleMap map;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_stats, container, false);

        Toolbar toolbar = rootView.findViewById(R.id.statsToolbar);
        toolbar.setNavigationIcon(R.drawable.ic_baseline_back_arrow);
        toolbar.setTitle("Journey Stats");
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setNavigationOnClickListener(v -> requireActivity().onBackPressed());

        distanceTextView = rootView.findViewById(R.id.distanceTextView);
        timeTextView = rootView.findViewById(R.id.timeTextView);
        avgSpeedTextView = rootView.findViewById(R.id.avgSpeedTextView);
        paceTextView = rootView.findViewById(R.id.paceTextView);
        altitudeTextView = rootView.findViewById(R.id.altitudeTextView);
        trajectoryTypeSpinner = rootView.findViewById(R.id.trajectoryTypeSpinner);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{"PDR", "GNSS", "WiFi", "EKF"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        trajectoryTypeSpinner.setAdapter(adapter);

        trajectoryTypeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (map != null) updateTrajectoryMap(map);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.statsMapFragment);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        return rootView;
    }

    private void calculateAndDisplayStats(List<LatLng> trajectory) {
        if (trajectory == null || trajectory.size() < 2) return;

        float totalDistance = 0f;
        for (int i = 1; i < trajectory.size(); i++) {
            LatLng prev = trajectory.get(i - 1);
            LatLng curr = trajectory.get(i);
            if (prev != null && curr != null) {
                totalDistance += UtilFunctions.distanceBetweenPoints(prev, curr);
            }
        }

        // Using replayData timestamps as fallback
        List<TrajParser.ReplayPoint> data = TrajParser.replayData;
        long startTime = data.get(0).timestamp;
        long endTime = data.get(data.size() - 1).timestamp;
        float durationSec = (endTime - startTime) / 1000f;

        float durationHours = durationSec / 3600f;
        float distanceKm = totalDistance / 1000f;
        float avgSpeed = distanceKm / durationHours;
        float paceSecPerKm = durationSec / distanceKm;
        int paceMin = (int) (paceSecPerKm / 60);
        int paceSec = (int) (paceSecPerKm % 60);

        DecimalFormat df = new DecimalFormat("#.##");

        distanceTextView.setText(df.format(distanceKm) + " km");
        timeTextView.setText(String.format("%02d:%02d", (int) (durationSec / 60), (int) (durationSec % 60)));
        avgSpeedTextView.setText(df.format(avgSpeed) + " km/h");
        paceTextView.setText(String.format("%d:%02d", paceMin, paceSec));
        altitudeTextView.setText("N/A");
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        this.map = googleMap;

        map.getUiSettings().setZoomControlsEnabled(true);
        map.getUiSettings().setMapToolbarEnabled(false);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.getUiSettings().setZoomGesturesEnabled(true);
        map.setBuildingsEnabled(true);
        map.setIndoorEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_NORMAL);

        updateTrajectoryMap(map);
    }

    private void updateTrajectoryMap(GoogleMap googleMap) {
        googleMap.clear();

        String mode = trajectoryTypeSpinner.getSelectedItem().toString();

        List<LatLng> trajectory;
        int color;

        switch (mode) {
            case "GNSS":
                trajectory = ReplayFragment.GNSS_data;
                color = Color.BLUE;
                break;
            case "WiFi":
                trajectory = ReplayFragment.WIFI_data;
                color = Color.GREEN;
                break;
            case "EKF":
                trajectory = ReplayFragment.EKF_data;
                color = Color.CYAN;
                break;
            default:
                trajectory = ReplayFragment.PDR_data;
                color = Color.RED;
        }

        if (trajectory == null || trajectory.size() < 2) {
            Toast.makeText(requireContext(), "No data for " + mode + " trajectory", Toast.LENGTH_SHORT).show();
            return;
        }

        PolylineOptions polyline = new PolylineOptions().color(color).width(6f);
        LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder();

        for (LatLng point : trajectory) {
            if (point != null) {
                polyline.add(point);
                boundsBuilder.include(point);
            }
        }

        googleMap.addPolyline(polyline);
        googleMap.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 150));

        // Update stats when trajectory changes
        calculateAndDisplayStats(trajectory);
    }
}
