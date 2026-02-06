//EE HUNG added

package com.openpositioning.PositionMe.presentation.viewitems;

import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.openpositioning.PositionMe.R;

public class WifiViewHolder extends RecyclerView.ViewHolder {

    public TextView ssid;
    public TextView level;
    public TextView macAddress; // Linked to wifiMacItem
    public TextView frequency;  // Linked to wifiFreqItem
    public TextView rtt;

    public WifiViewHolder(@NonNull View itemView) {
        super(itemView);
        ssid = itemView.findViewById(R.id.wifiNameItem);
        level = itemView.findViewById(R.id.wifiLevelItem);
        macAddress = itemView.findViewById(R.id.wifiMacItem);
        frequency = itemView.findViewById(R.id.wifiFreqItem);
        rtt = itemView.findViewById(R.id.wifiRttItem);
    }
}