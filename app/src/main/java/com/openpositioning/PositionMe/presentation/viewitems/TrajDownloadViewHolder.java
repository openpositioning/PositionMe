package com.openpositioning.PositionMe.presentation.viewitems;

import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.FilesFragment;

import java.lang.ref.WeakReference;

/**
 * View holder class for the RecyclerView displaying Trajectory download data.
 *
 * @see TrajDownloadListAdapter the corresponding list adapter.
 * @see com.openpositioning.PositionMe.R.layout#item_trajectorycard_view xml layout file
 *
 * @author Mate Stodulka
 */
public class TrajDownloadViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

    private final TextView trajId;
    private final TextView trajDate;
    final MaterialButton downloadButton;
    private final WeakReference<DownloadClickListener> listenerReference;

    /**
     * {@inheritDoc}
     * Assign TextView fields corresponding to Trajectory metadata.
     *
     * @param listener DownloadClickListener to enable acting on clicks on items.
     * @see FilesFragment generating the data and implementing the listener.
     */
    public TrajDownloadViewHolder(@NonNull View itemView, DownloadClickListener listener) {
        super(itemView);
        this.listenerReference = new WeakReference<>(listener);
        this.trajId = itemView.findViewById(R.id.trajectoryIdItem);
        this.trajDate = itemView.findViewById(R.id.trajectoryDateItem);
        this.downloadButton = itemView.findViewById(R.id.downloadTrajectoryButton);

        this.downloadButton.setOnClickListener(this);
    }

    /**
     * Public getter for trajId.
     */
    public TextView getTrajId() {
        return trajId;
    }

    /**
     * Public getter for trajDate.
     */
    public TextView getTrajDate() {
        return trajDate;
    }

    /**
     * Calls the onPositionClick function on the listenerReference object.
     */
    @Override
    public void onClick(View view) {
        listenerReference.get().onPositionClicked(getAdapterPosition());
        DownloadClickListener listener = listenerReference.get();
        if (listener != null) {
            listener.onPositionClicked(getAdapterPosition());
            System.out.println("Click detected at position: " + getAdapterPosition());
        } else {
            System.err.println("Listener reference is null.");
        }
    }
}
