package com.openpositioning.PositionMe.FusionAlgorithms;

import android.os.SystemClock;
import android.util.Log;
import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.CoordinateTransform;

import java.util.Deque;
import java.util.LinkedList;

/**
 * A thread-based short-lag smoothing approach.
 * Maintains a short buffer of recent states (time, easting, northing, heading) and applies a
 * smoothing step periodically. Allows the rest of the system to keep a near-real-time track,
 * while slightly adjusting the most recent 1s of the trajectory.
 */
public class SmoothThread extends Thread {

    // A simple class to hold position states with timestamps
    private static class TimeStampedState {
        long timestampMs;
        double easting;
        double northing;
        float headingRad;
        public TimeStampedState(long timestampMs, double easting, double northing, float headingRad) {
            this.timestampMs = timestampMs;
            this.easting = easting;
            this.northing = northing;
            this.headingRad = headingRad;
        }
    }

    private final long SMOOTHER_WINDOW_MS = 1000; // 1 second sliding window
    private volatile boolean running = true;

    private final Deque<TimeStampedState> smoothingBuffer = new LinkedList<>();

    private final Object lock = new Object();

    // Optionally store references to a callback or map fragment, etc.
    private final SmoothCallback callback;

    /**
     * Interface for delivering smoothed results.
     */
    public interface SmoothCallback {
        /**
         * Provide the latest smoothed state so that the main thread can handle it.
         */
        void onSmoothed(double easting, double northing, float heading);
    }

    public SmoothThread(SmoothCallback callback) {
        this.callback = callback;
    }

    /**
     * Add the latest raw state to the smoothing buffer.
     */
    public void addState(long timestampMs, double easting, double northing, float headingRad) {
        synchronized (lock) {
            smoothingBuffer.addLast(new TimeStampedState(timestampMs, easting, northing, headingRad));
        }
    }

    /**
     * Stop the smoothing thread.
     */
    public void stopSmoothing() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        Log.d("SmoothThread", "Smoothing thread started.");
        while (running) {
            try {
                // Sleep a bit between smoothing passes
                Thread.sleep(300); // 0.3s update frequency
            } catch (InterruptedException e) {
                // If interrupted, check if still running
                if (!running) {
                    break;
                }
            }

            // Perform short-lag smoothing
            shortLagSmooth();
        }
        Log.d("SmoothThread", "Smoothing thread stopped.");
    }

    /**
     * The actual short-lag smoothing step that removes old states and averages the new ones.
     */
    private void shortLagSmooth() {
        long now = SystemClock.uptimeMillis();
        synchronized (lock) {
            // 1. Remove states older than the smoothing window
            while (!smoothingBuffer.isEmpty() && (now - smoothingBuffer.peekFirst().timestampMs) > SMOOTHER_WINDOW_MS) {
                smoothingBuffer.removeFirst();
            }

            if (smoothingBuffer.size() < 2) {
                // not enough data to smooth
                return;
            }

            // 2. Compute average easting, northing, heading
            double sumE = 0.0;
            double sumN = 0.0;
            double sumH = 0.0;
            for (TimeStampedState s : smoothingBuffer) {
                sumE += s.easting;
                sumN += s.northing;
                sumH += s.headingRad;
            }
            int count = smoothingBuffer.size();
            double avgE = sumE / count;
            double avgN = sumN / count;
            float avgH = (float)(sumH / count);

            // 3. Shift each state partially so that the final state lines up with the average
            TimeStampedState latest = smoothingBuffer.peekLast();
            if (latest == null) return; // safeguard

            double shiftE = avgE - latest.easting;
            double shiftN = avgN - latest.northing;
            float shiftH = (float)(avgH - latest.headingRad);

            long firstTimestamp = smoothingBuffer.peekFirst().timestampMs;
            long lastTimestamp  = latest.timestampMs;
            double denom = (double)(lastTimestamp - firstTimestamp);
            if (denom <= 0) {
                return; // invalid timing
            }

            // Weighted shift. A more advanced approach might do a mini-optimization here.
            for (TimeStampedState s : smoothingBuffer) {
                double alpha = (double)(s.timestampMs - firstTimestamp) / denom;
                s.easting += alpha * shiftE;
                s.northing += alpha * shiftN;
                s.headingRad += alpha * shiftH;
            }

            // 4. Provide the final smoothed state to callback
            if (callback != null) {
                callback.onSmoothed(latest.easting, latest.northing, latest.headingRad);
            }
        }
    }
}
