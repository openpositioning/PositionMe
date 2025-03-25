package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link DataDisplay#newInstance} factory method to
 * create an instance of this fragment.
 */
public class DataDisplay extends Fragment implements OnMapReadyCallback {

    // TODO: Rename parameter arguments, choose names that match
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";

    // TODO: Rename and change types of parameters
    private String mParam1;
    private String mParam2;

    // For the map
    private GoogleMap mMap;
    private SupportMapFragment mapFragment;

    public DataDisplay() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment DataDisplay.
     */
    // TODO: Rename and change types and number of parameters
    public static DataDisplay newInstance(String param1, String param2) {
        DataDisplay fragment = new DataDisplay();
        Bundle args = new Bundle();
        args.putString(ARG_PARAM1, param1);
        args.putString(ARG_PARAM2, param2);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mParam1 = getArguments().getString(ARG_PARAM1);
            mParam2 = getArguments().getString(ARG_PARAM2);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        getActivity().setTitle("Live Position");
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_data_display, container, false);
    }

    /**
     * {@inheritDoc}
     * Initialise UI elements and set onClick actions for the buttons.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 🔽 启动 WiFi 扫描和传感器监听
        //SensorFusion.getInstance().resumeListening();

        // Initialize the SupportMapFragment and set the OnMapReadyCallback
        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager()
                .findFragmentById(R.id.mapFragmentContainer);

        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        showCurrentLocation();

    }

    public void showCurrentLocation(){
        LatLng wifiLocation = SensorFusion.getInstance().getLatLngWifiPositioning();
        if (wifiLocation != null) {
            mMap.addMarker(new MarkerOptions()
                    .position(wifiLocation)
                    .title("WiFi Estimated Position"));
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(wifiLocation, 18f));
        }

    }
}

