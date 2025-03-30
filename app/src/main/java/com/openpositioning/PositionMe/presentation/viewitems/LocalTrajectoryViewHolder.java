package com.openpositioning.PositionMe.presentation.viewitems;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.openpositioning.PositionMe.R;
//import com.openpositioning.PositionMe.presentation.fragment.LocalTrajectoriesFragment;

import java.lang.ref.WeakReference;

/**
 * ViewHolder for displaying local trajectory files in a RecyclerView.
 * @see LocalTrajectoriesFragment for usage
 */
public class LocalTrajectoryViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

    // UI elements
    TextView name;
    TextView date;
    MaterialButton playButton;
    
    // Weak reference to the click listener to enable garbage collection on recyclerview items
    private WeakReference<LocalTrajectoryClickListener> listenerReference;

    /**
     * Constructor for LocalTrajectoryViewHolder
     * @param itemView view item inflated from layout
     * @param listener click listener for list items
     */
    public LocalTrajectoryViewHolder(@NonNull View itemView, LocalTrajectoryClickListener listener) {
        super(itemView);
        
        this.listenerReference = new WeakReference<>(listener);
        this.name = itemView.findViewById(R.id.localTrajectoryName);
        this.date = itemView.findViewById(R.id.localTrajectoryDate);
        this.playButton = itemView.findViewById(R.id.playLocalTrajectoryButton);
        
        this.playButton.setOnClickListener(this);
    }

    /**
     * Handle click events on the play button
     * @param view clicked view
     */
    @Override
    public void onClick(View view) {
        if (listenerReference.get() != null) {
            listenerReference.get().onLocalTrajectoryClicked(getAdapterPosition());
        }
    }

    /**
     * Interface for handling clicks on local trajectory items
     */
    public interface LocalTrajectoryClickListener {
        void onLocalTrajectoryClicked(int position);
    }
} 