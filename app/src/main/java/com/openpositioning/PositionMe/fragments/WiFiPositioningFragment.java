package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;
import com.openpositioning.PositionMe.sensors.Wifi;
import com.openpositioning.PositionMe.sensors.WifiDataProcessor;
import com.openpositioning.PositionMe.sensors.Observer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class WiFiPositioningFragment extends androidx.fragment.app.Fragment implements OnMapReadyCallback, Observer {

    private GoogleMap mMap;
    private WiFiPositioning wifiPositioning;
    private FloatingActionButton floorUpButton, floorDownButton;
    private ExtendedFloatingActionButton returnButton;
    private WifiDataProcessor wifiDataProcessor;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_wifi_positioning, container, false);

        floorUpButton = rootView.findViewById(R.id.floorUpButton);
        floorDownButton = rootView.findViewById(R.id.floorDownButton);
        returnButton = rootView.findViewById(R.id.returnButton);

        floorUpButton.setOnClickListener(v -> {
            int currentFloor = wifiPositioning.getCurrentFloor();
            if (currentFloor < 3) {
                currentFloor++;
                wifiPositioning.updateFloorOverlay(currentFloor);
            }
        });

        floorDownButton.setOnClickListener(v -> {
            int currentFloor = wifiPositioning.getCurrentFloor();
            if (currentFloor > 0) {
                currentFloor--;
                wifiPositioning.updateFloorOverlay(currentFloor);
            }
        });

        returnButton.setOnClickListener(v -> {
            if(getActivity() != null) {
                getActivity().onBackPressed();
            }
        });

        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        wifiDataProcessor = new WifiDataProcessor(getContext());
        wifiDataProcessor.registerObserver(this);

        return rootView;
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        wifiPositioning = new WiFiPositioning(mMap, getContext());
    }

    @Override
    public void update(Object[] objList) {
        // 因为 WifiDataProcessor 调用 notifyObservers 时传递的是 Wifi[]，
        // 所以这里可以将 objList 强制转换为 Wifi[]
        Wifi[] wifiData = (Wifi[]) objList;

        JSONObject jsonFingerprint = new JSONObject();
        JSONArray wifiArray = new JSONArray();
        for (Wifi wifi : wifiData) {
            JSONObject wifiObject = new JSONObject();
            try {
                wifiObject.put("bssid", wifi.getBssid());
                wifiObject.put("level", wifi.getLevel());
                wifiObject.put("frequency", wifi.getFrequency());
            } catch (JSONException e) {
                e.printStackTrace();
            }
            wifiArray.put(wifiObject);
        }
        try {
            jsonFingerprint.put("wifi", wifiArray);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        // 发起定位请求并在回调中更新地图轨迹
        wifiPositioning.request(jsonFingerprint, new WiFiPositioning.VolleyCallback() {
            @Override
            public void onSuccess(LatLng location, int floor) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (floor == wifiPositioning.getCurrentFloor()) {
                        wifiPositioning.updatePosition(location, floor);
                    }
                });
            }

            @Override
            public void onError(String message) {
                // 错误处理，例如 Toast 或日志记录
            }
        });
    }
}
