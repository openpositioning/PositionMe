package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.TrajectoryValidator;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;

/**
 * Lets the user review and manually rescale the recorded PDR trajectory after a session.
 *
 * <p>Corrections made in this screen are applied to the preview only and are not persisted.
 */
public class CorrectionFragment extends Fragment {

    /** Google Map used to preview the corrected trajectory. */
    public GoogleMap mMap;
    /** Advances to the next screen after reviewing the correction. */
    private Button button;
    /** Shared sensor fusion pipeline instance. */
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
        // Required empty public constructor.
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null && activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().hide();
        }
        View rootView = inflater.inflate(R.layout.fragment_correction, container, false);

        // Validate trajectory quality before uploading.
        validateAndUpload();

        // Obtain the start position.
        float[] startPosition = sensorFusion.getGNSSLatitude(true);

        // Initialize the embedded map fragment.
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

                // Add a marker at the start position.
                start = new LatLng(startPosition[0], startPosition[1]);
                mMap.addMarker(new MarkerOptions().position(start).title("Start Position"));

                // Calculate a zoom level that matches the rendered path scale.
                double zoom = Math.log(156543.03392f * Math.cos(startPosition[0] * Math.PI / 180)
                        * scalingRatio) / Math.log(2);
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(start, (float) zoom));
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

        averageStepLength = sensorFusion.passAverageStepLength();
        averageStepLengthText.setText(getString(R.string.averageStepLgn) + ": "
                + String.format("%.2f", averageStepLength));

        // Listen for the Enter key to apply a new step length.
        this.stepLengthInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                newStepLength = Float.parseFloat(changedText.toString());
                // Rescale the previewed path.
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
     * Runs pre-upload quality validation and either uploads directly (if clean)
     * or shows a warning dialog letting the user choose to proceed or cancel.
     */
    private void validateAndUpload() {
        TrajectoryValidator.ValidationResult result = sensorFusion.validateTrajectory();

        if (result.isClean()) {
            Log.i("CorrectionFragment", "Trajectory validation passed, uploading");
            sensorFusion.sendTrajectoryToCloud();
            return;
        }

        String summary = result.buildSummary();
        Log.w("CorrectionFragment", "Trajectory quality issues:\n" + summary);

        if (!result.isPassed()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.validation_error_title)
                    .setMessage(getString(R.string.validation_error_message, summary))
                    .setPositiveButton(R.string.upload_anyway, (dialog, which) -> {
                        sensorFusion.sendTrajectoryToCloud();
                    })
                    .setNegativeButton(R.string.cancel_upload, (dialog, which) -> {
                        dialog.dismiss();
                    })
                    .setCancelable(false)
                    .show();
        } else {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.validation_warning_title)
                    .setMessage(getString(R.string.validation_warning_message, summary))
                    .setPositiveButton(R.string.upload_anyway, (dialog, which) -> {
                        sensorFusion.sendTrajectoryToCloud();
                    })
                    .setNegativeButton(R.string.cancel_upload, (dialog, which) -> {
                        dialog.dismiss();
                    })
                    .setCancelable(false)
                    .show();
        }
    }
}
