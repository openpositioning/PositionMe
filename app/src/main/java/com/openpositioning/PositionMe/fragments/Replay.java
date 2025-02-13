package com.openpositioning.PositionMe.fragments;

import static android.content.ContentValues.TAG;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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
import com.google.protobuf.InvalidProtocolBufferException;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.IndoorMapManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The Replay activity is used to play back recorded trajectory data. It provides a user interface
 * that includes a map displaying the recorded PDR (Pedestrian Dead Reckoning) path along with a
 * marker that indicates the current PDR location and its movement direction. In addition, a GNSS
 * marker is updated based on the recorded GNSS data. Users can control playback via play/pause,
 * fast forward, and fast rewind buttons, as well as by dragging the SeekBar, which represents the
 * normalized playback time. The activity also supports overlaying indoor maps to enhance the user
 * experience in indoor environments.
 *
 * @see com.openpositioning.PositionMe.ServerCommunications the previous fragment edited in order to
 * view the file from server, the file can be saved as proto files instead of json file.
 * @see com.openpositioning.PositionMe.sensors.SensorFusion Debug and modify to ensure that GNSS
 * and other initial data are present as the first entry in the trajectory file to avoid replaying
 * at position (0, 0).
 *
 * @author Xiaofei Huang
 * @author Asher Deng
 * @author Hao Cai
 * @author Zonghan Zhao
 */

public class Replay extends AppCompatActivity implements OnMapReadyCallback {

    private GoogleMap mMap;
    // Use TimedLatLng to store the PDR samples' coordinates and their normalized timestamps (in milliseconds)
    private List<TimedLatLng> trackPoints = new ArrayList<>();
    /**
     * Flag indicating whether playback is currently in progress.
     * This is used to control the start, pause, and resume of trajectory playback.
     */
    private Polyline polyline;
    /**
     * Polyline object that represents the drawn trajectory on the map.
     * This polyline is updated with the recorded PDR positions to visualize the user's path.
     */
    private Marker currentMarker;  // Marker representing the current PDR position (shows direction)
    private Marker gnssMarker;     // Marker used to display the GNSS position

    // Playback control variables
    private boolean isPlaying = false;
    /**
     * Handler for scheduling and managing playback update tasks.
     * It posts runnable tasks to update the UI based on the current playback time.
     */
    private Handler handler = new Handler();
    /**
     * SeekBar UI component used to display and control the current playback progress.
     * Users can drag the SeekBar to jump to a specific point in the recorded trajectory.
     */
    private SeekBar seekBar;
    private ImageButton playButton, fastRewind, fastForward, gotoStartButton, gotoEndButon;
    private TextView progressText, totaltimetext;
    private Switch switch1; // Controls whether to display the full trajectory
    private String filePath;

    // Playback time parameters (in milliseconds)
    private long totalDuration = 0; // Total duration of the trajectory, determined by the maximum normalized timestamp among all PDR samples
    // currentTime represents the current playback timestamp (normalized)
    private long currentTime = 0;

    // Playback speed factor (1.0 means playback at real-time; can be adjusted for faster playback)
    private double playbackSpeedFactor = 1.0;

    // Manager for displaying indoor maps
    private IndoorMapManager indoorMapManager;

    // Parsed Trajectory data, used for obtaining GNSS data
    private Traj.Trajectory trajectoryData;

    // Switch for auto-floor switching and floor change buttons
    private Switch autoFloor;
    // Button for floor +1
    public FloatingActionButton floorUpButton;
    // Button for floor -1
    public FloatingActionButton floorDownButton;
    private Spinner mapTypeSpinner;

<<<<<<< Updated upstream
    // 当前播放到的 PDR 样本索引（用于遍历 trackPoints）
=======
    // Index of the current PDR sample (used for iterating over trackPoints)
>>>>>>> Stashed changes
    private int currentIndex = 0;

    // Records the system time when playback started (in milliseconds)
    private long playbackStartTime = 0;

    /**
     * Inner class used to store the calculated geographic position from PDR along with its corresponding normalized timestamp (in milliseconds).
     */
    public static class TimedLatLng {
        public LatLng point;
        public long relativeTimestamp; // Normalized timestamp in milliseconds

        public TimedLatLng(LatLng point, long relativeTimestamp) {
            this.point = point;
            this.relativeTimestamp = relativeTimestamp;
        }
    }

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_replay);

        // Retrieve the file path from the Intent
        filePath = getIntent().getStringExtra("filePath");

        // Initialize UI controls
        seekBar = findViewById(R.id.seekBar);
        playButton = findViewById(R.id.playPauseButton);
        fastRewind = findViewById(R.id.fastRewindButton);
        fastForward = findViewById(R.id.fastForwardButton);
        gotoStartButton = findViewById(R.id.goToStartButton);
        gotoEndButon = findViewById(R.id.goToEndButton);
        progressText = findViewById(R.id.currentTime);
        totaltimetext = findViewById(R.id.totalTime);
        switch1 = findViewById(R.id.switch1);  // Ensure this Switch exists in the layout
        autoFloor = findViewById(R.id.autoFloor2);
        autoFloor.setChecked(false);
        floorUpButton = findViewById(R.id.floorUpButton2);
        floorDownButton = findViewById(R.id.floorDownButton2);

        // Set up click listeners for the floor change buttons
        floorUpButton.setOnClickListener(view -> {
            autoFloor.setChecked(false);
            indoorMapManager.increaseFloor();
        });
        floorDownButton.setOnClickListener(view -> {
            autoFloor.setChecked(false);
            indoorMapManager.decreaseFloor();
        });

        mapTypeSpinner = findViewById(R.id.mapTypeSpinner);
        setupMapTypeSpinner();

        // When the switch is toggled, update the trajectory display:
        // Show the full trajectory or only the portion up to the current playback time.
        switch1.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (polyline == null) return;
            if (isChecked) {
                List<LatLng> allPoints = new ArrayList<>();
                for (TimedLatLng tl : trackPoints) {
                    allPoints.add(tl.point);
                }
                polyline.setPoints(allPoints);
            } else {
                List<LatLng> partial = new ArrayList<>();
                for (TimedLatLng tl : trackPoints) {
                    if (tl.relativeTimestamp <= currentTime) {
                        partial.add(tl.point);
                    } else {
                        break;
                    }
                }
                if (partial.isEmpty() && !trackPoints.isEmpty()) {
                    partial.add(trackPoints.get(0).point);
                }
                polyline.setPoints(partial);
            }
        });

        // Initialize the map fragment
        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        // Parse the trajectory file (in Protobuf format)
        Traj.Trajectory trajectory = readTrajectoryFromFile(this, filePath);
        if (trajectory != null) {
            trajectoryData = trajectory;
            // Convert PDR data into a normalized TimedLatLng list
            trackPoints = convertTrajectoryToTimedLatLng(trajectory);
            if (!trackPoints.isEmpty()) {
                // Set the total duration as the normalized timestamp of the last PDR sample
                totalDuration = trackPoints.get(trackPoints.size() - 1).relativeTimestamp;
                // Set the maximum value of the SeekBar to totalDuration (assuming it fits in an int)
                seekBar.setMax((int) totalDuration);
            }
        } else {
            Log.e(TAG, "Trajectory file parsing failed!");
        }

        // Button click event handlers
        playButton.setOnClickListener(v -> {
            if (isPlaying) {
                pausePlayback();
            } else {
                // When resuming playback, do not reset currentTime and currentIndex; instead, continue from the current SeekBar position.
                playbackStartTime = System.currentTimeMillis() - (long)(currentTime / playbackSpeedFactor);
                startPlayback();
            }
        });
        fastRewind.setOnClickListener(v -> {
            // Fast rewind by 5 seconds and update playbackStartTime so that playback resumes from the new position.
            int jumpTime = 5000;
            currentTime = Math.max(currentTime - jumpTime, 0);
            for (int i = 0; i < trackPoints.size(); i++) {
                if (trackPoints.get(i).relativeTimestamp >= currentTime) {
                    currentIndex = i;
                    break;
                }
            }
            if (isPlaying) {
                playbackStartTime = System.currentTimeMillis() - (long)(currentTime / playbackSpeedFactor);
            }
            updateMapPosition();
            seekBar.setProgress((int) currentTime);
        });
        fastForward.setOnClickListener(v -> {
            // Fast forward by 5 seconds and update playbackStartTime.
            int jumpTime = 5000;
            currentTime = Math.min(currentTime + jumpTime, totalDuration);
            for (int i = 0; i < trackPoints.size(); i++) {
                if (trackPoints.get(i).relativeTimestamp >= currentTime) {
                    currentIndex = i;
                    break;
                }
            }
            if (isPlaying) {
                playbackStartTime = System.currentTimeMillis() - (long)(currentTime / playbackSpeedFactor);
            }
            updateMapPosition();
            seekBar.setProgress((int) currentTime);
        });
        gotoStartButton.setOnClickListener(v -> gotoStart());
        gotoEndButon.setOnClickListener(v -> gotoEnd());

        // SeekBar listener: pause automatic updates during dragging and update playbackStartTime after dragging ends
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentTime = progress; // 'progress' is an int; assign directly to long
                    // Update the current sample index: find the first sample with a normalized timestamp >= currentTime
                    for (int i = 0; i < trackPoints.size(); i++) {
                        if (trackPoints.get(i).relativeTimestamp >= currentTime) {
                            currentIndex = i;
                            break;
                        }
                    }
                    updateMapPosition();
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (isPlaying) {
                    handler.removeCallbacks(playbackRunnable);
                }
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (isPlaying) {
                    playbackStartTime = System.currentTimeMillis() - (long)(currentTime / playbackSpeedFactor);
                    handler.post(playbackRunnable);
                }
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        indoorMapManager = new IndoorMapManager(mMap);
        indoorMapManager.setIndicationOfIndoorMap();
        drawTrack();
        setFloorButtonVisibility(View.GONE);
    }

    // Parses the trajectory file (in Protobuf format)
    public static Traj.Trajectory readTrajectoryFromFile(Context context, String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            Log.e(TAG, "File not found");
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return Traj.Trajectory.parseFrom(data);
        } catch (InvalidProtocolBufferException e) {
            Log.e(TAG, "Protobuf parsing failed", e);
        } catch (IOException e) {
            Log.e(TAG, "File read failed", e);
        }
        return null;
    }

    /**
     * Converts the trajectory data into a list of TimedLatLng objects (based on PDR data)
     * and normalizes the timestamps.
     * <p>
     * Normalization method: use the relativeTimestamp of the first valid GNSS sample as the baseline.
     * This ensures that both the first GNSS data and the corresponding PDR data have a normalized time of 0,
     * thereby avoiding path jumps at the start.
     *
     * @param trajectory The Traj.Trajectory object containing the recorded data.
     * @return A list of TimedLatLng objects with normalized timestamps.
     */
    private List<TimedLatLng> convertTrajectoryToTimedLatLng(Traj.Trajectory trajectory) {
        List<TimedLatLng> points = new ArrayList<>();
        double R = 6378137; // Earth's radius in meters
        double lat0 = 0;
        double lon0 = 0;
        long baseline = 0;  // Normalization baseline

        // Find the first valid GNSS sample (non-zero latitude and longitude) and use its relativeTimestamp as the baseline
        for (Traj.GNSS_Sample sample : trajectory.getGnssDataList()) {
            if (sample.getLatitude() != 0 && sample.getLongitude() != 0) {
                lat0 = sample.getLatitude();
                lon0 = sample.getLongitude();
                baseline = sample.getRelativeTimestamp();
                break;
            }
        }
        if (lat0 == 0 && lon0 == 0) {
            Log.e(TAG, "No valid GNSS data found, using default start (0,0)!");
        }
        // Iterate over all PDR samples, normalize their timestamps, and compute coordinates
        for (Traj.Pdr_Sample pdrSample : trajectory.getPdrDataList()) {
            double trackX = pdrSample.getX();
            double trackY = pdrSample.getY();
            double dLat = trackY / R;
            double dLon = trackX / (R * Math.cos(Math.toRadians(lat0)));
            double lat = lat0 + Math.toDegrees(dLat);
            double lon = lon0 + Math.toDegrees(dLon);
            long timestamp = pdrSample.getRelativeTimestamp() - baseline;  // After normalization, the first sample is 0
            points.add(new TimedLatLng(new LatLng(lat, lon), timestamp));
        }
        return points;
    }

    /**
     * Selects the last GNSS sample with a relativeTimestamp less than or equal to the current playback time.
     *
     * @param currentTime    The current playback time (normalized).
     * @param trajectoryData The Traj.Trajectory object containing GNSS samples.
     * @return A LatLng object representing the current GNSS position.
     */
    private LatLng getCurrentGnssPosition(long currentTime, Traj.Trajectory trajectoryData) {
        Traj.GNSS_Sample bestSample = null;
        for (Traj.GNSS_Sample sample : trajectoryData.getGnssDataList()) {
            Log.d(TAG, "Checking GNSS sample: relativeTimestamp = " + sample.getRelativeTimestamp() + ", currentTime = " + currentTime);
            if (sample.getRelativeTimestamp() <= currentTime) {
                bestSample = sample;
            } else {
                break;
            }
        }
        if (bestSample == null && trajectoryData.getGnssDataCount() > 0) {
            bestSample = trajectoryData.getGnssData(0);
        }
        if (bestSample != null) {
            Log.d(TAG, "Selected GNSS sample: lat = " + bestSample.getLatitude() + ", lon = " + bestSample.getLongitude());
            return new LatLng(bestSample.getLatitude(), bestSample.getLongitude());
        }
        return null;
    }

    // Draws the trajectory and adds markers (including the GNSS marker)
    private void drawTrack() {
        if (mMap != null && !trackPoints.isEmpty()) {
            PolylineOptions polylineOptions = new PolylineOptions()
                    .width(10)
                    .color(0xFFFF00FF) // Trajectory color
                    .geodesic(true)
                    .zIndex(1000);
            if (switch1 != null && switch1.isChecked()) {
                List<LatLng> allPoints = new ArrayList<>();
                for (TimedLatLng tl : trackPoints) {
                    allPoints.add(tl.point);
                }
                polylineOptions.addAll(allPoints);
            } else {
                polylineOptions.add(trackPoints.get(0).point);
            }
            polyline = mMap.addPolyline(polylineOptions);

            // Add a marker at the starting point
            currentMarker = mMap.addMarker(new MarkerOptions()
                    .position(trackPoints.get(0).point)
<<<<<<< Updated upstream
                    .title("起点")
=======
                    .title(String.format("Elevation: %s", trajectoryData.getGnssData(0).getAltitude()))
>>>>>>> Stashed changes
                    .flat(true)
                    .icon(bitmapDescriptorFromVector(this, R.drawable.ic_baseline_navigation_24))
                    .zIndex(1100));

            // Add a GNSS marker (using the baseline_location_gnss icon) at the position of the first GNSS sample
            if (trajectoryData != null && trajectoryData.getGnssDataCount() > 0) {
                Traj.GNSS_Sample firstSample = trajectoryData.getGnssData(0);
                LatLng gnssLatLng = new LatLng(firstSample.getLatitude(), firstSample.getLongitude());
                gnssMarker = mMap.addMarker(new MarkerOptions()
                        .position(gnssLatLng)
                        .title("GNSS")
                        .icon(bitmapDescriptorFromVector(this, R.drawable.baseline_location_gnss))
                        .zIndex(1200));
            }
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(trackPoints.get(0).point, 20));
        }
    }

    /**
     * Converts a vector drawable to a BitmapDescriptor.
     *
     * @param context     The application context.
     * @param vectorResId The resource ID of the vector drawable.
     * @return A BitmapDescriptor representation of the vector drawable.
     */
    private BitmapDescriptor bitmapDescriptorFromVector(Context context, @DrawableRes int vectorResId) {
        Drawable vectorDrawable = ContextCompat.getDrawable(context, vectorResId);
        if (vectorDrawable == null) {
            Log.e(TAG, "Resource not found: " + vectorResId);
            return null;
        }
        vectorDrawable.setBounds(0, 0, vectorDrawable.getIntrinsicWidth(), vectorDrawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(vectorDrawable.getIntrinsicWidth(), vectorDrawable.getIntrinsicHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        vectorDrawable.draw(canvas);
        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    // Starts playback: uses a fixed timer task to update the playback progress and current playback time every second
    private void startPlayback() {
        if (trackPoints.isEmpty()) return;
        // Do not reset currentTime; use the current SeekBar progress as the starting point
        playbackStartTime = System.currentTimeMillis() - (long)(currentTime / playbackSpeedFactor);
        isPlaying = true;
        playButton.setImageResource(R.drawable.baseline_pause_24);
        handler.post(playbackRunnable);
    }

    // Runnable that updates the playback progress once per second based on the system time
    private final Runnable playbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying) return;
            long elapsed = System.currentTimeMillis() - playbackStartTime;
            currentTime = (long)(elapsed * playbackSpeedFactor);
            if (currentTime > totalDuration) {
                currentTime = totalDuration;
            }
            // Determine the current playback sample index based on currentTime
            for (int i = 0; i < trackPoints.size(); i++) {
                if (trackPoints.get(i).relativeTimestamp >= currentTime) {
                    currentIndex = i;
                    break;
                }
            }
            updateMapPosition();
            seekBar.setProgress((int) currentTime);
            if (currentTime < totalDuration) {
                handler.postDelayed(this, 1000);
            } else {
                isPlaying = false;
                playButton.setImageResource(R.drawable.baseline_play_arrow_24);
            }
        }
    };

    private void pausePlayback() {
        isPlaying = false;
        handler.removeCallbacksAndMessages(null);
        playButton.setImageResource(R.drawable.baseline_play_arrow_24);
    }

    // Fast forward 5 seconds: jumps ahead in the recording and updates playbackStartTime
    private void fastForward() {
        int jumpTime = 5000; // in milliseconds
        currentTime = Math.min(currentTime + jumpTime, totalDuration);
        for (int i = 0; i < trackPoints.size(); i++) {
            if (trackPoints.get(i).relativeTimestamp >= currentTime) {
                currentIndex = i;
                break;
            }
        }
        if (isPlaying) {
            playbackStartTime = System.currentTimeMillis() - (long)(currentTime / playbackSpeedFactor);
        }
        updateMapPosition();
        seekBar.setProgress((int) currentTime);
    }

    // Fast rewind 5 seconds: jumps back in the recording and updates playbackStartTime
    private void fastRewind() {
        int jumpTime = 5000; // in milliseconds
        currentTime = Math.max(currentTime - jumpTime, 0);
        for (int i = 0; i < trackPoints.size(); i++) {
            if (trackPoints.get(i).relativeTimestamp >= currentTime) {
                currentIndex = i;
                break;
            }
        }
        if (isPlaying) {
            playbackStartTime = System.currentTimeMillis() - (long)(currentTime / playbackSpeedFactor);
        }
        updateMapPosition();
        seekBar.setProgress((int) currentTime);
    }

    // Jumps to the start of the recording
    private void gotoStart() {
        currentTime = 0;
        currentIndex = 0;
        if (isPlaying) {
            playbackStartTime = System.currentTimeMillis();
        }
        updateMapPosition();
        seekBar.setProgress((int) currentTime);
    }

    // Jumps to the end of the recording
    private void gotoEnd() {
        currentTime = totalDuration;
        currentIndex = trackPoints.size() - 1;
        if (isPlaying) {
            playbackStartTime = System.currentTimeMillis() - (long)(currentTime / playbackSpeedFactor);
        }
        updateMapPosition();
        seekBar.setProgress((int) currentTime);
    }

    /**
     * Updates the map position:
     * 1. Finds the PDR sample corresponding to the current playback time and updates the camera and marker positions and orientation (calculated using the current and previous samples).
     * 2. Updates the Polyline based on the state of switch1 (displaying either the trajectory up to the current time or the full trajectory).
     * 3. Updates the time display.
     * 4. Updates the GNSS marker based on the current playback time.
     */
    private void updateMapPosition() {
        if (mMap != null && !trackPoints.isEmpty()) {
            TimedLatLng currentTimedPoint = trackPoints.get(0);
            int idx = 0;
            for (int i = 0; i < trackPoints.size(); i++) {
                if (trackPoints.get(i).relativeTimestamp <= currentTime) {
                    currentTimedPoint = trackPoints.get(i);
                    idx = i;
                } else {
                    break;
                }
            }
            LatLng point = currentTimedPoint.point;
            mMap.moveCamera(CameraUpdateFactory.newLatLng(point));
            if (currentMarker != null) {
                currentMarker.setPosition(point);
                if (idx > 0) {
                    LatLng prevPoint = trackPoints.get(idx - 1).point;
                    float bearing = computeBearing(prevPoint, point);
                    currentMarker.setRotation(bearing);
                }
            }
            if (indoorMapManager != null) {
                indoorMapManager.setCurrentLocation(point);
            }
            if (polyline != null) {
                if (switch1 != null && switch1.isChecked()) {
                    List<LatLng> allPoints = new ArrayList<>();
                    for (TimedLatLng tl : trackPoints) {
                        allPoints.add(tl.point);
                    }
                    polyline.setPoints(allPoints);
                } else {
                    List<LatLng> partial = new ArrayList<>();
                    for (TimedLatLng tl : trackPoints) {
                        if (tl.relativeTimestamp <= currentTime) {
                            partial.add(tl.point);
                        } else {
                            break;
                        }
                    }
                    if (partial.isEmpty() && !trackPoints.isEmpty()) {
                        partial.add(trackPoints.get(0).point);
                    }
                    polyline.setPoints(partial);
                }
            }
            int seconds = (int) (currentTime / 1000);
            int minutes = seconds / 60;
            int totalSeconds = (int) (totalDuration / 1000);
            int totalMinutes = totalSeconds / 60;
            totalSeconds = totalSeconds % 60;
            if (progressText != null) {
                progressText.setText(String.format("%02d:%02d", minutes, seconds));
                totaltimetext.setText(String.format("%02d:%02d", totalMinutes, totalSeconds));
            }
            if (trajectoryData != null && trajectoryData.getGnssDataCount() > 0 && gnssMarker != null) {
                LatLng currentGnss = getCurrentGnssPosition(currentTime, trajectoryData);
                if (currentGnss != null) {
                    gnssMarker.setPosition(currentGnss);
                    gnssMarker.setTitle(String.format("GNSS: %.6f, %.6f", currentGnss.latitude, currentGnss.longitude));
                }
            }
        }
        float elevationVal = 0;
        if (indoorMapManager.getIsIndoorMapSet()) {
            setFloorButtonVisibility(View.VISIBLE);
            if (autoFloor.isChecked()){
                int currentFloor = (int)(elevationVal / indoorMapManager.getFloorHeight());
                indoorMapManager.setCurrentFloor(currentFloor, true);
            }
        } else {
            setFloorButtonVisibility(View.GONE);
        }
    }

    /**
     * Sets the visibility of the floor change buttons.
     *
     * @param visibility The desired visibility (e.g., View.VISIBLE or View.GONE).
     */
    private void setFloorButtonVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        autoFloor.setVisibility(visibility);
    }

    /**
     * Calculates the bearing (direction angle) between two LatLng points in degrees (0–360).
     *
     * @param from The starting LatLng point.
     * @param to The destination LatLng point.
     * @return The bearing in degrees.
     */
    private float computeBearing(LatLng from, LatLng to) {
        double lat1 = Math.toRadians(from.latitude);
        double lon1 = Math.toRadians(from.longitude);
        double lat2 = Math.toRadians(to.latitude);
        double lon2 = Math.toRadians(to.longitude);
        double dLon = lon2 - lon1;
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (float)((bearing + 360) % 360);
    }

    // Sets up the Spinner listener to change the map type based on user selection
    private void setupMapTypeSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.map_types, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mapTypeSpinner.setAdapter(adapter);
        mapTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
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
                    default:
                        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                if (mMap != null) {
                    mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                }
            }
        });
    }
}
