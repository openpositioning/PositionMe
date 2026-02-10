package com.openpositioning.PositionMe.presentation.fragment;

import android.content.SharedPreferences;
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
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.presentation.viewitems.UploadViewHolder;
import com.openpositioning.PositionMe.presentation.viewitems.DownloadClickListener;
import com.openpositioning.PositionMe.presentation.viewitems.UploadListAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * A simple {@link Fragment} subclass. Displays trajectories that were saved locally.
 * FIXED: Now correctly detects files starting with "traj_"
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
    private SharedPreferences settings;

    // List of files saved locally
    private List<File> localTrajectories;

    public UploadFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Get communication class
        serverCommunications = new ServerCommunications(getActivity());
        settings = PreferenceManager.getDefaultSharedPreferences(getActivity());

        // Determine the directory to load trajectory files from.
        File trajectoriesDir = null;

        // for android 13 or higher use dedicated external storage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            trajectoriesDir = getActivity().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (trajectoriesDir == null) {
                trajectoriesDir = getActivity().getFilesDir();
            }
        } else { // for android 12 or lower use internal storage
            trajectoriesDir = getActivity().getFilesDir();
        }

        // Filter trajectory files (both old and new formats)
        File[] files = trajectoriesDir.listFiles((file, name) ->
                name.startsWith("traj_") && name.endsWith(".txt"));

        if (files != null) {
            localTrajectories = Stream.of(files)
                    .filter(file -> !file.isDirectory())
                    .collect(Collectors.toList());
        } else {
            localTrajectories = new ArrayList<>();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        getActivity().setTitle("Upload");
        return inflater.inflate(R.layout.fragment_upload, container, false);
    }

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
                @Override
                public void onPositionClicked(int position) {
                    // Read campaign from SharedPreferences, default to empty string
                    String campaign = settings.getString("current_campaign", "");
                    serverCommunications.uploadLocalTrajectory(localTrajectories.get(position), campaign);
                }
            });
            uploadList.setAdapter(listAdapter);
        }
    }
}