package com.openpositioning.PositionMe.presentation.fragment;

import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.material.button.MaterialButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.sensors.Wifi;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.util.ArrayList;
import java.util.List;

/**
 * Home screen with live fused-map preview.
 */
public class HomeFragment extends Fragment {

    private static final long LIVE_PREVIEW_INTERVAL_MS = 1_000L;
    private static final double FLOORPLAN_REFRESH_DISTANCE_METERS = 15.0;

    private MaterialButton goToInfo;
    private Button start;
    private Button measurements;
    private Button files;
    private Button indoorButton;
    private TextView gnssStatusTextView;

    private SensorFusion sensorFusion;
    private TrajectoryMapFragment trajectoryMapFragment;
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private LatLng lastFloorplanRequestPosition;
    private long lastRenderedPositionVersion = -1L;
    private long lastRenderedFusedTrackVersion = -1L;
    private long lastRenderedObservationVersion = -1L;

    private final Runnable livePreviewTask = new Runnable() {
        @Override
        public void run() {
            refreshLivePreview();
            previewHandler.postDelayed(this, LIVE_PREVIEW_INTERVAL_MS);
        }
    };

    public HomeFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        ((AppCompatActivity) requireActivity()).getSupportActionBar().show();
        View rootView = inflater.inflate(R.layout.fragment_home, container, false);
        requireActivity().setTitle("Home");
        sensorFusion = SensorFusion.getInstance();
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        goToInfo = view.findViewById(R.id.sensorInfoButton);
        start = view.findViewById(R.id.startStopButton);
        measurements = view.findViewById(R.id.measurementButton);
        files = view.findViewById(R.id.filesButton);
        indoorButton = view.findViewById(R.id.indoorButton);
        gnssStatusTextView = view.findViewById(R.id.gnssStatusTextView);

        goToInfo.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToInfoFragment();
            Navigation.findNavController(v).navigate(action);
        });

        start.setEnabled(!PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean("permanentDeny", false));
        start.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), RecordingActivity.class);
            startActivity(intent);
            ((AppCompatActivity) requireActivity()).getSupportActionBar().hide();
        });

        measurements.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToMeasurementsFragment();
            Navigation.findNavController(v).navigate(action);
        });

        files.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToFilesFragment();
            Navigation.findNavController(v).navigate(action);
        });

        indoorButton.setOnClickListener(v ->
                Toast.makeText(requireContext(), R.string.indoor_mode_hint, Toast.LENGTH_SHORT).show());

        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.mapFragmentContainer);
        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.mapFragmentContainer, trajectoryMapFragment)
                    .commit();
        }
        trajectoryMapFragment.setPreviewFloorOnlyMode(true);
        lastRenderedPositionVersion = -1L;
        lastRenderedFusedTrackVersion = -1L;
        lastRenderedObservationVersion = -1L;
    }

    @Override
    public void onResume() {
        super.onResume();
        previewHandler.post(livePreviewTask);
    }

    @Override
    public void onPause() {
        previewHandler.removeCallbacks(livePreviewTask);
        super.onPause();
    }

    private void refreshLivePreview() {
        if (!isAdded() || trajectoryMapFragment == null) {
            return;
        }

        if (!isGnssEnabled()) {
            gnssStatusTextView.setText(R.string.gnss_disabled);
            gnssStatusTextView.setVisibility(View.VISIBLE);
            return;
        }

        LatLng fusedPosition = sensorFusion.getCurrentFusedPosition();
        if (fusedPosition != null) {
            gnssStatusTextView.setVisibility(View.GONE);
            requestNearbyFloorplanIfNeeded(fusedPosition);
            trajectoryMapFragment.updateUserLocation(
                    fusedPosition,
                    (float) Math.toDegrees(sensorFusion.passOrientation())
            );
            renderMapOverlaysIfChanged();
            return;
        }

        float[] gnss = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        if (gnss != null && (gnss[0] != 0f || gnss[1] != 0f)) {
            LatLng gnssPosition = new LatLng(gnss[0], gnss[1]);
            trajectoryMapFragment.updateUserLocation(
                    gnssPosition,
                    (float) Math.toDegrees(sensorFusion.passOrientation())
            );
            requestNearbyFloorplanIfNeeded(gnssPosition);
            gnssStatusTextView.setText(R.string.auto_init_waiting);
            gnssStatusTextView.setVisibility(View.VISIBLE);
        } else {
            gnssStatusTextView.setText(R.string.auto_init_waiting);
            gnssStatusTextView.setVisibility(View.VISIBLE);
        }
    }

    private void renderMapOverlaysIfChanged() {
        if (trajectoryMapFragment == null || !trajectoryMapFragment.isRenderSurfaceReady()) {
            return;
        }

        long positionVersion = sensorFusion.getCurrentFusedPositionVersion();
        long fusedTrackVersion = sensorFusion.getFusedTrackVersion();
        if (fusedTrackVersion != lastRenderedFusedTrackVersion
                || positionVersion != lastRenderedPositionVersion) {
            trajectoryMapFragment.renderFusedHistory(sensorFusion.getFusedTrack());
            lastRenderedPositionVersion = positionVersion;
            lastRenderedFusedTrackVersion = fusedTrackVersion;
        }

        long observationVersion = sensorFusion.getObservationTrailsVersion();
        if (observationVersion != lastRenderedObservationVersion) {
            trajectoryMapFragment.renderObservationTails(
                    sensorFusion.getRecentGnssTrail(),
                    sensorFusion.getRecentWifiTrail(),
                    sensorFusion.getRecentPdrTrail()
            );
            lastRenderedObservationVersion = observationVersion;
        }
    }

    private void requestNearbyFloorplanIfNeeded(@NonNull LatLng position) {
        boolean shouldRefresh = lastFloorplanRequestPosition == null
                || sensorFusion.getFloorplanBuildings().isEmpty()
                || UtilFunctions.distanceBetweenPoints(lastFloorplanRequestPosition, position)
                >= FLOORPLAN_REFRESH_DISTANCE_METERS;
        if (!shouldRefresh) {
            return;
        }

        lastFloorplanRequestPosition = position;
        List<String> observedMacs = new ArrayList<>();
        List<Wifi> wifiList = sensorFusion.getWifiList();
        if (wifiList != null) {
            for (Wifi wifi : wifiList) {
                String mac = wifi.getBssidString();
                if (mac != null && !mac.isEmpty()) {
                    observedMacs.add(mac);
                }
            }
        }

        new FloorplanApiClient().requestFloorplan(
                position.latitude,
                position.longitude,
                observedMacs,
                new FloorplanApiClient.FloorplanCallback() {
                    @Override
                    public void onSuccess(List<FloorplanApiClient.BuildingInfo> buildings) {
                        if (!isAdded()) {
                            return;
                        }
                        sensorFusion.setFloorplanBuildings(buildings);
                    }

                    @Override
                    public void onFailure(String error) {
                        // Keep the last successful cache; no UI interruption needed.
                    }
                }
        );
    }

    private boolean isGnssEnabled() {
        LocationManager locationManager =
                (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        return gpsEnabled || networkEnabled;
    }
}
