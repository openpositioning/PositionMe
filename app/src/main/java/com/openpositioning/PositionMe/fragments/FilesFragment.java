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

import com.google.protobuf.util.JsonFormat;
import com.google.android.gms.maps.model.LatLng;
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
        // Note: The adapter expects a DownloadClickListener and a ReplayClickListener.
        // FilesFragment implements ReplayClickListener and we assume onDownloadClicked is defined.
        listAdapter = new TrajDownloadListAdapter(getActivity(), entryList, this::onDownloadClicked, this);
        filesList.setAdapter(listAdapter);
    }

    private void onDownloadClicked(int position) {
        serverCommunications.downloadTrajectory(position, fileName -> {

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
        // 获取文件路径

        final String[] trajectoryFileName = {null};
        CountDownLatch latch = new CountDownLatch(1);

        serverCommunications.downloadTrajectory(position, fileName -> {
            trajectoryFileName[0] = fileName;
            latch.countDown();
        });

        try {
            latch.await(); // Wait for download to finish
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), trajectoryFileName[0]);

        // 检查文件是否存在
        if (!file.exists()) {
            Log.e("FilesFragment", "Trajectory file not found!");
            Toast.makeText(getContext(), "Trajectory file not found. Please download it first.", Toast.LENGTH_SHORT).show();
            return;
        }

        // 读取 JSON 文件内容
        StringBuilder jsonContent = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                jsonContent.append(line);
            }
        } catch (IOException e) {
            Log.e("FilesFragment", "Error reading trajectory file: " + e.getMessage());
            Toast.makeText(getContext(), "Error reading trajectory file.", Toast.LENGTH_SHORT).show();
            return;
        }

        // **测试是否可以正确读取文件**
        Log.d("FilesFragment", "Trajectory file content: " + jsonContent.toString());
        Toast.makeText(getContext(), "Trajectory file read successfully!", Toast.LENGTH_SHORT).show();

        // **解析 JSON 数据**
        Traj.Trajectory.Builder trajectoryBuilder = Traj.Trajectory.newBuilder();
        try {
            JsonFormat.parser().merge(jsonContent.toString(), trajectoryBuilder);
        } catch (IOException e) {
            Log.e("FilesFragment", "Error parsing trajectory data: " + e.getMessage());
            Toast.makeText(getContext(), "Error parsing trajectory data.", Toast.LENGTH_SHORT).show();
            return;
        }

        // **构建 `Trajectory` 对象**
        Traj.Trajectory trajectory = trajectoryBuilder.build();
        Log.d("FilesFragment", "Trajectory data successfully parsed!");

        // **提取数据**
        List<LatLng> positionPoints = new ArrayList<>();
        List<Traj.Motion_Sample> imuDataList = new ArrayList<>();
        List<Traj.Pdr_Sample> pdrDataList = new ArrayList<>();
        List<Traj.Pressure_Sample> pressureDataList = new ArrayList<>();
        List<Traj.Light_Sample> lightDataList = new ArrayList<>();
        List<Traj.GNSS_Sample> gnssDataList = new ArrayList<>();
        List<Traj.WiFi_Sample> wifiDataList = new ArrayList<>();
        List<Traj.AP_Data> apsDataList = new ArrayList<>();

        // **提取 GNSS 数据 (真正的经纬度)**
        if (!trajectory.getGnssDataList().isEmpty()) {
            for (Traj.GNSS_Sample sample : trajectory.getGnssDataList()) {
                double lat = sample.getLatitude();   // 纬度
                double lng = sample.getLongitude();  // 经度
                positionPoints.add(new LatLng(lat, lng));
            }
            Log.d("FilesFragment", "GNSS data extracted successfully!");
        } else {
            Log.e("FilesFragment", "No GNSS data found!");
        }

        // **提取 IMU 传感器数据**
        if (!trajectory.getImuDataList().isEmpty()) {
            imuDataList.addAll(trajectory.getImuDataList());
            Log.d("FilesFragment", "IMU data extracted successfully!");
        } else {
            Log.e("FilesFragment", "No IMU data found!");
        }

        // **提取 PDR 数据**
        if (!trajectory.getPdrDataList().isEmpty()) {
            pdrDataList.addAll(trajectory.getPdrDataList());
            Log.d("FilesFragment", "PDR data extracted successfully!");
        } else {
            Log.e("FilesFragment", "No PDR data found!");
        }

        // **提取气压传感器数据**
        if (!trajectory.getPressureDataList().isEmpty()) {
            pressureDataList.addAll(trajectory.getPressureDataList());
            Log.d("FilesFragment", "Pressure data extracted successfully!");
        } else {
            Log.e("FilesFragment", "No Pressure data found!");
        }

        // **提取光照传感器数据**
        if (!trajectory.getLightDataList().isEmpty()) {
            lightDataList.addAll(trajectory.getLightDataList());
            Log.d("FilesFragment", "Light data extracted successfully!");
        } else {
            Log.e("FilesFragment", "No Light data found!");
        }

        // **提取 WiFi 采样数据**
        if (!trajectory.getWifiDataList().isEmpty()) {
            wifiDataList.addAll(trajectory.getWifiDataList());
            Log.d("FilesFragment", "WiFi data extracted successfully!");
        } else {
            Log.e("FilesFragment", "No WiFi data found!");
        }

        // **提取 AP (WiFi) 数据**
        if (!trajectory.getApsDataList().isEmpty()) {
            apsDataList.addAll(trajectory.getApsDataList());
            Log.d("FilesFragment", "AP data extracted successfully!");
        } else {
            Log.e("FilesFragment", "No AP data found!");
        }

        // **创建 Bundle 传输数据**
        Bundle args = new Bundle();
        args.putSerializable("trajectoryPoints", (ArrayList<LatLng>) positionPoints);
        args.putSerializable("imuData", (ArrayList<Traj.Motion_Sample>) imuDataList);
        args.putSerializable("pdrData", (ArrayList<Traj.Pdr_Sample>) pdrDataList);
        args.putSerializable("pressureData", (ArrayList<Traj.Pressure_Sample>) pressureDataList);
        args.putSerializable("lightData", (ArrayList<Traj.Light_Sample>) lightDataList);
        args.putSerializable("gnssData", (ArrayList<Traj.GNSS_Sample>) gnssDataList);
        args.putSerializable("wifiData", (ArrayList<Traj.WiFi_Sample>) wifiDataList);
        args.putSerializable("apsData", (ArrayList<Traj.AP_Data>) apsDataList);
        args.putLong("startTimestamp", trajectory.getStartTimestamp());
        args.putString("dataIdentifier", trajectory.getDataIdentifier());

        // **检查是否有有效数据**
        if (positionPoints.isEmpty()) {
            Log.e("FilesFragment", "No valid position data available!");
            Toast.makeText(getContext(), "No valid trajectory data to replay.", Toast.LENGTH_SHORT).show();
            return;
        }

        // **跳转到 ReplayFragment**
        Log.d("FilesFragment", "Navigating to ReplayFragment...");
        Navigation.findNavController(getView()).navigate(R.id.action_filesFragment_to_replayFragment, args);
    }
}
//public void onReplayClick(int position) {
//    Map<String, String> selectedEntry = entryList.get(position);
//    String trajectoryId = selectedEntry.get("id");
//    Bundle args = new Bundle();
//    args.putString("trajectoryId", trajectoryId);
//    Navigation.findNavController(getView()).navigate(R.id.action_filesFragment_to_replayFragment, args);
//}
//}