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
import java.text.SimpleDateFormat;
import java.util.Date;
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
        File file = uploadItems.get(position);
        String fileName = file.getName();

        // Extract trajectory name (remove file extension)
        String trajectoryName = fileName.replaceFirst("\\.(pb|txt)$", "");
        holder.trajId.setText(trajectoryName);

        // Try to extract date/timestamp from filename
        // Pattern 1: Custom name (e.g., "MyTrajectory" or "Lab_Test_1")
        // Pattern 2: Old format with date (e.g., "trajectory_08-02-26-15-30-45")
        // Pattern 3: Timestamp format (e.g., "traj_1707393847315")
        String dateString = "Custom Name";

        Pattern datePattern = Pattern.compile("_(\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2})");
        Matcher dateMatcher = datePattern.matcher(fileName);
        if (dateMatcher.find()) {
            dateString = dateMatcher.group(1);
        } else {
            // Check for timestamp format
            Pattern timestampPattern = Pattern.compile("_(\\d{13})");
            Matcher timestampMatcher = timestampPattern.matcher(fileName);
            if (timestampMatcher.find()) {
                long timestamp = Long.parseLong(timestampMatcher.group(1));
                dateString = new java.text.SimpleDateFormat("dd-MM-yy HH:mm:ss").format(new java.util.Date(timestamp));
            } else if (!fileName.startsWith("trajectory_") && !fileName.startsWith("traj_")) {
                // It's a custom named file, show file modification date
                dateString = new java.text.SimpleDateFormat("dd-MM-yy HH:mm:ss").format(new java.util.Date(file.lastModified()));
            }
        }

        System.err.println("UPLOAD - File: " + fileName + ", Display name: " + trajectoryName + ", Date: " + dateString);
        holder.trajDate.setText(dateString);

        // Set the custom name in the small box with emoji
        holder.trajName.setText("📝 " + trajectoryName);

        // Set click listener for the delete button
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
