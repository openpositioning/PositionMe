package com.openpositioning.PositionMe.presentation.fragment;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.PathView;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;

import androidx.preference.PreferenceManager;

import static com.openpositioning.PositionMe.utils.UtilConstants.BUILDING_NAME_OUTSIDE;
import static com.openpositioning.PositionMe.utils.UtilConstants.BUILDING_NAME_M_HOUSE;
import static com.openpositioning.PositionMe.utils.UtilConstants.BUILDING_NAME_NUCLEUS;

import org.w3c.dom.Text;

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

    private Spinner campaignSelect;
    private String selectedCampaign;

    private TextView campaignText;


    private CardView cardView;

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

                // Add a marker at the start position
                start = new LatLng(startPosition[0], startPosition[1]);
                mMap.addMarker(new MarkerOptions().position(start).title("Start Position"));

                // Calculate zoom for demonstration
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

        this.campaignSelect = view.findViewById(R.id.campaignSelectSpinner);
        this.campaignText = view.findViewById(R.id.correctedCampaingTextView);
        this.cardView = view.findViewById(R.id.cardView2);

        // Call for user to select campaign if necessary
        initCampaignSelect();

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
                // Set campaign, and wait until upload is complete
                sensorFusion.setCurrentBuilding(selectedCampaign);
                boolean result = sensorFusion.sendTrajectoryToCloud();
                while(!result){}
                sensorFusion.setCurrentBuilding(BUILDING_NAME_OUTSIDE);

                // Now, simply tell the Activity we are done:
                ((RecordingActivity) requireActivity()).finishFlow();
            }
        });
    }

    public void setScalingRatio(float scalingRatio) {
        this.scalingRatio = scalingRatio;
    }

    private void initCampaignSelect() {
        if (campaignSelect == null) return;

        // Retrieve the current building from SensorFusion
        String currentBuilding = SensorFusion.getInstance().getCurrentBuilding();

        // Check if the building is "outside"
        if ("outside".equalsIgnoreCase(currentBuilding)) {

            // Initialize campaigns only if the building is "outside"
            String[] campaigns = new String[]{
                    "Murchison House",
                    "Nucleus Building"
            };
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    campaigns
            );
            campaignSelect.setAdapter(adapter);
            campaignSelect.setSelection(0);

            // Set item selected listener
            campaignSelect.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long id
                ) {
                    switch (position) {
                        case 0:
                            selectedCampaign = BUILDING_NAME_M_HOUSE;
                            break;
                        case 1:
                            selectedCampaign = BUILDING_NAME_NUCLEUS;
                            break;
                    }
                    // Log selection
                    Log.d("CorrectionFragment", "Selected campaign: " + selectedCampaign);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    selectedCampaign = BUILDING_NAME_OUTSIDE;
                }
            });
        } else {
            selectedCampaign = currentBuilding;
            // Hide the Spinner if not "outside"
            campaignSelect.setVisibility(View.GONE);
            cardView.setVisibility(View.GONE);
            campaignText.setVisibility(View.GONE);
        }
    }

}
