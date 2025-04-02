package com.openpositioning.PositionMe.sensors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.widget.TextView;

public class BatteryReceiver extends BroadcastReceiver {
    private TextView batteryTextView;

    public BatteryReceiver(TextView batteryTextView) {
        this.batteryTextView = batteryTextView;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int batteryPct = (int) ((level / (float) scale) * 100);
        batteryTextView.setText("Battery: " + batteryPct + "%");
    }
}
