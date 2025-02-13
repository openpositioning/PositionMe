package com.openpositioning.PositionMe.viewitems;

import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.fragments.FilesFragmentDirections;

import java.lang.ref.WeakReference;

/**
 * View holder class for the RecyclerView displaying Trajectory download data.
 *
 * @see TrajDownloadListAdapter the corresponding list adapter.
 * @see com.openpositioning.PositionMe.R.layout#item_trajectorycard_view xml layout file
 *
 * @author Mate Stodulka
 * @author Fraser Bunting
 * @author Laura Maryakhina
 */
public class TrajDownloadViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{

    ImageButton playButton;
    TextView trajId;
    TextView trajDate;
    ImageButton downloadButton;
    // Weak reference to the click listener to enable garbage collection on recyclerview items
    private WeakReference<DownloadClickListener> downloadClickListenerReference;
    private WeakReference<DownloadClickListener> playClickListenerReference;

    /**
     * {@inheritDoc}
     * Assign TextView fields corresponding to Trajectory metadata.
     *
     * @param downloadClickListener DownloadClickListener to enable acting on clicks on download.
     * @param downloadClickListener DownloadClickListener to enable acting on clicks on play button.
     *
     * @see com.openpositioning.PositionMe.fragments.FilesFragment generating the data and implementing the
     * downloadClickListener.
     */
    public TrajDownloadViewHolder(@NonNull View itemView, DownloadClickListener downloadClickListener, DownloadClickListener playClickListener) {
        super(itemView);
        this.downloadClickListenerReference = new WeakReference<>(downloadClickListener);
        this.playClickListenerReference = new WeakReference<>(playClickListener);
        this.trajId = itemView.findViewById(R.id.trajectoryIdItem);
        this.trajDate = itemView.findViewById(R.id.trajectoryDateItem);
        this.downloadButton = itemView.findViewById(R.id.downloadTrajectoryButton);
        this.playButton = itemView.findViewById(R.id.playTrajectoryButton);

        this.downloadButton.setOnClickListener(this);
        this.playButton.setOnClickListener(this);

    }


    /**
     * {@inheritDoc}
     * Calls the onPositionClick function on the listenerReference object of the button that was
     * clicked.
     */
    @Override
    public void onClick(View view) {
        //if-else statement may not be necessary here, if view.getId() isn't of the buttons
        if (view.getId() == R.id.downloadTrajectoryButton) {
            downloadClickListenerReference.get().onPositionClicked(getAdapterPosition());
        } else if (view.getId() == R.id.playTrajectoryButton) {
            playClickListenerReference.get().onPositionClicked(getAdapterPosition());
            // Handle play button click by navigating to MapFragment
            NavDirections action = FilesFragmentDirections.actionFilesFragmentToReplayFragment();
            Navigation.findNavController(itemView).navigate(action);
        }
    }

}