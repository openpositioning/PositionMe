package com.openpositioning.PositionMe.fragments;

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
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;

import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLngBounds;
import com.openpositioning.PositionMe.PathView;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;

/**
 * A simple {@link Fragment} subclass. Corrections Fragment is displayed after a recording session
 * is finished to enable manual adjustments to the PDR. The adjustments are not saved as of now.
 *
 * @see RecordingFragment the preceeding fragment in the nav graph.
 * @see HomeFragment the next fragment in the nav graph.
 *
 *
 * @author Michal Dvorak
 * @author Mate Stodulka
 * @author Virginia Cangelosi
 */
public class CorrectionFragment extends Fragment {

    //Map variable to assign to map fragment
    public GoogleMap mMap;
    //Button to go to next fragment and save the corrections
    private Button button;
    //Singleton SensorFusion class which stores data from all sensors
    private SensorFusion sensorFusion = SensorFusion.getInstance();
    //TextView to display user instructions
    private TextView averageStepLengthText;
    //Text Input to edit step length
    private EditText stepLengthInput;
    //Average step length obtained from SensorFusion class
    private float averageStepLength;
    //User entered step length
    private float newStepLength;
    //OnKey is called twice so ensure only the second run updates the previous value for the scaling
    private int secondPass = 0;
    //Raw text entered by user
    private CharSequence changedText;
    //Scaling ratio based on size of trajectory
    private static float scalingRatio = 0f;
    //Initial location of PDR
    private static LatLng start;
    //Path view on screen
    private PathView pathView;

    /**
     * Public Constructor for the class.
     * Left empty as not required
     */
    public CorrectionFragment() {
        // Required empty public constructor
    }

    /**
     * {@inheritDoc}
     * Loads the starting position set in {@link StartLocationFragment}, and displays a map fragment.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_correction, container, false);

        // Inflate the layout for this fragment
        ((AppCompatActivity)getActivity()).getSupportActionBar().hide();

        //Send trajectory data to the cloud
        sensorFusion.sendTrajectoryToCloud();

        //Obtain start position set in the startLocation fragment
        float[] startPosition = sensorFusion.getGNSSLatitude(true);

        // Initialize map fragment
        SupportMapFragment supportMapFragment=(SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.map);

        // Asynchronous map which can be configured
        supportMapFragment.getMapAsync(new OnMapReadyCallback() {
            /**
             * {@inheritDoc}
             * Controls to allow scrolling, tilting, rotating and a compass view of the
             * map are enabled. A marker is added to the map with the start position and the PDR
             * trajectory is scaled before being overlaid over the map fragment in
             * CorrectionFragment.onViewCreated.
             *
             * @param map      Google map to be configured
             */
            @Override
            public void onMapReady(GoogleMap map) {
                mMap = map;
                mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                mMap.getUiSettings().setCompassEnabled(true);
                mMap.getUiSettings().setTiltGesturesEnabled(true);
                mMap.getUiSettings().setRotateGesturesEnabled(true);
                mMap.getUiSettings().setScrollGesturesEnabled(true);

                // Add a marker at the start position and move the camera
                start = new LatLng(startPosition[0], startPosition[1]);
                mMap.addMarker(new MarkerOptions().position(start).title("Start Position"));
                System.out.println("onMapReady scaling ratio: " + scalingRatio);
                // Calculate zoom of google maps based on the scaling ration from PathView
                double zoom = Math.log(156543.03392f * Math.cos(startPosition[0] * Math.PI / 180)
                        * scalingRatio) / Math.log(2);
                System.out.println("onMapReady zoom: " + zoom);
                //Center the camera
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(start, (float) zoom));

            }
        });

        return rootView;
    }

    /**
     * {@inheritDoc}.
     * Button onClick listener enabled to detect when to go to next fragment and show the action bar.
     * Load and display average step length from PDR.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        //Instantiate text view to show average step length
        this.averageStepLengthText = (TextView) getView().findViewById(R.id.averageStepView);
        //Instantiate input text view to edit average step length
        this.stepLengthInput = (EditText) getView().findViewById(R.id.inputStepLength);
        //Instantiate path view for drawing trajectory
        this.pathView = (PathView) getView().findViewById(R.id.pathView1);
        //obtain average step length from SensorFusion class
        averageStepLength = sensorFusion.passAverageStepLength();
        //Display average step count on UI
        averageStepLengthText.setText(getActivity().getResources().getString(R.string.averageStepLgn) + ": " + String.format("%.2f", averageStepLength));
        //Check for enter to be pressed when user inputs new step length
        this.stepLengthInput.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                //Check if enter key has been pressed
                if(keyCode == KeyEvent.KEYCODE_ENTER){
                    //Convert entered string to a float
                    newStepLength = Float.parseFloat(changedText.toString());
                    //Rescale the path and call function to redraw
                    //scalingRatio = newStepLength/averageStepLength;
                    sensorFusion.redrawPath(newStepLength/averageStepLength);
                    //Show user new average step value
                    averageStepLengthText.setText(getActivity().getResources().
                            getString(R.string.averageStepLgn) + ": " + String.format("%.2f", newStepLength));
                    //redraw the path
                    pathView.invalidate();
                    //OnKew is called twice (once on press and release of button so the previous
                    // step count is updated only the second time)
                    secondPass++;
                    if(secondPass == 2) {
                        averageStepLength = newStepLength;
                        secondPass = 0;
                    }
                }

                return false;
            }
        });

        //Detect changes in the text editor. Call all default methods and store final string
        this.stepLengthInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                //store string when user has finished changing the text
                changedText = s;
            }
        });

        // Add button to navigate back to home screen.
        this.button = (Button) getView().findViewById(R.id.correction_done);
        this.button.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * When button clicked the {@link HomeFragment} is loaded and the action bar is
             * returned.
             */
            @Override
            public void onClick(View view) {
                NavDirections action = CorrectionFragmentDirections.actionCorrectionFragmentToHomeFragment();
                Navigation.findNavController(view).navigate(action);
                //Show action bar
                ((AppCompatActivity)getActivity()).getSupportActionBar().show();
            }
        });
    }

    /**
     * Set the scaling ration for the map fragments.
     *
     * @param scalingRatio  float ratio for scaling zoom on Maps.
     */
    public void setScalingRatio(float scalingRatio) {
        this.scalingRatio = scalingRatio;
    }
}