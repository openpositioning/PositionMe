package com.openpositioning.PositionMe.presentation.viewitems;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.BleDevice;

import java.util.List;

/**
 * Adapter for displaying BLE device data in a RecyclerView.
 *
 * @see BleViewHolder
 * @see com.openpositioning.PositionMe.sensors.BleDevice
 */
public class BleListAdapter extends RecyclerView.Adapter<BleViewHolder> {

    Context context;
    List<BleDevice> items;

    public BleListAdapter(Context context, List<BleDevice> items) {
        this.context = context;
        this.items = items;
    }

    @NonNull
    @Override
    public BleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new BleViewHolder(LayoutInflater.from(context).inflate(R.layout.item_ble_card_view, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull BleViewHolder holder, int position) {
        BleDevice device = items.get(position);
        
        // Check if this is a placeholder item
        if ("00:00:00:00:00:00".equals(device.getMacAddress())) {
            holder.deviceName.setText(device.getName() != null ? device.getName() : "Scanning...");
            holder.macAddress.setText("No devices found");
            holder.rssi.setText("");
        } else {
            holder.macAddress.setText(device.getMacAddress());
            
            String name = device.getName();
            if (name == null || name.isEmpty()) {
                holder.deviceName.setText("Unknown Device");
            } else {
                holder.deviceName.setText(name);
            }
            
            String rssiString = device.getRssi() + " dBm";
            holder.rssi.setText(rssiString);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
