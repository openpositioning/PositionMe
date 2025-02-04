package com.openpositioning.PositionMe.viewitems;

import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

/**
 * View holder class for the RecyclerView displaying Trajectory files to be uploaded.
 *
 * @see UploadListAdapter the corresponding list adapter.
 * @see com.openpositioning.PositionMe.R.layout#item_upload_card_view xml layout file
 *
 * @author Mate Stodulka
 */
public class UploadViewHolder extends RecyclerView.ViewHolder {

    TextView trajId;
    TextView trajDate;
    ImageButton uploadButton;

    ImageButton replayButton;
    // Weak reference to the click listener to enable garbage collection on recyclerview items
    public Button deletebutton;
    /**
     * {@inheritDoc}
     * Assign TextView fields corresponding to Trajectory file metadata.
     *
     * @param listener DownloadClickListener to enable acting on clicks on items.
     *
     * @see com.openpositioning.PositionMe.fragments.UploadFragment locating the data and implementing the
     * listener.
     */
    public UploadViewHolder(@NonNull View itemView, DownloadClickListener listener) {
        super(itemView);
        this.trajId = itemView.findViewById(R.id.trajectoryIdItem);
        this.trajDate = itemView.findViewById(R.id.trajectoryDateItem);
        this.uploadButton = itemView.findViewById(R.id.uploadTrajectoryButton);
        this.uploadButton.setOnClickListener(v -> listener.onPositionClicked(getAdapterPosition()));
        this.replayButton = itemView.findViewById(R.id.replay_upload);
        this.replayButton.setOnClickListener(v -> listener.onPlayClicked(getAdapterPosition()));
        this.deletebutton = itemView.findViewById(R.id.deletebutton);
    }

}
