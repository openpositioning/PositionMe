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
 * @see R.layout#item_sensorinfo_card_view xml layout file
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
     * @see R.layout#item_sensorinfo_card_view xml layout file
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
     * @see R.string formatting for strings.
     * @see R.layout#item_sensorinfo_card_view xml layout file
     */
    @Override
    public void onBindViewHolder(@NonNull SensorInfoViewHolder holder, int position) {
        String fullName = sensorInfoList.get(position).getName();
        String displayName = "";
        
        // 定义传感器类型和它们的显示名称，以及可能的型号前缀
        String[][] sensorMappings = {
            {"Accelerometer", "Acceleration Sensor"},
            {"Acceleration", "Acceleration Sensor"},
            {"Gyroscope", "Gyroscope Sensor"},
            {"Magnetic", "Magnetic Sensor"},
            {"Light", "Light Sensor"},
            {"Pressure", "Pressure Sensor"},
            {"Proximity", "Proximity Sensor"}
        };

        // 定义要移除的型号前缀
        String[] prefixesToRemove = {
            "lsm6dso",
            "LSM6DSO",
            "ak0991x",
            "AK0991X",
            "Non-wakeup",
            "Non-Wakeup"
        };

        // 移除所有已知的型号前缀
        String cleanName = fullName;
        for (String prefix : prefixesToRemove) {
            cleanName = cleanName.replace(prefix, "").trim();
        }
        
        // 遍历查找传感器类型
        for (String[] mapping : sensorMappings) {
            if (fullName.toLowerCase().contains(mapping[0].toLowerCase())) {
                displayName = mapping[1];
                break;
            }
        }
        
        // 如果没有找到匹配的类型，检查是否包含"Magnetic"或其他关键词
        if (displayName.isEmpty()) {
            if (fullName.toLowerCase().contains("magnetic") || 
                fullName.toLowerCase().contains("mag") ||
                fullName.toLowerCase().contains("ak")) {
                displayName = "Magnetic Sensor";
            } else if (fullName.toLowerCase().contains("accel")) {
                displayName = "Acceleration Sensor";
            } else if (fullName.toLowerCase().contains("gyro")) {
                displayName = "Gyroscope Sensor";
            } else {
                // 如果还是没找到，使用清理后的名称
                displayName = cleanName.trim();
                if (displayName.isEmpty() || displayName.equals("Sensor")) {
                    displayName = "Unknown Sensor";
                } else if (!displayName.toLowerCase().contains("sensor")) {
                    displayName += " Sensor";
                }
            }
        }

        holder.name.setText(displayName);

        String vendorString = context.getString(R.string.vendor, sensorInfoList.get(position).getVendor());
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
