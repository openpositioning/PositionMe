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
import com.openpositioning.PositionMe.sensors.BLE;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.sensors.Wifi;
import com.openpositioning.PositionMe.presentation.viewitems.BleListAdapter;
import com.openpositioning.PositionMe.presentation.viewitems.WifiListAdapter;

import java.util.List;
import java.util.Map;

public class MeasurementsFragment extends Fragment {

    private static final long REFRESH_TIME = 5000;

    private SensorFusion sensorFusion;

    private Handler refreshDataHandler;

    private ConstraintLayout sensorMeasurementList;
    private RecyclerView wifiListView;
    private RecyclerView bleListView;

    private int[] prefaces;
    private int[] gnssPrefaces;

    public MeasurementsFragment() {}

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sensorFusion = SensorFusion.getInstance();

        prefaces = new int[]{R.string.x, R.string.y, R.string.z};
        gnssPrefaces = new int[]{R.string.lati, R.string.longi};

        refreshDataHandler = new Handler();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_measurements, container, false);
        requireActivity().setTitle("Sensor Measurements");
        refreshDataHandler.post(refreshTableTask);
        return rootView;
    }

    @Override
    public void onPause() {
        refreshDataHandler.removeCallbacks(refreshTableTask);
        super.onPause();
    }

    @Override
    public void onResume() {
        refreshDataHandler.postDelayed(refreshTableTask, REFRESH_TIME);
        super.onResume();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        sensorMeasurementList = view.findViewById(R.id.sensorMeasurementList);

        wifiListView = view.findViewById(R.id.wifiList);
        wifiListView.setLayoutManager(new LinearLayoutManager(getActivity()));

        bleListView = view.findViewById(R.id.bleList);
        bleListView.setLayoutManager(new LinearLayoutManager(getActivity()));
    }

    private final Runnable refreshTableTask = new Runnable() {
        @Override
        public void run() {
            // 1) 刷新传感器表（原逻辑不动）
            Map<SensorTypes, float[]> sensorValueMap = sensorFusion.getSensorValueMap();

            for (SensorTypes st : SensorTypes.values()) {
                CardView cardView = (CardView) sensorMeasurementList.getChildAt(st.ordinal());
                ConstraintLayout currentRow = (ConstraintLayout) cardView.getChildAt(0);

                float[] values = sensorValueMap.get(st);
                if (values == null) continue;

                for (int i = 0; i < values.length; i++) {
                    String valueString;

                    if (values.length == 1) {
                        valueString = getString(R.string.level, String.format("%.2f", values[0]));
                    } else if (values.length == 2) {
                        if (st == SensorTypes.GNSSLATLONG) {
                            valueString = getString(gnssPrefaces[i], String.format("%.2f", values[i]));
                        } else {
                            valueString = getString(prefaces[i], String.format("%.2f", values[i]));
                        }
                    } else {
                        valueString = getString(prefaces[i], String.format("%.2f", values[i]));
                    }

                    ((TextView) currentRow.getChildAt(i + 1)).setText(valueString);
                }
            }

            // 2) 刷新 WiFi（原逻辑不动）
            List<Wifi> wifiObjects = sensorFusion.getWifiList();
            if (wifiObjects != null) {
                wifiListView.setAdapter(new WifiListAdapter(getActivity(), wifiObjects));
            }

            // 3) 新增：刷新 BLE（MAC + RSSI）
            List<BLE> bleObjects = sensorFusion.getBleList(); // 你必须在 SensorFusion 里补这个方法
            if (bleObjects != null) {
                bleListView.setAdapter(new BleListAdapter(getActivity(), bleObjects));
            }

            refreshDataHandler.postDelayed(refreshTableTask, REFRESH_TIME);
        }
    };
}
