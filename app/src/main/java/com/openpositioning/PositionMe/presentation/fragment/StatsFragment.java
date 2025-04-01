package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.local.TrajParser;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.text.DecimalFormat;
import java.util.List;

public class StatsFragment extends Fragment {

    private TextView distanceTextView, timeTextView, avgSpeedTextView, paceTextView, altitudeTextView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_stats, container, false);

        Toolbar toolbar = rootView.findViewById(R.id.statsToolbar);
        toolbar.setNavigationIcon(R.drawable.ic_baseline_back_arrow);
        toolbar.setTitle("Journey Stats");
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setNavigationOnClickListener(v -> requireActivity().onBackPressed());

        distanceTextView = rootView.findViewById(R.id.distanceTextView);
        timeTextView = rootView.findViewById(R.id.timeTextView);
        avgSpeedTextView = rootView.findViewById(R.id.avgSpeedTextView);
        paceTextView = rootView.findViewById(R.id.paceTextView);
        altitudeTextView = rootView.findViewById(R.id.altitudeTextView);

        calculateAndDisplayStats();

        return rootView;
    }

    private void calculateAndDisplayStats() {
        List<TrajParser.ReplayPoint> data = TrajParser.replayData;
        if (data == null || data.size() < 2) return;

        float totalDistance = 0f;
        long startTime = data.get(0).timestamp;
        long endTime = data.get(data.size() - 1).timestamp;

        double minAlt = Double.MAX_VALUE;
        double maxAlt = Double.MIN_VALUE;

        for (int i = 1; i < data.size(); i++) {
            LatLng prev = data.get(i - 1).pdrLocation;
            LatLng curr = data.get(i).pdrLocation;

            if (prev != null && curr != null) {
                totalDistance += UtilFunctions.distanceBetweenPoints(prev, curr);
            }


        }

        long durationMillis = endTime - startTime;
        float durationSec = durationMillis / 1000f;
        float durationHours = durationSec / 3600f;
        float distanceKm = totalDistance / 1000f;
        float avgSpeed = distanceKm / durationHours;
        float paceSecPerKm = durationSec / distanceKm;
        int paceMin = (int) (paceSecPerKm / 60);
        int paceSec = (int) (paceSecPerKm % 60);
        float altDiff = (maxAlt == Double.MIN_VALUE || minAlt == Double.MAX_VALUE) ? 0 : (float) (maxAlt - minAlt);

        DecimalFormat df = new DecimalFormat("#.##");

        distanceTextView.setText(df.format(distanceKm) + " km");
        timeTextView.setText(String.format("%02d:%02d", (int) (durationSec / 60), (int) (durationSec % 60)));
        avgSpeedTextView.setText(df.format(avgSpeed) + " km/h");
        paceTextView.setText(String.format("%d:%02d", paceMin, paceSec));
        altitudeTextView.setText(df.format(altDiff) + " m");
    }
}
