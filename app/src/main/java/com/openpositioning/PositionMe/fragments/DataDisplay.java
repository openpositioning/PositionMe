package com.openpositioning.PositionMe.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
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
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.TrajectoryDrawer;
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

    // Add a field for the marker
    private Marker fusedMarker = null;
    private Marker wifiMarker = null;
    private Marker gnssMarker = null;
    private Marker pdrMarker = null;

    private SupportMapFragment mapFragment;
    private final android.os.Handler handler = new android.os.Handler();
    private final int updateInterval = 1000; // 1 second

    private PositioningFusion positioningFusion = PositioningFusion.getInstance();

    private IndoorMapManager indoorMapManager;

    private Spinner mapTypeSpinner;

    TrajectoryDrawer trajectoryDrawer;

    private Marker directionMarker;



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
//        if (getArguments() != null) {
//            mParam1 = getArguments().getString(ARG_PARAM1);
//            mParam2 = getArguments().getString(ARG_PARAM2);
//        }
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

        SensorFusion.getInstance().pdrReset();
        mapTypeSpinner = view.findViewById(R.id.spinner2);
        setupMapTypeSpinner();
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
//        showCurrentLocation();
        handler.post(updateWifiLocationRunnable);
        indoorMapManager = new IndoorMapManager(mMap);
        indoorMapManager.setIndicationOfIndoorMap();
        trajectoryDrawer = new TrajectoryDrawer(mMap);

        positioningFusion.initCoordSystem(SensorFusion.getInstance().getGNSSLatitude(false)[0], SensorFusion.getInstance().getGNSSLatitude(false)[1]);

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
            trajectoryDrawer.addPoint(fusedLocation);

            // 显示 estimated 经纬度 + 楼层
            String display = String.format(
                    "Location:\nLat: %.6f\nLon: %.6f\nFloor: %d",
                    fusedLocation.latitude,
                    fusedLocation.longitude,
                    floor
            );
            statusText.setText(display);

//            if (fusedMarker == null) {
//                // Create marker only once
//                fusedMarker = mMap.addMarker(new MarkerOptions()
//                        .position(fusedLocation)
//                        .title("Estimated Position"));
//                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(fusedLocation, 18f));
//            } else {
//                // Just move the marker
//                fusedMarker.setPosition(fusedLocation);
//            }

            // --- Fused ---
            float bearing = SensorFusion.getInstance().getHeading(); // 获取朝向角度（度）

            // 初始化图标
            BitmapDescriptor blueDotIcon = vectorToBitmap(requireContext(), R.drawable.ic_blue_dot);
            BitmapDescriptor coneIcon = vectorToBitmap(requireContext(), R.drawable.ic_direction_cone);


            if (fusedMarker == null) {
                // Create marker only once
                fusedMarker = mMap.addMarker(new MarkerOptions()
                        .position(fusedLocation)
                        .title("Estimated Position")
                        .icon(blueDotIcon)
                        .anchor(0.5f, 0.5f)
                        .flat(true));

                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(fusedLocation, 18f));
            } else {
                // Just move the marker
                fusedMarker.setPosition(fusedLocation);
            }

            // --- WiFi ---
            LatLng wifiLocation = positioningFusion.getWifiPosition();
            if (positioningFusion.isWifiPositionSet()) {
                if (wifiMarker == null) {
                    wifiMarker = mMap.addMarker(new MarkerOptions()
                            .position(wifiLocation)
                            .title("WiFi Position")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)));
                } else {
                    wifiMarker.setPosition(wifiLocation);
                }
            }

            // --- GNSS ---
            LatLng gnssLocation = positioningFusion.getGnssPosition();
            if (positioningFusion.isGNSSPositionSet()) {
                if (gnssMarker == null) {
                    gnssMarker = mMap.addMarker(new MarkerOptions()
                            .position(gnssLocation)
                            .title("GNSS Position")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
                } else {
                    gnssMarker.setPosition(gnssLocation);
                }
            }

            // --- PDR ---
            LatLng pdrLocation = positioningFusion.getPdrPosition();
            if (positioningFusion.isPDRPositionSet()) {
                if (pdrMarker == null) {
                    pdrMarker = mMap.addMarker(new MarkerOptions()
                            .position(pdrLocation)
                            .title("PDR Position")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)));
                } else {
                    pdrMarker.setPosition(pdrLocation);
                }
            }


            if (directionMarker == null) {
                directionMarker = mMap.addMarker(new MarkerOptions()
                        .position(fusedLocation)
                        .icon(coneIcon)
                        .anchor(0.5f, 0.5f)
                        .flat(true)
                        .rotation(bearing));
            } else {
                directionMarker.setPosition(fusedLocation);
                directionMarker.setRotation(bearing);
            }

            if (indoorMapManager != null) {
                indoorMapManager.setCurrentLocation(fusedLocation);
            }
            if (indoorMapManager.getIsIndoorMapSet()) {
                indoorMapManager.setCurrentFloor(floor, true);
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

    private BitmapDescriptor vectorToBitmap(Context context, @DrawableRes int vectorResId) {
        Drawable vectorDrawable = ContextCompat.getDrawable(context, vectorResId);
        vectorDrawable.setBounds(0, 0, vectorDrawable.getIntrinsicWidth(), vectorDrawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(
                vectorDrawable.getIntrinsicWidth(),
                vectorDrawable.getIntrinsicHeight(),
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(bitmap);
        vectorDrawable.draw(canvas);
        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }




    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacks(updateWifiLocationRunnable);
    }
}


