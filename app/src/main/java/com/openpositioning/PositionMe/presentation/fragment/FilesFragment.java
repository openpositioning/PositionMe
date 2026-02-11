package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.presentation.viewitems.TrajDownloadListAdapter;
import com.openpositioning.PositionMe.presentation.viewitems.TrajDownloadViewHolder;
import com.openpositioning.PositionMe.sensors.Observer;

// ✅ Step D: venue selection store
import com.openpositioning.PositionMe.utils.IndoorSelectionStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // ✅ New: selected venue label on the Files screen
    private TextView currentVenueText;

    // Class handling HTTP communication
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
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_files, container, false);
        getActivity().setTitle("Trajectory recordings");
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // ✅ Selected venue label
        currentVenueText = view.findViewById(R.id.currentVenueText);
        updateSelectedVenueLabel();

        // Get recyclerview
        filesList = view.findViewById(R.id.filesList);

        // Get clickable card view
        uploadCard = view.findViewById(R.id.uploadCard);
        uploadCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                NavDirections action = FilesFragmentDirections.actionFilesFragmentToUploadFragment();
                Navigation.findNavController(view).navigate(action);
            }
        });

        // Request list of uploaded trajectories from the server
        serverCommunications.sendInfoRequest();

        // Force RecyclerView refresh to ensure icon states are correct
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (filesList.getAdapter() != null) {
                filesList.getAdapter().notifyDataSetChanged();
                System.out.println("RecyclerView refreshed after page load.");
            }
        }, 500);
    }

    /**
     * Step D: show currently selected venue (venue_id / venue_name) in the Files screen.
     * This is demo-friendly and makes the “data collection venue” visible in-app.
     */
    private void updateSelectedVenueLabel() {
        if (currentVenueText == null || getActivity() == null) return;

        String venueName = IndoorSelectionStore.getSelectedVenueName(getActivity());
        String venueId = IndoorSelectionStore.getSelectedVenueId(getActivity());

        String label;
        if (venueName != null && !venueName.trim().isEmpty()) {
            label = venueName.trim();
        } else if (venueId != null && !venueId.trim().isEmpty()) {
            label = venueId.trim();
        } else {
            label = "unknown";
        }

        currentVenueText.setText("Selected venue: " + label);
    }

    @Override
    public void update(Object[] singletonStringList) {
        String infoString = (String) singletonStringList[0];

        if (infoString != null && !infoString.isEmpty()) {
            List<Map<String, String>> entryList = processInfoResponse(infoString);

            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    // ✅ Keep venue label fresh when returning to this screen
                    updateSelectedVenueLabel();

                    updateView(entryList);
                }
            });
        }
    }

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

        entryList.sort(Comparator.comparing(
                m -> Integer.parseInt(m.get("id")),
                Comparator.nullsLast(Comparator.naturalOrder())
        ));
        return entryList;
    }

    private void updateView(List<Map<String, String>> entryList) {
        LinearLayoutManager manager = new LinearLayoutManager(getActivity());
        filesList.setLayoutManager(manager);
        filesList.setHasFixedSize(true);

        listAdapter = new TrajDownloadListAdapter(getActivity(), entryList, position -> {
            Map<String, String> selectedItem = entryList.get(position);
            String id = selectedItem.get("id");
            String dateSubmitted = selectedItem.get("date_submitted");

            serverCommunications.downloadTrajectory(position, id, dateSubmitted);
        });

        filesList.setAdapter(listAdapter);
        listAdapter.notifyDataSetChanged();
    }
}
