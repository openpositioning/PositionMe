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
 * Adapter used for displaying BLE device data.
 *
 * @see BleViewHolder corresponding View Holder class
 * @see com.openpositioning.PositionMe.R.layout#item_ble_card_view xml layout file
 * @author Vlad Stratulat
 */
public class BleListAdapter extends RecyclerView.Adapter<BleViewHolder> {

    Context context;
    List<BleDevice> items;

    /**
     * Default public constructor with context for inflating views and list to be displayed.
     *
     * @param context application context to enable inflating views used in the list.
     * @param items list of BleDevice objects to be displayed in the list.
     * @see BleDevice the data class.
     */
    public BleListAdapter(Context context, List<BleDevice> items) {
        this.context = context;
        this.items = items;
    }

    /**
     * {@inheritDoc}
     *
     * @see com.openpositioning.PositionMe.R.layout#item_ble_card_view xml layout file
     */
    @NonNull
    @Override
    public BleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new BleViewHolder(
                LayoutInflater.from(context).inflate(R.layout.item_ble_card_view, parent, false));
    }

    /**
     * {@inheritDoc} Formats and assigns the data fields from the BleDevice object to the TextView
     * fields.
     *
     * @see BleDevice data class
     * @see com.openpositioning.PositionMe.R.string formatting for strings.
     * @see com.openpositioning.PositionMe.R.layout#item_ble_card_view xml layout file
     */
    @Override
    public void onBindViewHolder(@NonNull BleViewHolder holder, int position) {
        BleDevice device = items.get(position);
        String macString = context.getString(R.string.mac, device.getMacAddress());
        holder.macAddress.setText(macString);

        // BLE Name
        String rawName = device.getName();
        String nameToShow;
        if (rawName == null || rawName.trim().isEmpty()) {
            nameToShow = context.getString(R.string.unknown);
        } else {
            nameToShow = rawName;
        }
        String nameString = context.getString(R.string.name, nameToShow);
        holder.name.setText(nameString);

        String levelString = context.getString(R.string.db, Integer.toString(device.getRssi()));
        holder.rssi.setText(levelString);
    }

    /**
     * {@inheritDoc} Number of BleDevice objects.
     *
     * @return number of items in the list.
     */
    @Override
    public int getItemCount() {
        return items.size();
    }

    public void updateData(List<BleDevice> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }
}
