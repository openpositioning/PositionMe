package com.openpositioning.PositionMe.presentation.fragment;

import static com.openpositioning.PositionMe.utils.UtilConstants.POSITION_UOE_LAT;
import static com.openpositioning.PositionMe.utils.UtilConstants.POSITION_UOE_LON;
import static com.openpositioning.PositionMe.utils.UtilConstants.ZOOM_LEVEL_DEFAULT;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
import androidx.preference.PreferenceManager;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;

/**
 * A simple {@link Fragment} subclass. The startLocation fragment is displayed before the trajectory
 * recording starts. This fragment displays a map in which the user can adjust their location to
 * correct the PDR when it is complete
 *
 * @author Virginia Cangelosi
 * @see HomeFragment the previous fragment in the nav graph.
 * @see RecordingFragment the next fragment in the nav graph.
 * @see SensorFusion the class containing sensors and recording.
 */
public class StartLocationFragment extends Fragment {
    private static final String TAG = "StartLocationFragment";

    // Button to go to next fragment and save the location
    private Button buttonStart;
    // Singleton SensorFusion class which stores data from all sensors
    private SensorFusion sensorFusion;
    private boolean positionFound;
    private LatLng positionWiFi;
    private float[] positionGNSS;

    // Google maps LatLng object to pass location to the map
    private LatLng position;
    // Start position of the user to be stored
    private float[] startPosition = new float[2];
    private TextView startLocationHeader;
    private GoogleMap gMap;

    private Marker startMarker;
    private SharedPreferences settings;
    boolean debugEnabled;

    /** Public Constructor for the class. Left empty as not required */
    public StartLocationFragment() {
        // Required empty public constructor
    }

    /**
     * {@inheritDoc} The map is loaded and configured so that it displays a draggable marker for the
     * start location
     */
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null && activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().hide();
        }
        View rootView = inflater.inflate(R.layout.fragment_startlocation, container, false);

        startLocationHeader = rootView.findViewById(R.id.startLocationHeader);
        sensorFusion = SensorFusion.getInstance();
        positionFound = false;
        settings = PreferenceManager.getDefaultSharedPreferences(requireContext());
        debugEnabled = settings.getBoolean("debug_mode", false);

        // Initialize map fragment
        SupportMapFragment supportMapFragment =
                (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.startMap);

        supportMapFragment.getMapAsync(
                new OnMapReadyCallback() {
                    /**
                     * {@inheritDoc} Controls to allow scrolling, tilting, rotating and a compass
                     * view of the map are enabled.
                     *
                     * <p>A marker is added to the map with the start position, and a marker drag
                     * listener is generated to detect when the marker has moved to obtain the new
                     * location.
                     */
                    @Override
                    public void onMapReady(GoogleMap mMap) {
                        // Set map type and UI settings
                        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        mMap.getUiSettings().setCompassEnabled(true);
                        mMap.getUiSettings().setTiltGesturesEnabled(true);
                        mMap.getUiSettings().setRotateGesturesEnabled(true);
                        mMap.getUiSettings().setScrollGesturesEnabled(true);

                        // Save for future reference
                        gMap = mMap;
                        positionFound = checkForPosition();
                    }
                });
        return rootView;
    }

    /**
     * {@inheritDoc} Button onClick listener enabled to detect when to go to next fragment and start
     * PDR recording.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        buttonStart = view.findViewById(R.id.startLocationDone);
        buttonStart.setOnClickListener(
                new View.OnClickListener() {
                    /**
                     * {@inheritDoc}
                     *
                     * <p>Start a recording if the position is available, or rescan for positions if
                     * not
                     */
                    @Override
                    public void onClick(View view) {
                        if (positionFound) {
                            startRecording();
                        } else {
                            positionFound = checkForPosition();
                        }
                    }
                });
    }

    /**
     * Check {@link SensorFusion} for any available Wi-Fi and GNSS locations, prioritising Wi-Fi,
     * and updates the UI elements as required.
     *
     * @return True if a location was found; false otherwise
     */
    private boolean checkForPosition() {
        if (gMap == null) {
            setUINoLocation();
            return false;
        }
        positionWiFi = sensorFusion.getLatLngWifiPositioning();
        if (positionWiFi != null) {
            createStartMarker(positionWiFi);
            setUIFoundLocation("Wi-Fi");
            return true;
        }

        positionGNSS = sensorFusion.getGNSSLatitude(false);
        if (positionGNSS[0] != 0 && positionGNSS[1] != 0) {
            LatLng position = new LatLng(positionGNSS[0], positionGNSS[1]);
            createStartMarker(position);
            setUIFoundLocation("GNSS");
            return true;
        }

        new Handler(Looper.getMainLooper())
                .post(
                        () ->
                                Toast.makeText(
                                                getContext(),
                                                "No location found",
                                                Toast.LENGTH_SHORT)
                                        .show());
        setUINoLocation();
        return false;
    }

    /**
     * Update the UI to display that a location has been found
     *
     * @param type The type (Wi-Fi, or GNSS) of location found
     */
    private void setUIFoundLocation(String type) {
        startLocationHeader.setText(
                "Start location found (" + type + ")\nPress \"Start\" to begin your recording");
        buttonStart.setText("Start");
    }

    /**
     * Update the UI to state that no location is available, and zoom the map to a default location
     */
    private void setUINoLocation() {
        if (gMap != null) {
            // Clear any existing markers so the start marker isn’t duplicated
            gMap.clear();

            // Add a marker at the current GPS location and move the camera
            position = new LatLng(POSITION_UOE_LAT, POSITION_UOE_LON);
            gMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 16.5f));
        }

        startLocationHeader.setText("Waiting for starting location");
        buttonStart.setText("Scan for Location");
    }

    /** Pass control over to the {@link RecordingFragment} to being a recording */
    private void startRecording() {
        float markerLat = (float) startMarker.getPosition().latitude;
        float markerLon = (float) startMarker.getPosition().longitude;
        startPosition = new float[] {markerLat, markerLon};

        // If the Activity is RecordingActivity
        if (requireActivity() instanceof RecordingActivity) {
            // Start sensor recording + set the start location
            sensorFusion.setStartLocation(startPosition);
            sensorFusion.startRecording();

            // Now switch to the recording screen
            ((RecordingActivity) requireActivity()).showRecordingScreen();

            // Otherwise (unexpected host)
        } else {
            // Optional: log or handle error
            Log.w(TAG, "Unknown host Activity: " + requireActivity());
        }
    }

    /**
     * Draw a marker showing the start position on the map
     *
     * @param position The location the marker should be drawn to
     */
    private void createStartMarker(LatLng position) {
        if (startMarker != null) {
            startMarker.remove();
            startMarker = null;
        }
        startMarker =
                gMap.addMarker(
                        new MarkerOptions()
                                .position(position)
                                .draggable(debugEnabled)
                                .title("Start Position"));
        gMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, ZOOM_LEVEL_DEFAULT));
    }
}
