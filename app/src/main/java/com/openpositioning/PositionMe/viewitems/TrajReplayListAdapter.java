package com.openpositioning.PositionMe.viewitems;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 *
 */
public class TrajReplayListAdapter extends RecyclerView.Adapter<TrajReplayViewHolder>{


    private final Context context;
    private final List<File> replayItems;
    private final DownloadClickListener listener;

    /**
     * Default public constructor with context for inflating views and list to be displayed.
     *
     * @param context       application context to enable inflating views used in the list.
     * @param replayItems List of Maps, where each map is a response item from the server.
     * @param listener      clickListener to download trajectories when clicked.
     *
     * @see com.openpositioning.PositionMe.Traj protobuf objects exchanged with the server.
     */
    public TrajReplayListAdapter(Context context, List<File> replayItems, DownloadClickListener listener) {
        this.context = context;
        this.replayItems = replayItems;
        this.listener = listener;
    }

    @NonNull
    @Override
    public TrajReplayViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new TrajReplayViewHolder(LayoutInflater.from(context).inflate(R.layout.item_replay_card_view, parent, false), listener);
    }

    @Override
    public void onBindViewHolder(@NonNull TrajReplayViewHolder holder, int position) {
        holder.replayId.setText(String.valueOf(position));
        // Creates a pattern object of files, used to create a Matcher object
        Pattern datePattern = Pattern.compile("_(.*?)\\.txt");
        Matcher dateMatcher = datePattern.matcher(replayItems.get(position).getName());
        String dateString = dateMatcher.find() ? dateMatcher.group(1) : "N/A";
        System.err.println("REPLAY - Date string: " + dateString);
        holder.replayDate.setText(dateString);
    }

    @Override
    public int getItemCount() {
        return replayItems.size();
    }
}