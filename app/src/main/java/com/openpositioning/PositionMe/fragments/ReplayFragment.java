package com.openpositioning.PositionMe.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.Layout;
import android.text.SpannableString;
import android.text.style.AlignmentSpan;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
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
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.ServerCommunications;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.content.Intent;
import java.util.ArrayList;
import java.util.Locale;


/**
 * A {@link Fragment} for replaying the previously recorded trajectory specified by the trajectory
 * ID stored in {@link SharedPreferences}.
 *
 *
 * @see FilesFragment sub-menu when the trajectory to be replayed is selected. This is the proceeding
 *                    fragment in the nav graph.
 * @see Traj the data structure which is played.
 * @see ServerCommunications the class handling communication with the server and storing the current
 *                           trajectory.
 *
 * @author Laura Maryakhina
 * @author Fraser Bunting
 * @author Kalliopi Vakali
 *
 */
public class ReplayFragment extends Fragment implements OnMapReadyCallback {

    private static final String TAG = "ReplayFragment";

    private IndoorMapManager indoorMapManager;
    private GoogleMap gMap;
    private ServerCommunications serverCommunications;
    CompletableFuture<String> futureReplayTrajectory;

    // UI elements
    private FloatingActionButton replayFloorUpButton;
    private FloatingActionButton replayFloorDownButton;
    private Switch replayAutoFloor;
    private ImageButton btnPlayPause;
    private ImageButton btnEnd;
    private ImageButton btnRestart;
    private ImageButton btnSettings;
    private SeekBar seekBar;
    private Spinner switchMapSpinner;
    private TextView distanceTravelled;
    private TextView gnssError;
    private TextView elevation;
    private Switch gnssSwitch;

    // Replay control variables
    private boolean isReplayRunning = false;
    private boolean isReplayFinished = false;
    private boolean isSeeking = false; // Flag to prevent auto-update while seeking
    private int pdrIndex = 0;
    private Handler replayDataHandler = new Handler();
    private long replayDelayMs = 500;
    private long avgSpeed = 500;

    // Data storage
    private List<Traj.Pdr_Sample> pdrReplaySamples;
    private List<Traj.GNSS_Sample> gnssReplayPoints;
    private List<Traj.Pressure_Sample> pressureReplaySamples;
    private List<Traj.Motion_Sample> rotationReplaySamples;
    private long firstGnssTimestamp;
    private long firstPdrTimestamp;

    // Markers and polylines
    private Marker gnssMarker;
    private final List<Marker> markerList = new ArrayList<>();
    private Marker orientationMarker;
    private LatLng firstGnssPoint;
    private LatLng nextLocation;

    // Previous state for calculations
    private float previousPosX;
    private float previousPosY;
    private float distance;
    private float initialElevation;

    //Polylines for different floors
    private Polyline polylineLG;
    private Polyline polylineG;
    private Polyline polyline1;
    private Polyline polyline2;
    private Polyline polyline3;

    // Voice control elements
    private FloatingActionButton voiceControlButton;
    private SpeechRecognizer speechRecognizer;
    private boolean isVoiceControlActive = false;


    /**
     * {@inheritDoc}
     * Fetches the trajectory from the futures object, after the map has been initialised.
     *
     */
    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        gMap = googleMap;
        setupMap();
        updatePolylineZIndexes();

        // Once the map is ready, attempt to fetch trajectory data and start replay
        try {
            processTrajectoryData(futureReplayTrajectory.get());
        } catch(InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
    }

    /**
     * {@inheritDoc}
     * Sets an appropiate title for the fragment.
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        getActivity().setTitle("Trajectory Replay");
        return inflater.inflate(R.layout.fragment_replay, container, false);
    }

    /**
     * {@inheritDoc}
     * Use the getReplayTrajectory method to send a request to the server for the trajectory.
     * Storing in the futureReplayTrajectory future string allows the trajectory to be fetched in
     * parallel with the map.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Request that the trajectory get downloaded in parallel with the map using a futures
        serverCommunications = new ServerCommunications(requireActivity());
        futureReplayTrajectory = serverCommunications.getReplayTrajectory();

        // Initialize UI elements and listeners
        setupMapFragment();
        setupUI(view);

        // Configure dropdown for switching map types
        setupMapTypeDropdown();
    }

    /** Initializes the Google Map. */
    private void setupMap() {
        gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        gMap.getUiSettings().setCompassEnabled(true);
        gMap.getUiSettings().setTiltGesturesEnabled(true);
        gMap.getUiSettings().setRotateGesturesEnabled(true);
        gMap.getUiSettings().setScrollGesturesEnabled(true);

        indoorMapManager = new IndoorMapManager(gMap);
        indoorMapManager.setCurrentLocation(nextLocation);
        indoorMapManager.setIndicationOfIndoorMap();

        initPolylines();
        updatePolylineZIndexes();
    }

    /** Sets up the UI elements and their listeners. */
    private void setupUI(View view) {
        distanceTravelled = view.findViewById(R.id.replayDistanceTraveled);
        gnssError = view.findViewById(R.id.replayGnssError);
        elevation = view.findViewById(R.id.replayCurrentElevation);
        replayFloorUpButton = view.findViewById(R.id.replayFloorUpButton);
        replayFloorDownButton = view.findViewById(R.id.replayFloorDownButton);
        replayAutoFloor = view.findViewById(R.id.replayAutoFloor);
        gnssSwitch = view.findViewById(R.id.replayGnssSwitch);
        btnPlayPause = view.findViewById(R.id.btnPlayPause);
        btnEnd = view.findViewById(R.id.btnEnd);
        btnRestart = view.findViewById(R.id.btnRestart);
        seekBar = view.findViewById(R.id.progressBar);
        btnSettings = view.findViewById(R.id.btnSettings);
        switchMapSpinner = view.findViewById(R.id.replayMapSwitchSpinner);
        voiceControlButton = view.findViewById(R.id.btnVoiceControl);

        replayAutoFloor.setChecked(true); // Default to auto floor

        setupButtonListeners();
        setupSeekBarListener();
        setupGnssSwitchListener();
        setupVoiceControl();

    }

    /** Sets up the voice control button and its behavior. */
    private void setupVoiceControl() {
        // Create a speech recognizer instance
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext());

        // Attach a listener to handle speech recognition events
        setupSpeechRecognizer();

        // Set up the voice control button's click behavior
        voiceControlButton.setOnClickListener(v -> {
            isVoiceControlActive = !isVoiceControlActive; // Toggle voice control state
            updateVoiceButtonUI(); // Update UI to reflect the state

            if (isVoiceControlActive) {
                // If voice control is turned ON, start listening for voice commands
                Log.d("VoiceControl", "Starting speech recognition...");
                startListening();
            } else {
                // If voice control is turned OFF, stop recognition immediately
                Log.d("VoiceControl", "Stopping speech recognition...");

                // Stop any ongoing recognition process
                speechRecognizer.cancel();
                speechRecognizer.destroy();

                // Reinitialize speech recognizer to ensure a fresh start when turned on again
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext());
                setupSpeechRecognizer(); // Attach listener again to new recognizer instance
            }
        });
    }

    /**
     * Configures the speech recognizer by setting up a listener
     * that processes voice input and executes relevant commands.
     */
    private void setupSpeechRecognizer() {
        speechRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onResults(Bundle results) {
                // Retrieve the list of recognized words/phrases
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                if (matches != null && !matches.isEmpty()) {
                    // Convert first recognized phrase to lowercase for case-insensitive matching
                    String command = matches.get(0).toLowerCase(Locale.ROOT);
                    Log.d("VoiceControl", "Recognized command: " + command);

                    // Process the recognized voice command
                    if (command.contains("play")) {
                        // Start replay only if it is currently paused
                        if (!isReplayRunning) {
                            toggleReplay();
                            Log.d("VoiceControl", "Play command executed");
                        }
                    } else if (command.contains("pause")) {
                        // Pause replay only if it is currently running
                        if (isReplayRunning) {
                            toggleReplay();
                            Log.d("VoiceControl", "Pause command executed");
                        }
                    } else {
                        // Handle unrecognized commands
                        Log.d("VoiceControl", "Unknown command: " + command);
                    }
                }

                // If voice control is still active, restart listening for commands
                if (isVoiceControlActive) {
                    startListening();
                } else {
                    Log.d("VoiceControl", "Voice control turned off, stopping recognition.");
                    speechRecognizer.cancel();
                }
            }

            /** Five methods responsible for handling speech recognition events */
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}

            /** Handles errors during speech recognition. */
            @Override
            public void onError(int error) {
                Log.e("VoiceControl", "Speech recognition error: " + error);

                if (isVoiceControlActive) {
                    // Restart listening if voice control is still active
                    Log.d("VoiceControl", "Restarting speech recognition...");
                    startListening();
                } else {
                    // Stop listening completely if voice control is off
                    Log.d("VoiceControl", "Voice control is off. Stopping recognition.");
                    speechRecognizer.cancel();
                }
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    /** Starts listening for voice commands. */
    private void startListening() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        speechRecognizer.startListening(intent);
        Log.d("VoiceControl", "Speech recognition started");

    }

    /** Update the voice control button depending on the current state of recording. */
    private void updateVoiceButtonUI() {
        if (isVoiceControlActive) {
            voiceControlButton.setImageResource(R.drawable.mic_24px);
            voiceControlButton.setBackgroundTintList(getResources().getColorStateList(R.color.lightLogoBlue, null));
        } else {
            voiceControlButton.setImageResource(R.drawable.mic_off_24px);
            voiceControlButton.setBackgroundTintList(getResources().getColorStateList(R.color.gray, null));
        }
    }


    /** Sets up the map fragment and triggers the {@link #onMapReady(GoogleMap)} callback. */
    private void setupMapFragment() {
        SupportMapFragment mapFragment =
                (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.ReplayMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
    }

    /** Sets up the button listeners for floor changes and replay controls. */
    private void setupButtonListeners() {
        replayFloorUpButton.setOnClickListener(view -> {
            replayAutoFloor.setChecked(false); // Turn off auto-floor when manually changed
            indoorMapManager.increaseFloor();
        });

        replayFloorDownButton.setOnClickListener(view ->{
            replayAutoFloor.setChecked(false);
            indoorMapManager.decreaseFloor();
        });

        btnPlayPause.setOnClickListener(v -> toggleReplay());
        btnEnd.setOnClickListener(v -> goToEnd());
        btnRestart.setOnClickListener(v -> restartReplay());
        btnSettings.setOnClickListener(v -> showPlaybackSpeedMenu(v));
    }

    /** Sets up the seek bar listener for trajectory scrubbing. */
    private void setupSeekBarListener() {
        seekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser) {
                            isSeeking = true;
                            seekTo(progress);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                        isSeeking = true;
                        pauseReplay();
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        isSeeking = false;
                        if (isReplayRunning) {
                            startReplay();
                            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                        } else {
                            // If paused, keep it paused and update button
                            btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
                        }
                    }
                });
    }

    /** Sets up the GNSS switch listener to toggle GNSS marker and error display. */
    private void setupGnssSwitchListener() {
        gnssSwitch.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    if (isChecked) {
                        if (pdrReplaySamples != null && pdrIndex < pdrReplaySamples.size()) {
                            Traj.Pdr_Sample currentPdrSample = pdrReplaySamples.get(pdrIndex);
                            plotGnss(currentPdrSample.getRelativeTimestamp());
                        }
                    } else {
                        if (gnssMarker != null) {
                            gnssMarker.remove();
                        }
                        gnssError.setVisibility(View.GONE);
                    }
                });
    }

    /** Creates a dropdown for changing map types (Hybrid, Normal, Satellite). */
    private void setupMapTypeDropdown() {
        String[] maps =
                new String[] {
                        getString(R.string.hybrid), getString(R.string.normal), getString(R.string.satellite)
                };
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, maps);
        switchMapSpinner.setAdapter(adapter);

        switchMapSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        switch (position) {
                            case 0:
                                gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                                break;
                            case 1:
                                gMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                                break;
                            case 2:
                                gMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                                break;
                            default:
                                gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID); // Default
                    }
                });
    }

    /**
     * Processes the raw trajectory JSON string, populating the sample lists.
     *
     * @param trajectoryJson The raw JSON string from the server.
     */
    private void processTrajectoryData(String trajectoryJson) {
        if (trajectoryJson == null) {
            Log.e(TAG, "Trajectory data is null.");
            return;
        }
        try {
            JSONObject jsonObject = new JSONObject(trajectoryJson);
            pdrReplaySamples = parsePdrData(jsonObject.getJSONArray("pdrData"));
            gnssReplayPoints = parseGnssData(jsonObject.getJSONArray("gnssData"));
            pressureReplaySamples = parsePressureData(jsonObject.getJSONArray("pressureData"));
            rotationReplaySamples = parseMotionData(jsonObject.getJSONArray("imuData"));

            // Initialize map elements and UI state AFTER data is loaded
            initializeMapElements();

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing trajectory JSON: " + e.getMessage());
        }
    }

    private List<Traj.Pdr_Sample> parsePdrData(JSONArray pdrData) throws JSONException {
        List<Traj.Pdr_Sample> samples = new ArrayList<>();
        for (int i = 0; i < pdrData.length(); i++) {
            JSONObject pdrSampleJson = pdrData.getJSONObject(i);
            Traj.Pdr_Sample pdrSample =
                    Traj.Pdr_Sample.newBuilder()
                            .setX((float) pdrSampleJson.getDouble("x"))
                            .setY((float) pdrSampleJson.getDouble("y"))
                            .setRelativeTimestamp(pdrSampleJson.getLong("relativeTimestamp"))
                            .build();
            samples.add(pdrSample);

            if (i == 0) {
                firstPdrTimestamp = pdrSample.getRelativeTimestamp();
            }
            if (i == pdrData.length() - 1) {
                avgSpeed = (pdrSample.getRelativeTimestamp() - firstPdrTimestamp) / i;
            }
        }
        return samples;
    }

    private List<Traj.GNSS_Sample> parseGnssData(JSONArray gnssData) throws JSONException {
        List<Traj.GNSS_Sample> samples = new ArrayList<>();
        for (int i = 0; i < gnssData.length(); i++) {
            JSONObject gnssSampleJson = gnssData.getJSONObject(i);
            Traj.GNSS_Sample gnssSample =
                    Traj.GNSS_Sample.newBuilder()
                            .setLatitude((float) gnssSampleJson.getDouble("latitude"))
                            .setLongitude((float) gnssSampleJson.getDouble("longitude"))
                            .setAltitude((float) gnssSampleJson.getDouble("altitude"))
                            .setRelativeTimestamp(
                                    gnssSampleJson.getLong("relativeTimestamp")) // Load timestamp
                            .build();
            samples.add(gnssSample);

            if (i == 0) {
                firstGnssPoint = new LatLng(gnssSample.getLatitude(), gnssSample.getLongitude());
                firstGnssTimestamp = gnssSample.getRelativeTimestamp();
            }
        }
        return samples;
    }

    private List<Traj.Pressure_Sample> parsePressureData(JSONArray pressureData) throws JSONException {
        List<Traj.Pressure_Sample> samples = new ArrayList<>();
        for (int i = 0; i < pressureData.length(); i++) {
            JSONObject pressureSampleJson = pressureData.getJSONObject(i);
            Traj.Pressure_Sample pressureSample =
                    Traj.Pressure_Sample.newBuilder()
                            .setPressure((float) pressureSampleJson.getDouble("pressure"))
                            .setRelativeTimestamp(pressureSampleJson.getLong("relativeTimestamp"))
                            .build();
            samples.add(pressureSample);
        }
        return samples;
    }

    private List<Traj.Motion_Sample> parseMotionData(JSONArray imuData) throws JSONException {
        List<Traj.Motion_Sample> samples = new ArrayList<>();
        for (int i = 0; i < imuData.length(); i++) {
            JSONObject motionSampleJson = imuData.getJSONObject(i);
            Traj.Motion_Sample motionSample =
                    Traj.Motion_Sample.newBuilder()
                            .setRotationVectorX((float) motionSampleJson.getDouble("rotationVectorX"))
                            .setRotationVectorY((float) motionSampleJson.getDouble("rotationVectorY"))
                            .setRotationVectorZ((float) motionSampleJson.getDouble("rotationVectorZ"))
                            .setRotationVectorW((float) motionSampleJson.getDouble("rotationVectorW"))
                            .setRelativeTimestamp(motionSampleJson.getLong("relativeTimestamp"))
                            .build();
            samples.add(motionSample);
        }
        return samples;
    }

    /**
     * Initializes map elements (markers, camera position) and UI state after data is loaded. This
     * should be called *after* the trajectory data has been successfully loaded.
     */
    private void initializeMapElements() {
        orientationMarker =
                gMap.addMarker(
                        new MarkerOptions()
                                .position(firstGnssPoint)
                                .title("Current Position")
                                .flat(true)
                                .icon(
                                        BitmapDescriptorFactory.fromBitmap(
                                                UtilFunctions.getBitmapFromVector(
                                                        getContext(), R.drawable.ic_baseline_navigation_24))));

        initialElevation =
                SensorManager.getAltitude(
                        SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureReplaySamples.get(0).getPressure());
        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(firstGnssPoint, 19));

        seekBar.setMax(pdrReplaySamples.size());
        seekBar.setProgress(0);
    }

    /** Starts the replay animation. */
    private void startReplay() {
        if (pdrReplaySamples != null && !pdrReplaySamples.isEmpty()) {
            isReplayFinished = false;
            if (!isReplayRunning) {
                return;
            }
            replayDataHandler.postDelayed(replayTask, replayDelayMs);
        }
    }

    /** Pauses the replay animation. */
    private void pauseReplay() {
        replayDataHandler.removeCallbacks(replayTask);
    }

    /** Toggles between playing and pausing the replay. */
    private void toggleReplay() {
        if (isReplayFinished) {
            restartReplay();
        } else {
            isReplayRunning = !isReplayRunning;
            if (isReplayRunning) {
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                startReplay();
            } else {
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
                pauseReplay();
            }
        }
    }

    /** Moves the replay to the end of the trajectory. */
    private void goToEnd() {
        if (pdrReplaySamples == null || pdrReplaySamples.isEmpty()) {
            return;
        }

        isReplayRunning = false;
        replayDataHandler.removeCallbacks(replayTask);
        seekTo(pdrReplaySamples.size() - 1);
        seekBar.setProgress(seekBar.getMax());
        isReplayFinished = true;
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
    }

    /** Restarts the replay from the beginning. */
    private void restartReplay() {
        if (pdrReplaySamples == null || pdrReplaySamples.isEmpty()) {
            return;
        }

        isReplayRunning = false;
        replayDataHandler.removeCallbacks(replayTask);

        // Reset polylines
        clearAllPolylines();

        pdrIndex = 0;
        seekBar.setProgress(0);

        for (Marker marker : markerList) {
            marker.remove();
        }
        markerList.clear();

        isReplayFinished = false;
        isReplayRunning = true;
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
        startReplay();
    }

    /** The main replay task, executed periodically to update the map and UI. */
    private final Runnable replayTask =
            new Runnable() {
                @Override
                public void run() {
                    if (!isReplayRunning || pdrIndex >= pdrReplaySamples.size()) {
                        isReplayRunning = false;
                        isReplayFinished = true;
                        btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
                        return;
                    }

                    seekBar.setProgress(pdrIndex);

                    Traj.Pdr_Sample currentPdrSample = pdrReplaySamples.get(pdrIndex);
                    nextLocation =
                            UtilFunctions.calculateNewPos(
                                    firstGnssPoint, new float[] {currentPdrSample.getX(), currentPdrSample.getY()});

                    updatePolylineZIndexes();
                    plotGnss(currentPdrSample.getRelativeTimestamp());
                    plotLines(nextLocation);
                    updateUIandPosition();
                    pdrIndex++;

                    replayDataHandler.postDelayed(this, replayDelayMs);
                }
            };

    /**
     * Seeks to a specific point in the replay trajectory.
     *
     * @param progress The index of the PDR sample to seek to.
     */
    private void seekTo(int progress) {
        pdrIndex = progress;
        seekBar.setProgress(progress);
        clearAllPolylines();


        List<LatLng> trajectoryPointsLG = new ArrayList<>();
        List<LatLng> trajectoryPointsG = new ArrayList<>();
        List<LatLng> trajectoryPoints1 = new ArrayList<>();
        List<LatLng> trajectoryPoints2 = new ArrayList<>();
        List<LatLng> trajectoryPoints3 = new ArrayList<>();
        trajectoryPointsG.add(firstGnssPoint);

        for (int i = 0; i <= progress; i++) {
            LatLng point =
                    UtilFunctions.calculateNewPos(
                            firstGnssPoint,
                            new float[] {pdrReplaySamples.get(i).getX(), pdrReplaySamples.get(i).getY()});
            float elevation = getElevation(pdrReplaySamples.get(i).getRelativeTimestamp());

            if (elevation < (indoorMapManager.getFloorHeight() * -0.5)) {
                trajectoryPointsLG.add(point);
            } else if (elevation < indoorMapManager.getFloorHeight()) {
                trajectoryPointsG.add(point);
            } else if (elevation < (indoorMapManager.getFloorHeight() * 2)) {
                trajectoryPoints1.add(point);
            } else if (elevation < (indoorMapManager.getFloorHeight() * 3)) {
                trajectoryPoints2.add(point);
            } else if (elevation < (indoorMapManager.getFloorHeight() * 4)) {
                trajectoryPoints3.add(point);
            }
        }
        setPointsPolylines(trajectoryPointsLG,trajectoryPointsG,trajectoryPoints1,trajectoryPoints2,trajectoryPoints3);

        Traj.Pdr_Sample currentPdrSample = pdrReplaySamples.get(pdrIndex);

        nextLocation = UtilFunctions.calculateNewPos(firstGnssPoint,
                new float[]{currentPdrSample.getX(), currentPdrSample.getY()});

        orientationMarker.setPosition(nextLocation);
        plotGnss(currentPdrSample.getRelativeTimestamp());
        updateUIandPosition();
    }

    /**
     * Plots the PDR trajectory lines on the map, handling floor changes.
     *
     * @param nextLocation The next location to plot.
     */
    private void plotLines(LatLng nextLocation) {
        float elevation = getElevation(pdrReplaySamples.get(pdrIndex).getRelativeTimestamp());
        float currFloorHeight = indoorMapManager.getFloorHeight();
        orientationMarker.setPosition(nextLocation);

        addPointPolylines(nextLocation, elevation, currFloorHeight);

    }

    /**
     * Plots the GNSS marker on the map at the corresponding time.
     *
     * @param pdrTimestamp The relative timestamp of the current PDR sample.
     */
    private void plotGnss(long pdrTimestamp) {
        long absolutePdrTimestamp = pdrTimestamp - firstPdrTimestamp;
        LatLng currGNSSLocation = new LatLng(0, 0);

        // Clear previous GNSS markers
        for (Marker marker : markerList) {
            marker.remove();
        }
        markerList.clear();

        // Find the closest GNSS sample
        for (Traj.GNSS_Sample gnssSample : gnssReplayPoints) {
            if (gnssSample.getRelativeTimestamp() >= absolutePdrTimestamp) {
                break;
            }
            currGNSSLocation = new LatLng(gnssSample.getLatitude(), gnssSample.getLongitude());
        }

        // Add GNSS marker if switch is enabled
        if (gnssSwitch.isChecked()) {
            gnssMarker =
                    gMap.addMarker(
                            new MarkerOptions()
                                    .title("Replayed GNSS position")
                                    .position(currGNSSLocation)
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
            markerList.add(gnssMarker);

            // Display GNSS error if marker exists
            if (gnssMarker != null) {
                gnssError.setVisibility(View.VISIBLE);
                gnssError.setText(
                        String.format(
                                getString(R.string.gnss_error) + "%.2fm",
                                UtilFunctions.distanceBetweenPoints(nextLocation, currGNSSLocation)));
            }
        }
    }

    /**
     * Shows a popup menu for selecting playback speed.
     *
     * @param v The view that triggers the menu.
     */
    private void showPlaybackSpeedMenu(View v) {
        Context wrapper = new ContextThemeWrapper(getContext(), R.style.CustomPopupMenu);
        PopupMenu popupMenu = new PopupMenu(wrapper, v);
        popupMenu.getMenuInflater().inflate(R.menu.playback_speed_menu, popupMenu.getMenu());

        // Center-align menu item text
        Menu menu = popupMenu.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            SpannableString spanString = new SpannableString(item.getTitle());
            spanString.setSpan(
                    new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), 0, spanString.length(), 0);
            item.setTitle(spanString);
        }

        popupMenu.setOnMenuItemClickListener(
                item -> {
                    int itemId = item.getItemId();
                    if (itemId == R.id.speed_5x) {
                        replayDelayMs = avgSpeed / 5;
                    } else if (itemId == R.id.speed_2x) {
                        replayDelayMs = avgSpeed / 2;
                    } else if (itemId == R.id.speed_1x) {
                        replayDelayMs = avgSpeed;
                    } else if (itemId == R.id.speed_0_5x) {
                        replayDelayMs = avgSpeed * 2;
                    } else {
                        return false;
                    }
                    return true;
                });

        popupMenu.show();
    }

    /** Updates the UI elements and map camera position. */
    private void updateUIandPosition() {
        // Calculate distance travelled
        float[] pdrValues =
                new float[] {pdrReplaySamples.get(pdrIndex).getX(), pdrReplaySamples.get(pdrIndex).getY()};
        distance +=
                Math.sqrt(
                        Math.pow(pdrValues[0] - previousPosX, 2) + Math.pow(pdrValues[1] - previousPosY, 2));
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));

        // Update current location for indoor map
        indoorMapManager.setCurrentLocation(nextLocation);

        float elevationVal = getElevation(pdrReplaySamples.get(pdrIndex).getRelativeTimestamp());

        // Handle floor button visibility and auto-floor
        if (indoorMapManager.getIsIndoorMapSet()) {
            setFloorButtonVisibility(View.VISIBLE);
            if (replayAutoFloor.isChecked()) {
                indoorMapManager.setCurrentFloor(getCurrentFloor(elevationVal), true);
            }
        } else {
            setFloorButtonVisibility(View.GONE);
        }

        // Update previous PDR values
        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];

        // Display elevation and update orientation marker
        elevation.setText(getString(R.string.elevation, String.format("%.1f", elevationVal)));
        if (orientationMarker != null) {
            orientationMarker.setRotation(getOrientation(pdrReplaySamples.get(pdrIndex).getRelativeTimestamp()));
        }
    }

    /**
     * Sets the visibility of the floor change buttons and auto-floor switch.
     *
     * @param visibility The desired visibility (View.VISIBLE, View.GONE, etc.).
     */
    private void setFloorButtonVisibility(int visibility) {
        replayFloorUpButton.setVisibility(visibility);
        replayFloorDownButton.setVisibility(visibility);
        replayAutoFloor.setVisibility(visibility);
    }

    /**
     * Calculates the elevation based on the pressure data.
     *
     * @param pdrTimestamp The relative timestamp of the current PDR sample.
     * @return The calculated elevation.
     */
    private float getElevation(long pdrTimestamp) {
        long absolutePdrTimestamp = pdrTimestamp - firstPdrTimestamp;
        float currPressure = 0;

        for (Traj.Pressure_Sample pressureSample : pressureReplaySamples) {
            if (pressureSample.getRelativeTimestamp() >= absolutePdrTimestamp) {
                break;
            }
            currPressure = pressureSample.getPressure();
        }

        return SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, currPressure)
                - initialElevation;
    }

    /**
     * Calculates the orientation based on the rotation vector data.
     *
     * @param pdrTimestamp The relative timestamp of the current PDR sample.
     * @return The calculated orientation in degrees.
     */
    private float getOrientation(long pdrTimestamp) {
        long absolutePdrTimestamp = pdrTimestamp - firstPdrTimestamp;
        Traj.Motion_Sample currRotation = null;

        for (Traj.Motion_Sample rotationSample : rotationReplaySamples) {
            if (rotationSample.getRelativeTimestamp() >= absolutePdrTimestamp) {
                break;
            }
            currRotation = rotationSample;
        }

        if (currRotation != null) {
            float[] rotation = new float[4];
            float[] orientation = new float[3];
            float[] rotationVectorDCM = new float[9];

            rotation[0] = currRotation.getRotationVectorX();
            rotation[1] = currRotation.getRotationVectorY();
            rotation[2] = currRotation.getRotationVectorZ();
            rotation[3] = currRotation.getRotationVectorW();

            SensorManager.getRotationMatrixFromVector(rotationVectorDCM, rotation);
            float[] direction = SensorManager.getOrientation(rotationVectorDCM, orientation);
            return (float) Math.toDegrees(direction[0]);
        }
        return 0; // Default orientation
    }

    /**
     * Determines the current floor based on the elevation.
     *
     * @param elevation The current elevation.
     * @return The floor number (-1, 0, 1, 2, 3).
     */
    private int getCurrentFloor(float elevation) {
        float currFloorHeight = indoorMapManager.getFloorHeight();

        if (currFloorHeight != 0) {
            // Determine floor based on elevation ranges
            if ((elevation > (currFloorHeight * -10)) && (elevation < currFloorHeight * -0.5)) {
                return -1;
            } else if ((elevation > currFloorHeight) && (elevation < currFloorHeight * 2)) {
                return 1;
            } else if ((elevation > (currFloorHeight * 2)) && (elevation < currFloorHeight * 3)) {
                return 2;
            } else if ((elevation > (currFloorHeight * 3)) && (elevation < currFloorHeight * 4)) {
                return 3;
            } else {
                return 0; // Default to ground floor if within [-.5h, h]
            }
        } else {
            return 0; // Default to ground floor if outside building
        }
    }

    @Override
    public void onPause() {
        replayDataHandler.removeCallbacks(replayTask);
        super.onPause();
    }

    public void initPolylines(){
        polylineLG = gMap.addPolyline(new PolylineOptions().color(Color.RED));
        polylineG = gMap.addPolyline(new PolylineOptions().color(Color.RED));
        polyline1 = gMap.addPolyline(new PolylineOptions().color(Color.RED));
        polyline2 = gMap.addPolyline(new PolylineOptions().color(Color.RED));
        polyline3 = gMap.addPolyline(new PolylineOptions().color(Color.RED));
    }

    /** Updates the Z-indexes of the polylines for each floor based on the floor height of the
     * current building. */
    public void updatePolylineZIndexes() {
        float currFloorHeight = indoorMapManager.getFloorHeight();
        polylineLG.setZIndex((currFloorHeight * -1) + 1);
        polylineG.setZIndex((currFloorHeight * 0) + 1);
        polyline1.setZIndex((currFloorHeight * 1) + 1);
        polyline2.setZIndex((currFloorHeight * 2) + 1);
        polyline3.setZIndex((currFloorHeight * 3) + 1);
    }

    /** Clears all polylines. */
    public void clearAllPolylines() {
        polylineLG.setPoints(new ArrayList<>());
        polylineG.setPoints(new ArrayList<>());
        polyline1.setPoints(new ArrayList<>());
        polyline2.setPoints(new ArrayList<>());
        polyline3.setPoints(new ArrayList<>());
    }

    /**
     * Adds a point to the appropriate polyline based on elevation.
     *
     * @param location The location to add.
     * @param elevation The elevation at that location.
     * @param currFloorHeight The height of a single floor according to {@link IndoorMapManager}.
     */
    public void addPointPolylines(LatLng location, float elevation, float currFloorHeight) {
        List<LatLng> pointsMoved;
        if (currFloorHeight != 0) {
            if ((elevation > (currFloorHeight * -10)) && (elevation < currFloorHeight * -0.5)) {
                pointsMoved = polylineLG.getPoints();
                pointsMoved.add(location);
                polylineLG.setPoints(pointsMoved);
            } else if ((elevation > currFloorHeight) && (elevation < currFloorHeight * 2)) {
                pointsMoved = polyline1.getPoints();
                pointsMoved.add(location);
                polyline1.setPoints(pointsMoved);
            } else if ((elevation > (currFloorHeight * 2)) && (elevation < (currFloorHeight * 3))) {
                pointsMoved = polyline2.getPoints();
                pointsMoved.add(location);
                polyline2.setPoints(pointsMoved);
            } else if ((elevation > (currFloorHeight * 3)) && (elevation < (currFloorHeight * 4))) {
                pointsMoved = polyline3.getPoints();
                pointsMoved.add(location);
                polyline3.setPoints(pointsMoved);
            } else {
                // Default to ground floor
                pointsMoved = polylineG.getPoints();
                pointsMoved.add(location);
                polylineG.setPoints(pointsMoved);
            }
        } else {
            // If you're outside the building, default to ground floor
            pointsMoved = polylineG.getPoints();
            pointsMoved.add(location);
            polylineG.setPoints(pointsMoved);
        }
    }

    /**
     * Sets the points for all polylines. Used for seeking.
     * @param lgPoints Points on the Lower ground floor.
     * @param gPoints Points on the Ground floor.
     * @param onePoints Points on the 1st floor.
     * @param twoPoints Points on the 2nd floor.
     * @param threePoints Points on the 3rd floor.
     */
    public void setPointsPolylines(List<LatLng> lgPoints, List<LatLng> gPoints, List<LatLng> onePoints, List<LatLng> twoPoints, List<LatLng> threePoints){
        polylineLG.setPoints(lgPoints);
        polylineG.setPoints(gPoints);
        polyline1.setPoints(onePoints);
        polyline2.setPoints(twoPoints);
        polyline3.setPoints(threePoints);
    }


    @Override
    public void onDestroy() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        super.onDestroy();
    }

}