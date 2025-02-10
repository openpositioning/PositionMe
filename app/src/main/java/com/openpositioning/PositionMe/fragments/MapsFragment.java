package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.R;

public class MapsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_maps2, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 获取 FragmentManager 并加载 Google Maps
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.fragment_container);

        if (mapFragment == null) {
            mapFragment = new SupportMapFragment();
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, mapFragment)
                    .commit();
        }

        // 异步加载 Google Map
        mapFragment.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(GoogleMap googleMap) {
                LatLng defaultLocation = new LatLng(55.92, -3.17); // 悉尼
                googleMap.addMarker(new MarkerOptions().position(defaultLocation).title("Default Location"));
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 10));
            }
        });
    }
}