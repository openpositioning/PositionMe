package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
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
import com.openpositioning.PositionMe.ServerCommunications;
import com.openpositioning.PositionMe.viewitems.DownloadClickListener;
import com.openpositioning.PositionMe.viewitems.TrajReplayListAdapter;
import com.openpositioning.PositionMe.viewitems.TrajReplayViewHolder;
import com.openpositioning.PositionMe.viewitems.UploadListAdapter;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class ReplayFilesFragment extends Fragment {

    // UI elements
    private TextView emptyNotice;
    private RecyclerView replayList;
    private TrajReplayListAdapter listAdapter;
    private ImageButton DemoCard;

    // List of files saved locally
    private List<File> localTrajectories;

    /**
     * Public default constructor, empty.
     */
    public ReplayFilesFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Load local trajectories
        localTrajectories = Stream.of(getActivity().getFilesDir().listFiles((file, name) -> name.contains("trajectory_") && name.endsWith(".txt")))
                .filter(file -> !file.isDirectory())
                .collect(Collectors.toList());
    }

    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        getActivity().setTitle("Replay Files");
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_replay_files, container, false);
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

        this.emptyNotice = view.findViewById(R.id.emptyReplay);
        this.replayList = view.findViewById(R.id.replayTrajectories);

        // Demo button
        this.DemoCard = view.findViewById(R.id.demoTrajectoryButton);
        this.DemoCard.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                ReplayFilesFragmentDirections.ActionReplayFilesFragmentToReplayFragment action =
                        ReplayFilesFragmentDirections.actionReplayFilesFragmentToReplayFragment();

                action.setPosition(-1);
                Navigation.findNavController(requireView()).navigate(action);
            }
        });

        // Check if there are locally saved trajectories
        if (localTrajectories.isEmpty()) {
            replayList.setVisibility(View.GONE);
            emptyNotice.setVisibility(View.VISIBLE);
        } else {
            replayList.setVisibility(View.VISIBLE);
            emptyNotice.setVisibility(View.GONE);

            // Set up RecyclerView
            LinearLayoutManager manager = new LinearLayoutManager(getActivity());
            replayList.setLayoutManager(manager);
            replayList.setHasFixedSize(true);
            listAdapter = new TrajReplayListAdapter(getActivity(), localTrajectories, new DownloadClickListener() {

                @Override
                public void onDownloadClicked(int position) {
                    ReplayFilesFragmentDirections.ActionReplayFilesFragmentToReplayFragment action =
                            ReplayFilesFragmentDirections.actionReplayFilesFragmentToReplayFragment();

                    action.setPosition(position);
                    Navigation.findNavController(requireView()).navigate(action);
                }

                //@Override
                //public void onReplayClicked(int position) {

                //}
            });
            replayList.setAdapter(listAdapter);
        }
    }
}

