package com.openpositioning.PositionMe.presentation.viewitems;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.Wifi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Adapter used for displaying wifi network data.
 *
 * @see WifiViewHolder corresponding View Holder class
 * @see com.openpositioning.PositionMe.R.layout#item_wifi_card_view xml layout file
 *
 * @author Mate Stodulka
 */
public class WifiListAdapter extends RecyclerView.Adapter<WifiViewHolder> {

    private final Context context;
    private final List<Wifi> items;

    /**
     * Default public constructor with context for inflating views and list to be displayed.
     *
     * @param context   application context to enable inflating views used in the list.
     * @param items     list of Wifi objects to be displayed in the list.
     *
     * @see Wifi the data class.
     */
    public WifiListAdapter(Context context, List<Wifi> items) {
        this.context = context;
        this.items = new ArrayList<>();
        submitItems(items);
    }

    public void submitItems(List<Wifi> items) {
        this.items.clear();
        if (items != null && !items.isEmpty()) {
            this.items.addAll(items);
        }
        notifyDataSetChanged();
    }

    public List<Wifi> getItems() {
        return Collections.unmodifiableList(items);
    }

    /**
     * {@inheritDoc}
     * @see com.openpositioning.PositionMe.R.layout#item_wifi_card_view xml layout file
     */
    @NonNull
    @Override
    public WifiViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new WifiViewHolder(LayoutInflater.from(context).inflate(R.layout.item_wifi_card_view,parent,false));
    }

    /**
     * {@inheritDoc}
     * Formats and assigns the data fields from the Wifi object to the TextView fields.
     *
     * @see Wifi data class
     * @see com.openpositioning.PositionMe.R.string formatting for strings.
     * @see com.openpositioning.PositionMe.R.layout#item_wifi_card_view xml layout file
     */
    @Override
    public void onBindViewHolder(@NonNull WifiViewHolder holder, int position) {
        String macString = context.getString(R.string.mac, Long.toString(items.get(position).getBssid()));
        holder.bssid.setText(macString);
        String levelString = context.getString(R.string.db, Long.toString(items.get(position).getLevel()));
        holder.level.setText(levelString);
    }

    /**
     * {@inheritDoc}
     * Number of Wifi objects.
     *
     * @see Wifi
     */
    @Override
    public int getItemCount() {
        return items.size();
    }
}
