package com.openpositioning.PositionMe.presentation.viewitems;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.presentation.fragment.FilesFragment;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Adapter used for displaying Trajectory metadata in a RecyclerView list.
 *
 * @see TrajDownloadViewHolder the corresponding view holder.
 * @see FilesFragment on how the data is generated
 * @see ServerCommunications on where the response items are received.
 *
 * @author Mate
 */
public class TrajDownloadListAdapter extends RecyclerView.Adapter<TrajDownloadViewHolder> {

    // Date-time formatting object
    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Context context;
    private final List<Map<String, String>> responseItems;
    private final DownloadClickListener listener;

    /**
     * Default public constructor with context for inflating views and list to be displayed.
     *
     * @param context       application context to enable inflating views used in the list.
     * @param responseItems List of Maps, where each map is a response item from the server.
     * @param listener      clickListener to download trajectories when clicked.
     */
    public TrajDownloadListAdapter(Context context, List<Map<String, String>> responseItems, DownloadClickListener listener) {
        this.context = context;
        this.responseItems = responseItems;
        this.listener = listener;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public TrajDownloadViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new TrajDownloadViewHolder(LayoutInflater.from(context).inflate(R.layout.item_trajectorycard_view, parent, false), listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onBindViewHolder(@NonNull TrajDownloadViewHolder holder, int position) {
        String id = responseItems.get(position).get("id");
        holder.getTrajId().setText(id);
        if (id.length() > 2) {
            holder.getTrajId().setTextSize(58);
        } else {
            holder.getTrajId().setTextSize(65);
        }
        holder.getTrajDate().setText(
                dateFormat.format(
                        LocalDateTime.parse(
                                responseItems.get(position).get("date_submitted").split("\\.")[0]
                        )
                )
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getItemCount() {
        return responseItems.size();
    }
}
