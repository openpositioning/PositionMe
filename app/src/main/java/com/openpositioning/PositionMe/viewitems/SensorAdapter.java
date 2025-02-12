package com.openpositioning.PositionMe.viewitems;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.SensorData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SensorAdapter extends RecyclerView.Adapter<SensorAdapter.SensorViewHolder> {
    private List<SensorData> sensorList;
    private Map<String, SensorData> sensorDataMap;

    public SensorAdapter(List<SensorData> sensorList) {
        this.sensorList = sensorList;
        this.sensorDataMap = new LinkedHashMap<>(); // ✅ 确保传感器顺序不变
        for (SensorData data : sensorList) {
            sensorDataMap.put(data.getName(), data);
        }
    }

    @NonNull
    @Override
    public SensorViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_sensor, parent, false);
        return new SensorViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SensorViewHolder holder, int position) {
        SensorData data = sensorList.get(position);
        holder.sensorName.setText(data.getName());
        holder.sensorValue.setText(data.getValue());
    }

    @Override
    public int getItemCount() {
        return sensorList.size();
    }

    // ✅ **更新整个数据集（用于 `onResume()` 或初始化）**
    public void updateData(List<SensorData> newData) {
        sensorDataMap.clear();
        for (SensorData data : newData) {
            sensorDataMap.put(data.getName(), data);
        }
        sensorList.clear();
        sensorList.addAll(sensorDataMap.values()); // ✅ 确保按原始顺序更新
        notifyDataSetChanged();
    }

    static class SensorViewHolder extends RecyclerView.ViewHolder {
        TextView sensorName, sensorValue;

        public SensorViewHolder(@NonNull View itemView) {
            super(itemView);
            sensorName = itemView.findViewById(R.id.sensor_name);
            sensorValue = itemView.findViewById(R.id.sensor_value);
        }
    }
}
