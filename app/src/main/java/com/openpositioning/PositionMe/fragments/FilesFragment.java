package com.openpositioning.PositionMe.fragments;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.openpositioning.PositionMe.TrajectoryFileCallback;
import com.openpositioning.PositionMe.ServerCommunications;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.viewitems.TrajDownloadListAdapter;
import com.openpositioning.PositionMe.viewitems.DownloadClickListener;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.File;

/**
 * A simple {@link Fragment} subclass. The files fragments displays a list of trajectories already
 * uploaded with some metadata, and enabled re-downloading them to the device's local storage.
 *
 * @see HomeFragment the connected fragment in the nav graph.
 * @see UploadFragment sub-menu for uploading recordings that failed during recording.
 * @see com.openpositioning.PositionMe.Traj the data structure sent and received.
 * @see com.openpositioning.PositionMe.ServerCommunications the class handling communication with the server.
 *
 * @author Mate Stodulka
 */
public class FilesFragment extends Fragment implements Observer {

    // UI elements
    private RecyclerView filesList;
    private TrajDownloadListAdapter listAdapter;
    private CardView uploadCard;

    // Class handling HTTP communication
    private ServerCommunications serverCommunications;

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
        serverCommunications = new ServerCommunications(getActivity());//创建 ServerCommunications 实例，用于与服务器通信。
        serverCommunications.registerObserver(this);//注册 FilesFragment 作为 Observer，这一步只是注册观察者，不会立即触发网络请求
    }

    /**
     * {@inheritDoc}
     * Sets the title in the action bar.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_files, container, false);//加载名称为“fragment_files.xml的文件
        getActivity().setTitle("Trajectory recordings");//设置Activity的标题为“Trajectory recordings”
        return rootView;
    }

    /**
     * {@inheritDoc}
     * Initialises UI elements, including a navigation card to the {@link UploadFragment} and a
     * RecyclerView displaying online trajectories.
     *
     * @see com.openpositioning.PositionMe.viewitems.TrajDownloadViewHolder the View Holder for the list.
     * @see TrajDownloadListAdapter the list adapter for displaying the recycler view.
     * @see com.openpositioning.PositionMe.R.layout#item_trajectorycard_view the elements in the list.
     */
    //这是跳转upload的逻辑
    @Override//如果不加 @Override，万一拼写错了方法名（比如写成 onCreateViews()），编译器不会报错，而是 会把它当作一个新的方法，但系统不会调用它，从而导致 Fragment 无法正确初始化界面。
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Get recyclerview
        filesList = view.findViewById(R.id.filesList);
        // Get clickable card view
        uploadCard = view.findViewById(R.id.uploadCard);//找到UploadCard
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
        serverCommunications.sendInfoRequest();//这一行代码才真正让 FilesFragment 向服务器请求数据，并在收到响应后通知 Observer（即 FilesFragment）更新 UI。
    }
    //写一个根据卡片跳转replay的逻辑

    //写一个根据卡片完成下载的逻辑
    /**
     * {@inheritDoc}
     * Called by {@link ServerCommunications} when the response to the HTTP info request is received.
     *
     * @param singletonStringList   a single string wrapped in an object array containing the http
     *                              response from the server.
     */
    @Override
    public void update(Object[] singletonStringList) {
        // Cast input as a string
        String infoString = (String) singletonStringList[0];
        // Check if the string is non-null and non-empty before processing
        if(infoString != null && !infoString.isEmpty()) {
            // Process string
            //首先判断字符串是否有效，然后调用 processInfoResponse(infoString) 方法解析 JSON 数据，得到一个 List<Map<String, String>> 的列表，每个 Map 包含了如下键值对：
            //"id"：轨迹的唯一标识
            //"owner_id"：拥有者 ID
            List<Map<String, String>> entryList = processInfoResponse(infoString);//这一步是最重要的
            // Start a handler to be able to modify UI elements
            //为了保证对 UI 的操作在主线程中执行，通过 Handler(Looper.getMainLooper()).post() 将更新 RecyclerView 的代码切换到主线程执行。
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    // Update the RecyclerView with data from the server
                    updateView(entryList);
                }
            });
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
    private List<Map<String, String>> processInfoResponse(String infoString) {//processInfoResponse() 是 update() 方法内部调用的。
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
                entryList.add(entryMap);//加入card
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
     * @param entryList List of Maps of String to String containing metadata about the uploaded
     *                  trajectories (ID, owner ID, date).
     */
    private void updateView(List<Map<String, String>> entryList) {
        LinearLayoutManager manager = new LinearLayoutManager(getActivity());
        filesList.setLayoutManager(manager);
        filesList.setHasFixedSize(true);
        listAdapter = new TrajDownloadListAdapter(getActivity(), entryList, new DownloadClickListener() {
            @Override
            public void onPositionClicked(int position) {
                serverCommunications.downloadTrajectory(position);

                new AlertDialog.Builder(getContext())
                        .setTitle("File downloaded")
                        .setMessage("Trajectory downloaded to local storage")
                        .setPositiveButton(R.string.ok, null)
                        .setNegativeButton(R.string.show_storage, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
                            }
                        })
                        .setIcon(R.drawable.ic_baseline_download_24)
                        .show();
            }

            @Override
            public void onReplayClicked(int position) {//set Click to playback
                serverCommunications.downloadTrajectoryToTempFile(position, new TrajectoryFileCallback() {//Invoke the server download method
                    @Override
                    public void onFileReady(File file) {//TrajectoryFileCallback trajectories
                        Bundle bundle = new Bundle();
                        bundle.putString("trajectory_file_path", file.getAbsolutePath());
                        Navigation.findNavController(getView()).navigate(R.id.action_filesFragment_to_replayFragment, bundle);
                    }

                    @Override
                    public void onError(String errorMessage) {
                        new Handler(Looper.getMainLooper()).post(() ->
                                Toast.makeText(getContext(), "Error: " + errorMessage, Toast.LENGTH_LONG).show()
                        );
                    }
                });
            }
        });

        filesList.setAdapter(listAdapter);
    }

}