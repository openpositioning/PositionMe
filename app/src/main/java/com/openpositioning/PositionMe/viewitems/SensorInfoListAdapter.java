package com.openpositioning.PositionMe.viewitems;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorInfo;

import java.util.List;
import java.util.Objects;

/**
 * Adapter used for displaying sensor info data.
 *
 * @see SensorInfoViewHolder corresponding View Holder class
 * @see com.openpositioning.PositionMe.R.layout#item_sensorinfo_card_view xml layout file
 *
 * @author Mate Stodulka
 */
public class SensorInfoListAdapter extends RecyclerView.Adapter<SensorInfoViewHolder> {

    Context context;
    List<SensorInfo> sensorInfoList;

    /**
     * Default public constructor with context for inflating views and list to be displayed.
     *
     * @param context           application context to enable inflating views used in the list.
     * @param sensorInfoList    list of SensorInfo objects to be displayed in the list.
     *
     * @see SensorInfo the data class.
     */
    public SensorInfoListAdapter(Context context, List<SensorInfo> sensorInfoList) {
        this.context = context;
        this.sensorInfoList = sensorInfoList;
    }

    /**
     * {@inheritDoc}
     * @see com.openpositioning.PositionMe.R.layout#item_sensorinfo_card_view xml layout file
     */
    @NonNull
    @Override
    public SensorInfoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new SensorInfoViewHolder(LayoutInflater.from(context).inflate(R.layout.item_sensorinfo_card_view, parent, false));
    }

    /**
     * {@inheritDoc}
     * Formats and assigns the data fields from the SensorInfo object to the TextView fields.
     *
     * @see SensorInfo data class
     * @see com.openpositioning.PositionMe.R.string formatting for strings.
     * @see com.openpositioning.PositionMe.R.layout#item_sensorinfo_card_view xml layout file
     */
    @Override
    public void onBindViewHolder(@NonNull SensorInfoViewHolder holder, int position) {
        holder.name.setText(sensorInfoList.get(position).getName());

        String vendorString =  context.getString(R.string.vendor, sensorInfoList.get(position).getVendor());
        holder.vendor.setText(vendorString);

        String resolutionString =  context.getString(R.string.resolution, String.format("%.03g", sensorInfoList.get(position).getResolution()));
        holder.resolution.setText(resolutionString);
        String powerString =  context.getString(R.string.power, Objects.toString(sensorInfoList.get(position).getPower(), "N/A"));
        holder.power.setText(powerString);
        String versionString =  context.getString(R.string.version, Objects.toString(sensorInfoList.get(position).getVersion(), "N/A"));
        holder.version.setText(versionString);
    }

    /**
     * {@inheritDoc}
     * Number of SensorInfo objects.
     *
     * @see SensorInfo
     */
    @Override
    public int getItemCount() {
        return sensorInfoList.size();
    }
}
