package com.openpositioning.PositionMe.viewitems;

import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

import java.lang.ref.WeakReference;
import android.widget.ProgressBar;

/**
 * View holder class for the RecyclerView displaying Trajectory download data.
 *
 * @see TrajDownloadListAdapter the corresponding list adapter.
 * @see com.openpositioning.PositionMe.R.layout#item_trajectorycard_view xml layout file
 *
 * @author Mate Stodulka
 */
// Defines a class called TrajDownloadViewHolder, which inherits from RecyclerView.ViewHolder and implements the View.OnClickListener interface
public class TrajDownloadViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{

    TextView trajId;
    TextView trajDate;
    //ImageButton downloadButton;

    ImageButton replayButton;
    // Declares the ProgressBar to be private and does not expose the control directly
    private ProgressBar progressBarLoading;
    // Weak reference to the click listener to enable garbage collection on recyclerview items
    private WeakReference<DownloadClickListener> listenerReference;

    /**
     * {@inheritDoc}
     * Assign TextView fields corresponding to Trajectory metadata.
     *
     * @param listener DownloadClickListener to enable acting on clicks on items.
     *
     * @see com.openpositioning.PositionMe.fragments.FilesFragment generating the data and implementing the
     * listener.
     */
    //Set the binding event for each button in the TrajectoryReplay card
    public TrajDownloadViewHolder(@NonNull View itemView, DownloadClickListener listener) {
        super(itemView);
        this.listenerReference = new WeakReference<>(listener);
        this.trajId = itemView.findViewById(R.id.trajectoryIdItem);
        this.trajDate = itemView.findViewById(R.id.trajectoryDateItem);
        //this.downloadButton = itemView.findViewById(R.id.downloadTrajectoryButton);
        this.replayButton = itemView.findViewById(R.id.replayTrajectoryButton);
        //this.downloadButton.setOnClickListener(this);
        this.progressBarLoading = itemView.findViewById(R.id.progressBarLoading);
        this.replayButton.setOnClickListener(this);
    }
    @Override
    public void onClick(View view) {
        int id = view.getId();
        //if (id == R.id.downloadTrajectoryButton) {
            //listenerReference.get().onPositionClicked(getAdapterPosition());
        if (id == R.id.replayTrajectoryButton) {
            // The loading progress bar is displayed
            showLoading();
            listenerReference.get().onReplayClicked(getAdapterPosition());
        }
    }
    public void showLoading() {
        if (progressBarLoading != null) {
            progressBarLoading.setVisibility(View.VISIBLE);
        }
    }

    // Provides a public method to hide Progressbars
    public void hideLoading() {
        if (progressBarLoading != null) {
            progressBarLoading.setVisibility(View.GONE);
        }
    }
}
