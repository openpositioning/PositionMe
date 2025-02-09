package com.openpositioning.PositionMe.viewitems;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

import java.util.List;
import java.util.Map;

/**
 * Adapter for the RecyclerView displaying trajectory recordings.
 *
 * This adapter is responsible for binding trajectory metadata to each list item in the RecyclerView.
 * It includes functionality for both downloading and replaying trajectories.
 */
public class TrajDownloadListAdapter extends RecyclerView.Adapter<TrajDownloadViewHolder> {

    private final Context context;
    private final List<Map<String, String>> trajectoryList;
    private final DownloadClickListener downloadListener;
    private final ReplayClickListener replayListener;  //  Added ReplayClickListener

    /**
     * Constructor for the list adapter.
     *
     * @param context The application context
     * @param trajectoryList The list of trajectory metadata
     * @param downloadListener Click listener for the download button
     * @param replayListener Click listener for the replay button (NEW)
     */
    public TrajDownloadListAdapter(Context context, List<Map<String, String>> trajectoryList,
                                   DownloadClickListener downloadListener, ReplayClickListener replayListener) {
        this.context = context;
        this.trajectoryList = trajectoryList;
        this.downloadListener = downloadListener;
        this.replayListener = replayListener;  //  Store the replayListener
    }

    /**
     * Called when RecyclerView needs a new ViewHolder.
     *
     * @param parent The parent view group
     * @param viewType The view type
     * @return A new TrajDownloadViewHolder
     */
    @NonNull
    @Override
    public TrajDownloadViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Inflate the layout for each trajectory item
        View view = LayoutInflater.from(context).inflate(R.layout.item_trajectorycard_view, parent, false);
        return new TrajDownloadViewHolder(view, downloadListener, replayListener);  //  Pass replayListener
    }

    /**
     * Binds data to each ViewHolder.
     *
     * @param holder The ViewHolder
     * @param position The position of the item in the list
     */
    @Override
    public void onBindViewHolder(@NonNull TrajDownloadViewHolder holder, int position) {
        // Get the trajectory metadata
        Map<String, String> trajectory = trajectoryList.get(position);

        // Set the trajectory ID and date in the list item
        holder.trajId.setText("ID: " + trajectory.get("id"));
        holder.trajDate.setText("Date: " + trajectory.get("date_submitted"));
    }

    /**
     * Returns the total number of items in the list.
     *
     * @return The number of trajectories
     */
    @Override
    public int getItemCount() {
        return trajectoryList.size();
    }
}
