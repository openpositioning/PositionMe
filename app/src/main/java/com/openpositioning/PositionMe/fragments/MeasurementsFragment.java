package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.sensors.Wifi;
import com.openpositioning.PositionMe.viewitems.WifiListAdapter;

import java.util.List;
import java.util.Map;

/**
 * A simple {@link Fragment} subclass. The measurement fragment displays the set of current sensor
 * readings. The values are refreshed periodically, but slower than their internal refresh rate.
 * The refresh time is set by a static constant.
 *
 * @see HomeFragment the previous fragment in the nav graph.
 * @see SensorFusion the source of all sensor readings.
 *
 * @author Mate Stodulka
 */
public class MeasurementsFragment extends Fragment {

    // Static constant for refresh time in milliseconds
    private static final long REFRESH_TIME = 5000;

    // Singleton Sensor Fusion class handling all sensor data
    private SensorFusion sensorFusion;

    // UI Handler
    private Handler refreshDataHandler;
    // UI elements
    private ConstraintLayout sensorMeasurementList;
    private RecyclerView wifiListView;
    // List of string resource IDs
    private int[] prefaces;
    private int[] gnssPrefaces;


    /**
     * Public default constructor, empty.
     */
    public MeasurementsFragment() {
        // Required empty public constructor
    }

    /**
     * {@inheritDoc}
     * Obtains the singleton Sensor Fusion instance and initialises the string prefaces for display.
     * Creates a new handler to periodically refresh data.
     *
     * @see SensorFusion handles all sensor data.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Get sensor fusion instance
        sensorFusion = SensorFusion.getInstance();
        // Initialise string prefaces for display
        prefaces =  new int[]{R.string.x, R.string.y, R.string.z};
        gnssPrefaces =  new int[]{R.string.lati, R.string.longi};

        // Create new handler to refresh the UI.
        this.refreshDataHandler = new Handler();
    }

    /**
     * {@inheritDoc}
     * Sets title in the action bar to Sensor Measurements.
     * Posts the {@link MeasurementsFragment#refreshTableTask} using the Handler.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_measurements, container, false);
        getActivity().setTitle("Sensor Measurements");
        this.refreshDataHandler.post(refreshTableTask);
        return rootView;
    }

    /**
     * {@inheritDoc}
     * Pauses the data refreshing when the fragment is not in focus.
     */
    @Override
    public void onPause() {
        refreshDataHandler.removeCallbacks(refreshTableTask);
        super.onPause();
    }

    /**
     * {@inheritDoc}
     * Restarts the data refresh when the fragment returns to focus.
     */
    @Override
    public void onResume() {
        refreshDataHandler.postDelayed(refreshTableTask, REFRESH_TIME);
        super.onResume();
    }

    /**
     * {@inheritDoc}
     * Obtains the constraint layout holding the sensor measurement values. Initialises the Recycler
     * View for holding WiFi data and registers its Layout Manager.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        sensorMeasurementList = (ConstraintLayout) getView().findViewById(R.id.sensorMeasurementList);
        wifiListView = (RecyclerView) getView().findViewById(R.id.wifiList);
        wifiListView.setLayoutManager(new LinearLayoutManager(getActivity()));
    }

    /**
     * Runnable task containing functionality to update the UI with the relevant sensor data.
     * Must be run on the UI thread via a Handler. Obtains movement sensor values and the current
     * WiFi networks from the {@link SensorFusion} instance and updates the UI with the new data
     * and the string wrappers provided.
     *
     * @see SensorFusion class handling all sensors and data processing.
     * @see Wifi class holding network data.
     */
    private final Runnable refreshTableTask = new Runnable() {
        @Override
        public void run() {
            // Get all the values from SensorFusion
            Map<SensorTypes, float[]> sensorValueMap = sensorFusion.getSensorValueMap();
            // Loop through UI elements and update the values
            for(SensorTypes st : SensorTypes.values()) {
                CardView cardView = (CardView) sensorMeasurementList.getChildAt(st.ordinal());
                ConstraintLayout currentRow = (ConstraintLayout) cardView.getChildAt(0);
                float[] values = sensorValueMap.get(st);
                for (int i = 0; i < values.length; i++) {
                    String valueString;
                    // Set string wrapper based on data type.
                    if(values.length == 1) {
                        valueString = getString(R.string.level, String.format("%.2f", values[0]));
                    }
                    else if(values.length == 2){
                        if(st == SensorTypes.GNSSLATLONG)
                            valueString = getString(gnssPrefaces[i], String.format("%.2f", values[i]));
                        else
                            valueString = getString(prefaces[i], String.format("%.2f", values[i]));
                    }
                    else{
                        valueString = getString(prefaces[i], String.format("%.2f", values[i]));
                    }
                    ((TextView) currentRow.getChildAt(i + 1)).setText(valueString);
                }
            }
            // Get all WiFi values - convert to list of strings
            List<Wifi> wifiObjects = sensorFusion.getWifiList();
            // If there are WiFi networks visible, update the recycler view with the data.
            if(wifiObjects != null) {
                wifiListView.setAdapter(new WifiListAdapter(getActivity(), wifiObjects));
            }
            // Restart the data updater task in REFRESH_TIME milliseconds.
            refreshDataHandler.postDelayed(refreshTableTask, REFRESH_TIME);
        }
    };
}