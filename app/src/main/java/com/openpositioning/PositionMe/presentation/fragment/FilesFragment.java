package com.openpositioning.PositionMe.presentation.fragment;

import static com.openpositioning.PositionMe.utils.UtilConstants.URL_GET_USER_TRAJECTORIES;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.LoginManager;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.presentation.viewitems.TrajDownloadListAdapter;
import com.openpositioning.PositionMe.presentation.viewitems.TrajDownloadViewHolder;
import com.openpositioning.PositionMe.sensors.Observer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A simple {@link Fragment} subclass. The files fragments displays a list of trajectories already
 * uploaded with some metadata, and enabled re-downloading them to the device's local storage.
 *
 * @see HomeFragment the connected fragment in the nav graph.
 * @see UploadFragment sub-menu for uploading recordings that failed during recording.
 * @see com.openpositioning.PositionMe.Traj the data structure sent and received.
 * @see ServerCommunications the class handling communication with the server.
 * @author Mate Stodulka
 */
public class FilesFragment extends Fragment implements Observer {
    private static final String TAG = "FilesFragment";

    private static final String[] sortMethods = new String[] {"Latest First", "Oldest First"};

    // UI elements
    private RecyclerView filesList;
    private TrajDownloadListAdapter listAdapter;
    private CardView uploadCard;
    private Spinner spinnerSortBy;
    private Comparator<Integer> sortMethod = Comparator.reverseOrder();

    // Class handling HTTP communication
    private ServerCommunications serverCommunications;
    private LoginManager loginManager;
    private List<Map<String, String>> entryList = new ArrayList<>();

    /** Default public constructor, empty. */
    public FilesFragment() {
        // Required empty public constructor
    }

    /**
     * {@inheritDoc} Initialise the server communication class and register the FilesFragment as an
     * Observer to receive the async http responses.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        serverCommunications = new ServerCommunications(getActivity());
        serverCommunications.registerObserver(this);
        loginManager = LoginManager.getInstance();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (serverCommunications == null) {
            serverCommunications = new ServerCommunications(getActivity());
        }
        serverCommunications.registerObserver(this);

        if (loginManager == null) {
            loginManager = LoginManager.getInstance();
        }
    }

    /** {@inheritDoc} Sets the title in the action bar. */
    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_files, container, false);
        getActivity().setTitle("Trajectory Recordings - " + loginManager.getUsername());
        return rootView;
    }

    /**
     * {@inheritDoc} Initialises UI elements, including a navigation card to the {@link
     * UploadFragment} and a RecyclerView displaying online trajectories.
     *
     * @see TrajDownloadViewHolder the View Holder for the list.
     * @see TrajDownloadListAdapter the list adapter for displaying the recycler view.
     * @see com.openpositioning.PositionMe.R.layout#item_trajectorycard_view the elements in the
     *     list.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Get recyclerview
        filesList = view.findViewById(R.id.filesList);
        // Get clickable card view
        uploadCard = view.findViewById(R.id.uploadCard);
        uploadCard.setOnClickListener(
                new View.OnClickListener() {
                    /** {@inheritDoc} Navigates to {@link UploadFragment}. */
                    @Override
                    public void onClick(View view) {
                        NavDirections action =
                                FilesFragmentDirections.actionFilesFragmentToUploadFragment();
                        Navigation.findNavController(view).navigate(action);
                    }
                });

        spinnerSortBy = view.findViewById(R.id.spinnerSortBy);
        initialiseSpinner();

        // Request list of uploaded trajectories from the server.
        serverCommunications.requestPathsFromServer(URL_GET_USER_TRAJECTORIES);

        // Force RecyclerView refresh to ensure icon states are correct
        new Handler(Looper.getMainLooper())
                .postDelayed(
                        () -> {
                            if (filesList.getAdapter() != null) {
                                filesList.getAdapter().notifyDataSetChanged();
                                Log.i("FilesFragment", "RecyclerView refreshed after page load.");
                            }
                        },
                        500);
    }

    private void initialiseSpinner() {
        if (spinnerSortBy == null) return;

        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(
                        requireContext(),
                        android.R.layout.simple_spinner_dropdown_item,
                        sortMethods);
        spinnerSortBy.setAdapter(adapter);
        spinnerSortBy.setSelection(0);

        spinnerSortBy.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {

                    @Override
                    public void onItemSelected(
                            AdapterView<?> adapterView, View view, int position, long l) {
                        switch (position) {
                            case 0:
                                sortMethod = Comparator.reverseOrder();
                                break;
                            case 1:
                                sortMethod = Comparator.naturalOrder();
                                break;
                            default:
                                sortMethod = Comparator.reverseOrder();
                                break;
                        }
                        updateEntryList();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {
                        sortMethod = Comparator.reverseOrder();
                    }
                });
    }

    /**
     * {@inheritDoc} Called by {@link ServerCommunications} when the response to the HTTP info
     * request is received.
     *
     * @param objList The response from the server, including a {@link Boolean} value of success and
     *     the server's response as a string.
     */
    @Override
    public void update(Object[] objList) {
        Boolean success = (boolean) objList[0];
        String infoString = objList[1].toString();

        // Check if the string is non-null and non-empty before processing
        if (infoString != null && !infoString.isEmpty()) {
            // Process string
            entryList = processInfoResponse(infoString);
            updateEntryList();
        }
    }

    /**
     * Parses the info response string from the HTTP communication. Process the data using the Json
     * library and return the matching Java data structure as a List of Maps of \<String, String\>.
     * Throws a JSONException if the data is not valid.
     *
     * @param infoString HTTP info request response as a single string
     * @return List of Maps of String to String containing ID, owner ID, and date.
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
                entryMap.put("date_submitted", trajectoryEntry.get("date_submitted").toString());
                entryMap.put("id", String.valueOf(trajectoryEntry.get("id")));
                // Store the original index
                entryMap.put("original_index", String.valueOf(i));
                // Add decoded map to list of entries
                entryList.add(entryMap);
            }
        } catch (JSONException e) {
            Log.e("FilesFragment", "JSON reading failed: " + e.getMessage());
        }
        // Sort the list by the ID fields of the maps
        entryList.sort(
                Comparator.comparing(
                        m -> Integer.parseInt(m.get("id")), Comparator.nullsLast(sortMethod)));
        return entryList;
    }

    private void updateEntryList() {
        entryList.sort(
                Comparator.comparing(
                        m -> Integer.parseInt(m.get("id")), Comparator.nullsLast(sortMethod)));
        // Start a handler to be able to modify UI elements
        new Handler(Looper.getMainLooper())
                .post(
                        new Runnable() {
                            @Override
                            public void run() {
                                // Update the RecyclerView with data from the server
                                updateView(entryList);
                            }
                        });
    }

    /**
     * Update the RecyclerView in the FilesFragment with new data. Must be called from a UI thread.
     * Initialises a new Layout Manager, and passes it to the RecyclerView. Initialises a {@link
     * TrajDownloadListAdapter} with the input array and setting up a listener so that trajectories
     * are downloaded when clicked.
     *
     * @param entryList List of Maps of String to String containing metadata about the uploaded
     *     trajectories (ID, owner ID, date).
     */
    private void updateView(List<Map<String, String>> entryList) {
        // Initialise RecyclerView with Manager and Adapter
        LinearLayoutManager manager = new LinearLayoutManager(getActivity());
        filesList.setLayoutManager(manager);
        filesList.setHasFixedSize(true);
        listAdapter =
                new TrajDownloadListAdapter(
                        getActivity(),
                        entryList,
                        position -> {
                            Map<String, String> selectedItem = entryList.get(position);
                            String id = selectedItem.get("id");
                            String dateSubmitted = selectedItem.get("date_submitted");

                            int originalIndex =
                                    Integer.parseInt(selectedItem.get("original_index"));

                            // Pass ID and date_submitted
                            serverCommunications.downloadTrajectory(
                                    originalIndex, id, dateSubmitted);
                        });
        filesList.setAdapter(listAdapter);

        // Force refresh RecyclerView to ensure downloadRecords changes are detected
        listAdapter.notifyDataSetChanged();
    }
}
