package com.openpositioning.PositionMe.health;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;

import java.util.Calendar;

public class ZenWorker extends Worker {

    public static final String CHANNEL_ID = "zen_channel";
    // Define keys for SharedPreferences
    public static final String PREFS_NAME = "ZenPrefs";
    public static final String KEY_LAST_WALK_TIMESTAMP = "lastWalkTimestamp";
    private boolean shouldNotify = false;

    public ZenWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!hasWalkedToday()) {
            sendZenNotification();
            shouldNotify = true;
        }
        return Result.success();
    }

    private boolean hasWalkedToday() {
        // Read the timestamp of the last walk from SharedPreferences
        SharedPreferences prefs = getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastWalkTimestamp = prefs.getLong(KEY_LAST_WALK_TIMESTAMP, 0);

        // If no walk has ever been recorded, return false
        if (lastWalkTimestamp == 0) {
            return false;
        }

        long todayStart = getStartOfTodayMillis();
        // Check if the last walk was today
        return lastWalkTimestamp >= todayStart;
    }

    private long getStartOfTodayMillis() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private void sendZenNotification() {
        NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Zen Notifications", NotificationManager.IMPORTANCE_DEFAULT);
            notificationManager.createNotificationChannel(channel);
        }

        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_directions_walk_24) // Using a more appropriate icon
                .setContentTitle("A Moment for You")
                .setContentText("A short walk can do wonders for your well-being. How about a few mindful steps?")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        notificationManager.notify(1, builder.build());
    }

    @VisibleForTesting
    public boolean getShouldNotify() {
        return shouldNotify;
    }
}
