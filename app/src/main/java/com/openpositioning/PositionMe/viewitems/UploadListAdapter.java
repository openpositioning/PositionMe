package com.openpositioning.PositionMe.viewitems;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter used for displaying local Trajectory file data
 *
 * @see UploadViewHolder corresponding View Holder class
 * @see com.openpositioning.PositionMe.R.layout#item_upload_card_view xml layout file
 *
 * @author Mate Stodulka
 */
public class UploadListAdapter extends RecyclerView.Adapter<UploadViewHolder> {

    private final Context context;
    private final List<File> uploadItems;
    private final DownloadClickListener listener;

    /**
     * Default public constructor with context for inflating views and list to be displayed.
     *
     * @param context       application context to enable inflating views used in the list.
     * @param uploadItems   List of trajectory Files found locally on the device.
     * @param listener      clickListener to download trajectories when clicked.
     *
     * @see com.openpositioning.PositionMe.Traj protobuf objects exchanged with the server.
     */
    public UploadListAdapter(Context context, List<File> uploadItems, DownloadClickListener listener) {
        this.context = context;
        this.uploadItems = uploadItems;
        this.listener = listener;
    }

    /**
     * {@inheritDoc}
     *
     * @see com.openpositioning.PositionMe.R.layout#item_upload_card_view xml layout file
     */
    @NonNull
    @Override
    public UploadViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new UploadViewHolder(LayoutInflater.from(context).inflate(R.layout.item_upload_card_view, parent, false), listener);
    }

    /**
     * {@inheritDoc}
     * Formats and assigns the data fields from the local Trajectory Files object to the TextView fields.
     *
     * @see com.openpositioning.PositionMe.fragments.UploadFragment finding the data from on local storage.
     * @see com.openpositioning.PositionMe.R.layout#item_upload_card_view xml layout file.
     */
    @Override
    public void onBindViewHolder(@NonNull UploadViewHolder holder, int position) {
        holder.trajId.setText(String.valueOf(position));
        Pattern datePattern = Pattern.compile("_(.*?)\\.txt");
        Matcher dateMatcher = datePattern.matcher(uploadItems.get(position).getName());
        String dateString = dateMatcher.find() ? dateMatcher.group(1) : "N/A";
        System.err.println("UPLOAD - Date string: " + dateString);
        holder.trajDate.setText(dateString);
    }

    /**
     * {@inheritDoc}
     * Number of local files.
     */
    @Override
    public int getItemCount() {
        return uploadItems.size();
    }
}
