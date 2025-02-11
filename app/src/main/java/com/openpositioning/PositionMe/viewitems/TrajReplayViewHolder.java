package com.openpositioning.PositionMe.viewitems;

import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

import java.lang.ref.WeakReference;


/**
 * Use the {@link TrajReplayViewHolder} factory method to
 * create an instance of this fragment.
 */
public class TrajReplayViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{

    TextView replayId;
    TextView replayDate;
    ImageButton replayButton;

    // Create WeakReference to the DownloadClickListener interface for RecyclerView garbage collection
    public WeakReference<DownloadClickListener> listenerReference;

    /**
     * {@inheritDoc}
     * Assign TextView fields corresponding to Trajectory file metadata.
     *
     * @param listener DownloadClickListener to enable acting on clicks on items.
     *
     * @see com.openpositioning.PositionMe.fragments.UploadFragment locating the data and implementing the
     * listener.
     */

    public TrajReplayViewHolder(@NonNull View itemView, DownloadClickListener listener) {
        super(itemView);

        this.listenerReference = new WeakReference<>(listener);
        this.replayId = itemView.findViewById(R.id.replayIdItem);
        this.replayDate = itemView.findViewById(R.id.replayDateItem);
        this.replayButton = itemView.findViewById(R.id.replayTrajectoryButton);

        this.replayButton.setOnClickListener(this);
    }

    @Override
    public void onClick(View view) {
        listenerReference.get().onReplayClicked(getAdapterPosition());
    }





}