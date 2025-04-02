package com.openpositioning.PositionMe.fragments;

import android.app.AlertDialog;
import android.os.Bundle;
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

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.ServerCommunications;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.viewitems.DownloadClickListener;
import com.openpositioning.PositionMe.viewitems.TrajDownloadListAdapter;
import com.openpositioning.PositionMe.viewitems.TrajDownloadViewHolder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.text.SimpleDateFormat;

/**
 * A simple {@link Fragment} subclass. The files fragments displays a list of trajectories already
 * uploaded with some metadata, and enabled re-downloading them to the device's local storage.
 *
 * @see HomeFragment the connected fragment in the nav graph.
 * @see UploadFragment sub-menu for uploading recordings that failed during recording.
 * @see com.openpositioning.PositionMe.Traj the data structure sent and received.
 * @see ServerCommunications the class handling communication with the server.
 *
 * @author Mate Stodulka
 */
public class FilesFragment extends Fragment implements Observer {

    // UI elements
    private RecyclerView filesList;
    private TrajDownloadListAdapter listAdapter;
    private CardView uploadCard;
    private View rootView;  // 添加这个变量来存储根视图

    // Class handling HTTP communication
    private ServerCommunications serverCommunications;

    // 添加一个 Set 来记录已下载的文件 ID
    private Set<String> downloadedFiles = new HashSet<>();

    // 添加一个变量来跟踪当前下载的位置
    private int currentDownloadPosition = -1;

    // 添加 entryList 作为类的成员变量
    private List<Map<String, String>> entryList = new ArrayList<>();

    private Map<String, String> cloudToLocalFileMap = new HashMap<>();

    /**
     * Default public constructor, empty.
     */
    public FilesFragment() {
        // Required empty public constructor
    }

    /**
     * {@inheritDoc}
     * Initialise the server communication class and register the FilesFragment as an Observer to
     * receive the async http responses.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        serverCommunications = new ServerCommunications(getActivity());
        serverCommunications.registerObserver(this);
    }

    /**
     * {@inheritDoc}
     * Sets the title in the action bar.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_files, container, false);
        getActivity().setTitle("Trajectory recordings");
        return view;
    }

    /**
     * {@inheritDoc}
     * Initialises UI elements, including a navigation card to the {@link UploadFragment} and a
     * RecyclerView displaying online trajectories.
     *
     * @see TrajDownloadViewHolder the View Holder for the list.
     * @see TrajDownloadListAdapter the list adapter for displaying the recycler view.
     * @see R.layout#item_trajectorycard_view the elements in the list.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Get recyclerview
        filesList = view.findViewById(R.id.filesList);
        // Get clickable card view
        uploadCard = view.findViewById(R.id.uploadCard);
        uploadCard.setOnClickListener(new View.OnClickListener() {
            /**
             * {@inheritDoc}
             * Navigates to {@link UploadFragment}.
             */
            @Override
            public void onClick(View view) {
                NavDirections action = FilesFragmentDirections.actionFilesFragmentToUploadFragment();
                Navigation.findNavController(view).navigate(action);
            }
        });

        // Request list of uploaded trajectories from the server.
        serverCommunications.sendInfoRequest();
    }

    /**
     * {@inheritDoc}
     * Called by {@link ServerCommunications} when the response to the HTTP info request is received.
     *
     * @param singletonStringList   a single string wrapped in an object array containing the http
     *                              response from the server.
     */
    @Override
    public void update(Object[] data) {
        if (data[0] instanceof Boolean) {
            boolean success = (Boolean) data[0];
            if (success) {
                Log.d("FilesFragment", "Download success notification received");
                if (getCurrentDownloadPosition() != -1) {
                    String id = entryList.get(getCurrentDownloadPosition()).get("id");
                    downloadedFiles.add(id);
                    Log.d("FilesFragment", "Current download position: " + getCurrentDownloadPosition());
                    Log.d("FilesFragment", "Updating UI for trajectory ID: " + id);
                    
                    // 更新 UI
                    requireActivity().runOnUiThread(() -> {
                        if (filesList != null && filesList.getAdapter() != null) {
                            filesList.getAdapter().notifyItemChanged(getCurrentDownloadPosition());
                            Log.d("FilesFragment", "Updated button visibility");
                        }
                    });
                }
            } else {
                // 下载失败时显示提示
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "Downloading ...", Toast.LENGTH_SHORT).show();
                });
            }
        } else if (data[0] instanceof String) {
            String infoString = (String) data[0];
            Log.d("FilesFragment", "Received info string: " + (infoString != null ? infoString.substring(0, Math.min(100, infoString.length())) : "null"));
            if(infoString != null && !infoString.isEmpty()) {
                this.entryList = processInfoResponse(infoString);
                new Handler(Looper.getMainLooper()).post(() -> {
                    updateView(this.entryList);
                });
            }
        }
    }

    /**
     * Parses the info response string from the HTTP communication.
     * Process the data using the Json library and return the matching Java data structure as a
     * List of Maps of \<String, String\>. Throws a JSONException if the data is not valid.
     *
     * @param infoString    HTTP info request response as a single string
     * @return              List of Maps of String to String containing ID, owner ID, and date.
     */
    private List<Map<String, String>> processInfoResponse(String infoString) {
        // Initialise empty list
        List<Map<String, String>> entryList = new ArrayList<>();
        try {
            // Attempt to decode using known JSON pattern
            JSONArray jsonArray = new JSONArray(infoString);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject trajectoryEntry = jsonArray.getJSONObject(i);
                Map<String, String> entryMap = new HashMap<>();
                entryMap.put("owner_id", String.valueOf(trajectoryEntry.get("owner_id")));
                entryMap.put("date_submitted", (String) trajectoryEntry.get("date_submitted"));
                entryMap.put("id", String.valueOf(trajectoryEntry.get("id")));
                // Add decoded map to list of entries
                entryList.add(entryMap);
            }
        } catch (JSONException e) {
            System.err.println("JSON reading failed");
            e.printStackTrace();
        }
        // Sort the list by the ID fields of the maps
        entryList.sort(Comparator.comparing(m -> Integer.parseInt(m.get("id")), Comparator.nullsLast(Comparator.naturalOrder())));
        return entryList;
    }

    /**
     * Update the RecyclerView in the FilesFragment with new data.
     * Must be called from a UI thread. Initialises a new Layout Manager, and passes it to the
     * RecyclerView. Initialises a {@link TrajDownloadListAdapter} with the input array and setting
     * up a listener so that trajectories are downloaded when clicked, and a pop-up message is
     * displayed to notify the user.
     *
     * @param newEntryList List of Maps of String to String containing metadata about the uploaded
     *                  trajectories (ID, owner ID, date).
     */
    private void updateView(List<Map<String, String>> newEntryList) {
        // 更新类的成员变量
        this.entryList = newEntryList;
        
        // Initialise RecyclerView with Manager and Adapter
        LinearLayoutManager manager = new LinearLayoutManager(getActivity());
        filesList.setLayoutManager(manager);
        filesList.setHasFixedSize(true);
        listAdapter = new TrajDownloadListAdapter(getActivity(), entryList, new DownloadClickListener() {
            @Override
            public void onPositionClicked(int position) {
                Log.d("FilesFragment", "Download clicked for position: " + position);
                currentDownloadPosition = position;
                
                // 获取轨迹信息
                Map<String, String> trajectory = entryList.get(position);
                Log.d("FilesFragment", "Downloading trajectory with ID: " + trajectory.get("id"));
                
                // 设置 entryList
                serverCommunications.setEntryList(entryList);
                
                // 显示下载中对话框
                AlertDialog downloadingDialog = new AlertDialog.Builder(getContext())
                    .setTitle("Downloading...")
                    .setMessage("Please wait...")
                    .setCancelable(false)
                    .show();
                
                // 开始下载
                Log.d("FilesFragment", "Starting download...");
                serverCommunications.downloadTrajectory(position);
                
                // 3秒后关闭对话框
                new Handler().postDelayed(() -> {
                    Log.d("FilesFragment", "Closing download dialog");
                    downloadingDialog.dismiss();
                }, 3000);
            }

            @Override
            public void onReplayClicked(int position) {
                try {
                    // 在location_logs目录中查找所有轨迹文件
                    File directory = new File(getActivity().getExternalFilesDir(null), "location_logs");
                    File[] allFiles = directory.listFiles((dir, name) -> 
                        name.startsWith("location_log_local_") && name.endsWith(".json")
                    );
                    
                    if (allFiles != null && allFiles.length > 0) {
                        // 找到最新的文件
                        File latestFile = allFiles[0];
                        for (File file : allFiles) {
                            if (file.lastModified() > latestFile.lastModified()) {
                                latestFile = file;
                            }
                        }
                        
                        Log.d("FilesFragment", "Using latest file: " + latestFile.getAbsolutePath());
                        Log.d("FilesFragment", "File last modified: " + new java.util.Date(latestFile.lastModified()));
                        
                        // 如果文件修改时间在最近5分钟内，直接使用该文件
                        if ((System.currentTimeMillis() - latestFile.lastModified()) < 5 * 60 * 1000) {
                            Log.d("FilesFragment", "Using recently modified file");
                            NavDirections action = FilesFragmentDirections.actionFilesFragmentToReplayFragment(latestFile.getAbsolutePath());
                            Navigation.findNavController(requireView()).navigate(action);
                            return;
                        }
                        
                        // 如果不是最近的文件，则按照原来的逻辑查找匹配的文件
                        Map<String, String> trajectory = entryList.get(position);
                        String utcDateStr = trajectory.get("date_submitted");
                        
                        // 解析UTC时间字符串
                        SimpleDateFormat utcFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX");
                        utcFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                        java.util.Date utcDate = utcFormat.parse(utcDateStr);
                        
                        // 转换为本地时间
                        SimpleDateFormat localFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm");
                        localFormat.setTimeZone(java.util.TimeZone.getDefault());
                        String minuteTimestamp = localFormat.format(utcDate); // 格式：YYYY-MM-DD_HH-mm
                        
                        Log.d("FilesFragment", "Looking for local file with timestamp: " + minuteTimestamp);
                        
                        // 查找匹配的文件
                        File[] matchingFiles = directory.listFiles((dir, name) -> {
                            try {
                                if (!name.startsWith("location_log_local_") || !name.endsWith(".json")) {
                                    return false;
                                }
                                
                                String timestampPart = name.substring("location_log_local_".length(), name.length() - 5);
                                String[] parts = timestampPart.split("[-_]");
                                
                                if (parts.length < 5) {
                                    return false;
                                }
                                
                                String fileTimestamp = parts[0] + "-" + parts[1] + "-" + parts[2] + "_" + 
                                                     parts[3] + "-" + parts[4];
                                
                                Log.d("FilesFragment", "Comparing timestamps - File: " + fileTimestamp + ", Target: " + minuteTimestamp);
                                return fileTimestamp.equals(minuteTimestamp);
                            } catch (Exception e) {
                                return false;
                            }
                        });
                        
                        if (matchingFiles != null && matchingFiles.length > 0) {
                            File matchingFile = matchingFiles[0];
                            for (File file : matchingFiles) {
                                if (file.lastModified() > matchingFile.lastModified()) {
                                    matchingFile = file;
                                }
                            }
                            
                            Log.d("FilesFragment", "Found matching file: " + matchingFile.getAbsolutePath());
                            NavDirections action = FilesFragmentDirections.actionFilesFragmentToReplayFragment(matchingFile.getAbsolutePath());
                            Navigation.findNavController(requireView()).navigate(action);
                            return;
                        }
                        
                        // 如果没有找到匹配的文件，使用最新的文件
                        Log.d("FilesFragment", "No matching file found, using latest file");
                        NavDirections action = FilesFragmentDirections.actionFilesFragmentToReplayFragment(latestFile.getAbsolutePath());
                        Navigation.findNavController(requireView()).navigate(action);
                    } else {
                        Log.e("FilesFragment", "No local files found");
                        Toast.makeText(getContext(), "未找到本地轨迹文件", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Log.e("FilesFragment", "Error in onReplayClicked", e);
                    Toast.makeText(getContext(), "回放失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }) {
            @Override
            public void onBindViewHolder(@NonNull TrajDownloadViewHolder holder, int position) {
                super.onBindViewHolder(holder, position);
                
                // 恢复下载状态
                String id = entryList.get(position).get("id");
                if (downloadedFiles.contains(id)) {
                    holder.downloadButton.setVisibility(View.GONE);
                    holder.replayButton.setVisibility(View.VISIBLE);
                }
            }
        };
        
        filesList.setAdapter(listAdapter);
    }

    // 修改 getTrajectoryFilePath 方法，添加参数控制是否显示提示
    private String getTrajectoryFilePath(int position, List<Map<String, String>> entryList, boolean showToast) {
        Map<String, String> trajectory = entryList.get(position);
        String id = trajectory.get("id");
        String date = trajectory.get("date_submitted").split("\\.")[0].replace(":", "-");
        
        // 使用和下载时相同的文件名格式
        String fileName = String.format("location_log_%s_%s.json", date, id);
        
        File directory = new File(getActivity().getExternalFilesDir(null), "location_logs");
        File file = new File(directory, fileName);
        
        // 只在需要时显示提示
        if (!file.exists() && showToast) {
            Log.e("FilesFragment", "Trajectory file not found: " + file.getAbsolutePath());
            Toast.makeText(getContext(), "请先下载轨迹文件", Toast.LENGTH_SHORT).show();
        }
        
        return file.getAbsolutePath();
    }

    private int getCurrentDownloadPosition() {
        return currentDownloadPosition;
    }

    // 添加一个辅助方法来解析时间戳
    private long parseTimestamp(String timestamp) {
        try {
            // 格式：YYYY-MM-DD_HH-mm
            String[] parts = timestamp.split("[-_]");
            if (parts.length != 5) {
                return 0;
            }
            
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int day = Integer.parseInt(parts[2]);
            int hour = Integer.parseInt(parts[3]);
            int minute = Integer.parseInt(parts[4]);
            
            return (long) year * 100000000 + month * 1000000 + day * 10000 + hour * 100 + minute;
        } catch (Exception e) {
            Log.e("FilesFragment", "Error parsing timestamp: " + timestamp, e);
            return 0;
        }
    }
}