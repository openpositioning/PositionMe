package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
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
import com.openpositioning.PositionMe.sensors.Observer;
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
public class MeasurementsFragment extends Fragment implements Observer {

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
    private TextView floorTextView;

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
        
        // 初始化视图
        floorTextView = view.findViewById(R.id.Floor);
        
        // 获取SensorFusion实例并注册为观察者
        sensorFusion = SensorFusion.getInstance();
        sensorFusion.registerFloorObserver(this);
        
        // 检查布局中的卡片视图数量是否与传感器类型匹配
        int cardViewCount = sensorMeasurementList.getChildCount();
        int sensorTypeCount = SensorTypes.values().length;
        
        if (cardViewCount < sensorTypeCount) {
            Log.e("MeasurementsFragment", "布局中的CardView数量(" + cardViewCount + 
                  ")小于SensorTypes枚举数量(" + sensorTypeCount + ")");
        }
        
        // 设置初始楼层值
        updateFloorDisplay(sensorFusion.getCurrentFloor());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 取消注册观察者
        if (sensorFusion != null) {
            sensorFusion.removeFloorObserver(this);
        }
        
        // 移除所有回调，防止内存泄漏
        if (refreshDataHandler != null) {
            refreshDataHandler.removeCallbacksAndMessages(null);
        }
        
        // 清空UI引用
        sensorMeasurementList = null;
        wifiListView = null;
        floorTextView = null;
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
            try {
                // 确保视图已经初始化
                if (sensorMeasurementList == null || getActivity() == null) {
                    Log.e("MeasurementsFragment", "View not initialized or fragment detached");
                    return;
                }
                
                // Get all the values from SensorFusion
                Map<SensorTypes, float[]> sensorValueMap = sensorFusion.getSensorValueMap();
                // Loop through UI elements and update the values
                for(SensorTypes st : SensorTypes.values()) {
                    // 检查索引是否有效
                    if (st.ordinal() >= sensorMeasurementList.getChildCount()) {
                        Log.e("MeasurementsFragment", "Invalid index: " + st.ordinal() + 
                              ", ChildCount: " + sensorMeasurementList.getChildCount());
                        continue;
                    }
                    
                    CardView cardView = (CardView) sensorMeasurementList.getChildAt(st.ordinal());
                    // 空值检查
                    if (cardView == null) {
                        Log.e("MeasurementsFragment", "CardView is null for sensor: " + st.name());
                        continue;
                    }
                    
                    ConstraintLayout currentRow = (ConstraintLayout) cardView.getChildAt(0);
                    // 空值检查
                    if (currentRow == null) {
                        Log.e("MeasurementsFragment", "ConstraintLayout is null for sensor: " + st.name());
                        continue;
                    }
                    
                    float[] values = sensorValueMap.get(st);
                    // 空值检查
                    if (values == null) {
                        Log.e("MeasurementsFragment", "Values array is null for sensor: " + st.name());
                        continue;
                    }
                    
                    for (int i = 0; i < values.length; i++) {
                        // 检查索引有效性
                        if (i + 1 >= currentRow.getChildCount()) {
                            Log.e("MeasurementsFragment", "Invalid child index: " + (i + 1) + 
                                  " for sensor: " + st.name());
                            continue;
                        }
                        
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
                        
                        View childView = currentRow.getChildAt(i + 1);
                        if (childView instanceof TextView) {
                            ((TextView) childView).setText(valueString);
                        }
                    }
                }
                
                // Get all WiFi values - convert to list of strings
                List<Wifi> wifiObjects = sensorFusion.getWifiList();
                // If there are WiFi networks visible, update the recycler view with the data.
                if(wifiObjects != null && wifiListView != null) {
                    wifiListView.setAdapter(new WifiListAdapter(getActivity(), wifiObjects));
                }
                
                // Restart the data updater task in REFRESH_TIME milliseconds.
                refreshDataHandler.postDelayed(refreshTableTask, REFRESH_TIME);
            } catch (Exception e) {
                Log.e("MeasurementsFragment", "Error updating sensor data: " + e.getMessage());
                // 即使发生错误，也确保继续刷新
                if (refreshDataHandler != null) {
                    refreshDataHandler.postDelayed(refreshTableTask, REFRESH_TIME);
                }
            }
        }
    };

    @Override
    public void update(Object[] obj) {
        if (obj.length > 0 && obj[0] instanceof Integer) {
            int floor = (Integer) obj[0];
            // 确保在主线程中更新UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> updateFloorDisplay(floor));
            }
        }
    }

    private void updateFloorDisplay(int floor) {
        if (floorTextView != null) {
            String oldText = floorTextView.getText().toString();
            String displayText = sensorFusion.getFloorDisplay();
            floorTextView.setText(displayText);
            Log.d("FLOOR_UPDATE", String.format(
                "Fragment UI更新 - 旧值: %s, 新值: %s (数值: %d)", 
                oldText,
                displayText,
                floor
            ));
        }
    }

    /**
     * 设置基准气压值
     * @param basePressure 新的基准气压值 (hPa)
     */
    public void setBasePressure(float basePressure) {
        if (sensorFusion != null) {
            sensorFusion.calibrateBasePressure(basePressure);
            // 更新显示
            updateFloorDisplay(sensorFusion.getCurrentFloor());
        }
    }

    /**
     * 在当前楼层校准气压计
     * @param currentFloor 当前所在楼层
     */
    public void calibrateAtCurrentFloor(int currentFloor) {
        if (sensorFusion != null) {
            sensorFusion.calibrateAtKnownFloor(currentFloor);
            // 更新显示
            updateFloorDisplay(sensorFusion.getCurrentFloor());
        }
    }
}