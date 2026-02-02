package com.openpositioning.PositionMe.presentation.viewitems;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.Ble;
import java.util.List;

public class BleListAdapter extends RecyclerView.Adapter<WifiViewHolder> {

    private final Context context;
    private final List<Ble> items;

    public BleListAdapter(Context context, List<Ble> items) {
        this.context = context;
        this.items = items;
    }

    @NonNull
    @Override
    public WifiViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent, int viewType) {

        return new WifiViewHolder(
                LayoutInflater.from(context)
                        .inflate(R.layout.item_wifi_card_view, parent, false)
        );
    }

    @Override
    public void onBindViewHolder(@NonNull WifiViewHolder holder, int position) {
        String macString = context.getString(R.string.mac, Long.toString(items.get(position).getMacLong()));
        holder.bssid.setText(macString);
        String levelString = context.getString(R.string.db, Long.toString(items.get(position).getRssi()));
        holder.level.setText(levelString);
        Ble ble = items.get(position);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
