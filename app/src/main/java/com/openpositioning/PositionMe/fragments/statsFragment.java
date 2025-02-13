package com.openpositioning.PositionMe.fragments;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.model.LatLng;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.UtilFunctions;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class statsFragment extends Fragment {

    private TextView distanceTextView, timeTextView, avgSpeedTextView, paceTextView;
    private GraphView speedGraph;
    private Traj.Trajectory receivedTrajectory;
    private float totalDistance = 0f;
    private int totalDuration = 0; // Time in seconds
    private List<Float> speedList = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.stats_fragment, container, false);

        Toolbar toolbar = rootView.findViewById(R.id.toolbar);
        toolbar.setTitle("Journey Stats");
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setNavigationOnClickListener(v -> getActivity().onBackPressed());


        // Initialize UI elements
        distanceTextView = rootView.findViewById(R.id.distanceTextView);
        timeTextView = rootView.findViewById(R.id.timeTextView);
        avgSpeedTextView = rootView.findViewById(R.id.avgSpeedTextView);
        paceTextView = rootView.findViewById(R.id.paceTextView);
        speedGraph = rootView.findViewById(R.id.speedGraph);

        // Retrieve the trajectory data from the bundle
        if (getArguments() != null) {
            String trajectoryJson = getArguments().getString("trajectory");
            if (trajectoryJson != null) {
                Traj.Trajectory.Builder trajectoryBuilder = Traj.Trajectory.newBuilder();
                try {
                    com.google.protobuf.util.JsonFormat.parser().merge(trajectoryJson, trajectoryBuilder);
                    receivedTrajectory = trajectoryBuilder.build();
                } catch (Exception e) {
                    Log.e("StatsFragment", "Error parsing trajectory JSON", e);
                }
            }
        }

        // Calculate statistics
        if (receivedTrajectory != null) {
            calculateStats(receivedTrajectory);
            updateUI();
            plotSpeedGraph();
        }

        return rootView;
    }

    /**
     * Calculate Distance, Time, Speed, and Pace
     */
    private void calculateStats(Traj.Trajectory trajectory) {
        List<LatLng> trajectoryPoints = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();

        // Extract trajectory points and timestamps
        for (Traj.Pdr_Sample pdr : trajectory.getPdrDataList()) {
            LatLng newPosition = UtilFunctions.calculateNewPos(new LatLng(0, 0), new float[]{pdr.getX(), pdr.getY()});
            trajectoryPoints.add(newPosition);
            timestamps.add(pdr.getRelativeTimestamp());
        }

        // Compute distance
        for (int i = 1; i < trajectoryPoints.size(); i++) {
            float segmentDistance = (float) UtilFunctions.distanceBetweenPoints(trajectoryPoints.get(i - 1), trajectoryPoints.get(i));
            totalDistance += segmentDistance;

            // Calculate Speed (m/s)
            long timeDifference = timestamps.get(i) - timestamps.get(i - 1);
            if (timeDifference > 0) {
                float speed = segmentDistance / (timeDifference / 1000f); // Convert ms to seconds
                speedList.add(speed);
            }
        }

        // Compute total duration
        if (trajectory.getPdrDataCount() > 1) {
            long startTime = trajectory.getPdrData(0).getRelativeTimestamp();
            long endTime = trajectory.getPdrData(trajectory.getPdrDataCount() - 1).getRelativeTimestamp();
            totalDuration = (int) ((endTime - startTime) / 1000); // Convert to seconds
        }
    }

    /**
     * 🔢 Update UI elements with calculated values
     */
    private void updateUI() {
        DecimalFormat df = new DecimalFormat("#.##");

        // Distance in km
        float distanceKm = totalDistance / 1000;
        distanceTextView.setText(df.format(distanceKm) + " km");

        // Time in minutes and seconds
        int minutes = totalDuration / 60;
        int seconds = totalDuration % 60;
        timeTextView.setText(String.format("%02d:%02d", minutes, seconds));

        // Average Speed in km/h
        float avgSpeed = (totalDistance / 1000) / (totalDuration / 3600.0f);
        avgSpeedTextView.setText(df.format(avgSpeed) + " km/h");

        // Pace in minutes per km
        float pace = totalDuration / (distanceKm > 0 ? distanceKm : 1);
        int paceMinutes = (int) (pace / 60);
        int paceSeconds = (int) (pace % 60);
        paceTextView.setText(String.format("%d:%02d min/km", paceMinutes, paceSeconds));
    }

    /**
     * 📈 Plot Speed Over Time Graph
     */
    private void plotSpeedGraph() {
        if (speedList.isEmpty()) return;

        speedGraph.removeAllSeries(); // Clear existing series before adding new data

        DataPoint[] dataPoints = new DataPoint[speedList.size()];
        for (int i = 0; i < speedList.size(); i++) {
            dataPoints[i] = new DataPoint(i, speedList.get(i));
        }

        LineGraphSeries<DataPoint> series = new LineGraphSeries<>(dataPoints);
        series.setColor(Color.BLUE);
        series.setThickness(6);

        speedGraph.setTitle("Speed-Time Graph of Trajectory");
        speedGraph.setTitleTextSize(50);
        speedGraph.setTitleColor(Color.BLACK);

        speedGraph.getGridLabelRenderer().setHorizontalAxisTitle("Time (s)");
        speedGraph.getGridLabelRenderer().setVerticalAxisTitle("Speed (m/s)");

        speedGraph.getGridLabelRenderer().setHorizontalLabelsColor(Color.BLACK);
        speedGraph.getGridLabelRenderer().setVerticalLabelsColor(Color.BLACK);

        speedGraph.getGridLabelRenderer().setGridColor(Color.GRAY);

        // Set up viewport for scaling & scrolling
        speedGraph.getViewport().setXAxisBoundsManual(true);
        speedGraph.getViewport().setMinX(0);
        speedGraph.getViewport().setMaxX(speedList.size());

        speedGraph.getViewport().setYAxisBoundsManual(true);
        speedGraph.getViewport().setMinY(0);
        speedGraph.getViewport().setMaxY(findMaxSpeed(speedList));

        speedGraph.getViewport().setScalable(true);  // Allow zooming
        speedGraph.getViewport().setScrollable(true); // Allow scrolling

        speedGraph.addSeries(series);
    }

    /**
     * Helper function to get max speed for graph scaling
     */
    private float findMaxSpeed(List<Float> speeds) {
        float maxSpeed = 0;
        for (float speed : speeds) {
            if (speed > maxSpeed) {
                maxSpeed = speed;
            }
        }
        return maxSpeed;
    }
}
