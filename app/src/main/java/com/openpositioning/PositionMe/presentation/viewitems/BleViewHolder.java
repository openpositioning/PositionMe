package com.openpositioning.PositionMe.presentation.viewitems;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

/**
 * View holder class for the RecyclerView displaying BLE device data.
 *
 * @see BleListAdapter the corresponding list adapter.
 * @see com.openpositioning.PositionMe.R.layout#item_ble_card_view xml layout file
 *
 * @author Vlad Stratulat
 */
public class BleViewHolder extends RecyclerView.ViewHolder {

    TextView macAddress;
    TextView name;
    TextView rssi;

    /**
     * {@inheritDoc}
     * Assign TextView fields corresponding to BleDevice attributes.
     *
     * @see com.openpositioning.PositionMe.sensors.BleDevice the data class
     */
    public BleViewHolder(@NonNull View itemView) {
        super(itemView);
        macAddress = itemView.findViewById(R.id.bleAddressItem);
        name = itemView.findViewById(R.id.bleNameItem);
        rssi = itemView.findViewById(R.id.bleRssiItem);
    }
}
