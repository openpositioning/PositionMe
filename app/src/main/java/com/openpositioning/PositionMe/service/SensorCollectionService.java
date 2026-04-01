package com.openpositioning.PositionMe.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;

/**
 * Foreground service that keeps sensor data collection alive when the app is in the background
 * or the screen is locked.
 *
 * <p>While this service is running, the system treats the process as a foreground process,
 * preventing it from being killed. A persistent notification is shown to the user.</p>
 *
 * <p>During recording, this service also becomes the lifecycle owner for WiFi/BLE
 * scan collectors by notifying {@link SensorFusion} on start/stop. This ensures
 * wireless scanning remains active while the app is backgrounded or the screen is locked.</p>
 *
 * @see com.openpositioning.PositionMe.sensors.SensorFusion#startRecording()
 * @see com.openpositioning.PositionMe.sensors.SensorFusion#stopRecording()
 */
public class SensorCollectionService extends Service {

  private static final String CHANNEL_ID = "sensor_collection_channel";
  private static final int NOTIFICATION_ID = 1001;

  /** Tracks whether the service is currently running. */
  private static volatile boolean running = false;

  @Override
  public void onCreate() {
    super.onCreate();
    createNotificationChannel();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Notification notification = buildNotification();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      // Android 14+ requires explicit foreground service type
      startForeground(NOTIFICATION_ID, notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
    } else {
      startForeground(NOTIFICATION_ID, notification);
    }

    running = true;
    SensorFusion.getInstance().onCollectionServiceStarted();
    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    SensorFusion.getInstance().onCollectionServiceStopped();
    running = false;
    super.onDestroy();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  /**
   * Returns whether the service is currently running.
   *
   * @return true if the foreground service is active
   */
  public static boolean isRunning() {
    return running;
  }

  /**
   * Convenience method to start the foreground service.
   *
   * @param context any context (activity, application, etc.)
   */
  public static void start(Context context) {
    Intent intent = new Intent(context, SensorCollectionService.class);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(intent);
    } else {
      context.startService(intent);
    }
  }

  /**
   * Convenience method to stop the foreground service.
   *
   * @param context any context (activity, application, etc.)
   */
  public static void stop(Context context) {
    context.stopService(new Intent(context, SensorCollectionService.class));
  }

  private void createNotificationChannel() {
    NotificationChannel channel = new NotificationChannel(
        CHANNEL_ID,
        "Sensor Data Collection",
        NotificationManager.IMPORTANCE_LOW);
    channel.setDescription("Shows while recording trajectory data in the background");
    channel.setShowBadge(false);

    NotificationManager manager = getSystemService(NotificationManager.class);
    if (manager != null) {
      manager.createNotificationChannel(channel);
    }
  }

  private Notification buildNotification() {
    Intent tapIntent = new Intent(this, MainActivity.class);
    tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent pendingIntent = PendingIntent.getActivity(
        this, 0, tapIntent,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    return new NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("PositionMe Recording")
        .setContentText("Collecting sensor data in the background")
        .setSmallIcon(R.drawable.ic_baseline_back_arrow)
        .setContentIntent(pendingIntent)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build();
  }
}
