package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.ServerCommunications;
import com.openpositioning.PositionMe.viewitems.DownloadClickListener;
import com.openpositioning.PositionMe.viewitems.UploadListAdapter;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A simple {@link Fragment} subclass. Displays trajectories that were saved locally because no
 * acceptable network was available to upload it when the recording finished. Trajectories can be
 * uploaded manually.
 *
 * @author Mate Stodulka
 */
public class UploadFragment extends Fragment {

    // UI elements
    private TextView emptyNotice;
    private RecyclerView uploadList;
    private UploadListAdapter listAdapter;

    // Server communication class
    private ServerCommunications serverCommunications;

    // List of files saved locally
    private List<File> localTrajectories;

    /**
     * Public default constructor, empty.
     */
    public UploadFragment() {
        // Required empty public constructor
    }


    /**
     * {@inheritDoc}
     * Initialises new Server Communication instance with the context, and finds all the files that
     * match the trajectory naming scheme in local storage.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Get communication class
        serverCommunications = new ServerCommunications(getActivity());
        // Load local trajectories
        localTrajectories = Stream.of(getActivity().getFilesDir().listFiles((file, name) -> name.contains("trajectory_") && name.endsWith(".txt")))
                .filter(file -> !file.isDirectory())
                .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     * Sets the title in the action bar to "Upload"
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        getActivity().setTitle("Upload");
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_upload, container, false);
    }

    /**
     * {@inheritDoc}
     * Checks if there are locally saved trajectories. If there are none, it displays a text message
     * notifying the user. If there are local files, the text is hidden, and instead a Recycler View
     * is displayed showing all the trajectories.
     * <p>
     * A Layout Manager is registered, and the adapter and list of files passed. An onClick listener
     * is set up to upload the file when clicked and remove it from local storage.
     *
     * @see UploadListAdapter list adapter for the recycler view.
     * @see com.openpositioning.PositionMe.viewitems.UploadViewHolder view holder for the recycler view.
     * @see com.openpositioning.PositionMe.R.layout#item_upload_card_view xml view for list elements.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.emptyNotice = view.findViewById(R.id.emptyUpload);
        this.uploadList = view.findViewById(R.id.uploadTrajectories);
        // Check if there are locally saved trajectories
        if(localTrajectories.isEmpty()) {
            uploadList.setVisibility(View.GONE);
            emptyNotice.setVisibility(View.VISIBLE);
        }
        else {
            uploadList.setVisibility(View.VISIBLE);
            emptyNotice.setVisibility(View.GONE);

            // Set up RecyclerView
            LinearLayoutManager manager = new LinearLayoutManager(getActivity());
            uploadList.setLayoutManager(manager);
            uploadList.setHasFixedSize(true);
            listAdapter = new UploadListAdapter(getActivity(), localTrajectories, new DownloadClickListener() {
                /**
                 * {@inheritDoc}
                 * Upload the trajectory at the clicked position, remove it from the recycler view
                 * and the local list.
                 */
                @Override
                public void onPositionClicked(int position) {
                    serverCommunications.uploadLocalTrajectory(localTrajectories.get(position));
//                    localTrajectories.remove(position);
//                    listAdapter.notifyItemRemoved(position);
                }
            });
            uploadList.setAdapter(listAdapter);


        }
    }

}