package com.openpositioning.PositionMe.presentation.viewitems;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.BLE;

import java.util.List;

public class BleListAdapter extends RecyclerView.Adapter<BleViewHolder> {

    private final Context context;
    private final List<BLE> items;

    public BleListAdapter(Context context, List<BLE> items) {
        this.context = context;
        this.items = items;
    }

    @NonNull
    @Override
    public BleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new BleViewHolder(
                LayoutInflater.from(context).inflate(R.layout.item_ble_card_view, parent, false)
        );
    }

    @Override
    public void onBindViewHolder(@NonNull BleViewHolder holder, int position) {
        BLE device = items.get(position);

        // 下面两个 getter 名字按你 BLE.java 实际来：
        // 如果你的 BLE.java 不是 getMac()/getRssi()，就改成你实际的方法名
        String mac = device.getMac();     // 例如 "AA:BB:CC:DD:EE:FF"
        int rssi = device.getRssi();      // 例如 -63

        holder.mac.setText(context.getString(R.string.mac, mac));
        holder.rssi.setText("RSSI: " + rssi);
        // 如果你也做了 string resource：context.getString(R.string.rssi, rssi)
    }

    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }
}
