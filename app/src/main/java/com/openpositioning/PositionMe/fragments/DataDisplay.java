package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.PositioningFusion;
import com.openpositioning.PositionMe.sensors.SensorFusion;


/**
 * A simple {@link Fragment} subclass.
 * Use the {@link DataDisplay#newInstance} factory method to
 * create an instance of this fragment.
 */
public class DataDisplay extends Fragment implements OnMapReadyCallback {

//    // TODO: Rename parameter arguments, choose names that match
//    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
//    private static final String ARG_PARAM1 = "param1";
//    private static final String ARG_PARAM2 = "param2";
//
//    // TODO: Rename and change types of parameters
//    private String mParam1;
//    private String mParam2;

    // For the map
    private GoogleMap mMap;
    private SupportMapFragment mapFragment;
    private final android.os.Handler handler = new android.os.Handler();
    private final int updateInterval = 1000; // 1 second

    private PositioningFusion positioningFusion = PositioningFusion.getInstance();

    private IndoorMapManager indoorMapManager;

    private Spinner mapTypeSpinner;

    private final Runnable updateWifiLocationRunnable = new Runnable() {
        @Override
        public void run() {
            updateWifiLocationText();
            handler.postDelayed(this, updateInterval);
        }
    };

    private TextView statusText;

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
//        Bundle args = new Bundle();
//        args.putString(ARG_PARAM1, param1);
//        args.putString(ARG_PARAM2, param2);
//        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Set activity title
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

        // Initialize the SupportMapFragment and set the OnMapReadyCallback
        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager()
                .findFragmentById(R.id.mapFragmentContainer);

        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        statusText = view.findViewById(R.id.textView3);

        positioningFusion.initCoordSystem(SensorFusion.getInstance().getGNSSLatitude(false)[0], SensorFusion.getInstance().getGNSSLatitude(false)[1]);

        SensorFusion.getInstance().pdrReset();
        mapTypeSpinner = view.findViewById(R.id.spinner2);
        setupMapTypeSpinner();
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        showCurrentLocation();
        handler.post(updateWifiLocationRunnable);
        indoorMapManager = new IndoorMapManager(mMap);
        indoorMapManager.setIndicationOfIndoorMap();

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

    private void updateWifiLocationText() {
        LatLng fusedLocation = PositioningFusion.getInstance().getFusedPosition();
//        LatLng wifiLocation = SensorFusion.getInstance().getLatLngWifiPositioning();
        int floor = SensorFusion.getInstance().getWifiFloor();

        Log.d("DataDisplay", "Fused Location: " + fusedLocation);

        if (fusedLocation != null) {

            // 显示 WiFi 经纬度 + 相对坐标
            String display = String.format(
                    "Location:\nLat: %.6f\nLon: %.6f\nFloor: %d",
                    fusedLocation.latitude,
                    fusedLocation.longitude,
                    floor
            );
            statusText.setText(display);

            mMap.addMarker(new MarkerOptions()
                    .position(fusedLocation)
                    .title("Estimated Position"));
            //mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(fusedLocation, 18f));
            if (indoorMapManager != null) {
                indoorMapManager.setCurrentLocation(fusedLocation);
            }
            if (indoorMapManager.getIsIndoorMapSet()) {
                indoorMapManager.setCurrentFloor(2, false);
            }

        } else {
            statusText.setText("Location: Unavailable");
        }



    }

    private void setupMapTypeSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                requireContext(), R.array.map_types, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mapTypeSpinner.setAdapter(adapter);
        mapTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mMap == null) return;
                switch (position) {
                    case 0:
                        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                    case 1:
                        mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                        break;
                    case 2:
                        mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                        break;
                    default:
                        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                if (mMap != null) {
                    mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                }
            }
        });
    }



    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacks(updateWifiLocationRunnable);
    }
}


