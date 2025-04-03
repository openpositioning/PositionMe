package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import android.os.Environment;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.openpositioning.PositionMe.MainActivity;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.ReplayDataProcessor;
import com.openpositioning.PositionMe.ServerCommunications;
import com.openpositioning.PositionMe.Traj;
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
     * Default public constructor (required for Fragment).
     */
    public UploadFragment() {
        // Required empty public constructor
    }

    /**
     * Initializes the server communication instance and retrieves all local trajectory files
     * from the appropriate storage directory based on the Android version.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize communication with the server
        serverCommunications = new ServerCommunications(getActivity());

        // Determine directory to load trajectory files from
        File trajectoriesDir;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // For Android 13 or above, use the external documents directory
            trajectoriesDir = getActivity().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (trajectoriesDir == null) {
                trajectoriesDir = getActivity().getFilesDir(); // Fallback to internal storage
            }
        } else {
            // For Android 12 and below, use internal storage
            trajectoriesDir = getActivity().getFilesDir();
        }

        // Load files that match the trajectory naming pattern
        localTrajectories = Stream.of(trajectoriesDir.listFiles((file, name) ->
                        name.contains("trajectory_") && name.endsWith(".txt")))
                .filter(file -> !file.isDirectory())
                .collect(Collectors.toList());
    }

    /**
     * Called when the fragment resumes. Ensures the bottom navigation is visible
     * and disables the back button override in MainActivity.
     */
    @Override
    public void onResume() {
        super.onResume();

        // Show BottomNavigationView
        BottomNavigationView bottomNav = getActivity().findViewById(R.id.bottom_navigation);
        if (bottomNav != null) {
            bottomNav.setVisibility(View.VISIBLE);
        }

        // Disable back button override while in this fragment
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null && activity.onBackPressedCallback != null) {
            activity.onBackPressedCallback.setEnabled(false);
        }
    }

    /**
     * Re-enables back button interception when the fragment is paused.
     */
    @Override
    public void onPause() {
        super.onPause();

        MainActivity activity = (MainActivity) getActivity();
        if (activity != null && activity.onBackPressedCallback != null) {
            activity.onBackPressedCallback.setEnabled(true);
        }
    }

    /**
     * Sets the title of the fragment to "Upload".
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        getActivity().setTitle("Upload");
        return inflater.inflate(R.layout.fragment_upload, container, false);
    }

    /**
     * Sets up the UI after view creation. If there are no local trajectories,
     * shows a message. Otherwise, populates the RecyclerView with available files.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.emptyNotice = view.findViewById(R.id.emptyUpload);
        this.uploadList = view.findViewById(R.id.uploadTrajectories);

        if (localTrajectories.isEmpty()) {
            // Show empty state
            uploadList.setVisibility(View.GONE);
            emptyNotice.setVisibility(View.VISIBLE);
        } else {
            // Show RecyclerView with trajectory files
            uploadList.setVisibility(View.VISIBLE);
            emptyNotice.setVisibility(View.GONE);

            // Configure RecyclerView
            LinearLayoutManager manager = new LinearLayoutManager(getActivity());
            uploadList.setLayoutManager(manager);
            uploadList.setHasFixedSize(true);

            listAdapter = new UploadListAdapter(getActivity(), localTrajectories, new DownloadClickListener() {

                /**
                 * Uploads the selected file to the server when the upload button is clicked.
                 * Optionally removes it from the list.
                 */
                @Override
                public void onPositionClicked(int position) {
                    serverCommunications.uploadLocalTrajectory(localTrajectories.get(position));
//                    localTrajectories.remove(position);
//                    listAdapter.notifyItemRemoved(position);
                }

                /**
                 * Handles the replay button click by parsing the trajectory and switching to Replay view.
                 */
                @Override
                public void onReplayClicked(int position) {
                    File replayFile = localTrajectories.get(position);

                    if (replayFile == null) {
                        Toast.makeText(getContext(), "Trajectory file not found, cannot invoke replay!", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Traj.Trajectory trajectory = ReplayDataProcessor.protoDecoder(replayFile);
                    if (trajectory == null) {
                        Toast.makeText(getContext(), "Trajectory empty, cannot invoke replay!", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    ReplayDataProcessor.TrajRecorder replayProcessor =
                            ReplayDataProcessor.TrajRecorder.getInstance();
                    replayProcessor.setReplayFile(trajectory);

                    // Navigate to the replay fragment
                    FragmentTransaction transaction = getParentFragmentManager().beginTransaction();
                    transaction.replace(R.id.fragment_container, new ReplayTrajFragment());
                    transaction.addToBackStack(null);
                    transaction.commit();
                }
            });

            uploadList.setAdapter(listAdapter);
        }
    }
}
