package com.openpositioning.PositionMe.viewitems;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

/**
 * View holder class for the RecyclerView displaying Wifi data.
 *
 * @see WifiListAdapter the corresponding list adapter.
 * @see com.openpositioning.PositionMe.R.layout#item_wifi_card_view xml layout file
 *
 * @author Mate Stodulka
 */
public class WifiViewHolder extends RecyclerView.ViewHolder {

    TextView bssid;
    TextView level;

    /**
     * {@inheritDoc}
     * Assign TextView fields corresponding to Wifi attributes.
     *
     * @see com.openpositioning.PositionMe.sensors.Wifi the data class
     */
    public WifiViewHolder(@NonNull View itemView) {
        super(itemView);
        bssid = itemView.findViewById(R.id.wifiNameItem);
        level = itemView.findViewById(R.id.wifiLevelItem);
    }
}
