package com.openpositioning.PositionMe.fragments;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

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
import com.openpositioning.PositionMe.viewitems.TrajDownloadListAdapter;
import com.openpositioning.PositionMe.viewitems.ReplayClickListener;  //   Updated interface name

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fragment displaying uploaded trajectories. Users can download and replay trajectories.
 *
 * @author Mate Stodulka
 */
public class FilesFragment extends Fragment implements Observer, ReplayClickListener {  //   Implementing ReplayClickListener

    // UI elements
    private RecyclerView filesList;
    private TrajDownloadListAdapter listAdapter;
    private CardView uploadCard;
    private List<Map<String, String>> entryList = new ArrayList<>();  //   Stores trajectory metadata

    // Class handling HTTP communication
    private ServerCommunications serverCommunications;

    /**
     * Default public constructor.
     */
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

        // Initialize RecyclerView
        filesList = view.findViewById(R.id.filesList);
        uploadCard = view.findViewById(R.id.uploadCard);

        // Set up Upload button navigation
        uploadCard.setOnClickListener(v -> {
            NavDirections action = FilesFragmentDirections.actionFilesFragmentToUploadFragment();
            Navigation.findNavController(view).navigate(action);
        });

        //   Request list of trajectories from server
        serverCommunications.sendInfoRequest();
    }

    /**
     * Handles the server's response and updates the RecyclerView with trajectory data.
     */
    @Override
    public void update(Object[] singletonStringList) {
        String infoString = (String) singletonStringList[0];

        if (infoString != null && !infoString.isEmpty()) {
            entryList = processInfoResponse(infoString);

            // Update UI from the main thread
            new Handler(Looper.getMainLooper()).post(() -> updateView(entryList));
        }
    }

    /**
     * Parses the JSON response from the server and extracts trajectory metadata.
     */
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
            System.err.println("JSON parsing failed: " + e.getMessage());
        }

        parsedList.sort(Comparator.comparing(m -> Integer.parseInt(m.get("id")), Comparator.nullsLast(Comparator.naturalOrder())));
        return parsedList;
    }

    /**
     * Updates the RecyclerView with trajectory data.
     */
    private void updateView(List<Map<String, String>> entryList) {
        LinearLayoutManager manager = new LinearLayoutManager(getActivity());
        filesList.setLayoutManager(manager);
        filesList.setHasFixedSize(true);

        //   Added ReplayClickListener for handling play button clicks
        listAdapter = new TrajDownloadListAdapter(getActivity(), entryList, this::onDownloadClicked, this);
        filesList.setAdapter(listAdapter);
    }

    /**
     * Handles the Download button click event.
     */
    private void onDownloadClicked(int position) {
        serverCommunications.downloadTrajectory(position);

        new AlertDialog.Builder(getContext())
                .setTitle("File Downloaded")
                .setMessage("Trajectory downloaded to local storage.")
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.show_storage, (dialogInterface, i) ->
                        startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)))
                .setIcon(R.drawable.ic_baseline_download_24)
                .show();
    }

    /**
     *   Handles the Play button click event, launching the ReplayFragment.
     */
    @Override
    public void onPlayClicked(int position) {
        Map<String, String> selectedEntry = entryList.get(position);
        String trajectoryId = selectedEntry.get("id");

        //  Pass the trajectory ID to the ReplayFragment
        Bundle args = new Bundle();
        args.putString("trajectoryId", trajectoryId);

        // Create and launch ReplayFragment
        Fragment replayFragment = new ReplayFragment();
        replayFragment.setArguments(args);

        Navigation.findNavController(getView()).navigate(R.id.action_filesFragment_to_replayFragment);

    }}
