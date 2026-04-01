package com.openpositioning.PositionMe.presentation.viewitems;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.UploadFragment;

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
     * @see UploadFragment finding the data from on local storage.
     * @see com.openpositioning.PositionMe.R.layout#item_upload_card_view xml layout file.
     */
    @Override
    public void onBindViewHolder(@NonNull UploadViewHolder holder, int position) {
        holder.trajId.setText(String.valueOf(position + 1));
        Pattern datePattern = Pattern.compile("_(\\d+)\\.proto");
        Matcher dateMatcher = datePattern.matcher(uploadItems.get(position).getName());
        String dateString = "N/A";
        if (dateMatcher.find()) {
            try {
                long epochMs = Long.parseLong(dateMatcher.group(1));
                dateString = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.getDefault()).format(new java.util.Date(epochMs));
            } catch (NumberFormatException e) {
                dateString = dateMatcher.group(1);
            }
        }
        holder.trajDate.setText(dateString);

        // Bind both buttons directly with position to avoid getAdapterPosition() returning NO_ID
        holder.uploadButton.setOnClickListener(v -> listener.onPositionClicked(position));
        holder.deletebutton.setOnClickListener(v -> deleteFileAtPosition(position));
    }

    /**
     * {@inheritDoc}
     * Number of local files.
     */
    @Override
    public int getItemCount() {
        return uploadItems.size();
    }

    private void deleteFileAtPosition(int position)
    {
        if (position >= 0 && position < uploadItems.size())
        {
            File fileToDelete = uploadItems.get(position);

            if (fileToDelete.exists() && fileToDelete.delete())
            {
                uploadItems.remove(position);
                notifyItemRemoved(position);
                notifyItemRangeChanged(position, uploadItems.size()); // Update subsequent items
                Toast.makeText(context, "File deleted successfully", Toast.LENGTH_SHORT).show();
            }
            else
            {
                Toast.makeText(context, "Failed to delete file", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
