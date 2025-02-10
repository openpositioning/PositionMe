package com.openpositioning.PositionMe.viewitems;

import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

import java.lang.ref.WeakReference;

/**
 * View holder class for the RecyclerView displaying Trajectory download data.
 *
 * @see TrajDownloadListAdapter the corresponding list adapter.
 * @see com.openpositioning.PositionMe.R.layout#item_trajectorycard_view xml layout file
 *
 * @author Mate Stodulka
 */
public class TrajDownloadViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{

    TextView trajId;
    TextView trajDate;
    ImageButton downloadButton;
    ImageButton playButton;
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
    public TrajDownloadViewHolder(@NonNull View itemView, DownloadClickListener listener) {
        super(itemView);
        this.listenerReference = new WeakReference<>(listener);
        this.trajId = itemView.findViewById(R.id.trajectoryIdItem);
        this.trajDate = itemView.findViewById(R.id.trajectoryDateItem);
        this.downloadButton = itemView.findViewById(R.id.downloadTrajectoryButton);
        this.playButton = itemView.findViewById(R.id.play_button_item);

        this.downloadButton.setOnClickListener(this);
        this.playButton.setOnClickListener(this);
    }


    /**
     * {@inheritDoc}
     * Calls the onPositionClick function on the listenerReference object.
     */
    @Override
    public void onClick(View view) {
        DownloadClickListener listener = listenerReference.get();
        if (listener != null) {
            if (view.getId() == R.id.downloadTrajectoryButton) {
                listener.onPositionClicked(getAdapterPosition());
            } else if (view.getId() == R.id.play_button_item) {
                Navigation.findNavController(view)
                        .navigate(R.id.action_uploadFragment_to_mapsFragment);
            }

        }
    }
}
