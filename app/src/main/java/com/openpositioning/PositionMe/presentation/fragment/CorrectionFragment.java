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
import com.openpositioning.PositionMe.utils.TrajectoryOverlayView;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.button.MaterialButton;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.ArrayList;
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

    // Position correction mode variables
    private boolean positionCorrectionEnabled = false;
    private TrajectoryOverlayView trajectoryOverlay;
    private MaterialButton positionCorrectionToggle;
    private Polyline trajectoryPolyline;
    private List<Marker> trajectoryMarkers = new ArrayList<>();
    private LatLng originalCameraPosition;
    private double latitudeOffset = 0.0;
    private double longitudeOffset = 0.0;

    // Upload protection flag
    private boolean uploadInProgress = false;

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
                    trajectoryPolyline = mMap.addPolyline(polylineOptions);

                    // Start marker
                    double[] first = trajectoryPoints.get(0);
                    Marker startMarker = mMap.addMarker(new MarkerOptions()
                            .position(new LatLng(first[0], first[1]))
                            .title("Start Position")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
                    trajectoryMarkers.add(startMarker);

                    // End marker
                    double[] last = trajectoryPoints.get(trajectoryPoints.size() - 1);
                    Marker endMarker = mMap.addMarker(new MarkerOptions()
                            .position(new LatLng(last[0], last[1]))
                            .title("End Position")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
                    trajectoryMarkers.add(endMarker);
                } else {
                    // Fallback: use GNSS start position
                    start = new LatLng(startPosition[0], startPosition[1]);
                    mMap.addMarker(new MarkerOptions().position(start).title("Start Position"));
                    boundsBuilder.include(start);
                    hasPoints = true;
                }

                // Draw test points with timestamps
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", Locale.UK);
                sdf.setTimeZone(TimeZone.getTimeZone("Europe/London"));

                List<SensorFusion.TestPoint> testPoints = sensorFusion.getTestPoints();
                for (int i = 0; i < testPoints.size(); i++) {
                    SensorFusion.TestPoint tp = testPoints.get(i);
                    LatLng tpPos = new LatLng(tp.latitude, tp.longitude);
                    String timeStr = sdf.format(new Date(tp.absoluteTimestamp));
                    String snippet = (tp.floor != null ? "Floor: " + tp.floor + ", " : "")
                            + "Time: " + timeStr
                            + "\nLat: " + String.format(Locale.UK, "%.6f", tp.latitude)
                            + ", Lon: " + String.format(Locale.UK, "%.6f", tp.longitude);
                    Marker testPointMarker = mMap.addMarker(new MarkerOptions()
                            .position(tpPos)
                            .title("Test Point #" + (i + 1))
                            .snippet(snippet)
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)));
                    trajectoryMarkers.add(testPointMarker);  // Store test point markers too
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

                // Store original camera position for offset calculation
                originalCameraPosition = mMap.getCameraPosition().target;

                // Add camera movement listener for position correction mode
                mMap.setOnCameraIdleListener(new GoogleMap.OnCameraIdleListener() {
                    @Override
                    public void onCameraIdle() {
                        if (positionCorrectionEnabled) {
                            LatLng currentPosition = mMap.getCameraPosition().target;
                            latitudeOffset = currentPosition.latitude - originalCameraPosition.latitude;
                            longitudeOffset = currentPosition.longitude - originalCameraPosition.longitude;
                            Log.i("CorrectionFragment", "Camera moved. Offset: lat=" + latitudeOffset + ", lng=" + longitudeOffset);
                        }
                    }
                });
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

        // Initialize trajectory overlay and position correction toggle
        this.trajectoryOverlay = view.findViewById(R.id.trajectoryOverlay);
        this.positionCorrectionToggle = view.findViewById(R.id.position_correction_toggle);
        setupPositionCorrectionToggle();

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
                // Validate trajectory has minimum required data before allowing upload
                String validationError = sensorFusion.validateTrajectoryForUpload();
                if (validationError != null) {
                    // Show error dialog
                    new AlertDialog.Builder(requireActivity())
                            .setTitle("Cannot Upload Trajectory")
                            .setMessage(validationError)
                            .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .show();
                    return;
                }

                // Validation passed, show trajectory naming dialog
                showTrajectoryNameDialog();
            }
        });
    }

    public void setScalingRatio(float scalingRatio) {
        this.scalingRatio = scalingRatio;
    }

    /**
     * Set up the position correction toggle button
     */
    private void setupPositionCorrectionToggle() {
        if (positionCorrectionToggle == null) return;

        positionCorrectionToggle.setOnClickListener(v -> {
            positionCorrectionEnabled = !positionCorrectionEnabled;

            if (positionCorrectionEnabled) {
                enablePositionCorrectionMode();
            } else {
                disablePositionCorrectionMode();
            }
        });
    }

    /**
     * Enable position correction mode - trajectory fixed, map moveable
     */
    private void enablePositionCorrectionMode() {
        if (mMap == null) return;

        // Hide polyline and markers on map
        if (trajectoryPolyline != null) {
            trajectoryPolyline.setVisible(false);
        }
        for (Marker marker : trajectoryMarkers) {
            marker.setVisible(false);
        }

        // Show trajectory overlay with fixed position
        if (trajectoryOverlay != null) {
            List<double[]> trajectoryPoints = sensorFusion.getTrajectoryLatLngs();
            trajectoryOverlay.setTrajectoryPoints(trajectoryPoints);

            // Convert test points to overlay format
            List<SensorFusion.TestPoint> testPoints = sensorFusion.getTestPoints();
            List<TrajectoryOverlayView.TestPointData> overlayTestPoints = new ArrayList<>();
            for (SensorFusion.TestPoint tp : testPoints) {
                overlayTestPoints.add(new TrajectoryOverlayView.TestPointData(tp.latitude, tp.longitude));
            }
            trajectoryOverlay.setTestPoints(overlayTestPoints);
            trajectoryOverlay.setVisibility(View.VISIBLE);
        }

        // Update button appearance
        positionCorrectionToggle.setText("Exit Adjust Mode");
        positionCorrectionToggle.setIconResource(android.R.drawable.ic_menu_close_clear_cancel);

        Toast.makeText(requireContext(), "Position correction mode enabled. Drag the map to adjust trajectory position.", Toast.LENGTH_LONG).show();
    }

    /**
     * Disable position correction mode - back to normal view with offset preview
     */
    private void disablePositionCorrectionMode() {
        if (mMap == null) return;

        // Hide trajectory overlay
        if (trajectoryOverlay != null) {
            trajectoryOverlay.setVisibility(View.GONE);
        }

        // If there's an offset, redraw trajectory and markers at new positions
        if (latitudeOffset != 0.0 || longitudeOffset != 0.0) {
            // Remove old polyline
            if (trajectoryPolyline != null) {
                trajectoryPolyline.remove();
            }

            // Remove old markers
            for (Marker marker : trajectoryMarkers) {
                marker.remove();
            }
            trajectoryMarkers.clear();

            // Redraw trajectory with offset
            List<double[]> trajectoryPoints = sensorFusion.getTrajectoryLatLngs();
            if (trajectoryPoints != null && !trajectoryPoints.isEmpty()) {
                PolylineOptions polylineOptions = new PolylineOptions()
                        .color(Color.BLUE)
                        .width(6f);
                for (double[] pt : trajectoryPoints) {
                    LatLng ll = new LatLng(pt[0] + latitudeOffset, pt[1] + longitudeOffset);
                    polylineOptions.add(ll);
                }
                trajectoryPolyline = mMap.addPolyline(polylineOptions);

                // Redraw start marker with offset
                double[] first = trajectoryPoints.get(0);
                Marker startMarker = mMap.addMarker(new MarkerOptions()
                        .position(new LatLng(first[0] + latitudeOffset, first[1] + longitudeOffset))
                        .title("Start Position")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
                trajectoryMarkers.add(startMarker);

                // Redraw end marker with offset
                double[] last = trajectoryPoints.get(trajectoryPoints.size() - 1);
                Marker endMarker = mMap.addMarker(new MarkerOptions()
                        .position(new LatLng(last[0] + latitudeOffset, last[1] + longitudeOffset))
                        .title("End Position")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
                trajectoryMarkers.add(endMarker);
            }

            // Redraw test point markers with offset
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", Locale.UK);
            sdf.setTimeZone(TimeZone.getTimeZone("Europe/London"));
            List<SensorFusion.TestPoint> testPoints = sensorFusion.getTestPoints();
            for (int i = 0; i < testPoints.size(); i++) {
                SensorFusion.TestPoint tp = testPoints.get(i);
                LatLng tpPos = new LatLng(tp.latitude + latitudeOffset, tp.longitude + longitudeOffset);
                String timeStr = sdf.format(new Date(tp.absoluteTimestamp));
                String snippet = (tp.floor != null ? "Floor: " + tp.floor + ", " : "")
                        + "Time: " + timeStr
                        + "\nLat: " + String.format(Locale.UK, "%.6f", tp.latitude + latitudeOffset)
                        + ", Lon: " + String.format(Locale.UK, "%.6f", tp.longitude + longitudeOffset);
                Marker testPointMarker = mMap.addMarker(new MarkerOptions()
                        .position(tpPos)
                        .title("Test Point #" + (i + 1))
                        .snippet(snippet)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)));
                trajectoryMarkers.add(testPointMarker);
            }

            Toast.makeText(requireContext(),
                String.format("Preview: Δlat=%.6f, Δlng=%.6f", latitudeOffset, longitudeOffset),
                Toast.LENGTH_LONG).show();
        } else {
            // No offset, just show the original polyline and markers
            if (trajectoryPolyline != null) {
                trajectoryPolyline.setVisible(true);
            }
            for (Marker marker : trajectoryMarkers) {
                marker.setVisible(true);
            }
        }

        // Update button appearance
        positionCorrectionToggle.setText("Adjust Position");
        positionCorrectionToggle.setIconResource(R.drawable.ic_baseline_navigation_24);
    }

    /**
     * Check if a trajectory name already exists
     */
    private boolean isDuplicateName(String name) {
        try {
            // Get the app's file directory
            File filesDir = requireContext().getFilesDir();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                File extDir = requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS);
                if (extDir != null) {
                    filesDir = extDir;
                }
            }

            // Sanitize the name for filesystem compatibility
            String sanitizedName = name.replaceAll("[^a-zA-Z0-9._-]", "_") + ".pb";

            // Check if file exists
            File checkFile = new File(filesDir, sanitizedName);
            return checkFile.exists();
        } catch (Exception e) {
            Log.e("CorrectionFragment", "Error checking duplicate name: " + e.getMessage());
            return false;
        }
    }

    /**
     * Proceed with trajectory upload
     */
    private void proceedWithUpload(String trajectoryName) {
        Log.i("CorrectionFragment", "Starting upload with trajectory name: " + trajectoryName);

        // Store trajectory name in SensorFusion
        sensorFusion.setTrajectoryName(trajectoryName);

        // Apply position correction offset (if any)
        applyPositionCorrectionOffset();

        // Send trajectory data to the cloud
        sensorFusion.sendTrajectoryToCloud();

        // Finish the recording flow
        requireActivity().finish();
    }

    /**
     * Generate a smart default trajectory name by checking existing files
     * Suggests incremental names like: Traj_001, Traj_002, etc.
     */
    private String generateSmartDefaultName() {
        try {
            // Get the app's file directory
            File filesDir = requireContext().getFilesDir();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                File extDir = requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS);
                if (extDir != null) {
                    filesDir = extDir;
                }
            }

            // Find all .pb files
            File[] files = filesDir.listFiles((dir, name) -> name.endsWith(".pb"));
            if (files == null || files.length == 0) {
                return "Traj_001"; // First trajectory
            }

            // Extract numbers from existing trajectory names
            int maxNumber = 0;
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("Traj_(\\d+)");
            for (File file : files) {
                java.util.regex.Matcher matcher = pattern.matcher(file.getName());
                if (matcher.find()) {
                    try {
                        int number = Integer.parseInt(matcher.group(1));
                        maxNumber = Math.max(maxNumber, number);
                    } catch (NumberFormatException e) {
                        // Ignore files with invalid numbers
                    }
                }
            }

            // Suggest next number
            int nextNumber = maxNumber + 1;
            return String.format("Traj_%03d", nextNumber); // e.g., Traj_001, Traj_002
        } catch (Exception e) {
            Log.e("CorrectionFragment", "Error generating smart name: " + e.getMessage());
            // Fallback to timestamp-based name
            return "Traj_" + System.currentTimeMillis();
        }
    }

    /**
     * Apply position correction offset to trajectory before upload
     * This method should be called before sendTrajectoryToCloud()
     */
    private void applyPositionCorrectionOffset() {
        if (latitudeOffset == 0.0 && longitudeOffset == 0.0) {
            return; // No offset to apply
        }

        Log.i("CorrectionFragment", "Applying position offset: lat=" + latitudeOffset + ", lng=" + longitudeOffset);
        sensorFusion.applyTrajectoryOffset(latitudeOffset, longitudeOffset);

        Toast.makeText(requireContext(), "Position correction applied to trajectory", Toast.LENGTH_SHORT).show();
    }

    /**
     * Show dialog to name the trajectory before uploading
     */
    private void showTrajectoryNameDialog() {
        // Create input field for trajectory name
        EditText input = new EditText(requireContext());
        input.setHint("Enter trajectory name (e.g., GF01, Building_A)");

        // Generate smart default name based on recent recordings
        String defaultName = generateSmartDefaultName();
        input.setText(defaultName);
        input.selectAll(); // Select all text for easy replacement

        // Create dialog
        AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                .setTitle("Name Your Trajectory")
                .setMessage("Enter a name for this trajectory recording:")
                .setView(input)
                .setPositiveButton("Upload", (dialogInterface, which) -> {
                    // Prevent multiple uploads
                    if (uploadInProgress) {
                        Log.w("CorrectionFragment", "Upload already in progress, ignoring duplicate request");
                        Toast.makeText(requireContext(), "Upload already in progress", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Get trajectory name from input
                    String trajectoryName = input.getText().toString().trim();
                    if (trajectoryName.isEmpty()) {
                        trajectoryName = defaultName; // Use default if empty
                    }

                    // Check for duplicate names
                    final String finalTrajectoryName = trajectoryName;
                    if (isDuplicateName(finalTrajectoryName)) {
                        // Show warning dialog for duplicate name
                        new AlertDialog.Builder(requireActivity())
                                .setTitle("Duplicate Name Warning")
                                .setMessage("A trajectory with name '" + finalTrajectoryName + "' already exists.\n\nDo you want to continue with this name? It's recommended to use unique names.")
                                .setPositiveButton("Use Anyway", (d, w) -> {
                                    uploadInProgress = true;
                                    proceedWithUpload(finalTrajectoryName);
                                })
                                .setNegativeButton("Change Name", (d, w) -> {
                                    // Reopen the naming dialog
                                    showTrajectoryNameDialog();
                                })
                                .setIcon(android.R.drawable.ic_dialog_alert)
                                .show();
                        return;
                    }

                    uploadInProgress = true;
                    proceedWithUpload(finalTrajectoryName);
                })
                .setNegativeButton("Cancel", (dialogInterface, which) -> {
                    // User cancelled, do nothing
                    dialogInterface.dismiss();
                })
                .create();

        // Show keyboard automatically
        dialog.setOnShowListener(dialogInterface -> {
            input.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) requireActivity()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });

        dialog.show();
    }
}
