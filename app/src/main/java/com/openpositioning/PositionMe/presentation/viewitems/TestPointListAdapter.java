package com.openpositioning.PositionMe.presentation.viewitems;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.model.TestPointInfo;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class TestPointListAdapter extends RecyclerView.Adapter<TestPointListAdapter.TestPointViewHolder> {

    private final Context context;
    private final List<TestPointInfo> testPoints;

    public TestPointListAdapter(Context context, List<TestPointInfo> testPoints) {
        this.context = context;
        this.testPoints = testPoints;
    }

    @NonNull
    @Override
    public TestPointViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_testpoint_row, parent, false);
        return new TestPointViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TestPointViewHolder holder, int position) {
        TestPointInfo tp = testPoints.get(position);
        holder.tvNumber.setText(String.valueOf(tp.number));
        holder.tvLatLon.setText(String.format(Locale.US, "(%.6f, %.6f)", tp.latitude, tp.longitude));

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        holder.tvTimestamp.setText(sdf.format(tp.timestamp));
    }

    @Override
    public int getItemCount() {
        return testPoints.size();
    }

    public static class TestPointViewHolder extends RecyclerView.ViewHolder {
        TextView tvNumber, tvLatLon, tvTimestamp;

        public TestPointViewHolder(@NonNull View itemView) {
            super(itemView);
            tvNumber = itemView.findViewById(R.id.tvTestPointNumber);
            tvLatLon = itemView.findViewById(R.id.tvLatLon);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
        }
    }
}
