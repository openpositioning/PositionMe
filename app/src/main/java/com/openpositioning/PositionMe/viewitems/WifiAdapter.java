package com.openpositioning.PositionMe.viewitems;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.WifiData;

import java.util.List;

public class WifiAdapter extends RecyclerView.Adapter<WifiAdapter.WifiViewHolder> {

    private List<WifiData> wifiList;

    public WifiAdapter(List<WifiData> wifiList) {
        this.wifiList = wifiList;
    }

    @NonNull
    @Override
    public WifiViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_wifi, parent, false);
        return new WifiViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull WifiViewHolder holder, int position) {
        WifiData wifiData = wifiList.get(position);
        holder.ssidTextView.setText(wifiData.getSsid());
        holder.bssidTextView.setText(wifiData.getBssid());
        holder.signalTextView.setText(wifiData.getSignalStrength());
    }

    @Override
    public int getItemCount() {
        return wifiList.size();
    }

    public static class WifiViewHolder extends RecyclerView.ViewHolder {
        TextView ssidTextView, bssidTextView, signalTextView;

        public WifiViewHolder(@NonNull View itemView) {
            super(itemView);
            ssidTextView = itemView.findViewById(R.id.text_ssid);
            bssidTextView = itemView.findViewById(R.id.text_bssid);
            signalTextView = itemView.findViewById(R.id.text_signal);
        }
    }
}
