package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.CountDownTimer;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.openpositioning.PositionMe.R;

/**
 * A service that detects falls using accelerometer data and provides alert functionality.
 * 
 * This service implements a fall detection algorithm that identifies potential falls
 * by monitoring for a sequence of accelerometer patterns characteristic of falls:
 * 1. A period of free fall (low acceleration magnitude)
 * 2. A high impact (high acceleration spike)
 * 3. A period of inactivity following the impact
 * 
 * When a fall is detected, it notifies a registered listener and displays an alert
 * overlay that counts down before simulating an emergency call.
 *
 * @author Semih Vazgecen
 */
public class FallDetectionService {
    private static final float FREE_FALL_THRESHOLD = 0.4f;
    private static final float IMPACT_THRESHOLD = 65.0f;
    private static final long FALL_TIME_WINDOW = 5000;
    private static final long INACTIVITY_THRESHOLD = 3000;

    private boolean isFalling = false;
    private boolean impactDetected = false;
    private long fallStartTime = 0;
    private long impactTime = 0;

    private Context appContext;
    private WindowManager windowManager;
    private View alertView;
    private FallListener fallListener;
    private SharedPreferences settings;

    /**
     * Constructs a new FallDetectionService.
     * 
     * @param context The application context used for accessing system services
     *                and shared preferences
     * 
     * @author Semih Vazgecen
     */
    public FallDetectionService(Context context) {
        this.appContext = context;
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
    }

    /**
     * Sets a listener to be notified when a fall is detected.
     * The listener will be called when the fall detection algorithm
     * confirms a fall event has occurred.
     *
     * @param listener An implementation of the FallListener interface
     *                 that will receive fall detection notifications
     * 
     * @author Semih Vazgecen
     */
    public void setFallListener(FallListener listener) {
        this.fallListener = listener;
    }

    /**
     * Processes acceleration data to detect fall patterns.
     * 
     * This is the main method of the fall detection algorithm that analyzes
     * acceleration data to identify potential falls. The algorithm looks for:
     * 1. A free fall phase (acceleration magnitude below FREE_FALL_THRESHOLD)
     * 2. An impact phase (acceleration magnitude above IMPACT_THRESHOLD)
     * 3. A post-impact inactivity phase (time period after impact)
     * 
     * If all phases are detected in the correct sequence and timing, a fall is confirmed.
     * When a fall is confirmed, the registered listener is notified and an alert is shown.
     *
     * @param ax The acceleration along the X axis in m/s²
     * @param ay The acceleration along the Y axis in m/s²
     * @param az The acceleration along the Z axis in m/s²
     * @param currentTime The timestamp of the acceleration measurement in milliseconds
     * 
     * @author Semih Vazgecen
     */
    public void processAcceleration(float ax, float ay, float az, long currentTime) {
        // Check if fall detection is enabled in settings
        boolean fallDetectionEnabled = settings.getBoolean("enable_fall_detection", true);
        if (!fallDetectionEnabled) {
            return; // Skip processing if fall detection is disabled
        }
        
        double accelerationMagnitude = Math.sqrt(ax * ax + ay * ay + az * az);

        if (accelerationMagnitude < FREE_FALL_THRESHOLD && !isFalling) {
            isFalling = true;
            fallStartTime = currentTime;
            Log.d("FallDetection", "Free fall detected");
        }

        if (isFalling && accelerationMagnitude > IMPACT_THRESHOLD) {
            impactTime = currentTime;
            impactDetected = true;
            Log.d("FallDetection", "Impact detected");
        }

        if (impactDetected && (currentTime - impactTime > INACTIVITY_THRESHOLD)) {
            Log.d("FallDetection", "Fall confirmed! Triggering alert...");

            if (fallListener != null) {
                fallListener.onFallDetected();  // Notify listener (e.g., SensorFusion)
            }

            showFallAlert();  // Show overlay alert
            resetFallDetection();
        }

        if (isFalling && (currentTime - fallStartTime > FALL_TIME_WINDOW)) {
            resetFallDetection();
        }
    }

    /**
     * Resets the fall detection state variables.
     * 
     * This method is called after a fall is confirmed or when the fall time window
     * expires without detecting an impact. It resets the internal state variables
     * to prepare for detecting the next potential fall.
     * 
     * @author Semih Vazgecen
     */
    private void resetFallDetection() {
        isFalling = false;
        impactDetected = false;
    }

    /**
     * Displays an alert overlay when a fall is detected.
     * 
     * This method creates and shows a system overlay alert with a countdown timer
     * simulating an emergency call notification. The user can dismiss the alert
     * by pressing a button, or it will automatically dismiss after the countdown
     * and a brief delay.
     * 
     * The method checks that:
     * 1. Fall detection is still enabled in settings
     * 2. The app has permission to draw overlays
     * before displaying the alert.
     * 
     * @author Semih Vazgecen
     */
    private void showFallAlert() {
        // Double-check fall detection is enabled before showing alert
        boolean fallDetectionEnabled = settings.getBoolean("enable_fall_detection", true);
        if (!fallDetectionEnabled) {
            return; // Skip showing alert if fall detection is disabled
        }
        
        if (!Settings.canDrawOverlays(appContext)) {
            Log.e("FallDetection", "Overlay permission not granted! Cannot show alert.");
            return;
        }

        windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        LayoutInflater inflater = LayoutInflater.from(appContext);
        alertView = inflater.inflate(R.layout.fall_alert_dialog, null);

        TextView timerText = alertView.findViewById(R.id.timerText);
        Button dismissButton = alertView.findViewById(R.id.dismissButton);

        // Countdown timer (7 seconds)
        CountDownTimer countDownTimer = new CountDownTimer(7000, 1000) {
            public void onTick(long millisUntilFinished) {
                timerText.setText("Emergency call in: " + (millisUntilFinished / 1000) + "s");
            }

            public void onFinish() {
                timerText.setText("Emergency services are now called.");
                new Handler().postDelayed(() -> removeAlert(), 3000);
            }
        }.start();

        dismissButton.setOnClickListener(v -> {
            countDownTimer.cancel();
            removeAlert();
        });

        // Overlay window parameters
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER;

        windowManager.addView(alertView, params);
    }

    /**
     * Removes the fall alert overlay from the screen.
     * 
     * This method safely removes the alert view from the window manager
     * and cleans up references to prevent memory leaks. It's called 
     * either when the user dismisses the alert or when the countdown
     * timer completes.
     * 
     * @author Semih Vazgecen
     */
    private void removeAlert() {
        if (alertView != null && windowManager != null) {
            windowManager.removeView(alertView);
            alertView = null;
        }
    }

    /**
     * Interface for receiving notifications when a fall is detected.
     * 
     * Implementations of this interface can register with the FallDetectionService
     * to be notified when a fall event is confirmed.
     * 
     * @author Semih Vazgecen
     */
    public interface FallListener {
        void onFallDetected();
    }
}
