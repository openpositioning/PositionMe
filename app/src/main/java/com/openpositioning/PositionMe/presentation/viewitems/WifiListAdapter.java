//EE HUNG added

package com.openpositioning.PositionMe.presentation.viewitems;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.Wifi;

import java.util.List;

public class WifiListAdapter extends RecyclerView.Adapter<WifiViewHolder> {

    private final Context context;
    private final List<Wifi> wifiList;

    public WifiListAdapter(Context context, List<Wifi> wifiList) {
        this.context = context;
        this.wifiList = wifiList;
    }

    @NonNull
    @Override
    public WifiViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context)
                .inflate(R.layout.item_wifi_card_view, parent, false);
        return new WifiViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull WifiViewHolder holder, int position) {
        Wifi currentWifi = wifiList.get(position);

        // 1. Set SSID (Name)
        String ssid = currentWifi.getSsid();
        if (ssid != null && !ssid.isEmpty()) {
            holder.ssid.setText(ssid);
        } else {
            holder.ssid.setText("Unknown");
        }

        // 2. Set MAC Address (BSSID)
        // Convert the long value to a standard MAC string (e.g. "00:11:22:33:44:55")
        String mac = longToMac(currentWifi.getBssid());
        holder.macAddress.setText(mac);

        // 3. Set Frequency
        holder.frequency.setText(currentWifi.getFrequency() + " MHz");

        // 4. Set RTT Status
        if (currentWifi.is80211mcResponder()) {
            holder.rtt.setText("RTT: YES");
            // Dark Green
            holder.rtt.setTextColor(Color.parseColor("#008800"));
        } else {
            holder.rtt.setText("RTT: NO");
            holder.rtt.setTextColor(Color.GRAY);
        }

        // 5. Set Signal Level
        holder.level.setText(currentWifi.getLevel() + " dBm");
    }

    @Override
    public int getItemCount() {
        return wifiList != null ? wifiList.size() : 0;
    }

    /**
     * Helper method: Converts 'long' BSSID to "xx:xx:xx:xx:xx:xx" String.
     */
    private String longToMac(long bssid) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            // Extract the last byte and format as 2-digit Hex
            sb.insert(0, String.format("%02x", bssid & 0xFF));
            // Shift to the next byte
            bssid >>= 8;
            // Add colon separator (except for the last byte processed)
            if (i < 5) sb.insert(0, ":");
        }
        return sb.toString();
    }
}