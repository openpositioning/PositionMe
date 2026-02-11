package com.openpositioning.PositionMe.presentation.viewitems;

import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.UploadFragment;

import java.lang.ref.WeakReference;

/**
 * View holder class for the RecyclerView displaying Trajectory files to be uploaded.
 *
 * @see UploadListAdapter the corresponding list adapter.
 * @see com.openpositioning.PositionMe.R.layout#item_upload_card_view xml layout file
 *
 * @author Mate Stodulka
 */
public class UploadViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

    TextView trajId;
    TextView trajDate;
    TextView trajectoryNameText;  // NEW: Trajectory name display
    MaterialButton uploadButton;  // Correct reference to MaterialButton
    // Weak reference to the click listener to enable garbage collection on recyclerview items
    private WeakReference<DownloadClickListener> listenerReference;
    public Button deletebutton;
    public RecyclerView testPointRecyclerView;
    public MaterialButton toggleTestPointsButton;


    /**
     * {@inheritDoc}
     * Assign TextView fields corresponding to Trajectory file metadata.
     *
     * @param listener DownloadClickListener to enable acting on clicks on items.
     *
     * @see UploadFragment locating the data and implementing the
     * listener.
     */
    public UploadViewHolder(@NonNull View itemView, DownloadClickListener listener) {
        super(itemView);

        this.listenerReference = new WeakReference<>(listener);
        trajId = itemView.findViewById(R.id.trajectoryIdItem);
        trajDate = itemView.findViewById(R.id.trajectoryDateItem);
        trajectoryNameText = itemView.findViewById(R.id.trajectory_name_text); // ✅ ADD THIS
        uploadButton = itemView.findViewById(R.id.uploadTrajectoryButton);
        toggleTestPointsButton = itemView.findViewById(R.id.toggleTestPointsButton);
        testPointRecyclerView = itemView.findViewById(R.id.rvTestPoints);
        deletebutton = itemView.findViewById(R.id.deletebutton);
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