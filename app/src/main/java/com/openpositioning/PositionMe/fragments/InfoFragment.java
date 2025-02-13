package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorInfo;
import com.openpositioning.PositionMe.viewitems.SensorInfoListAdapter;

import java.util.List;

/**
 * A simple {@link Fragment} subclass. The info fragment display the available sensors and data
 * collection devices with relevant information about their capabilities.
 *
 * @see HomeFragment the previous fragment in the nav graph.
 * @see com.openpositioning.PositionMe.sensors.SensorFusion the class containing all sensors.
 * @see SensorInfo the class used for each sensor instance's metadata
 *
 * @author Mate Stodulka
 */
public class InfoFragment extends Fragment {

    // Singleton SensorFusion instance to access the sensors used
    private SensorFusion sensorFusion;
    // UI element recyclerview to display sensor information
    private RecyclerView sensorInfoView;

    /**
     * Public default constructor, empty.
     */
    public InfoFragment() {
        // Required empty public constructor
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    /**
     * {@inheritDoc}
     * Set title in the action bar to Sensor Information.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_info, container, false);
        getActivity().setTitle("Sensor Information");
        return rootView;
    }

    /**
     * {@inheritDoc}
     * Initialise the RecyclerView by creating and registering a Layout Manager, getting the
     * {@link SensorFusion} instance and obtaining the Sensor Info data, and passing it to the
     * {@link SensorInfoListAdapter}.
     *
     * @see SensorInfoListAdapter List adapter for the Sensor Info Recycler View.
     * @see com.openpositioning.PositionMe.viewitems.SensorInfoViewHolder View holder for the Sensor Infor RV.
     * @see com.openpositioning.PositionMe.R.layout#item_sensorinfo_card_view
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Find recyclerView
        sensorInfoView = (RecyclerView) getView().findViewById(R.id.sensorInfoList);
        // Register layout manager
        sensorInfoView.setLayoutManager(new LinearLayoutManager(getActivity()));
        // Get singleton sensor fusion instance, load sensor info data
        sensorFusion = SensorFusion.getInstance();
        List<SensorInfo> sensorInfoList = sensorFusion.getSensorInfos();
        // Set adapter for the recycler view.
        sensorInfoView.setAdapter(new SensorInfoListAdapter(getActivity(), sensorInfoList));
    }
}