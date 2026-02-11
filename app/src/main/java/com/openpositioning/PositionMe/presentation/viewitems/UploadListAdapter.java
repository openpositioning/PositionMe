package com.openpositioning.PositionMe.presentation.viewitems;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.UploadFragment;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.openpositioning.PositionMe.data.model.TestPointInfo;
import com.openpositioning.PositionMe.data.utils.TestPointParser;

import androidx.core.content.ContextCompat;

import com.openpositioning.PositionMe.data.remote.ServerCommunications;

import android.os.Handler;
import android.os.Looper;


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

    List<TestPointInfo> testPoints;

    private final List<Boolean> expandedState;

    private final ServerCommunications serverCommunications;


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
        this.serverCommunications = new ServerCommunications(context);

        expandedState = new ArrayList<>();
        for (int i = 0; i < uploadItems.size(); i++) {
            expandedState.add(false); // collapsed by default
        }
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
        File trajectoryFile = uploadItems.get(position);

        holder.trajId.setText(String.valueOf(position));

        // Extract date from filename
        Pattern datePattern = Pattern.compile("_(.*?)\\.txt");
        Matcher dateMatcher = datePattern.matcher(uploadItems.get(position).getName());
        String dateString = dateMatcher.find() ? dateMatcher.group(1) : "N/A";
        System.err.println("UPLOAD - Date string: " + dateString);
        holder.trajDate.setText(dateString);

        // Read and display Trajectory Name (NEW)
        String trajectoryName = readTrajectoryName(uploadItems.get(position));
        if (trajectoryName != null && !trajectoryName.isEmpty()) {
            holder.trajectoryNameText.setText(trajectoryName);
            holder.trajectoryNameText.setVisibility(View.VISIBLE);
        } else {
            holder.trajectoryNameText.setText("No name");
            holder.trajectoryNameText.setVisibility(View.VISIBLE);
        }

        // ---------------------------
        // 🔽 Setup test point dropdown
        // ---------------------------
        // Parse test points from protobuf file

//        List<TestPointInfo> testPoints =
//                TestPointParser.parseFromFile(trajectoryFile);

        try {
            testPoints = TestPointParser.parseFromFile(trajectoryFile);
        } catch (Exception e) {
            testPoints = java.util.Collections.emptyList();
            android.util.Log.e("UploadListAdapter",
                    "Failed to parse test points from " + trajectoryFile.getName(), e);
        }


        // Setup nested RecyclerView
        TestPointListAdapter testPointListAdapter =
                new TestPointListAdapter(context, testPoints);

        holder.testPointRecyclerView.setLayoutManager(
                new LinearLayoutManager(context));

        holder.testPointRecyclerView.setAdapter(testPointListAdapter);

        // Initially collapsed
        holder.testPointRecyclerView.setVisibility(android.view.View.GONE);

        boolean isExpanded = expandedState.get(position);

        // Apply state when binding (IMPORTANT for RecyclerView recycling)
        holder.testPointRecyclerView.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        holder.toggleTestPointsButton.setIcon(
                ContextCompat.getDrawable(context,
                        isExpanded ? R.drawable.up_arrow : R.drawable.down_arrow)
        );

        // Toggle expand/collapse
        holder.toggleTestPointsButton.setOnClickListener(v -> {
            boolean newState = !expandedState.get(position);
            expandedState.set(position, newState);

            holder.testPointRecyclerView.setVisibility(newState ? View.VISIBLE : View.GONE);
            holder.toggleTestPointsButton.setIcon(
                    ContextCompat.getDrawable(context,
                            newState ? R.drawable.up_arrow : R.drawable.down_arrow)
            );
        });


        // Set click listener for the delete button
        holder.deletebutton.setOnClickListener(v -> deleteFileAtPosition(position));

        // ---------------------------
        // ⬆ Upload button
        // ---------------------------
        holder.uploadButton.setOnClickListener(v -> {
            File fileToUpload = uploadItems.get(position);

            if (!fileToUpload.exists()) {
                Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show();
                return;
            }

            holder.uploadButton.setEnabled(false);

            String name = readTrajectoryName(fileToUpload);
            String displayName = (name != null && !name.isEmpty())

                    ? name
                    : fileToUpload.getName();

            Toast.makeText(context,
                    "Uploading trajectory: " + displayName,
                    Toast.LENGTH_LONG).show();

            serverCommunications.uploadLocalTrajectory(fileToUpload);

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                holder.uploadButton.setEnabled(true);
            }, 3000);  // re-enable after 3 seconds
        });


    }

    /**
     * {@inheritDoc}
     * Number of local files.
     */
    @Override
    public int getItemCount() {
        return uploadItems.size();
    }

    /**
     * Delete trajectory file at specified position
     */
    private void deleteFileAtPosition(int position) {
        if (position >= 0 && position < uploadItems.size()) {
            File fileToDelete = uploadItems.get(position);

            if (fileToDelete.exists() && fileToDelete.delete()) {
                uploadItems.remove(position);
                notifyItemRemoved(position);
                notifyItemRangeChanged(position, uploadItems.size()); // Update subsequent items
                Toast.makeText(context, "File deleted successfully", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "Failed to delete file", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Read trajectory name (trajectory_id) from the protobuf file
     *
     * @param file The trajectory .txt file containing protobuf data
     * @return The trajectory_id string, or null if not found/error
     */
    private String readTrajectoryName(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            com.openpositioning.PositionMe.Traj.Trajectory trajectory =
                    com.openpositioning.PositionMe.Traj.Trajectory.parseFrom(fis);
            fis.close();

            // Get trajectory_id from proto
            String trajectoryId = trajectory.getTrajectoryId();
            return (trajectoryId != null && !trajectoryId.isEmpty()) ? trajectoryId : null;

        } catch (Exception e) {
            android.util.Log.e("UploadListAdapter", "Error reading trajectory name from " +
                    file.getName() + ": " + e.getMessage());
            return null;
        }
    }
}