package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.data.local.TrajParser;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReplayFragment extends Fragment {

    private static final String TAG = "ReplayFragment";

    private float initialLat = 0f;
    private float initialLon = 0f;
    private String filePath = "";

    private TrajectoryMapFragment trajectoryMapFragment;
    private ImageButton playButton, pauseButton, replayButton, goToEndButton;
    private Button speedHalfButton, speedDoubleButton;
    private SeekBar playbackSeekBar;
    private Spinner dataSourceSpinner;

    private String selectedMode = "PDR";
    private final Handler playbackHandler = new Handler();
    private long playbackInterval = 500;
    private List<TrajParser.ReplayPoint> replayData = new ArrayList<>();
    private int currentIndex = 0;
    private boolean isPlaying = false;

    private LatLng prevWiFiLocation;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            filePath = getArguments().getString(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, "");
            initialLat = getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LAT, 0f);
            initialLon = getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LON, 0f);
        }

        File trajectoryFile = new File(filePath);
        if (!trajectoryFile.exists() || !trajectoryFile.canRead()) return;

        replayData = TrajParser.parseTrajectoryData(filePath, requireContext(), initialLat, initialLon);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_replay, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.replayMapFragmentContainer);
        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.replayMapFragmentContainer, trajectoryMapFragment)
                    .commit();
        }

        playButton = view.findViewById(R.id.playButton);
        pauseButton = view.findViewById(R.id.pauseButton);
        replayButton = view.findViewById(R.id.replayButton);
        goToEndButton = view.findViewById(R.id.goToEndButton);
        speedHalfButton = view.findViewById(R.id.speedHalfButton);
        speedDoubleButton = view.findViewById(R.id.speedDoubleButton);
        playbackSeekBar = view.findViewById(R.id.playbackSeekBar);
        dataSourceSpinner = view.findViewById(R.id.dataSourceSpinner);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"PDR", "GNSS", "WiFi"});
        dataSourceSpinner.setAdapter(adapter);

        dataSourceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selectedMode = parent.getItemAtPosition(position).toString();
                trajectoryMapFragment.setPolylineColor(getColorForMode(selectedMode));
                trajectoryMapFragment.clearMapAndReset();
                currentIndex = 0;
                playbackSeekBar.setProgress(0);
                updateSeekBarMax();
                drawReplayPointWithMode(currentIndex);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        updateSeekBarMax();

        playButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            if (!isPlaying) {
                isPlaying = true;
                playButton.setEnabled(false);
                pauseButton.setEnabled(true);
                playbackHandler.postDelayed(playbackRunnable, playbackInterval);
            }
        });

        pauseButton.setOnClickListener(v -> stopPlaying());

        speedHalfButton.setOnClickListener(v -> {
            playbackInterval = 1000;
            restartPlaybackIfPlaying();
        });

        speedDoubleButton.setOnClickListener(v -> {
            playbackInterval = 250;
            restartPlaybackIfPlaying();
        });

        replayButton.setOnClickListener(v -> {
            currentIndex = 0;
            playbackSeekBar.setProgress(0);
            trajectoryMapFragment.clearMapAndReset();
            drawReplayPointWithMode(currentIndex);
        });

        goToEndButton.setOnClickListener(v -> {
            currentIndex = replayData.size() - 1;
            playbackSeekBar.setProgress(currentIndex);
            trajectoryMapFragment.clearMapAndReset();
            drawReplayPointWithMode(currentIndex);
            stopPlaying();
        });

        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentIndex = progress;
                    trajectoryMapFragment.clearMapAndReset();
                    drawReplayPointWithMode(currentIndex);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { stopPlaying(); }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        if (!replayData.isEmpty()) {
            drawReplayPointWithMode(0);
        }
    }

    private void restartPlaybackIfPlaying() {
        if (isPlaying) {
            playbackHandler.removeCallbacks(playbackRunnable);
            playbackHandler.postDelayed(playbackRunnable, playbackInterval);
        }
    }

    private void updateSeekBarMax() {
        int size = getVisibleDataSize(selectedMode);
        if (size > 0) {
            playbackSeekBar.setMax(size - 1);
        }
    }

    private int getVisibleDataSize(String mode) {
        switch (mode) {
            case "GNSS":
                return (int) replayData.stream().filter(p -> p.gnssLocation != null).count();
            case "WiFi":
                return (int) replayData.stream().filter(p -> p.cachedWiFiLocation != null || (p.wifiSamples != null && !p.wifiSamples.isEmpty())).count();
            default:
                return replayData.size();
        }
    }

    private final Runnable playbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying || currentIndex >= replayData.size()) {
                stopPlaying();
                return;
            }
            trajectoryMapFragment.clearMapAndReset();
            drawReplayPointWithMode(currentIndex);
            currentIndex++;
            playbackSeekBar.setProgress(currentIndex);
            playbackHandler.postDelayed(this, playbackInterval);
        }
    };

    private void drawReplayPointWithMode(int index) {
        if (index < 0 || index >= replayData.size()) return;

        TrajParser.ReplayPoint p = replayData.get(index);
        switch (selectedMode) {
            case "GNSS":
                if (p.gnssLocation != null) {
                    trajectoryMapFragment.updateUserLocation(p.gnssLocation, p.orientation);
                } else {
                    trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
                }
                break;

            case "WiFi":
                if (p.cachedWiFiLocation != null) {
                    trajectoryMapFragment.updateUserLocation(p.cachedWiFiLocation, p.orientation);
                } else if (p.wifiSamples != null && !p.wifiSamples.isEmpty()) {
                    JSONObject wifiFingerprint = new JSONObject();
                    JSONObject wifiAccessPoints = new JSONObject();
                    for (Traj.WiFi_Sample sample : p.wifiSamples) {
                        for (Traj.Mac_Scan macScan : sample.getMacScansList()) {
                            try {
                                wifiAccessPoints.put(String.valueOf(macScan.getMac()), macScan.getRssi());
                            } catch (JSONException e) {
                                Log.e(TAG, "WiFi JSON error: " + e.getMessage());
                            }
                        }
                    }
                    try {
                        wifiFingerprint.put("wf", wifiAccessPoints);
                    } catch (JSONException e) {
                        Log.e(TAG, "WiFi fingerprint JSON failed: " + e.getMessage());
                    }

                    new WiFiPositioning(getContext()).request(wifiFingerprint, new WiFiPositioning.VolleyCallback() {
                        @Override
                        public void onSuccess(LatLng location, int floor) {
                            Log.d(TAG, "WiFi Positioning Successful");
                            p.cachedWiFiLocation = location;
                            trajectoryMapFragment.updateUserLocation(location, p.orientation);
                            prevWiFiLocation = location;
                        }

                        @Override
                        public void onError(String message) {
                            Log.w(TAG, "WiFi Positioning failed: " + message);
                            if (prevWiFiLocation != null) {
                                trajectoryMapFragment.updateUserLocation(prevWiFiLocation, p.orientation);
                            } else {
                                trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
                            }
                        }
                    });
                } else {
                    if (prevWiFiLocation != null) {
                        trajectoryMapFragment.updateUserLocation(prevWiFiLocation, p.orientation);
                    } else {
                        trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
                    }
                }
                break;

            default:
                trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
        }

        if (selectedMode.equals("GNSS") && p.gnssLocation != null) {
            trajectoryMapFragment.updateGNSS(p.gnssLocation);
        }
    }

    private int getColorForMode(String mode) {
        switch (mode) {
            case "GNSS": return android.graphics.Color.BLUE;
            case "WiFi": return android.graphics.Color.GREEN;
            default: return android.graphics.Color.RED;
        }
    }

    private void stopPlaying() {
        isPlaying = false;
        playbackHandler.removeCallbacks(playbackRunnable);
        playButton.setEnabled(true);
        pauseButton.setEnabled(false);
    }

    @Override
    public void onPause() {
        super.onPause();
        stopPlaying();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopPlaying();
    }
}
