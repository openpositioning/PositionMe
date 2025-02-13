package com.openpositioning.PositionMe.fragments;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.preference.PreferenceManager;

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
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.ArrayList;

// imports for graphs
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

/**
 * A fragment that replays recorded trajectories. It inherits most functionality from RecordingFragment
 * but modifies it to work with pre-recorded data instead of live sensor data.
 *
 * @author Joseph Azrak
 * @author Semih Vazgecen
 * @author Sofea Jazlan Arif
 * @author Stone Anderson
 */
public class ReplayFragment extends Fragment {
    // UI Elements (reused from RecordingFragment)
    TextView elevation;
    private TextView distanceTravelled;
    private TextView gnssError;
    private Button exitButton;
    private GoogleMap gMap;
    private Marker orientationMarker;
    private LatLng currentLocation;
    private LatLng nextLocation;
    private Polyline polyline;
    private IndoorMapManager indoorMapManager;
    private FloatingActionButton floorUpButton;
    private FloatingActionButton floorDownButton;
    private Switch autoFloor;
    private Handler refreshDataHandler;
    private Button magneticGraphButton;
    private Button gyroGraphButton;
    private LineChart accChart;
    private LineChart lineChart;

    // Replay specific fields
    private Traj.Trajectory trajectory;
    private long replayStartTime;
    private int currentPdrIndex = 0;
    private int currentImuIndex = 0;
    private float currentOrientation = 0.0f;
    private float[] lastPdrValues = new float[]{0.0f, 0.0f};
    private float lastElevation = 0.0f;
    private float distance = 0.0f;
    private LatLng userProvidedStartPos = null;
    private boolean waitingForCoordinates = false;
    private Button restartButton;
    private Button leaveButton;
    private Button playPauseButton;
    private Button goToEndButton;
    private LinearLayout controlButtonsLayout;
    private boolean isPlaying = true;  // Track play/pause state
    private long pausedTime = 0;  // Tracks when the replay was paused
    private long pausedReplayTimeOffset = 0;    // Time offset added when resuming the replay
    private ProgressBar replayProgressBar;
    private TextView progressText;
    private boolean isPaused = false;   // State to track if the replay is paused
    private long totalDuration;
    private LineData lineData;
    private boolean isMagneticGraphVisible = false;
    private boolean isAccelGraphVisible = false;
    private int currentGraphDataIndex = 0;
    private int currentAccDataIndex = 0;
    /**
     * Cleans up resources, resets state, and navigates back to home screen.
     * Used for both back navigation and explicit exit.
     */
    private void cleanupAndNavigateHome() {
        // Clean up handlers
        if (refreshDataHandler != null) {
            refreshDataHandler.removeCallbacks(replayUpdateTask);
        }

        // Clean up map resources
        if (gMap != null && polyline != null) {
            polyline.remove();
        }

        if (orientationMarker != null) {
            orientationMarker.remove();
        }

        // Reset state variables
        currentPdrIndex = 0;
        currentImuIndex = 0;
        currentOrientation = 0.0f;
        lastPdrValues = new float[]{0.0f, 0.0f};
        lastElevation = 0.0f;
        distance = 0.0f;

        try {
            // Navigate back to home fragment
            NavDirections action = ReplayFragmentDirections
                    .actionReplayFragmentToHomeFragment();
            Navigation.findNavController(requireView()).navigate(action);
        } catch (Exception e) {
            Log.e("ReplayFragment", "Error during navigation", e);
            // Fallback navigation if needed
            requireActivity().finish();
        }
    }

    /**
     * Initialises the fragment and loads the recorded trajectory file
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Set up callback for handling back navigation
        requireActivity().getOnBackPressedDispatcher().addCallback(this,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        cleanupAndNavigateHome();
                    }
                });

        this.refreshDataHandler = new Handler();


        try {
            // Get trajectory from arguments
            ReplayFragmentArgs args = ReplayFragmentArgs.fromBundle(getArguments());
            String trajectoryPath = args.getTrajectoryPath();
            File trajectoryFile = new File(trajectoryPath);
            byte[] fileContent = Files.readAllBytes(trajectoryFile.toPath());
            trajectory = Traj.Trajectory.parseFrom(fileContent);
            replayStartTime = System.currentTimeMillis();

            // Debug print all fields
            System.out.println("=== TRAJECTORY DUMP ===");
            System.out.println("Start timestamp: " + trajectory.getStartTimestamp());
            System.out.println("Android version: " + trajectory.getAndroidVersion());
            // Print sensor info
            System.out.println("\n=== SENSOR INFO ===");
            if (trajectory.hasAccelerometerInfo()) {
                System.out.println("Accelerometer: " + trajectory.getAccelerometerInfo());
            }
            if (trajectory.hasGyroscopeInfo()) {
                System.out.println("Gyroscope: " + trajectory.getGyroscopeInfo());
            }
            if (trajectory.hasMagnetometerInfo()) {
                System.out.println("Magnetometer: " + trajectory.getMagnetometerInfo());
            }
            if (trajectory.hasBarometerInfo()) {
                System.out.println("Barometer: " + trajectory.getBarometerInfo());
            }
            if (trajectory.hasLightSensorInfo()) {
                System.out.println("Light: " + trajectory.getLightSensorInfo());
            }
            System.out.println("\n=== DATA COUNTS ===");
            System.out.println("PDR samples: " + trajectory.getPdrDataCount());
            System.out.println("IMU samples: " + trajectory.getImuDataCount());
            System.out.println("Pressure samples: " + trajectory.getPressureDataCount());
            System.out.println("Light samples: " + trajectory.getLightDataCount());
            System.out.println("GNSS samples: " + trajectory.getGnssDataCount());
            System.out.println("Position samples: " + trajectory.getPositionDataCount());
            System.out.println("WiFi samples: " + trajectory.getWifiDataCount());
            System.out.println("AP data: " + trajectory.getApsDataCount());
            System.out.println("========================");
            System.out.println("\n=== INITIAL POSITION ===");
            System.out.println("Initial Latitude: " + trajectory.getInitialPos().getInitialLatitude());
            System.out.println("Initial Longitude: " + trajectory.getInitialPos().getInitialLongitude());
            System.out.println("========================");

        } catch (Exception e) {
            Log.e("ReplayFragment", "Error loading trajectory", e);
        }
    }

    /**
     * Inflates the fragment_replay.xml file
     * If PDR data was recorded, initialises and sets up Google Map and begins trajectory visualisation.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_replay, container, false);
        ((AppCompatActivity)getActivity()).getSupportActionBar().hide();
        getActivity().setTitle("Replaying Trajectory...");

        // Get initial position from trajectory
        LatLng startPos;
        startPos = new LatLng(trajectory.getInitialPos().getInitialLatitude(), trajectory.getInitialPos().getInitialLongitude());

        // Initialize map if we have coordinates
        if (startPos != null) {
            // If PDR data is available in trajectory
            rootView.setVisibility(View.VISIBLE); // Show map
            SupportMapFragment mapFragment = (SupportMapFragment)
                    getChildFragmentManager().findFragmentById(R.id.RecordingMap);
            mapFragment.getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(GoogleMap map) {
                    gMap = map;
                    setupMap(map, startPos);
                }
            });
        }


        return rootView;
    }

    /**
     * Configures Google Maps settings
     */
    private void setupMap(GoogleMap map, LatLng startPos) {
        if (map == null || startPos == null || waitingForCoordinates) {
            Log.w("ReplayFragment", "setupMap called but not ready: " +
                    "map=" + (map != null) +
                    ", startPos=" + (startPos != null) +
                    ", waiting=" + waitingForCoordinates);
            return;
        }

        gMap = map;
        indoorMapManager = new IndoorMapManager(map);

        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);

        currentLocation = startPos;
        orientationMarker = map.addMarker(new MarkerOptions()
                .position(startPos)
                .title("Current Position")
                .flat(true)
                .icon(BitmapDescriptorFactory.fromBitmap(
                        UtilFunctions.getBitmapFromVector(getContext(),
                                R.drawable.ic_baseline_navigation_24))));

        map.moveCamera(CameraUpdateFactory.newLatLngZoom(startPos, 19f));

        polyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .add(currentLocation));

        indoorMapManager.setCurrentLocation(currentLocation);
        indoorMapManager.setIndicationOfIndoorMap();

        System.out.println("ReplayFragment: Map initialized with start position: " + startPos +
                "\n - Marker created: " + (orientationMarker != null) +
                "\n - Polyline created: " + (polyline != null) +
                "\n - Indoor map manager initialized: " + (indoorMapManager != null));
    }

    /**
     * Initialises UI components, assigns click listeners to buttons
     * Begins replay execution by posting the replayUpdateTask.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialise UI components
        elevation = getView().findViewById(R.id.currentElevation);
        distanceTravelled = getView().findViewById(R.id.currentDistanceTraveled);
        gnssError = getView().findViewById(R.id.gnssError);
        floorUpButton = getView().findViewById(R.id.floorUpButton);
        floorDownButton = getView().findViewById(R.id.floorDownButton);
        autoFloor = getView().findViewById(R.id.autoFloor);
        magneticGraphButton = getView().findViewById(R.id.magneticButton);
        gyroGraphButton = getView().findViewById(R.id.gyroButton);
        accChart = getView().findViewById(R.id.accChart);
        lineChart = getView().findViewById(R.id.lineChart);
        controlButtonsLayout = view.findViewById(R.id.controlButtonsLayout);
        restartButton = getView().findViewById(R.id.restartButton);
        playPauseButton = getView().findViewById(R.id.playPauseButton);
        goToEndButton = getView().findViewById(R.id.goToEndButton);
        replayProgressBar = view.findViewById(R.id.replayProgressBar);
        progressText = view.findViewById(R.id.progressText);
        leaveButton = getView().findViewById(R.id.leaveButton);
        magneticGraphButton = getView().findViewById(R.id.magneticButton);
        gyroGraphButton = getView().findViewById(R.id.gyroButton);
        prepareMagneticData();
        prepareAccData();

        //click listeners
        restartButton.setOnClickListener(v -> restartReplay());
        playPauseButton.setOnClickListener(v -> togglePlayPause());
        goToEndButton.setOnClickListener(v -> goToEndOfReplay());
        leaveButton.setOnClickListener(v -> cleanupAndNavigateHome());
        magneticGraphButton.setOnClickListener(v -> clickButtonMagnetic());
        gyroGraphButton.setOnClickListener(v -> clickButtonAccel());

        // Hide recording-specific UI elements
        getView().findViewById(R.id.timeRemainingBar).setVisibility(View.GONE);
        getView().findViewById(R.id.redDot).setVisibility(View.GONE);
        //getView().findViewById(R.id.cancelButton).setVisibility(View.GONE);
        getView().findViewById(R.id.floorUpButton).setVisibility(View.GONE);
        getView().findViewById(R.id.floorDownButton).setVisibility(View.GONE);
        getView().findViewById(R.id.gnssSwitch).setVisibility(View.GONE);
        getView().findViewById(R.id.autoFloor).setVisibility(View.GONE);
        //getView().findViewById(R.id.stopButton).setVisibility(View.GONE);
        getView().findViewById(R.id.lineColorButton).setVisibility(View.GONE);


        // Start replay updates
        refreshDataHandler.post(replayUpdateTask);
    }

    /**
     * calls updateReplayPosition() function every 200 milliseconds to update movement / replay recording
     */
    private final Runnable replayUpdateTask = new Runnable() {
        @Override
        public void run() {
            updateReplayPosition();
            refreshDataHandler.postDelayed(this, 200);
        }
    };

    /**
     * Processes the PDR and IMU data based on timestamps and then updates the user’s position on the map and polyline trajectory
     */
    private void updateReplayPosition() {
        if (gMap == null || orientationMarker == null) {
            // Map not ready yet, try again next update
            return;
        }

        long currentTime = System.currentTimeMillis() - replayStartTime - pausedReplayTimeOffset;

        if(trajectory.getPdrDataCount() != 0)
        {
            totalDuration = trajectory.getPdrData(trajectory.getPdrDataCount() - 1).getRelativeTimestamp();
            if (!isPlaying && currentPdrIndex >= trajectory.getPdrDataCount())
            {
                currentTime = totalDuration;  // Prevents the currentTime from going below total duration
            }
        }
        else if(trajectory.getImuDataCount() != 0)
        {
            totalDuration = trajectory.getImuData(trajectory.getImuDataCount() - 1).getRelativeTimestamp();
            if (!isPlaying && currentImuIndex >= trajectory.getImuDataCount())
            {
                currentTime = totalDuration;  // Prevents the currentTime from going below total duration
            }

        }
        else{
            totalDuration=100;
        }



        int progressPercent = (int) ((currentTime * 100) / totalDuration);
        if (progressPercent > 100) progressPercent = 100;

        replayProgressBar.setProgress(progressPercent);
        progressText.setText(progressPercent + "% ");


        System.out.println("ReplayFragment: Update at T+" + currentTime + "ms" +
                "\n - Current PDR index: " + currentPdrIndex + "/" + trajectory.getPdrDataCount() +
                "\n - Current IMU index: " + currentImuIndex + "/" + trajectory.getImuDataCount());


        // Update PDR position
        if (isPlaying) {
            while (currentPdrIndex < trajectory.getPdrDataCount() &&
                    trajectory.getPdrData(currentPdrIndex).getRelativeTimestamp() <= currentTime) {
                Traj.Pdr_Sample pdr = trajectory.getPdrData(currentPdrIndex);
                float[] pdrMoved = new float[]{
                        pdr.getX() - lastPdrValues[0],
                        pdr.getY() - lastPdrValues[1]
                };

                // Update distance
                distance += Math.sqrt(Math.pow(pdrMoved[0], 2) + Math.pow(pdrMoved[1], 2));
                distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));

                // Update position
                if (pdrMoved[0] != 0 || pdrMoved[1] != 0) {
                    nextLocation = UtilFunctions.calculateNewPos(currentLocation, pdrMoved);

                    if (polyline != null) {
                        List<LatLng> points = polyline.getPoints();
                        points.add(nextLocation);
                        polyline.setPoints(points);
                    }

                    List<LatLng> points = polyline.getPoints();
                    points.add(nextLocation);
                    polyline.setPoints(points);
                    orientationMarker.setPosition(nextLocation);
                    gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(nextLocation, 19f));
                    currentLocation = nextLocation;

                    System.out.println("ReplayFragment: PDR movement" +
                            "\n - Delta: [" + pdrMoved[0] + ", " + pdrMoved[1] + "]" +
                            "\n - New location: " + nextLocation +
                            "\n - Total distance: " + distance + "m");

                }

                lastPdrValues[0] = pdr.getX();
                lastPdrValues[1] = pdr.getY();
                currentPdrIndex++;
            }
        }// end of is(playing)

        // Update IMU/orientation
        while (currentImuIndex < trajectory.getImuDataCount() &&
                trajectory.getImuData(currentImuIndex).getRelativeTimestamp() <= currentTime) {
            Traj.Motion_Sample imu = trajectory.getImuData(currentImuIndex);

            // Reconstruct rotation vector
            float[] rotationVector = new float[4];
            rotationVector[0] = imu.getRotationVectorX();
            rotationVector[1] = imu.getRotationVectorY();
            rotationVector[2] = imu.getRotationVectorZ();
            rotationVector[3] = imu.getRotationVectorW();

            // Convert to orientation angles same way as recording
            float[] rotationMatrix = new float[9];
            float[] orientationAngles = new float[3];
            SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector);
            SensorManager.getOrientation(rotationMatrix, orientationAngles);

            // Get azimuth (yaw) angle
            currentOrientation = orientationAngles[0];

            if (orientationMarker != null) {
                orientationMarker.setRotation((float) Math.toDegrees(currentOrientation));
            }
            currentImuIndex++;
            System.out.println("ReplayFragment: Orientation update" +
                    "\n - New orientation: " + Math.toDegrees(currentOrientation) + "° (index was " + (currentImuIndex-1) + ")" );
        }

        // Handle elevation updates
        if (trajectory.getPressureDataCount() > 0) {
            int pressureIndex = 0;
            while (pressureIndex < trajectory.getPressureDataCount() &&
                    trajectory.getPressureData(pressureIndex).getRelativeTimestamp() <= currentTime) {
                lastElevation = trajectory.getPressureData(pressureIndex).getPressure();
                pressureIndex++;
            }
            elevation.setText(getString(R.string.elevation, String.format("%.1f", lastElevation)));

            if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet() && autoFloor.isChecked()) {
                indoorMapManager.setCurrentFloor(
                        (int)(lastElevation/indoorMapManager.getFloorHeight()), true);
            }

            System.out.println("ReplayFragment: Elevation update" +
                    "\n - New elevation: " + lastElevation + "m" +
                    "\n - Floor estimation: " + (int)(lastElevation/indoorMapManager.getFloorHeight()));
        }
    }

    /**
     * Sets replay recording back to the beginning
     */
    private void restartReplay() {
        currentPdrIndex = 0;
        currentImuIndex = 0;
        replayStartTime = System.currentTimeMillis();  // Resetting the start time
        pausedReplayTimeOffset = 0;
        isPlaying = true;
        isPaused = false;

        // gets rid of the existing path
        if (polyline != null) {
            polyline.setPoints(new ArrayList<>());
        }

        replayProgressBar.setProgress(0);
        progressText.setText("0%");

        // Use the user provided GNSS starting position if available
        LatLng startPos = null;
        if (userProvidedStartPos != null) {
            startPos = userProvidedStartPos;  // Uses the user's input
        } else if (trajectory.getGnssDataCount() > 0) {
            // use the first GNSS sample if no user input
            Traj.GNSS_Sample firstGnss = trajectory.getGnssData(0);
            startPos = new LatLng(firstGnss.getLatitude(), firstGnss.getLongitude());
        }

        if (startPos != null) {
            currentLocation = startPos;
            if (orientationMarker != null) {
                orientationMarker.setPosition(currentLocation);
            }
            if (gMap != null) {
                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 19f));
            }
        }

        refreshDataHandler.post(replayUpdateTask);
        playPauseButton.setBackgroundResource(R.drawable.pause_icon);
        Toast.makeText(getContext(), "Replay restarted!", Toast.LENGTH_SHORT).show();
    }

    /**
     * Used to stop and start replay recording
     * If paused, stores the timestamp to ensure accurate position when playing again
     * If resumed, recalculates the time offset and continues replay recording
     */
    private void togglePlayPause() {
        if (isPlaying) {
            // Pause the replay from playing mode
            pausedTime = System.currentTimeMillis();
            isPlaying = false;
            isPaused = true;
            refreshDataHandler.removeCallbacks(replayUpdateTask);
            playPauseButton.setBackgroundResource(R.drawable.play_icon);
            Toast.makeText(getContext(), "Replay Paused", Toast.LENGTH_SHORT).show();
        } else {
            // scenario for if replay is at the end
            if (currentPdrIndex >= trajectory.getPdrDataCount()) {
                restartReplay();  // Automatically restarts replay
                return;
            }

            // Resumes the replay from pause mode
            if (isPaused) {
                long currentTime = System.currentTimeMillis();
                pausedReplayTimeOffset += currentTime - pausedTime;
                isPaused = false;
            }

            isPlaying = true;
            refreshDataHandler.post(replayUpdateTask);
            playPauseButton.setBackgroundResource(R.drawable.pause_icon);
            Toast.makeText(getContext(), "Replay Resumed", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Stops replay updates when the replay recording is paused.
     */
    @Override
    public void onPause() {
        if (isPlaying) {
            refreshDataHandler.removeCallbacks(replayUpdateTask);
        }
        super.onPause();
    }

    /**
     * Resumes replay updates when the replay recording is resumed.
     */
    @Override
    public void onResume() {
        super.onResume();
        if(isPlaying) {
            refreshDataHandler.postDelayed(replayUpdateTask, 200);
        }
    }

    /**
     * Fast-forwards replay recording to the end of the recording
     */
    private void goToEndOfReplay() {
        // Checks if the map and polyline is ready
        if (gMap == null || polyline == null) {
            Toast.makeText(getContext(), "Map or polyline is not initialised.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Goes through all the PDR data
        List<LatLng> fullPath = new ArrayList<>(polyline.getPoints());
        while (currentPdrIndex < trajectory.getPdrDataCount()) {
            Traj.Pdr_Sample pdr = trajectory.getPdrData(currentPdrIndex);

            float[] pdrMoved = new float[]{
                    pdr.getX() - lastPdrValues[0],
                    pdr.getY() - lastPdrValues[1]
            };

            if (pdrMoved[0] != 0 || pdrMoved[1] != 0) {
                LatLng nextLocation = UtilFunctions.calculateNewPos(currentLocation, pdrMoved);
                fullPath.add(nextLocation);
                currentLocation = nextLocation;
            }

            // Update the last PDR values
            lastPdrValues[0] = pdr.getX();
            lastPdrValues[1] = pdr.getY();

            currentPdrIndex++;
        }

        // Update the polyline with full/final path and progress bar
        polyline.setPoints(fullPath);
        replayProgressBar.setProgress(100);
        progressText.setText("100%");

        // Move the marker to the final last position
        if (!fullPath.isEmpty()) {
            currentLocation = fullPath.get(fullPath.size() - 1);
            if (orientationMarker != null) {
                orientationMarker.setPosition(currentLocation);
            }

            // Adjusting the camera to show the final position
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 19f));
        }


        // Pauses the replay and updates the UI
        isPlaying = false;
        pausedTime = System.currentTimeMillis();
        pausedReplayTimeOffset = pausedTime - replayStartTime;  // to ensure the offset matches the current time at 100%
        replayStartTime = pausedTime;

        playPauseButton.setBackgroundResource(R.drawable.play_icon);  // Switch to "Play" icon
        Toast.makeText(getContext(), "Fast forwarded to the end of the replay.", Toast.LENGTH_SHORT).show();
    }


    // Function prep data into dataset

    int accSize = 100;

    float[] magneticX = new float[accSize];
    float[] magneticY = new float[accSize];
    float[] magneticZ = new float[accSize];

    /**
     * Extracts magnetometer data (X, Y, Z) from recorded trajectory samples and updates magnetic field graph.
     */
    public void prepareMagneticData()
    {
        updateGraphDataX UpdateGraphDataX = new updateGraphDataX();
        updateGraphDataY UpdateGraphDataY = new updateGraphDataY();
        updateGraphDataZ UpdateGraphDataZ = new updateGraphDataZ();

        System.out.println("Max Length" + trajectory.getPositionDataCount());
        while(currentGraphDataIndex < trajectory.getPositionDataCount()){

            float magneticXValue = trajectory.getPositionData(currentGraphDataIndex).getMagX();
            float magneticYValue = trajectory.getPositionData(currentGraphDataIndex).getMagY();
            float magneticZValue = trajectory.getPositionData(currentGraphDataIndex).getMagZ();

            UpdateGraphDataX.addElement(magneticXValue);
            UpdateGraphDataY.addElement(magneticYValue);
            UpdateGraphDataZ.addElement(magneticZValue);


            System.out.println("MagX: " + magneticXValue);
            System.out.println("MagY: " + magneticYValue);
            System.out.println("MagZ: " + magneticZValue);
            currentGraphDataIndex++;
        }

        float[] magneticX = UpdateGraphDataX.getArray(); // Get updated array
        float[] magneticY = UpdateGraphDataY.getArray(); // Get updated array
        float[] magneticZ = UpdateGraphDataZ.getArray(); // Get updated array

        // convert the float magnetic into ArrayList for graph
        ArrayList<Entry> magneticXArrayList = new ArrayList<>();
        ArrayList<Entry> magneticYArrayList = new ArrayList<>();
        ArrayList<Entry> magneticZArrayList = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            magneticXArrayList.add(new Entry(i, magneticX[i]));
            magneticYArrayList.add(new Entry(i, magneticY[i]));
            magneticZArrayList.add(new Entry(i, magneticZ[i]));
        }

        ////////////////////////////// PLOT GRAPH MAGNETIC START //////////////////////////////
        // create data set for magnetic X,Y and Z
        LineDataSet lineDataSetMagnetX = new LineDataSet(magneticXArrayList, "Magnetic X");
        lineDataSetMagnetX.setColor(Color.BLUE);
        lineDataSetMagnetX.setCircleColor(Color.BLUE);
        lineDataSetMagnetX.setValueTextColor(Color.BLACK);
        lineDataSetMagnetX.setValueTextSize(12f);
        lineDataSetMagnetX.setLineWidth(2f);
        lineDataSetMagnetX.setCircleRadius(4f);

        LineDataSet lineDataSetMagnetY = new LineDataSet(magneticYArrayList, "Magnetic Y");
        lineDataSetMagnetY.setColor(Color.GREEN);
        lineDataSetMagnetY.setCircleColor(Color.GREEN);
        lineDataSetMagnetY.setValueTextColor(Color.BLACK);
        lineDataSetMagnetY.setValueTextSize(12f);
        lineDataSetMagnetY.setLineWidth(2f);
        lineDataSetMagnetY.setCircleRadius(4f);

        LineDataSet lineDataSetMagnetZ = new LineDataSet(magneticZArrayList, "Magnetic Z");
        lineDataSetMagnetZ.setColor(Color.RED);
        lineDataSetMagnetZ.setCircleColor(Color.RED);
        lineDataSetMagnetZ.setValueTextColor(Color.BLACK);
        lineDataSetMagnetZ.setValueTextSize(12f);
        lineDataSetMagnetZ.setLineWidth(2f);
        lineDataSetMagnetZ.setCircleRadius(4f);

        // Add dataset to LineData
        LineData lineData = new LineData(lineDataSetMagnetX, lineDataSetMagnetY, lineDataSetMagnetZ);

        // Set data to chart
        lineChart.setData(lineData);
        lineChart.setDescription(null); // Remove the title
        lineChart.setBackgroundColor(Color.parseColor("#DFFFFFFF"));  // 50% opacity
        lineChart.invalidate(); // Refresh the chart

        /////////////////////////////// PLOT GRAPH MAGNETIC END //////////////////////////////
    }

    float[] accX = new float[100];
    float[] accY = new float[100];
    float[] accZ = new float[100];

    /**
     * Extracts accelerometer data (X, Y, Z) from recorded trajectory samples and updates acceleration graph.
     */
    public void prepareAccData()
    {

        updateAccDataX UpdateAccDataX = new updateAccDataX();
        updateAccDataY UpdateAccDataY = new updateAccDataY();
        updateAccDataZ UpdateAccDataZ = new updateAccDataZ();

        System.out.println("Max Length" + trajectory.getPositionDataCount());
        while(currentAccDataIndex < trajectory.getPositionDataCount()){

            float accXValue = trajectory.getImuData(currentAccDataIndex).getAccX();
            float accYValue = trajectory.getImuData(currentAccDataIndex).getAccY();
            float accZValue = trajectory.getImuData(currentAccDataIndex).getAccZ();

            UpdateAccDataX.addElement(accXValue);
            UpdateAccDataY.addElement(accYValue);
            UpdateAccDataZ.addElement(accZValue);

            currentAccDataIndex++;
        }

        float[] accX = UpdateAccDataX.getArray(); // Get updated array
        float[] accY = UpdateAccDataY.getArray(); // Get updated array
        float[] accZ = UpdateAccDataZ.getArray(); // Get updated array

        // convert the float magnetic into ArrayList for graph
        ArrayList<Entry> accXArrayList = new ArrayList<>();
        ArrayList<Entry> accYArrayList = new ArrayList<>();
        ArrayList<Entry> accZArrayList = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            accXArrayList.add(new Entry(i, accX[i]));
            accYArrayList.add(new Entry(i, accY[i]));
            accZArrayList.add(new Entry(i, accZ[i]));
        }

        // create data set for magnetic X,Y and Z
        LineDataSet lineDataSetAccX = new LineDataSet(accXArrayList, "Accel X");
        lineDataSetAccX.setColor(Color.BLUE);
        lineDataSetAccX.setCircleColor(Color.BLUE);
        lineDataSetAccX.setValueTextColor(Color.BLACK);
        lineDataSetAccX.setValueTextSize(12f);
        lineDataSetAccX.setLineWidth(2f);
        lineDataSetAccX.setCircleRadius(4f);

        LineDataSet lineDataSetAccY = new LineDataSet(accYArrayList, "Accel Y");
        lineDataSetAccY.setColor(Color.GREEN);
        lineDataSetAccY.setCircleColor(Color.GREEN);
        lineDataSetAccY.setValueTextColor(Color.BLACK);
        lineDataSetAccY.setValueTextSize(12f);
        lineDataSetAccY.setLineWidth(2f);
        lineDataSetAccY.setCircleRadius(4f);

        LineDataSet lineDataSetAccZ = new LineDataSet(accZArrayList, "Accel Z");
        lineDataSetAccZ.setColor(Color.RED);
        lineDataSetAccZ.setCircleColor(Color.RED);
        lineDataSetAccZ.setValueTextColor(Color.BLACK);
        lineDataSetAccZ.setValueTextSize(12f);
        lineDataSetAccZ.setLineWidth(2f);
        lineDataSetAccZ.setCircleRadius(4f);

        // Add dataset to LineData
        LineData accData = new LineData(lineDataSetAccX, lineDataSetAccY, lineDataSetAccZ);

        // Set data to chart
        accChart.setData(accData);
        accChart.setDescription(null); // Remove the title
        accChart.setBackgroundColor(Color.parseColor("#DFFFFFFF"));  // 50% opacity
        accChart.invalidate(); // Refresh the chart
    }

    /**
     * Toggles the magnetometer graph visibility
     */
    public void clickButtonMagnetic()
    {
        ObjectAnimator slideDownAccel = ObjectAnimator.ofFloat(accChart, "translationY", 0f, accChart.getHeight());
        ObjectAnimator slideDownMag = ObjectAnimator.ofFloat(lineChart, "translationY", 0f, lineChart.getHeight());
        ObjectAnimator slideUpAccel = ObjectAnimator.ofFloat(accChart, "translationY", accChart.getHeight(), 0f);
        ObjectAnimator slideUpMag = ObjectAnimator.ofFloat(lineChart, "translationY", lineChart.getHeight(), 0f);
        if (isAccelGraphVisible){
            slideDownAccel.setDuration(100);
            slideDownAccel.start();
            isAccelGraphVisible = false;
        }

        if (isMagneticGraphVisible) {
            // Slide the magnetic graph down
            slideDownMag.setDuration(500);
            slideDownMag.start();
            isMagneticGraphVisible = false;
        } else {
            // Make the graph visible first
            lineChart.setVisibility(View.VISIBLE);

            // Slide the graph up
            slideUpMag.setDuration(500);
            slideUpMag.start();
            isMagneticGraphVisible = true;
        }

    }

    /**
     * Toggles the accelerometer graph visibility
     */
    public void clickButtonAccel() {

        ObjectAnimator slideDownAccel = ObjectAnimator.ofFloat(accChart, "translationY", 0f, accChart.getHeight());
        ObjectAnimator slideDownMag = ObjectAnimator.ofFloat(lineChart, "translationY", 0f, lineChart.getHeight());
        ObjectAnimator slideUpAccel = ObjectAnimator.ofFloat(accChart, "translationY", accChart.getHeight(), 0f);
        ObjectAnimator slideUpMag = ObjectAnimator.ofFloat(lineChart, "translationY", lineChart.getHeight(), 0f);

        if (isMagneticGraphVisible)
        {
            slideDownMag.setDuration(100);
            slideDownMag.start();
            isMagneticGraphVisible = false;
        }

        if (isAccelGraphVisible) {
            // Slide the graph down
            slideDownAccel.setDuration(500); // Duration of slide down (in milliseconds)
            slideDownAccel.start();
            isAccelGraphVisible = false;
        } else {
            // Make the graph visible first
            accChart.setVisibility(View.VISIBLE);

            // Slide the graph up
            slideUpAccel.setDuration(500); // Duration of slide up (in milliseconds)
            slideUpAccel.start();
            isAccelGraphVisible = true;
        }
    }

    /**
     * Manages X_coordinate updates to magnetometer graph data.
     * Takes in one argument, which is the new element to be added into the array.
     * Example usage: updateGraphData.addElement(2.5f)
     * This will add float 2.5 to the end of the array while removing the 0th element.
     */
    public class updateGraphDataX {

        int SIZE = accSize;
        public void addElement(float newXElement) {
            // Shift elements to the left
            System.arraycopy(magneticX, 1, magneticX, 0, SIZE - 1);
            // Add new element at the last index
            magneticX[SIZE - 1] = newXElement;
        }

        public float[] getArray() {
            return magneticX;
        }
    }

    /**
     * Manages Y_coordinate updates to magnetometer graph data.
     * Takes in one argument, which is the new element to be added into the array.
     * Example usage: updateGraphData.addElement(2.5f)
     * This will add float 2.5 to the end of the array while removing the 0th element.
     */
    public class updateGraphDataY {

        int SIZE = accSize;
        public void addElement(float newYElement) {
            // Shift elements to the left
            System.arraycopy(magneticY, 1, magneticY, 0, SIZE - 1);
            // Add new element at the last index
            magneticY[SIZE - 1] = newYElement;
        }

        public float[] getArray() {
            return magneticY;
        }
    }

    /**
     * Manages Z_coordinate updates to magnetometer graph data.
     * Takes in one argument, which is the new element to be added into the array.
     * Example usage: updateGraphData.addElement(2.5f)
     * This will add float 2.5 to the end of the array while removing the 0th element.
     */
    public class updateGraphDataZ {

        int SIZE = accSize;
        public void addElement(float newZElement) {
            // Shift elements to the left
            System.arraycopy(magneticZ, 1, magneticZ, 0, SIZE - 1);
            // Add new element at the last index
            magneticZ[SIZE - 1] = newZElement;
        }

        public float[] getArray() {
            return magneticZ;
        }
    }

    /**
     * Manages X_coordinate updates to accelerometer graph data.
     * Takes in one argument, which is the new element to be added into the array.
     * Example usage: updateGraphData.addElement(2.5f)
     * This will add float 2.5 to the end of the array while removing the 0th element.
     */
    public class updateAccDataX {

        int SIZE = 100;
        public void addElement(float newElement) {
            // Shift elements to the left
            System.arraycopy(accX, 1, accX, 0, SIZE - 1);
            // Add new element at the last index
            accX[SIZE - 1] = newElement;
        }

        public float[] getArray() {
            return accX;
        }
    }

    /**
     * Manages Y_coordinate updates to accelerometer graph data.
     * Takes in one argument, which is the new element to be added into the array.
     * Example usage: updateGraphData.addElement(2.5f)
     * This will add float 2.5 to the end of the array while removing the 0th element.
     */
    public class updateAccDataY {

        int SIZE = 100;
        public void addElement(float newElement) {
            // Shift elements to the left
            System.arraycopy(accY, 1, accY, 0, SIZE - 1);
            // Add new element at the last index
            accY[SIZE - 1] = newElement;
        }

        public float[] getArray() {
            return accY;
        }
    }

    /**
     * Manages Z_coordinate updates to accelerometer graph data.
     * Takes in one argument, which is the new element to be added into the array.
     * Example usage: updateGraphData.addElement(2.5f)
     * This will add float 2.5 to the end of the array while removing the 0th element.
     */
    public class updateAccDataZ {

        int SIZE = 100;
        public void addElement(float newElement) {
            // Shift elements to the left
            System.arraycopy(accZ, 1, accZ, 0, SIZE - 1);
            // Add new element at the last index
            accZ[SIZE - 1] = newElement;
        }

        public float[] getArray() {
            return accZ;
        }
    }
}












