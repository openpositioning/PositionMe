package com.openpositioning.PositionMe.presentation.viewitems;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

public class BleViewHolder extends RecyclerView.ViewHolder {

    TextView mac;
    TextView rssi;

    public BleViewHolder(@NonNull View itemView) {
        super(itemView);
        mac = itemView.findViewById(R.id.bleMac);
        rssi = itemView.findViewById(R.id.bleRssi);
    }
}
