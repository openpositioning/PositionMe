package com.openpositioning.PositionMe.fragments;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.maps.model.LatLng;
import com.google.protobuf.util.JsonFormat;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.ServerCommunications;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.viewitems.ReplayClickListener;
import com.openpositioning.PositionMe.viewitems.TrajDownloadListAdapter;
import com.openpositioning.PositionMe.Traj; // your protobuf-generated class

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public class FilesFragment extends Fragment implements Observer, ReplayClickListener {

    private static final String TAG = "FilesFragment";
    private RecyclerView filesList;
    private TrajDownloadListAdapter listAdapter;
    private CardView uploadCard;
    private List<Map<String, String>> entryList = new ArrayList<>();
    private ServerCommunications serverCommunications;

    public FilesFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        serverCommunications = new ServerCommunications(getActivity());
        serverCommunications.registerObserver(this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_files, container, false);
        getActivity().setTitle("Trajectory Recordings");
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        filesList = view.findViewById(R.id.filesList);
        uploadCard = view.findViewById(R.id.uploadCard);

        uploadCard.setOnClickListener(v -> {
            NavDirections action = FilesFragmentDirections.actionFilesFragmentToUploadFragment();
            Navigation.findNavController(view).navigate(action);
        });

        // Request the list of trajectories from the server
        serverCommunications.sendInfoRequest();
    }

    @Override
    public void update(Object[] singletonStringList) {
        String infoString = (String) singletonStringList[0];
        if (infoString != null && !infoString.isEmpty()) {
            entryList = processInfoResponse(infoString);
            new Handler(Looper.getMainLooper()).post(() -> updateView(entryList));
        }
    }

    private List<Map<String, String>> processInfoResponse(String infoString) {
        List<Map<String, String>> parsedList = new ArrayList<>();
        try {
            JSONArray jsonArray = new JSONArray(infoString);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject trajectoryEntry = jsonArray.getJSONObject(i);
                Map<String, String> entryMap = new HashMap<>();
                entryMap.put("owner_id", trajectoryEntry.getString("owner_id"));
                entryMap.put("date_submitted", trajectoryEntry.getString("date_submitted"));
                entryMap.put("id", trajectoryEntry.getString("id"));
                parsedList.add(entryMap);
            }
        } catch (JSONException e) {
            Log.e(TAG, "JSON parsing failed: " + e.getMessage());
        }
        parsedList.sort(Comparator.comparing(m -> Integer.parseInt(m.get("id")), Comparator.nullsLast(Comparator.naturalOrder())));
        return parsedList;
    }

    private void updateView(List<Map<String, String>> entryList) {
        LinearLayoutManager manager = new LinearLayoutManager(getActivity());
        filesList.setLayoutManager(manager);
        filesList.setHasFixedSize(true);
        // The adapter expects a DownloadClickListener and a ReplayClickListener.
        listAdapter = new TrajDownloadListAdapter(getActivity(), entryList, this::onDownloadClicked, this);
        filesList.setAdapter(listAdapter);
    }

    private void onDownloadClicked(int position) {
        serverCommunications.downloadTrajectory(position, fileName -> {
            // Callback (if needed)
        });
        new AlertDialog.Builder(getContext())
                .setTitle("File Downloaded")
                .setMessage("Trajectory downloaded to local storage.")
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.show_storage, (dialogInterface, i) ->
                        startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)))
                .setIcon(R.drawable.ic_baseline_download_24)
                .show();
    }

    @Override
    public void onReplayClick(int position) {
        // Download the trajectory file first
        final String[] trajectoryFileName = {null};
        CountDownLatch latch = new CountDownLatch(1);

        serverCommunications.downloadTrajectory(position, fileName -> {
            trajectoryFileName[0] = fileName;
            latch.countDown();
        });

        try {
            latch.await(); // Wait for the download to finish
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), trajectoryFileName[0]);

        // Check if file exists
        if (!file.exists()) {
            Log.e(TAG, "Trajectory file not found!");
            Toast.makeText(getContext(), "Trajectory file not found. Please download it first.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Read the JSON file content
        StringBuilder jsonContent = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                jsonContent.append(line);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading trajectory file: " + e.getMessage());
            Toast.makeText(getContext(), "Error reading trajectory file.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Log the file content for testing
        Log.d(TAG, "Trajectory file content: " + jsonContent.toString());
        Toast.makeText(getContext(), "Trajectory file read successfully!", Toast.LENGTH_SHORT).show();

        // Parse the JSON data into a Traj.Trajectory object
        Traj.Trajectory.Builder trajectoryBuilder = Traj.Trajectory.newBuilder();
        try {
            JsonFormat.parser().merge(jsonContent.toString(), trajectoryBuilder);
        } catch (IOException e) {
            Log.e(TAG, "Error parsing trajectory data: " + e.getMessage());
            Toast.makeText(getContext(), "Error parsing trajectory data.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Build the Trajectory object
        Traj.Trajectory trajectory = trajectoryBuilder.build();
        Log.d(TAG, "Trajectory data successfully parsed!");

        // Extract GNSS (position) data
        List<LatLng> positionPoints = new ArrayList<>();
        if (!trajectory.getGnssDataList().isEmpty()) {
            for (Traj.GNSS_Sample sample : trajectory.getGnssDataList()) {
                double lat = sample.getLatitude();
                double lng = sample.getLongitude();
                positionPoints.add(new LatLng(lat, lng));
            }
            Log.d(TAG, "GNSS data extracted successfully!");
        } else {
            Log.e(TAG, "No GNSS data found!");
        }

        // (Extract additional data if needed...)

        // Check if valid position data exists
        if (positionPoints.isEmpty()) {
            Log.e(TAG, "No valid position data available!");
            Toast.makeText(getContext(), "No valid trajectory data to replay.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create a Bundle to pass data to the ReplayFragment
        Bundle args = new Bundle();
        // IMPORTANT: Use putParcelableArrayList instead of putSerializable for LatLng!
        args.putParcelableArrayList("trajectoryPoints", new ArrayList<>(positionPoints));
        // (Pass additional data if ReplayFragment requires it)
        args.putLong("startTimestamp", trajectory.getStartTimestamp());
        args.putString("dataIdentifier", trajectory.getDataIdentifier());

        // Navigate to ReplayFragment
        Log.d(TAG, "Navigating to ReplayFragment...");
        Navigation.findNavController(getView()).navigate(R.id.action_filesFragment_to_replayFragment, args);
    }
}
