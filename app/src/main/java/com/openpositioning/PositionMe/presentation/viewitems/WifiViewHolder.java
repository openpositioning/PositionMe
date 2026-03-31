package com.openpositioning.PositionMe.presentation.viewitems;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

// View holder class for the RecyclerView displaying Wifi data.
// Related: WifiListAdapter the corresponding list adapter.
// Related: com.openpositioning.PositionMe.R.layout#item_wifi_card_view xml layout file
// @author Mate Stodulka
public class WifiViewHolder extends RecyclerView.ViewHolder {

    TextView bssid;
    TextView level;

    // {@inheritDoc}
    // Assign TextView fields corresponding to Wifi attributes.
// Related: com.openpositioning.PositionMe.sensors.Wifi the data class
    public WifiViewHolder(@NonNull View itemView) {
        super(itemView);
        bssid = itemView.findViewById(R.id.wifiNameItem);
        level = itemView.findViewById(R.id.wifiLevelItem);
    }
}


