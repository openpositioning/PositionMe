package com.openpositioning.PositionMe.fusion;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Small helper for particle filter debug logging.
 *
 * This class does not affect particle filter logic.
 * It only formats and prints debug information in a consistent way,
 * and can optionally limit how often logs are printed.
 */
public class ParticleConstraintDebugger {

    // Logcat tag used for all messages from this debugger.
    private final String tag;
    // Time of the last printed log message.
    private long lastLogTimeMs = 0L;
    // Minimum allowed time gap between logs.
    private long minIntervalMs = 0L;


    /**
     * Creates a debugger with the given Logcat tag.
     */
    public ParticleConstraintDebugger(@NonNull String tag) {
        this.tag = tag;
    }

    /**
     * Sets the minimum interval between log messages.
     *
     * If set to 0, logs are not rate-limited.
     */
    public void setMinIntervalMs(long minIntervalMs) {
        this.minIntervalMs = Math.max(0L, minIntervalMs);
    }
    /**
     * Logs a summary of one particle filter step.
     *
     * @param phase current PF phase name
     * @param particleCount total number of particles
     * @param aliveCount number of particles still active
     * @param wallRejectedCount number of particles rejected by wall checks
     * @param floorRejectedCount number of particles rejected by floor checks
     * @param observationWeightedCount number of particles weighted by observations
     * @param note optional extra note for debugging
     */
    public void logStep(@NonNull String phase,
                        int particleCount,
                        int aliveCount,
                        int wallRejectedCount,
                        int floorRejectedCount,
                        int observationWeightedCount,
                        @Nullable String note) {

        // Get current time for rate limiting.
        long now = System.currentTimeMillis();

        // Skip this log if the minimum interval has not passed yet.
        if (now - lastLogTimeMs < minIntervalMs) {
            return;
        }

        // Update last log time.
        lastLogTimeMs = now;

        // Print formatted particle step debug information.
        Log.d(tag, String.format(Locale.US,
                "PF_STEP phase=%s particles=%d alive=%d wallRejected=%d floorRejected=%d obsWeighted=%d note=%s",
                phase,
                particleCount,
                aliveCount,
                wallRejectedCount,
                floorRejectedCount,
                observationWeightedCount,
                note == null ? "-" : note));
    }

    /**
     * Logs the current fused pose estimate.
     *
     * @param phase current PF phase name
     * @param lat fused latitude
     * @param lng fused longitude
     * @param floor fused floor
     * @param headingRad fused heading in radians
     * @param confidence fused pose confidence
     */
    public void logFusedPose(@NonNull String phase,
                             double lat,
                             double lng,
                             int floor,
                             double headingRad,
                             double confidence) {

        // Get current time for rate limiting.
        long now = System.currentTimeMillis();

        // Skip this log if the minimum interval has not passed yet.
        if (now - lastLogTimeMs < minIntervalMs) {
            return;
        }

        // Update last log time.
        lastLogTimeMs = now;

        // Print formatted fused pose debug information.
        Log.d(tag, String.format(Locale.US,
                "PF_FUSED phase=%s lat=%.6f lng=%.6f floor=%d headingRad=%.3f confidence=%.3f",
                phase, lat, lng, floor, headingRad, confidence));
    }
}
