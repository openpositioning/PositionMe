package com.openpositioning.PositionMe.viewitems;

import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.fragments.UploadFragment;

import java.lang.ref.WeakReference;

/**
 * View holder class for the RecyclerView displaying Trajectory files to be uploaded.
 *
 * @see UploadListAdapter the corresponding list adapter.
 * @see com.openpositioning.PositionMe.R.layout#item_upload_card_view xml layout file
 *
 * @author Mate Stodulka
 */
public class UploadViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

    TextView trajId;
    TextView trajDate;
    ImageButton uploadButton;
    // Weak reference to the click listener to enable garbage collection on recyclerview items
    private WeakReference<DownloadClickListener> listenerReference;
    public ImageButton deletebutton;

    public ImageButton replaybutton;

    /**
     * {@inheritDoc}
     * Assign TextView fields corresponding to Trajectory file metadata.
     *
     * @param listener DownloadClickListener to enable acting on clicks on items.
     *
     * @see UploadFragment locating the data and implementing the
     * listener.
     */
    public UploadViewHolder(@NonNull View itemView, DownloadClickListener listener) {
        super(itemView);

        this.listenerReference = new WeakReference<>(listener);
        this.trajId = itemView.findViewById(R.id.trajectoryIdItem);
        this.trajDate = itemView.findViewById(R.id.trajectoryDateItem);
        this.uploadButton = itemView.findViewById(R.id.uploadTrajectoryButton);

        this.uploadButton.setOnClickListener(this);
        this.deletebutton = itemView.findViewById(R.id.deletebutton);

        this.replaybutton = itemView.findViewById(R.id.replayTrajectoryButton);
        this.replaybutton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // call when the replay button is pressed
                if (listenerReference.get() != null) {
                    listenerReference.get().onReplayClicked(getAdapterPosition());
                }
            }
        });
    }

    /**
     * {@inheritDoc}
     * Calls the onPositionClick function on the listenerReference object.
     */
    @Override
    public void onClick(View view) {
        listenerReference.get().onPositionClicked(getAdapterPosition());
    }
}
