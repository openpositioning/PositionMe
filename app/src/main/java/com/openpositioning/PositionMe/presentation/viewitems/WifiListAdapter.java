package com.openpositioning.PositionMe.presentation.viewitems;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.Wifi;
import java.util.List;

/**
 * Adapter used for displaying wifi network data.
 *
 * @see WifiViewHolder corresponding View Holder class
 * @see com.openpositioning.PositionMe.R.layout#item_wifi_card_view xml layout file
 * @author Mate Stodulka
 */
public class WifiListAdapter extends RecyclerView.Adapter<WifiViewHolder> {

    Context context;
    List<Wifi> items;

    /**
     * Default public constructor with context for inflating views and list to be displayed.
     *
     * @param context application context to enable inflating views used in the list.
     * @param items list of Wifi objects to be displayed in the list.
     * @see Wifi the data class.
     */
    public WifiListAdapter(Context context, List<Wifi> items) {
        this.context = context;
        this.items = items;
    }

    /**
     * {@inheritDoc}
     *
     * @see com.openpositioning.PositionMe.R.layout#item_wifi_card_view xml layout file
     */
    @NonNull
    @Override
    public WifiViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new WifiViewHolder(
                LayoutInflater.from(context).inflate(R.layout.item_wifi_card_view, parent, false));
    }

    /**
     * {@inheritDoc} Formats and assigns the data fields from the Wifi object to the TextView
     * fields.
     *
     * @see Wifi data class
     * @see com.openpositioning.PositionMe.R.string formatting for strings.
     * @see com.openpositioning.PositionMe.R.layout#item_wifi_card_view xml layout file
     */
    @Override
    public void onBindViewHolder(@NonNull WifiViewHolder holder, int position) {
        Wifi wifi = items.get(position);

        String macString = context.getString(R.string.mac, Long.toString(wifi.getBssid()));
        holder.mac.setText(macString);
        String levelString = context.getString(R.string.db, Long.toString(wifi.getLevel()));
        holder.level.setText(levelString);

        // Show SSID (name)
        String rawSsid = wifi.getSsid();
        String ssidToShow;
        if (rawSsid == null || rawSsid.trim().isEmpty()) {
            ssidToShow = context.getString(R.string.unknown);
        } else {
            ssidToShow = rawSsid;
        }
        String nameString = context.getString(R.string.name, ssidToShow);
        holder.ssid.setText(nameString);
    }

    /**
     * {@inheritDoc} Number of Wifi objects.
     *
     * @see Wifi
     */
    @Override
    public int getItemCount() {
        return items.size();
    }

    public void updateData(List<Wifi> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }
}
