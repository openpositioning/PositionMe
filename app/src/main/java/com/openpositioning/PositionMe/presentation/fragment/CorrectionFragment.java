package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.PathView;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;

import android.graphics.Color;
import java.util.List;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A simple {@link Fragment} subclass. Corrections Fragment is displayed after a recording session
 * is finished to enable manual adjustments to the PDR. The adjustments are not saved as of now.
 */
public class CorrectionFragment extends Fragment {

    //Map variable
    public GoogleMap mMap;
    //Button to go to next
    private Button button;
    //Singleton SensorFusion class
    private SensorFusion sensorFusion = SensorFusion.getInstance();
    private TextView averageStepLengthText;
    private EditText stepLengthInput;
    private float averageStepLength;
    private float newStepLength;
    private int secondPass = 0;
    private CharSequence changedText;
    private static float scalingRatio = 0f;
    private static LatLng start;
    private PathView pathView;

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
        View rootView = inflater.inflate(R.layout.fragment_correction, container, false);

        // Send trajectory data to the cloud
        sensorFusion.sendTrajectoryToCloud();

        //Obtain start position
        float[] startPosition = sensorFusion.getGNSSLatitude(true);

        // Initialize map fragment
        SupportMapFragment supportMapFragment=(SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.map);

        supportMapFragment.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(GoogleMap map) {
                mMap = map;
                mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                mMap.getUiSettings().setCompassEnabled(true);
                mMap.getUiSettings().setTiltGesturesEnabled(true);
                mMap.getUiSettings().setRotateGesturesEnabled(true);
                mMap.getUiSettings().setScrollGesturesEnabled(true);
                mMap.getUiSettings().setZoomGesturesEnabled(true);

                // Custom InfoWindow to support multi-line snippets
                mMap.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {
                    @Override
                    public android.view.View getInfoWindow(com.google.android.gms.maps.model.Marker marker) { return null; }
                    @Override
                    public android.view.View getInfoContents(com.google.android.gms.maps.model.Marker marker) {
                        android.widget.LinearLayout layout = new android.widget.LinearLayout(requireContext());
                        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
                        android.widget.TextView title = new android.widget.TextView(requireContext());
                        title.setText(marker.getTitle());
                        title.setTypeface(null, android.graphics.Typeface.BOLD);
                        title.setTextSize(14f);
                        layout.addView(title);
                        if (marker.getSnippet() != null) {
                            android.widget.TextView snippet = new android.widget.TextView(requireContext());
                            snippet.setText(marker.getSnippet());
                            snippet.setTextSize(12f);
                            layout.addView(snippet);
                        }
                        return layout;
                    }
                });

                // Time formatter for London timezone
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", Locale.UK);
                sdf.setTimeZone(TimeZone.getTimeZone("Europe/London"));

                // Floor info for Start/End (if indoor recording had floor data)
                String floorLabel = sensorFusion.getEstimatedFloorLabel();
                String floorStr = (floorLabel != null) ? "Floor: " + floorLabel + "\n" : "";

                // Draw trajectory as a Polyline on the map
                List<double[]> trajectoryPoints = sensorFusion.getTrajectoryLatLngs();
                LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder();
                boolean hasPoints = false;

                if (trajectoryPoints != null && !trajectoryPoints.isEmpty()) {
                    PolylineOptions polylineOptions = new PolylineOptions()
                            .color(Color.BLUE)
                            .width(6f);
                    for (double[] pt : trajectoryPoints) {
                        LatLng ll = new LatLng(pt[0], pt[1]);
                        polylineOptions.add(ll);
                        boundsBuilder.include(ll);
                        hasPoints = true;
                    }
                    mMap.addPolyline(polylineOptions);

                    // Start marker with floor/time/position details
                    double[] first = trajectoryPoints.get(0);
                    String startTime = sdf.format(new Date(sensorFusion.getAbsoluteStartTime()));
                    mMap.addMarker(new MarkerOptions()
                            .position(new LatLng(first[0], first[1]))
                            .title("Start Position")
                            .snippet(floorStr + "Time: " + startTime
                                    + "\nLat: " + String.format(Locale.UK, "%.6f", first[0])
                                    + ", Lon: " + String.format(Locale.UK, "%.6f", first[1]))
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));

                    // End marker with floor/time/position details
                    double[] last = trajectoryPoints.get(trajectoryPoints.size() - 1);
                    long endTime = sensorFusion.getAbsoluteEndTime();
                    String endTimeStr = endTime > 0 ? sdf.format(new Date(endTime)) : "N/A";
                    mMap.addMarker(new MarkerOptions()
                            .position(new LatLng(last[0], last[1]))
                            .title("End Position")
                            .snippet(floorStr + "Time: " + endTimeStr
                                    + "\nLat: " + String.format(Locale.UK, "%.6f", last[0])
                                    + ", Lon: " + String.format(Locale.UK, "%.6f", last[1]))
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
                } else {
                    // Fallback: use GNSS start position
                    start = new LatLng(startPosition[0], startPosition[1]);
                    mMap.addMarker(new MarkerOptions().position(start).title("Start Position"));
                    boundsBuilder.include(start);
                    hasPoints = true;
                }

                // Draw test points with timestamps (offset overlapping markers slightly)
                List<SensorFusion.TestPoint> testPoints = sensorFusion.getTestPoints();
                List<LatLng> placedPositions = new java.util.ArrayList<>();
                for (int i = 0; i < testPoints.size(); i++) {
                    SensorFusion.TestPoint tp = testPoints.get(i);
                    LatLng tpPos = new LatLng(tp.latitude, tp.longitude);

                    tpPos = offsetIfOverlapping(tpPos, placedPositions);
                    placedPositions.add(tpPos);

                    String timeStr = sdf.format(new Date(tp.absoluteTimestamp));
                    String snippet = (tp.floor != null ? "Floor: " + tp.floor + "\n" : "")
                            + "Time: " + timeStr
                            + "\nLat: " + String.format(Locale.UK, "%.6f", tp.latitude)
                            + ", Lon: " + String.format(Locale.UK, "%.6f", tp.longitude);
                    mMap.addMarker(new MarkerOptions()
                            .position(tpPos)
                            .title("Test Point #" + (i + 1))
                            .snippet(snippet)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)));
                    boundsBuilder.include(tpPos);
                }

                // Zoom to fit all points
                if (hasPoints) {
                    try {
                        LatLngBounds bounds = boundsBuilder.build();
                        mMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100));
                    } catch (Exception e) {
                        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                                new LatLng(startPosition[0], startPosition[1]), 19f));
                    }
                }
            }
        });

        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.averageStepLengthText = view.findViewById(R.id.averageStepView);
        this.stepLengthInput = view.findViewById(R.id.inputStepLength);
        this.pathView = view.findViewById(R.id.pathView1);
        // Hide PathView - trajectory is now drawn as map Polyline
        if (pathView != null) pathView.setVisibility(View.GONE);

        averageStepLength = sensorFusion.passAverageStepLength();
        averageStepLengthText.setText(getString(R.string.averageStepLgn) + ": "
                + String.format("%.2f", averageStepLength));

        // Listen for ENTER key
        this.stepLengthInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                newStepLength = Float.parseFloat(changedText.toString());
                // Rescale path
                sensorFusion.redrawPath(newStepLength / averageStepLength);
                averageStepLengthText.setText(getString(R.string.averageStepLgn)
                        + ": " + String.format("%.2f", newStepLength));
                pathView.invalidate();

                secondPass++;
                if (secondPass == 2) {
                    averageStepLength = newStepLength;
                    secondPass = 0;
                }
            }
            return false;
        });

        this.stepLengthInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count,int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before,int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                changedText = s;
            }
        });

        // Button to finalize corrections
        this.button = view.findViewById(R.id.correction_done);
        this.button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // ************* CHANGED CODE HERE *************
                // Before:
                //   NavDirections action = CorrectionFragmentDirections.actionCorrectionFragmentToHomeFragment();
                //   Navigation.findNavController(view).navigate(action);
                //   ((AppCompatActivity)getActivity()).getSupportActionBar().show();

                // Now, simply tell the Activity we are done:
                ((RecordingActivity) requireActivity()).finishFlow();
            }
        });
    }

    public void setScalingRatio(float scalingRatio) {
        this.scalingRatio = scalingRatio;
    }

    /**
     * Offset a marker position if it overlaps with any already-placed position.
     * Uses a spiral offset pattern (~2m per step) to spread out close markers.
     */
    private LatLng offsetIfOverlapping(LatLng pos, List<LatLng> placed) {
        final double THRESHOLD = 0.00002; // ~2m at Edinburgh latitude
        final double OFFSET_STEP = 0.00003; // ~3m offset per ring
        LatLng candidate = pos;
        int ring = 1;
        while (isOverlapping(candidate, placed, THRESHOLD) && ring <= 20) {
            // Place in a circle around original position
            double angle = ring * 2.39996; // golden angle in radians for even distribution
            double offsetLat = OFFSET_STEP * ring * Math.cos(angle);
            double offsetLon = OFFSET_STEP * ring * Math.sin(angle);
            candidate = new LatLng(pos.latitude + offsetLat, pos.longitude + offsetLon);
            ring++;
        }
        return candidate;
    }

    private boolean isOverlapping(LatLng pos, List<LatLng> placed, double threshold) {
        for (LatLng p : placed) {
            if (Math.abs(pos.latitude - p.latitude) < threshold
                    && Math.abs(pos.longitude - p.longitude) < threshold) {
                return true;
            }
        }
        return false;
    }
}
