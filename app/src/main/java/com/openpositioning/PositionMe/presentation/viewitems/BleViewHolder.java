package com.openpositioning.PositionMe.presentation.viewitems;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

// View holder for BLE device list items.
// @see BleListAdapter
// @see com.openpositioning.PositionMe.sensors.BleDevice
public class BleViewHolder extends RecyclerView.ViewHolder {

    TextView macAddress;
    TextView deviceName;
    TextView rssi;

    public BleViewHolder(@NonNull View itemView) {
        super(itemView);
        macAddress = itemView.findViewById(R.id.bleMacItem);
        deviceName = itemView.findViewById(R.id.bleNameItem);
        rssi = itemView.findViewById(R.id.bleRssiItem);
    }
}


