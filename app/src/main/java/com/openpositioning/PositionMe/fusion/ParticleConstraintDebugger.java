package com.openpositioning.PositionMe.fusion;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Helper used to keep PF constraint logging readable.
 *
 * This class does not change PF behaviour.
 * It only formats state into consistent Logcat lines.
 */

public class ParticleConstraintDebugger {
    private final String tag;
    private long lastLogTimeMs = 0L;
    private long minIntervalMs = 0L;

    public ParticleConstraintDebugger(@NonNull String tag) {
        this.tag = tag;
    }

    public void setMinIntervalMs(long minIntervalMs) {
        this.minIntervalMs = Math.max(0L, minIntervalMs);
    }

    public void logStep(@NonNull String phase,
                        int particleCount,
                        int aliveCount,
                        int wallRejectedCount,
                        int floorRejectedCount,
                        int connectorAcceptedCount,
                        int observationAcceptedCount,
                        @Nullable String note) {
        long now = System.currentTimeMillis();
        if (now - lastLogTimeMs < minIntervalMs) {
            return;
        }
        lastLogTimeMs = now;

        Log.d(tag, String.format(Locale.US,
                "PF_CONSTRAINT phase=%s particles=%d alive=%d wallRejected=%d floorRejected=%d connectorAccepted=%d obsAccepted=%d note=%s",
                phase,
                particleCount,
                aliveCount,
                wallRejectedCount,
                floorRejectedCount,
                connectorAcceptedCount,
                observationAcceptedCount,
                note == null ? "-" : note));
    }

    public void logFusedPose(@NonNull String phase,
                             double lat,
                             double lng,
                             int floor,
                             double headingRad,
                             double confidence) {
        long now = System.currentTimeMillis();
        if (now - lastLogTimeMs < minIntervalMs) {
            return;
        }
        lastLogTimeMs = now;

        Log.d(tag, String.format(Locale.US,
                "PF_FUSED phase=%s lat=%.6f lng=%.6f floor=%d headingRad=%.3f confidence=%.3f",
                phase, lat, lng, floor, headingRad, confidence));
    }
}
