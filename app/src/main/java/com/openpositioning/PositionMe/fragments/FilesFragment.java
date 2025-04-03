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
 * A simple {@link Fragment} subclass. The files fragment displays a list of trajectories already
 * uploaded with some metadata, and enables re-downloading them to the device's local storage.
 *
 * @see HomeFragment the connected fragment in the nav graph.
 * @see UploadFragment sub-menu for uploading recordings that failed during recording.
 * @see com.openpositioning.PositionMe.Traj the data structure sent and received.
 * @see com.openpositioning.PositionMe.ServerCommunications the class handling communication with the server.
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
        serverCommunications = new ServerCommunications(getActivity()); // Create ServerCommunications instance for server communication
        serverCommunications.registerObserver(this); // Register FilesFragment as an observer (does not immediately trigger a request)
    }

    /**
     * {@inheritDoc}
     * Sets the title in the action bar.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_files, container, false); // Inflate fragment_files.xml
        getActivity().setTitle("Trajectory recordings"); // Set the activity title
        return rootView;
    }

    /**
     * {@inheritDoc}
     * Initialises UI elements, including a navigation card to the {@link UploadFragment} and a
     * RecyclerView displaying online trajectories.
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
        serverCommunications.sendInfoRequest(); // This line actually triggers the request to the server.
    }

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
        if(infoString != null && !infoString.isEmpty()) {
            List<Map<String, String>> entryList = processInfoResponse(infoString);
            new Handler(Looper.getMainLooper()).post(() -> updateView(entryList));
        }
    }

    /**
     * Parses the info response string from the HTTP communication.
     * Processes the data using the JSON library and returns a List of Maps.
     *
     * @param infoString HTTP info request response as a single string
     * @return List of Maps containing ID, owner ID, and date
     */
    private List<Map<String, String>> processInfoResponse(String infoString) {
        List<Map<String, String>> entryList = new ArrayList<>();
        try {
            JSONArray jsonArray = new JSONArray(infoString);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject trajectoryEntry = jsonArray.getJSONObject(i);
                Map<String, String> entryMap = new HashMap<>();
                entryMap.put("owner_id", String.valueOf(trajectoryEntry.get("owner_id")));
                entryMap.put("date_submitted", (String) trajectoryEntry.get("date_submitted"));
                entryMap.put("id", String.valueOf(trajectoryEntry.get("id")));
                entryList.add(entryMap);
            }
        } catch (JSONException e) {
            System.err.println("JSON reading failed");
            e.printStackTrace();
        }
        entryList.sort(Comparator.comparing(m -> Integer.parseInt(m.get("id")), Comparator.nullsLast(Comparator.naturalOrder())));
        return entryList;
    }

    /**
     * Updates the RecyclerView in the FilesFragment with new data.
     * Must be called from the UI thread.
     *
     * @param entryList List of Maps with metadata about uploaded trajectories.
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
                        .setNegativeButton(R.string.show_storage, (dialogInterface, i) -> {
                            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
                        })
                        .setIcon(R.drawable.ic_baseline_download_24)
                        .show();
            }

            @Override
            public void onReplayClicked(int position) {
                serverCommunications.downloadTrajectoryToTempFile(position, new TrajectoryFileCallback() {
                    @Override
                    public void onFileReady(File file) {
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