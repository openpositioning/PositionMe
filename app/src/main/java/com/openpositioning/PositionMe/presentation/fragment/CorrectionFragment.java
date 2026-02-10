package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
// Fix: Removed non-existent TrajMapPoints reference
// import com.openpositioning.PositionMe.utils.TrajMapPoints;

import java.util.List;

public class CorrectionFragment extends Fragment {

    // UI Components
    private Button uploadButton;
    private SeekBar rotationBar;
    private TextView correctionTitle;

    // Map & Logic
    private GoogleMap gMap;
    private SensorFusion sensorFusion = SensorFusion.getInstance();
    private IndoorMapManager indoorMapManager;

    // Correction State
    private float currentRotation = 0f;

    public CorrectionFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null && activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().hide();
        }
        return inflater.inflate(R.layout.fragment_correction, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Bind UI (matching XML IDs)
        uploadButton = view.findViewById(R.id.uploadButton);
        rotationBar = view.findViewById(R.id.rotationSeekBar);
        correctionTitle = view.findViewById(R.id.correctionTitle);

        // Configure SeekBar
        if (rotationBar != null) {
            rotationBar.setMax(360);
            rotationBar.setProgress(180);
            rotationBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    currentRotation = (progress - 180);
                    // Actual rotation logic requires data support, omitted here
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // Initialize Map
        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager()
                .findFragmentById(R.id.map); // Note: Map ID must be 'map' in XML

        if (mapFragment != null) {
            mapFragment.getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(GoogleMap googleMap) {
                    gMap = googleMap;
                    gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                    gMap.getUiSettings().setCompassEnabled(true);
                    gMap.getUiSettings().setZoomControlsEnabled(true);

                    indoorMapManager = new IndoorMapManager(gMap, requireContext());
                    drawTrajectory();
                }
            });
        }

        // Upload Button Listener
        if (uploadButton != null) {
            uploadButton.setOnClickListener(v -> {
                sensorFusion.sendTrajectoryToCloud();
                Toast.makeText(getContext(), "Trajectory Uploaded!", Toast.LENGTH_SHORT).show();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (getActivity() instanceof RecordingActivity) {
                        ((RecordingActivity) getActivity()).finishFlow();
                    }
                }, 1000);
            });
        }
    }

    private void drawTrajectory() {
        if (gMap == null) return;

        float[] startGen = sensorFusion.getGNSSLatitude(true);
        LatLng startPos = new LatLng(startGen[0], startGen[1]);

        gMap.addMarker(new MarkerOptions().position(startPos).title("Start"));
        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startPos, 19f));

        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(startPos);
        }
    }
}