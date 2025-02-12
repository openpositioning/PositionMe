package com.openpositioning.PositionMe.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Tile;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.android.gms.maps.model.TileProvider;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.Traj.Trajectory;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.IndoorMapManager;

import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

public class ReplayFragment extends Fragment implements OnMapReadyCallback {

    private MapView mapView;
    private GoogleMap mMap;
    private Button btnPlayPause, btnRestart, btnGoToEnd, btnExit;
    private SeekBar progressBar;

    // 播放控制
    private boolean isPlaying = false;
    private int currentGnssIndex = 0;
    private int currentPdrIndex = 0;
    private Handler playbackHandler = new Handler(Looper.getMainLooper());
    private Runnable playbackRunnable;

    // using Traj.Trajectory to get GNSS and PDR data
    private Traj.Trajectory trajectory;
    private List<Traj.Position_Sample> _positionData;
    private int _positionData_pointer = 0;
    private List<Traj.GNSS_Sample> gnssPositions;//Stores the parsed GNSS data list
    private List<Traj.Pdr_Sample> pdrPositions;//Stores the parsed PDR data list

    private Polyline gnssPolyline;
    private Polyline pdrPolyline;
    private Marker gnssMarker;
    private Marker pdrMarker;

    private IndoorMapManager _indoorMapManager;

    private String filePath;

    public FloatingActionButton _floorUpButton; // Floor Up button
    public FloatingActionButton _floorDownButton; // Floor Down button
    private Switch _autoFloor;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_replay, container, false);
        mapView = view.findViewById(R.id.mapView);
        btnPlayPause = view.findViewById(R.id.btnPlayPause);
        btnRestart = view.findViewById(R.id.btnRestart);
        btnGoToEnd = view.findViewById(R.id.btnGoToEnd);
        btnExit = view.findViewById(R.id.btnExit);
        progressBar = view.findViewById(R.id.progressBar);

        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Get the temporary file path from the Bundle
        if (getArguments() != null) {
            filePath = getArguments().getString("trajectory_file_path");
        }
        if (filePath == null) {
            Toast.makeText(getContext(), "No trajectory file provided", Toast.LENGTH_SHORT).show();
            return;
        }
        // Reads and parses file data to the Trajectory object
        try {
            File file = new File(filePath);
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();
            trajectory = Traj.Trajectory.parseFrom(data);
            _positionData = trajectory.getPositionDataList();
            gnssPositions = trajectory.getGnssDataList();
            pdrPositions = trajectory.getPdrDataList();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Failed to load trajectory data", Toast.LENGTH_SHORT).show();
        }

        // Floor changer Buttons
        this._floorUpButton=getView().findViewById(R.id.floorUpButton1);
        this._floorDownButton=getView().findViewById(R.id.floorDownButton1);
        // Auto-floor switch
        this._autoFloor=getView().findViewById(R.id.autoFloor1);
        _autoFloor.setChecked(true);
        // Hiding floor changing buttons and auto-floor switch
        setFloorButtonVisibility(View.GONE);
        this._floorUpButton.setOnClickListener(new View.OnClickListener() {
            /**
             *{@inheritDoc}
             * Listener for increasing the floor for the indoor map
             */
            @Override
            public void onClick(View view) {
                // Setting off auto-floor as manually changed
                _autoFloor.setChecked(false);
                _indoorMapManager.increaseFloor();
            }
        });
        this._floorDownButton.setOnClickListener(new View.OnClickListener() {
            /**
             *{@inheritDoc}
             * Listener for decreasing the floor for the indoor map
             */
            @Override
            public void onClick(View view) {
                // Setting off auto-floor as manually changed
                _autoFloor.setChecked(false);
                _indoorMapManager.decreaseFloor();
            }
        });

        btnPlayPause.setOnClickListener(v -> {
            if (isPlaying) {
                pauseReplay();
            } else {
                startReplay();
            }
        });

        btnRestart.setOnClickListener(v -> restartReplay());
        btnGoToEnd.setOnClickListener(v -> goToEndReplay());
        btnExit.setOnClickListener(v -> exitReplay());

        // Take the average of GNSS AND PDR to the seekbar
        if ((gnssPositions != null && !gnssPositions.isEmpty()) ||
                (pdrPositions != null && !pdrPositions.isEmpty())) {
            int maxCount = Math.max(gnssPositions != null ? gnssPositions.size() : 0,
                    pdrPositions != null ? pdrPositions.size() : 0);
            progressBar.setMax(maxCount);
        }

        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentGnssIndex = progress;
                    currentPdrIndex = progress;
                    updateMarkersForProgress();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Pause autoplay when dragging starts to prevent conflicts with automatic updates
                pauseReplay();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Resume auto play after dragging
            }
        });
    }
    private void updateMarkersForProgress() {
        if (mMap == null) {
            return;
        }
        // update GNSS track
        if (gnssPositions != null && !gnssPositions.isEmpty()) {
            int index = Math.min(currentGnssIndex, gnssPositions.size() - 1);
            Traj.GNSS_Sample sample = gnssPositions.get(index);
            LatLng latLng = new LatLng(sample.getLatitude(), sample.getLongitude());
            if (gnssMarker != null) {
                gnssMarker.setPosition(latLng);
            } else {
                gnssMarker = mMap.addMarker(new MarkerOptions()
                        .position(latLng)
                        .title("GNSS Position")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
            }
        }
        // update PDR track
        if (pdrPositions != null && !pdrPositions.isEmpty()) {
            int index = Math.min(currentPdrIndex, pdrPositions.size() - 1);
            Traj.Pdr_Sample sample = pdrPositions.get(index);
            LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty())
                    ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude())
                    : new LatLng(0, 0);
            float[] pdrOffset = new float[]{ sample.getX(), sample.getY() };
            LatLng latLng = UtilFunctions.calculateNewPos(pdrStart, pdrOffset);
            if (pdrMarker != null) {
                pdrMarker.setPosition(latLng);
            } else {
                pdrMarker = mMap.addMarker(new MarkerOptions()
                        .position(latLng)
                        .title("PDR Position")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
            }
        }
    }


    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        mMap.getUiSettings().setCompassEnabled(true);
        mMap.getUiSettings().setTiltGesturesEnabled(true);
        mMap.getUiSettings().setRotateGesturesEnabled(true);
        mMap.getUiSettings().setScrollGesturesEnabled(true);

        //inner buildings
        _indoorMapManager = new IndoorMapManager(mMap);
        //Showing an indication of available indoor maps using PolyLines
        _indoorMapManager .setIndicationOfIndoorMap();

        // Plot GNSS trajectories (blue)
        if (gnssPositions != null && !gnssPositions.isEmpty()) {
            PolylineOptions gnssOptions = new PolylineOptions().color(Color.BLUE);
            for (Traj.GNSS_Sample sample : gnssPositions) {
                LatLng latLng = new LatLng(sample.getLatitude(), sample.getLongitude());
                gnssOptions.add(latLng);
            }
            gnssPolyline = mMap.addPolyline(gnssOptions);

            LatLng gnssStart = new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude());
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(gnssStart, 18f));
            // create GNSS dynamic track
            gnssMarker = mMap.addMarker(new MarkerOptions().position(gnssStart).title("GNSS Position")
                    .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_BLUE)));
        }

        // plot PDR trajectories（red）
        if (pdrPositions != null && !pdrPositions.isEmpty()) {
            LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty())
                    ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude())
                    : new LatLng(0, 0);
            PolylineOptions pdrOptions = new PolylineOptions().color(Color.RED);
            for (Traj.Pdr_Sample sample : pdrPositions) {
                float[] pdrOffset = new float[]{ sample.getX(), sample.getY() };
                LatLng latLng = UtilFunctions.calculateNewPos(pdrStart, pdrOffset);

                pdrOptions.add(latLng);
            }
            pdrPolyline = mMap.addPolyline(pdrOptions);
            if (!pdrOptions.getPoints().isEmpty()) {
                pdrMarker = mMap.addMarker(new MarkerOptions().position(pdrOptions.getPoints().get(0)).title("PDR Position")
                        .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_RED)));
            }
        }
    }

    private void setFloorButtonVisibility(int visibility){
        _floorUpButton.setVisibility(visibility);
        _floorDownButton.setVisibility(visibility);
        _autoFloor.setVisibility(visibility);
    }

    // restart
    private void startReplay() {
        if ((gnssPositions == null || gnssPositions.isEmpty()) && (pdrPositions == null || pdrPositions.isEmpty()))
            return;

        isPlaying = true;
        btnPlayPause.setText("Pause");

        playbackRunnable = new Runnable() {
            @Override
            public void run() {
                // update GNSS marker
                if (gnssPositions != null && currentGnssIndex < gnssPositions.size()) {
                    Traj.GNSS_Sample sample = gnssPositions.get(currentGnssIndex);
                    LatLng latLng = new LatLng(sample.getLatitude(), sample.getLongitude());
                    if (gnssMarker != null) {
                        gnssMarker.setPosition(latLng);
                    } else {
                        gnssMarker = mMap.addMarker(new MarkerOptions().position(latLng).title("GNSS Position")
                                .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_BLUE)));
                    }
                    currentGnssIndex++;
                }
                // update PDR marker
                if (pdrPositions != null && currentPdrIndex < pdrPositions.size()) {
                    Traj.Pdr_Sample sample = pdrPositions.get(currentPdrIndex);
                    LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty()) ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude()) : new LatLng(0, 0);

                    //LatLng latLng = UtilFunctions.offsetLatLng(pdrStart, sample.getX(), sample.getY());
                    float[] pdrOffset = new float[]{ sample.getX(), sample.getY() };
                    LatLng latLng = UtilFunctions.calculateNewPos(pdrStart, pdrOffset);


                    if(_positionData != null && _positionData_pointer < _positionData.size()) {
                        // If not initialized, initialize
                        if (_indoorMapManager == null) {
                            _indoorMapManager = new IndoorMapManager(mMap);
                        }
                        //  Updates current location of user to show the indoor floor map (if applicable)
                        //latLng = new LatLng(55.923089201509164, -3.17426605622692); //test
                        _indoorMapManager.setCurrentLocation(latLng); //latLng
                        float elevationVal = _positionData.get(_positionData_pointer).getMagZ();
                        Log.d("MagZ", String.format("Elevation Value: %.2f", elevationVal));
                        // Display buttons to allow user to change floors if indoor map is visible
                        if (_indoorMapManager.getIsIndoorMapSet()) {
                            setFloorButtonVisibility(View.VISIBLE);
                            // Auto-floor logic
                            if (_autoFloor.isChecked()) {
                                _indoorMapManager.setCurrentFloor((int) (elevationVal / _indoorMapManager.getFloorHeight())
                                        , true);
                            }
                        } else {
                            // Hide the buttons and switch used to change floor if indoor map is not visible
                            setFloorButtonVisibility(View.GONE);
                        }
                        _positionData_pointer++;
                    }


                    if (pdrMarker != null) {
                        pdrMarker.setPosition(latLng);
                    } else {
                        pdrMarker = mMap.addMarker(new MarkerOptions().position(latLng).title("PDR Position")
                                .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_RED)));
                    }
                    currentPdrIndex++;
                }
                // update the seekbar
                int progress = (currentGnssIndex + currentPdrIndex) / 2;
                progressBar.setProgress(progress);

                if ((gnssPositions != null && currentGnssIndex < gnssPositions.size()) ||
                        (pdrPositions != null && currentPdrIndex < pdrPositions.size())) {
                    playbackHandler.postDelayed(this, 500); // 每500毫秒更新一次
                } else {
                    pauseReplay();
                }
            }
        };
        playbackHandler.post(playbackRunnable);
    }

    // pause
    private void pauseReplay() {
        isPlaying = false;
        btnPlayPause.setText("Play");
        if (playbackRunnable != null) {
            playbackHandler.removeCallbacks(playbackRunnable);
        }
    }

    // restart
    private void restartReplay() {
        pauseReplay();
        currentGnssIndex = 0;
        currentPdrIndex = 0;
        _positionData_pointer = 0;
        progressBar.setProgress(0);
        startReplay();
    }

    // end
    private void goToEndReplay() {
        pauseReplay();
        if (gnssPositions != null && !gnssPositions.isEmpty()) {
            currentGnssIndex = gnssPositions.size() - 1;
            Traj.GNSS_Sample sample = gnssPositions.get(currentGnssIndex);
            LatLng latLng = new LatLng(sample.getLatitude(), sample.getLongitude());
            if (gnssMarker != null) {
                gnssMarker.setPosition(latLng);
            } else {
                gnssMarker = mMap.addMarker(new MarkerOptions().position(latLng).title("GNSS Position"));
            }
            mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng));
            progressBar.setProgress(currentGnssIndex);
        }
        if (pdrPositions != null && !pdrPositions.isEmpty()) {
            currentPdrIndex = pdrPositions.size() - 1;
            LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty())
                    ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude())
                    : new LatLng(0, 0);
            Traj.Pdr_Sample sample = pdrPositions.get(currentPdrIndex);
            //LatLng latLng = UtilFunctions.offsetLatLng(pdrStart, sample.getX(), sample.getY());
            float[] pdrOffset = new float[]{ sample.getX(), sample.getY() };
            LatLng latLng = UtilFunctions.calculateNewPos(pdrStart, pdrOffset);

            if (pdrMarker != null) {
                pdrMarker.setPosition(latLng);
            } else {
                pdrMarker = mMap.addMarker(new MarkerOptions().position(latLng).title("PDR Position"));
            }
            mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng));
            progressBar.setProgress(Math.max(currentGnssIndex, currentPdrIndex));
        }
    }

    // exit
    private void exitReplay() {
        pauseReplay();
        getActivity().onBackPressed();
    }

    // MapView life cycle
    @Override
    public void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mapView.onDestroy();
        // Delete temporary files after playback
        if (getArguments() != null) {
            String path = getArguments().getString("trajectory_file_path");
            if (path != null) {
                File file = new File(path);
                if (file.exists()) {
                    file.delete();
                }
            }
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }
}
