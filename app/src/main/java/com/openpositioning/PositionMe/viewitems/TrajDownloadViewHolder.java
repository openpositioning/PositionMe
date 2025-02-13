package com.openpositioning.PositionMe.viewitems;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.ServerCommunications;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.fragments.FilesFragmentDirections;

import java.io.IOException;
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

    Button playButton;

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
        // add in the playback button and add the listener function - Jamie A
        this.playButton = itemView.findViewById(R.id.playButton);

        this.downloadButton.setOnClickListener(this);
        /**
         * Listener function for the playButton added which navigates to replayFragment and
         * passes the trajectory via the NavDirections. Converted the trajectory to JSON to make the argument passing
         * easier as strings can be passed without causing 'non parcelable[]' errors (at least from what I saw)
         * @ Author - Jamie Arnott
         */
        this.playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String trajectoryID = trajId.getText().toString();

                // Fetch the trajectory before navigating
                ServerCommunications serverCommunications = new ServerCommunications(view.getContext());
                serverCommunications.downloadTrajectory(getAdapterPosition(), new ServerCommunications.TrajectoryDownloadCallback() {
                    @Override
                    public void onTrajectoryDownloaded(Traj.Trajectory trajectory) { // ✅ Required method
                        Log.d("ReplayFragment", "Downloaded trajectory: " + trajectory.toString());

                        // Convert to JSON format to pass as argument for navigation to ReplayFragment
                        String trajectoryJson;
                        try {
                            trajectoryJson = com.google.protobuf.util.JsonFormat.printer().print(trajectory);
                        } catch (Exception e) {
                            Log.e("ReplayFragment", "Error converting trajectory to JSON", e);
                            return; // Stop execution if conversion fails
                        }
                        // handler to handle the navigation to ReplayFragment
                        new Handler(Looper.getMainLooper()).post(() -> {
                            NavDirections action = FilesFragmentDirections.actionFilesFragmentToReplayFragment(trajectoryJson);
                            Navigation.findNavController(view).navigate(action);
                        });
                    }




                    @Override
                    public void onFailure(IOException e) {
                        e.printStackTrace();
                    }
                });
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