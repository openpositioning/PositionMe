package com.openpositioning.PositionMe.fragments;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.viewitems.WifiAdapter;
import com.openpositioning.PositionMe.WifiData;

import java.util.ArrayList;
import java.util.List;

public class WifiFragment extends Fragment {

    private RecyclerView wifiRecyclerView;
    private WifiAdapter wifiAdapter;
    private List<WifiData> wifiList;
    private WifiManager wifiManager;
    private BroadcastReceiver wifiReceiver;
    private Button startButton, stopButton;
    private boolean isScanning = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_wifi, container, false);

        wifiRecyclerView = view.findViewById(R.id.wifi_recycler_view);
        wifiRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        wifiList = new ArrayList<>();
        wifiAdapter = new WifiAdapter(wifiList);
        wifiRecyclerView.setAdapter(wifiAdapter);

        startButton = view.findViewById(R.id.button_start_wifi);
        stopButton = view.findViewById(R.id.button_stop_wifi);

        wifiManager = (WifiManager) requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        startButton.setOnClickListener(v -> startWifiScan());
        stopButton.setOnClickListener(v -> stopWifiScan());

        return view;
    }


    private void startWifiScan() {
        if (!isScanning) {
            isScanning = true;

            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
                return;
            }

            if (!wifiManager.isWifiEnabled()) {
                Toast.makeText(getContext(), "WiFi is disabled. Enabling WiFi...", Toast.LENGTH_SHORT).show();
                wifiManager.setWifiEnabled(true);
            }


            wifiReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                        List<ScanResult> scanResults = wifiManager.getScanResults();
                        updateWifiList(scanResults);
                    }
                }
            };

            requireContext().registerReceiver(wifiReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));


            wifiManager.startScan();
            Toast.makeText(getContext(), "Scanning WiFi...", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateWifiList(List<ScanResult> scanResults) {
        wifiList.clear();
        for (ScanResult scanResult : scanResults) {
            wifiList.add(new WifiData(scanResult.SSID, scanResult.BSSID, scanResult.level + " dB"));
        }
        wifiAdapter.notifyDataSetChanged();
    }

    private void stopWifiScan() {
        if (isScanning) {
            isScanning = false;
            if (wifiReceiver != null) {
                requireContext().unregisterReceiver(wifiReceiver);
                wifiReceiver = null;
            }
            Toast.makeText(getContext(), "WiFi Scan Stopped", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wifiReceiver != null) {
            requireContext().unregisterReceiver(wifiReceiver);
        }
    }
}
