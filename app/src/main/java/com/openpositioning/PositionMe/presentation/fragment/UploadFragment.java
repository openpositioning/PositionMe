package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.os.Environment;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.presentation.viewitems.UploadViewHolder;
import com.openpositioning.PositionMe.presentation.viewitems.DownloadClickListener;
import com.openpositioning.PositionMe.presentation.viewitems.UploadListAdapter;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Fragment responsible for displaying and manually uploading locally saved trajectories.
 * Triggered when network was unavailable during recording.
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

    public UploadFragment() {
        // Required empty public constructor
    }

    /**
     * Initializes ServerCommunication and scans local storage for trajectory files.
     * Supports both legacy ("trajectory_") and new ("Traj_") naming conventions.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        serverCommunications = new ServerCommunications(getActivity());

        File trajectoriesDir = null;

        // Path selection based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            trajectoriesDir = getActivity().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (trajectoriesDir == null) {
                trajectoriesDir = getActivity().getFilesDir();
            }
        } else {
            trajectoriesDir = getActivity().getFilesDir();
        }

        // [Fix] Updated filter to include new "Traj_" filenames
        if (trajectoriesDir != null && trajectoriesDir.exists()) {
            localTrajectories = Stream.of(trajectoriesDir.listFiles((file, name) ->
                            (name.startsWith("trajectory_") || name.startsWith("Traj_")) && name.endsWith(".txt")))
                    .filter(file -> !file.isDirectory())
                    .collect(Collectors.toList());
        } else {
            localTrajectories = new java.util.ArrayList<>();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        getActivity().setTitle("Upload");
        return inflater.inflate(R.layout.fragment_upload, container, false);
    }

    /**
     * Sets up the RecyclerView to display local files.
     * Attaches a click listener to trigger the manual upload.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.emptyNotice = view.findViewById(R.id.emptyUpload);
        this.uploadList = view.findViewById(R.id.uploadTrajectories);

        // Toggle visibility based on file existence
        if(localTrajectories.isEmpty()) {
            uploadList.setVisibility(View.GONE);
            emptyNotice.setVisibility(View.VISIBLE);
        }
        else {
            uploadList.setVisibility(View.VISIBLE);
            emptyNotice.setVisibility(View.GONE);

            // Configure RecyclerView
            LinearLayoutManager manager = new LinearLayoutManager(getActivity());
            uploadList.setLayoutManager(manager);
            uploadList.setHasFixedSize(true);

            listAdapter = new UploadListAdapter(getActivity(), localTrajectories, new DownloadClickListener() {
                @Override
                public void onPositionClicked(int position) {
                    // Trigger upload for the selected file
                    // Ensure uploadLocalTrajectory exists in ServerCommunications.java
                    serverCommunications.uploadLocalTrajectory(localTrajectories.get(position));

                    // UI update logic (Optional: remove item immediately or wait for callback)
                    // localTrajectories.remove(position);
                    // listAdapter.notifyItemRemoved(position);
                }
            });
            uploadList.setAdapter(listAdapter);
        }
    }
}