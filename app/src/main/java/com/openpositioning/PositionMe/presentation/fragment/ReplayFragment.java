package com.openpositioning.PositionMe.presentation.fragment;

import android.content.Context;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.model.LatLng;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Revised ReplayFragment that parses a JSON trajectory file, computes orientation and speed,
 * and plots the data on the map.
 */
public class ReplayFragment extends Fragment {

    private static final String TAG = "ReplayFragment";

    // UI Controls
    private TrajectoryMapFragment trajectoryMapFragment;
    private Button playPauseButton, restartButton, exitButton, goEndButton;
    private SeekBar playbackSeekBar;

    // Playback-related
    private final Handler playbackHandler = new Handler();
    private final long PLAYBACK_INTERVAL_MS = 500; // Frame interval in milliseconds
    private List<ReplayPoint> replayData = new ArrayList<>();
    private int currentIndex = 0;
    private boolean isPlaying = false;

    // Path to the trajectory JSON file
    private String filePath;

    // Data class for each replay frame
    public static class ReplayPoint {
        public LatLng pdrLocation;  // from pdrData
        public LatLng gnssLocation; // optional (e.g., from GNSS data)
        public float orientation;   // in degrees
        public float speed;         // computed speed (m/s)
        public long timestamp;      // using the relativeTimestamp from the source

        public ReplayPoint(LatLng pdrLocation, LatLng gnssLocation, float orientation, float speed, long timestamp) {
            this.pdrLocation = pdrLocation;
            this.gnssLocation = gnssLocation;
            this.orientation = orientation;
            this.speed = speed;
            this.timestamp = timestamp;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Retrieve file path from arguments
        if (getArguments() != null) {
            filePath = getArguments().getString(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, "placeholder-path");
        } else {
            filePath = "placeholder-path";
        }

        Log.i(TAG, "Loading trajectory from JSON file: " + filePath);

        // Parse the JSON file and prepare replayData
        replayData = parseTrajectoryData(filePath);
        // Sort the replay points by their timestamp
        Collections.sort(replayData, Comparator.comparingLong(o -> o.timestamp));
    }

    /**
     * Parse the trajectory data from the JSON file and construct ReplayPoint objects.
     */
    private List<ReplayPoint> parseTrajectoryData(String filePath) {
        List<ReplayPoint> result = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            // For older versions of Gson, use new JsonParser().parse(reader)
            JsonObject root = new JsonParser().parse(br).getAsJsonObject();

            // Optional: get startTimestamp if needed
            long startTimestamp = 0;
            if (root.has("startTimestamp")) {
                startTimestamp = Long.parseLong(root.get("startTimestamp").getAsString());
            }

            // Parse IMU data
            List<ImuRecord> imuList = parseImuData(root.getAsJsonArray("imuData"));
            // Parse PDR data
            List<PdrRecord> pdrList = parsePdrData(root.getAsJsonArray("pdrData"));

            // Build ReplayPoints from PDR data
            for (int i = 0; i < pdrList.size(); i++) {
                PdrRecord pdr = pdrList.get(i);

                // 1) Find the closest IMU record by timestamp (using relativeTimestamp)
                float orientationDeg = 0f;
                ImuRecord closestImu = findClosestImuRecord(imuList, pdr.relativeTimestamp);
                if (closestImu != null) {
                    orientationDeg = computeOrientationFromRotationVector(
                            closestImu.rotationVectorX,
                            closestImu.rotationVectorY,
                            closestImu.rotationVectorZ,
                            closestImu.rotationVectorW,
                            requireContext()
                    );
                }

                // 2) Compute speed from consecutive PDR points (naive approach)
                float speed = 0f;
                if (i > 0) {
                    PdrRecord prev = pdrList.get(i - 1);
                    double dt = (pdr.relativeTimestamp - prev.relativeTimestamp) / 1000.0; // convert ms to seconds
                    double dx = pdr.x - prev.x;
                    double dy = pdr.y - prev.y;
                    double distance = Math.sqrt(dx * dx + dy * dy);
                    if (dt > 0) {
                        speed = (float) (distance / dt);
                    }
                }

                // 3) Convert PDR (x, y) to latitude/longitude.
                //    This is a naive conversion. In a real application, use a proper coordinate transform.
                double originLat = 37.4219999;
                double originLng = -122.0840575;
                double lat = originLat + pdr.y * 1E-5;
                double lng = originLng + pdr.x * 1E-5;
                LatLng pdrLocation = new LatLng(lat, lng);

                // 4) If available, assign GNSS location; otherwise, set to null.
                LatLng gnssLocation = null;

                // 5) Create a ReplayPoint. Note: using pdr.relativeTimestamp for the timestamp.
                ReplayPoint rp = new ReplayPoint(
                        pdrLocation,
                        gnssLocation,
                        orientationDeg,
                        speed,
                        pdr.relativeTimestamp
                );
                result.add(rp);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * Parse the "imuData" array from JSON.
     */
    private List<ImuRecord> parseImuData(JsonArray imuArray) {
        List<ImuRecord> imuList = new ArrayList<>();
        if (imuArray == null) return imuList;
        Gson gson = new Gson();
        for (int i = 0; i < imuArray.size(); i++) {
            JsonObject obj = imuArray.get(i).getAsJsonObject();
            ImuRecord record = gson.fromJson(obj, ImuRecord.class);
            imuList.add(record);
        }
        return imuList;
    }

    /**
     * Parse the "pdrData" array from JSON.
     */
    private List<PdrRecord> parsePdrData(JsonArray pdrArray) {
        List<PdrRecord> pdrList = new ArrayList<>();
        if (pdrArray == null) return pdrList;
        Gson gson = new Gson();
        for (int i = 0; i < pdrArray.size(); i++) {
            JsonObject obj = pdrArray.get(i).getAsJsonObject();
            PdrRecord record = gson.fromJson(obj, PdrRecord.class);
            pdrList.add(record);
        }
        return pdrList;
    }

    /**
     * Finds the IMU record with the closest timestamp (relativeTimestamp) to targetTimestamp.
     */
    private ImuRecord findClosestImuRecord(List<ImuRecord> imuList, long targetTimestamp) {
        ImuRecord closest = null;
        long minDiff = Long.MAX_VALUE;
        for (ImuRecord imu : imuList) {
            long diff = Math.abs(imu.relativeTimestamp - targetTimestamp);
            if (diff < minDiff) {
                minDiff = diff;
                closest = imu;
            }
        }
        return closest;
    }

    /**
     * Converts a rotation vector (from IMU) into an azimuth angle (in degrees) using SensorManager.
     */
    private float computeOrientationFromRotationVector(float rx, float ry, float rz, float rw, Context context) {
        float[] rotationVector = new float[]{rx, ry, rz, rw};
        float[] rotationMatrix = new float[9];
        float[] orientationAngles = new float[3];

        SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);

        float azimuthDeg = (float) Math.toDegrees(orientationAngles[0]);
        if (azimuthDeg < 0) {
            azimuthDeg += 360.0f;
        }
        return azimuthDeg;
    }

    // Data classes for JSON parsing

    private static class ImuRecord {
        public long relativeTimestamp;  // The timestamp from JSON (rename if needed)
        public float accX, accY, accZ;
        public float gyrX, gyrY, gyrZ;
        public float rotationVectorX, rotationVectorY, rotationVectorZ, rotationVectorW;
    }

    private static class PdrRecord {
        public long relativeTimestamp;  // The timestamp from JSON (rename if needed)
        public float x, y;
    }

    // ----------------- Lifecycle and UI Methods -----------------

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_replay, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize the map fragment
        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.replayMapFragmentContainer);
        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.replayMapFragmentContainer, trajectoryMapFragment)
                    .commit();
        }

        // Initialize UI controls
        playPauseButton = view.findViewById(R.id.playPauseButton);
        restartButton   = view.findViewById(R.id.restartButton);
        exitButton      = view.findViewById(R.id.exitButton);
        goEndButton     = view.findViewById(R.id.goEndButton);
        playbackSeekBar = view.findViewById(R.id.playbackSeekBar);

        // Set the SeekBar maximum value
        if (!replayData.isEmpty()) {
            playbackSeekBar.setMax(replayData.size() - 1);
        }

        // Button listeners
        playPauseButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            if (isPlaying) {
                isPlaying = false;
                playPauseButton.setText("Play");
            } else {
                isPlaying = true;
                playPauseButton.setText("Pause");
                if (currentIndex >= replayData.size()) {
                    currentIndex = 0;
                }
                playbackHandler.post(playbackRunnable);
            }
        });

        restartButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            currentIndex = 0;
            playbackSeekBar.setProgress(0);
            updateMapForIndex(0);
        });

        goEndButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            currentIndex = replayData.size() - 1;
            playbackSeekBar.setProgress(currentIndex);
            updateMapForIndex(currentIndex);
            isPlaying = false;
            playPauseButton.setText("Play");
        });

        exitButton.setOnClickListener(v -> {
            if (getActivity() instanceof ReplayActivity) {
                ((ReplayActivity) getActivity()).finishFlow();
            } else {
                requireActivity().onBackPressed();
            }
        });

        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentIndex = progress;
                    updateMapForIndex(currentIndex);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Display the first frame if available
        if (!replayData.isEmpty()) {
            updateMapForIndex(0);
        }
    }

    /**
     * Runnable that updates the map at each playback interval.
     */
    private final Runnable playbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying || replayData.isEmpty()) return;

            updateMapForIndex(currentIndex);
            currentIndex++;
            playbackSeekBar.setProgress(currentIndex);

            if (currentIndex < replayData.size()) {
                playbackHandler.postDelayed(this, PLAYBACK_INTERVAL_MS);
            } else {
                isPlaying = false;
                playPauseButton.setText("Play");
            }
        }
    };

    /**
     * Updates the map with the ReplayPoint data at the given index.
     */
    private void updateMapForIndex(int index) {
        if (index < 0 || index >= replayData.size()) return;

        ReplayPoint point = replayData.get(index);
        if (trajectoryMapFragment != null) {
            trajectoryMapFragment.updateUserLocation(point.pdrLocation, point.orientation);
            if (point.gnssLocation != null) {
                trajectoryMapFragment.updateGNSS(point.gnssLocation);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        isPlaying = false;
        playbackHandler.removeCallbacks(playbackRunnable);
        if (playPauseButton != null) {
            playPauseButton.setText("Play");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        playbackHandler.removeCallbacks(playbackRunnable);
    }
}
