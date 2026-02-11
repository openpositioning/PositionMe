package com.openpositioning.PositionMe.presentation.activity;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.health.WalkSessionSummary;
import com.openpositioning.PositionMe.presentation.adapter.HistoryAdapter;
import com.openpositioning.PositionMe.utils.WalkFileParser;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Activity to display the user's walk history in a list by parsing real data files.
 */
public class HistoryActivity extends AppCompatActivity {

    private static final String TAG = HistoryActivity.class.getSimpleName();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        // --- Toolbar Setup ---
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        // --- RecyclerView Setup ---
        RecyclerView rvHistory = findViewById(R.id.history_recycler_view);

        // Load real data from saved trajectory files
        List<WalkSessionSummary> walkHistory = loadWalkHistoryFromFiles();

        // Create adapter passing in the real user data
        HistoryAdapter adapter = new HistoryAdapter(walkHistory);
        // Attach the adapter to the recyclerview to populate items
        rvHistory.setAdapter(adapter);
        // Set layout manager to position the items
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
    }

    /**
     * Scans the app's storage for trajectory files, parses them, and returns a list of summaries.
     *
     * @return A list of WalkSessionSummary objects sorted by most recent first.
     */
    private List<WalkSessionSummary> loadWalkHistoryFromFiles() {
        List<WalkSessionSummary> sessions = new ArrayList<>();
        WalkFileParser parser = new WalkFileParser(getApplicationContext());

        // Assume files are stored in a "trajectories" subdirectory of the app's external files directory.
        File storageDir = new File(getExternalFilesDir(null), "trajectories");

        if (!storageDir.exists()) {
            Log.w(TAG, "Trajectories directory does not exist: " + storageDir.getAbsolutePath());
            return sessions; // Return empty list
        }

        File[] files = storageDir.listFiles();
        if (files == null) {
            Log.w(TAG, "Failed to list files in directory: " + storageDir.getAbsolutePath());
            return sessions;
        }

        for (File file : files) {
            // Optional: Add a filter to only parse relevant files, e.g., .csv or .gpx
            if (file.isFile() && file.getName().endsWith(".csv")) {
                WalkSessionSummary summary = parser.parseFile(file);
                if (summary != null) {
                    sessions.add(summary);
                }
            }
        }

        // Sort by most recent first, so the newest walks appear at the top of the list.
        Collections.sort(sessions, (s1, s2) -> Long.compare(s2.getTimestampMillis(), s1.getTimestampMillis()));

        Log.i(TAG, "Loaded " + sessions.size() + " walk sessions from files.");
        return sessions;
    }
}
