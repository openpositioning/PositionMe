package com.openpositioning.PositionMe.viewitems;

import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

import java.lang.ref.WeakReference;

/**
 * ViewHolder for the trajectory list.
 * Handles both downloading and replaying trajectory files.
 */
public class TrajDownloadViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

    TextView trajId;
    TextView trajDate;
    ImageButton downloadButton;
    ImageButton playButton;  //   NEW: Play button for replaying trajectories.

    private WeakReference<DownloadClickListener> downloadListenerReference;
    private WeakReference<ReplayClickListener> replayListenerReference;  //   NEW: Replay click listener.

    /**
     * Constructor for the ViewHolder.
     *
     * @param itemView       The view of the list item.
     * @param downloadListener Listener for handling downloads.
     * @param replayListener   Listener for handling replay actions.
     */
    public TrajDownloadViewHolder(@NonNull View itemView, DownloadClickListener downloadListener, ReplayClickListener replayListener) {
        super(itemView);
        this.downloadListenerReference = new WeakReference<>(downloadListener);
        this.replayListenerReference = new WeakReference<>(replayListener);  //   Store replay listener.

        // Initialize UI elements.
        this.trajId = itemView.findViewById(R.id.trajectoryIdItem);
        this.trajDate = itemView.findViewById(R.id.trajectoryDateItem);
        this.downloadButton = itemView.findViewById(R.id.downloadTrajectoryButton);
        this.playButton = itemView.findViewById(R.id.playTrajectoryButton);  //   Find play button.

        // Set click listeners for both buttons.
        this.downloadButton.setOnClickListener(this);
        this.playButton.setOnClickListener(this);
    }

    /**
     * Handles button clicks for downloading and replaying.
     *
     * @param view The clicked view.
     */
    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.downloadTrajectoryButton) {
            //   Handle download button click.
            if (downloadListenerReference.get() != null) {
                downloadListenerReference.get().onPositionClicked(getAdapterPosition());
            }
        } else if (view.getId() == R.id.playTrajectoryButton) {
            //   Handle replay button click safely.
            if (replayListenerReference.get() != null) {
                replayListenerReference.get().onReplayClick(getAdapterPosition());
            }
        }
    }
}
