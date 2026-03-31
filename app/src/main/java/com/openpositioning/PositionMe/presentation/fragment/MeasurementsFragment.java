package com.openpositioning.PositionMe.presentation.fragment;

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
import com.openpositioning.PositionMe.sensors.BleDevice;
import com.openpositioning.PositionMe.presentation.viewitems.WifiListAdapter;
import com.openpositioning.PositionMe.presentation.viewitems.BleListAdapter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// A simple {@link Fragment} subclass. The measurement fragment displays the set of current sensor
// readings. The values are refreshed periodically, but slower than their internal refresh rate.
// The refresh time is set by a static constant.
// @see HomeFragment the previous fragment in the nav graph.
// @see SensorFusion the source of all sensor readings.
// @author Mate Stodulka
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
    private RecyclerView bleListView;
    // List of string resource IDs
    private int[] prefaces;
    private int[] gnssPrefaces;
    private final Map<SensorTypes, Integer> sensorRowIds = new EnumMap<>(SensorTypes.class);


    // Public default constructor, empty.
    public MeasurementsFragment() {
        // Required empty public constructor
    }

    // {@inheritDoc}
    // Obtains the singleton Sensor Fusion instance and initialises the string prefaces for display.
    // Creates a new handler to periodically refresh data.
    // @see SensorFusion handles all sensor data.
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Get sensor fusion instance
        sensorFusion = SensorFusion.getInstance();
        // Initialise string prefaces for display
        prefaces =  new int[]{R.string.x, R.string.y, R.string.z};
        gnssPrefaces =  new int[]{R.string.lati, R.string.longi};
        initialiseSensorRowIds();

        // Create new handler to refresh the UI.
        this.refreshDataHandler = new Handler();
    }

    // {@inheritDoc}
    // Sets title in the action bar to Sensor Measurements.
    // Posts the {@link MeasurementsFragment#refreshTableTask} using the Handler.
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_measurements, container, false);
        getActivity().setTitle("Sensor Measurements");
        this.refreshDataHandler.post(refreshTableTask);
        return rootView;
    }

    // {@inheritDoc}
    // Pauses the data refreshing when the fragment is not in focus.
    @Override
    public void onPause() {
        refreshDataHandler.removeCallbacks(refreshTableTask);
        super.onPause();
    }

    // {@inheritDoc}
    // Restarts the data refresh when the fragment returns to focus.
    @Override
    public void onResume() {
        super.onResume();
        // Ensure sensors and WiFi scanner are running
        sensorFusion.resumeListening();
        refreshDataHandler.postDelayed(refreshTableTask, REFRESH_TIME);
    }

    // {@inheritDoc}
    // Obtains the constraint layout holding the sensor measurement values. Initialises the Recycler
    // View for holding WiFi data and registers its Layout Manager.
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        sensorMeasurementList = (ConstraintLayout) getView().findViewById(R.id.sensorMeasurementList);
        wifiListView = (RecyclerView) getView().findViewById(R.id.wifiList);
        wifiListView.setLayoutManager(new LinearLayoutManager(getActivity()));
        bleListView = (RecyclerView) getView().findViewById(R.id.bleList);
        bleListView.setLayoutManager(new LinearLayoutManager(getActivity()));
    }

    // Runnable task containing functionality to update the UI with the relevant sensor data.
    // Must be run on the UI thread via a Handler. Obtains movement sensor values and the current
    // WiFi networks from the {@link SensorFusion} instance and updates the UI with the new data
    // and the string wrappers provided.
    // @see SensorFusion class handling all sensors and data processing.
    // @see Wifi class holding network data.
    private final Runnable refreshTableTask = new Runnable() {
        @Override
        public void run() {
            // Get all the values from SensorFusion
            Map<SensorTypes, float[]> sensorValueMap = sensorFusion.getSensorValueMap();

            for (Map.Entry<SensorTypes, Integer> entry : sensorRowIds.entrySet()) {
                updateSensorRow(entry.getKey(), entry.getValue(), sensorValueMap.get(entry.getKey()));
            }

            // Update WiFi list
            List<Wifi> wifiObjects = sensorFusion.getWifiList();
            if (wifiObjects != null && !wifiObjects.isEmpty()) {
                android.util.Log.d("WiFiDebug", "Detected networks: " + wifiObjects.size());
                if (getContext() != null) {
                    wifiListView.setAdapter(new WifiListAdapter(getContext(), wifiObjects));
                }
            } else {
                android.util.Log.d("WiFiDebug", "No WiFi networks detected");
                // Create placeholder item for empty state
                if (getContext() != null) {
                    List<Wifi> placeholderList = new ArrayList<>();
                    Wifi placeholder = new Wifi();
                    placeholder.setBssid(0);
                    placeholder.setSsid("Scanning...");
                    placeholder.setLevel(-100);
                    placeholderList.add(placeholder);
                    wifiListView.setAdapter(new WifiListAdapter(getContext(), placeholderList));
                }
            }

            // Update BLE list
            List<BleDevice> bleDevices = sensorFusion.getBleList();
            if (bleDevices != null && !bleDevices.isEmpty()) {
                android.util.Log.d("BLEDebug", "Detected BLE devices: " + bleDevices.size());
                if (getContext() != null) {
                    bleListView.setAdapter(new BleListAdapter(getContext(), bleDevices));
                }
            } else {
                android.util.Log.d("BLEDebug", "No BLE devices detected");
                // Create placeholder item for empty state
                if (getContext() != null) {
                    List<BleDevice> placeholderList = new ArrayList<>();
                    BleDevice placeholder = new BleDevice("00:00:00:00:00:00", "Scanning...", -100);
                    placeholderList.add(placeholder);
                    bleListView.setAdapter(new BleListAdapter(getContext(), placeholderList));
                }
            }

            // Restart the data updater task in REFRESH_TIME milliseconds.
            refreshDataHandler.postDelayed(refreshTableTask, REFRESH_TIME);
        }
    };

    private void initialiseSensorRowIds() {
        sensorRowIds.put(SensorTypes.ACCELEROMETER, R.id.accelerometerView);
        sensorRowIds.put(SensorTypes.GRAVITY, R.id.gravityView);
        sensorRowIds.put(SensorTypes.MAGNETICFIELD, R.id.magneticFieldView);
        sensorRowIds.put(SensorTypes.GYRO, R.id.gyroscopeView);
        sensorRowIds.put(SensorTypes.LIGHT, R.id.lightSensorView);
        sensorRowIds.put(SensorTypes.PRESSURE, R.id.pressureSensorView);
        sensorRowIds.put(SensorTypes.PROXIMITY, R.id.proximityView);
        sensorRowIds.put(SensorTypes.GNSSLATLONG, R.id.gnssView);
        sensorRowIds.put(SensorTypes.PDR, R.id.pdrView);
    }

    private void updateSensorRow(SensorTypes sensorType, int cardId, float[] values) {
        if (sensorMeasurementList == null || values == null) {
            return;
        }

        View view = sensorMeasurementList.findViewById(cardId);
        if (!(view instanceof CardView)) {
            return;
        }

        CardView cardView = (CardView) view;
        if (cardView.getChildCount() == 0 || !(cardView.getChildAt(0) instanceof ConstraintLayout)) {
            return;
        }

        ConstraintLayout currentRow = (ConstraintLayout) cardView.getChildAt(0);
        for (int i = 0; i < values.length; i++) {
            if (i + 1 >= currentRow.getChildCount()) {
                break;
            }

            String valueString;
            if (values.length == 1) {
                valueString = getString(R.string.level, String.format("%.2f", values[0]));
            } else if (sensorType == SensorTypes.GNSSLATLONG) {
                valueString = getString(gnssPrefaces[i], String.format("%.2f", values[i]));
            } else {
                valueString = getString(prefaces[i], String.format("%.2f", values[i]));
            }
            ((TextView) currentRow.getChildAt(i + 1)).setText(valueString);
        }
    }
}


