package com.openpositioning.PositionMe.viewitems;

import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

import java.lang.ref.WeakReference;

/**
 * View holder class for the RecyclerView displaying Trajectory download data.
 *
 * @see TrajDownloadListAdapter the corresponding list adapter.
 * @see com.openpositioning.PositionMe.R.layout#item_trajectorycard_view xml layout file
 *
 * @author Mate Stodulka
 */
public class TrajDownloadViewHolder extends RecyclerView.ViewHolder {

    public TextView trajId;
    public TextView trajDate;
    public ImageButton downloadButton;
    public Button replayButton;
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
    public TrajDownloadViewHolder(@NonNull View itemView, final DownloadClickListener listener) {
        super(itemView);
        this.listenerReference = new WeakReference<>(listener);
        this.trajId = itemView.findViewById(R.id.trajectoryIdItem);
        this.trajDate = itemView.findViewById(R.id.trajectoryDateItem);
        this.downloadButton = itemView.findViewById(R.id.downloadTrajectoryButton);
        this.replayButton = itemView.findViewById(R.id.replayTrajectoryButton);

        // 设置点击监听器
        downloadButton.setOnClickListener(v -> {
            if (listener != null) {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    listener.onPositionClicked(position);
                }
            }
        });

        replayButton.setOnClickListener(v -> {
            if (listener != null) {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    listener.onReplayClicked(position);
                }
            }
        });

        // 初始状态：显示下载按钮，隐藏播放按钮
        downloadButton.setVisibility(View.VISIBLE);
        replayButton.setVisibility(View.GONE);
    }
}
