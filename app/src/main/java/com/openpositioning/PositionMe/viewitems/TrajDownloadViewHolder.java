package com.openpositioning.PositionMe.viewitems;

import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

import java.lang.ref.WeakReference;
import android.widget.ProgressBar;

/**
 * View holder class for the RecyclerView displaying Trajectory download data.
 *
 * @see TrajDownloadListAdapter the corresponding list adapter.
 * @see com.openpositioning.PositionMe.R.layout#item_trajectorycard_view xml layout file
 *
 * @author Mate Stodulka
 */
// 就是用来保存每个卡片里的控件（比如显示ID、日期、按钮）的，它让我们不用每次都去找这些控件（省时间又省力）。
public class TrajDownloadViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{

    TextView trajId;
    TextView trajDate;
    //ImageButton downloadButton;

    ImageButton replayButton;
    // 声明 ProgressBar 为 private，不直接暴露控件
    private ProgressBar progressBarLoading;
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
        //this.downloadButton = itemView.findViewById(R.id.downloadTrajectoryButton);
        this.replayButton = itemView.findViewById(R.id.replayTrajectoryButton);
        //this.downloadButton.setOnClickListener(this);//绑定点击事件
        // 绑定 ProgressBar 控件，注意布局中要有此 id
        this.progressBarLoading = itemView.findViewById(R.id.progressBarLoading);
        this.replayButton.setOnClickListener(this);
    }
    @Override
    public void onClick(View view) {
        int id = view.getId();
        //if (id == R.id.downloadTrajectoryButton) {
            //listenerReference.get().onPositionClicked(getAdapterPosition());
        if (id == R.id.replayTrajectoryButton) {
            // 显示加载进度条
            showLoading();
            listenerReference.get().onReplayClicked(getAdapterPosition());
        }
    }
    public void showLoading() {
        if (progressBarLoading != null) {
            progressBarLoading.setVisibility(View.VISIBLE);
        }
    }

    // 提供公共方法用于隐藏 ProgressBar
    public void hideLoading() {
        if (progressBarLoading != null) {
            progressBarLoading.setVisibility(View.GONE);
        }
    }
}
