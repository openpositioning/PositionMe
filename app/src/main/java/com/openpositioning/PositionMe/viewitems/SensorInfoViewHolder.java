package com.openpositioning.PositionMe.viewitems;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

/**
 * View holder class for the RecyclerView displaying SensorInfo data.
 *
 * @see SensorInfoListAdapter the corresponding list adapter.
 * @see com.openpositioning.PositionMe.R.layout#item_sensorinfo_card_view xml layout file
 *
 * @author Mate Stodulka
 */
public class SensorInfoViewHolder extends RecyclerView.ViewHolder {

    // Text fields in the item view
    TextView name, vendor, resolution, power, version;

    /**
     * {@inheritDoc}
     * Assign TextView fields corresponding to SensorInfo attributes.
     *
     * @see com.openpositioning.PositionMe.sensors.SensorInfo the data class
     */
    public SensorInfoViewHolder(@NonNull View itemView) {
        super(itemView);
        name = itemView.findViewById(R.id.sensorNameItem);
        vendor = itemView.findViewById(R.id.sensorVendorItem);
        resolution = itemView.findViewById(R.id.sensorResolutionItem);
        power = itemView.findViewById(R.id.sensorPowerItem);
        version = itemView.findViewById(R.id.sensorVersionItem);
    }
}
