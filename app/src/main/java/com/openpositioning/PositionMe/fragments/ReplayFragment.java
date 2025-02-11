package com.openpositioning.PositionMe.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.Tile;
import com.google.android.gms.maps.model.TileProvider;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.Traj.Trajectory;
import com.openpositioning.PositionMe.UtilFunctions;
import com.openpositioning.PositionMe.IndoorMapManager;

import android.graphics.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

public class ReplayFragment extends Fragment implements OnMapReadyCallback {//它实现了 OnMapReadyCallback 接口，用于在Google Map准备好时执行相关操作。

    private MapView mapView;//用于在界面上显示 Google 地图的视图组件。
    private GoogleMap mMap;//对应 MapView 中的地图对象，用于添加标记、多边线、移动摄像头等操作。
    private Button btnPlayPause, btnRestart, btnGoToEnd, btnExit;
    private ProgressBar progressBar;

    // 播放控制
    private boolean isPlaying = false;//表示是否正在回放
    private int currentGnssIndex = 0;
    private int currentPdrIndex = 0;
    private Handler playbackHandler = new Handler(Looper.getMainLooper());//用于在主线程上调度回放更新任务。
    private Runnable playbackRunnable;//用于实现周期性更新轨迹标记位置的任务。

    // 轨迹数据：这里分别使用 Traj.Trajectory 解析得到的 GNSS 和 PDR 数据
    private Traj.Trajectory trajectory;//从文件中解析得到的轨迹数据对象。
    private List<Traj.GNSS_Sample> gnssPositions;//存储解析后的 GNSS 数据列表（每个数据包含纬度、经度）。
    private List<Traj.Pdr_Sample> pdrPositions;//存储解析后的 PDR 数据列表（每个数据通常包含相对位移信息，如 x、y 偏移量）。

    private Polyline gnssPolyline;
    private Polyline pdrPolyline;
    private Marker gnssMarker;
    private Marker pdrMarker;

    private IndoorMapManager indoorMapManager;
    // 文件路径从 Bundle 中获取
    private String filePath;//用于存储传入的轨迹数据文件路径，从bundle中获取。

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

        mapView.onCreate(savedInstanceState);//以保证 MapView 能正确管理自己的生命周期。
        mapView.getMapAsync(this);//this是当前类的实例对象，注册当前 Fragment 作为地图加载完成的回调（即调用 onMapReady 方法）。
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 从 Bundle 获取临时文件路径
        if (getArguments() != null) {
            filePath = getArguments().getString("trajectory_file_path");
        }
        if (filePath == null) {
            Toast.makeText(getContext(), "No trajectory file provided", Toast.LENGTH_SHORT).show();
            return;
        }
        // 读取并解析文件数据到 Traj.Trajectory 对象
        try {
            File file = new File(filePath);
            FileInputStream fis = new FileInputStream(file);//stream类型读取信息的方式
            byte[] data = new byte[(int) file.length()];//file.length() 返回文件的大小（以字节为单位）。(int) file.length() 将文件大小转换为 int 类型。
            fis.read(data);
            fis.close();
            trajectory = Traj.Trajectory.parseFrom(data);
            // 分别获取 GNSS 数据列表和 PDR 数据列表
            gnssPositions = trajectory.getGnssDataList();
            pdrPositions = trajectory.getPdrDataList();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Failed to load trajectory data", Toast.LENGTH_SHORT).show();
        }

        // 初始化按钮点击事件
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

        // 设置进度条的最大值为两种数据中较大者
        if ((gnssPositions != null && !gnssPositions.isEmpty()) ||
                (pdrPositions != null && !pdrPositions.isEmpty())) {
            int maxCount = Math.max(gnssPositions != null ? gnssPositions.size() : 0,
                    pdrPositions != null ? pdrPositions.size() : 0);
            progressBar.setMax(maxCount);
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
        // 绘制 GNSS 轨迹（蓝色）

        // 根据需要加载
        if (gnssPositions != null && !gnssPositions.isEmpty()) {
            PolylineOptions gnssOptions = new PolylineOptions().color(Color.BLUE);
            for (Traj.GNSS_Sample sample : gnssPositions) {
                LatLng latLng = new LatLng(sample.getLatitude(), sample.getLongitude());
                gnssOptions.add(latLng);
            }
            gnssPolyline = mMap.addPolyline(gnssOptions);
            // 将摄像头移动到 GNSS 轨迹起点
            LatLng gnssStart = new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude());
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(gnssStart, 18f));
            // 创建 GNSS 动态标记
            gnssMarker = mMap.addMarker(new MarkerOptions().position(gnssStart).title("GNSS Position")
                    .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_BLUE)));

            //inner buildings
            LatLng Nucleus_building_inner = new LatLng(55.923089201509164, -3.17426605622692);
            GroundOverlayOptions Nucleus_building_inner_ = new GroundOverlayOptions()
                    .image(BitmapDescriptorFactory.fromResource(R.drawable.floor_1))
                    .position(Nucleus_building_inner, 48f, 53f);
            mMap.addGroundOverlay(Nucleus_building_inner_);
            // Add an overlay to the map, retaining a handle to the GroundOverlay object.
            GroundOverlay Nucleus_building_inner_imageOverlay = mMap.addGroundOverlay(Nucleus_building_inner_);

            LatLng Murray_library_inner = new LatLng(55.922947075165695, -3.174960196013571);
            GroundOverlayOptions Murray_library_inner_ = new GroundOverlayOptions()
                    .image(BitmapDescriptorFactory.fromResource(R.drawable.library1))
                    .position(Murray_library_inner, 27f, 27f);
            mMap.addGroundOverlay(Murray_library_inner_);
            GroundOverlay Murray_library_inner_imageOverlay = mMap.addGroundOverlay(Murray_library_inner_);

            //addTileOverlay(); //when there is a huge amount of inner maps
        }
        // 绘制 PDR 轨迹（红色）
        if (pdrPositions != null && !pdrPositions.isEmpty()) {
            // 假设 PDR 数据的起点采用 GNSS 轨迹的起点（如果有的话）
            LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty())
                    ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude())
                    : new LatLng(0, 0);
            PolylineOptions pdrOptions = new PolylineOptions().color(Color.RED);
            for (Traj.Pdr_Sample sample : pdrPositions) {
                // 利用工具函数将 PDR 数据的相对偏移量转换为地图坐标
                float[] pdrOffset = new float[]{ sample.getX(), sample.getY() };
                LatLng latLng = UtilFunctions.calculateNewPos(pdrStart, pdrOffset);

                pdrOptions.add(latLng);
            }
            pdrPolyline = mMap.addPolyline(pdrOptions);
            // 创建 PDR 动态标记（初始位置取第一个 PDR 转换后的点）
            if (!pdrOptions.getPoints().isEmpty()) {
                pdrMarker = mMap.addMarker(new MarkerOptions().position(pdrOptions.getPoints().get(0)).title("PDR Position")
                        .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_RED)));
            }
        }
    }

    private TileOverlay tileOverlay;
    private void addTileOverlay() {
        LocalTileProvider tileProvider = new LocalTileProvider(getActivity());
        tileOverlay = mMap.addTileOverlay(new TileOverlayOptions().tileProvider(tileProvider) .transparency(0.85f));
    }

    public static class LocalTileProvider implements TileProvider {
        private static final int TILE_SIZE = 256; // Tile size in pixels
        private final Context context;

        public LocalTileProvider(Context context) {
            this.context = context;
        }

        @Override
        public Tile getTile(int x, int y, int zoom) {
            byte[] image = getTileImage(x, y, zoom);
            if (image == null) {
                return NO_TILE; // Return empty tile if not found
            }
            return new Tile(TILE_SIZE, TILE_SIZE, image);
        }

        private byte[] getTileImage(int x, int y, int zoom) {
            //String filePath = String.format("tiles/%d/%d/%d.png", zoom, x, y);
            //String filePath = "res/drawable/floor_1.png";
            if(zoom > 15) {
                try {
                    //InputStream inputStream = context.getAssets().open(filePath);
                    //Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                    //inputStream.close();
                    Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.floor_1);
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                    return stream.toByteArray();
                    //return bitmapToByteArray(bitmap);
                } catch (Exception e) {//IOException e
                    //Log.e("LocalTileProvider", "Tile not found: " + filePath);
                    Log.e("LocalTileProvider", "Error loading tile image", e);
                    return null;
                }
            } else {
                return null;
            }
        }

        private byte[] bitmapToByteArray(Bitmap bitmap) {
            java.io.ByteArrayOutputStream stream = new java.io.ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            return stream.toByteArray();
        }
    }

    private TileOverlay tileOverlayTransparent;
    // Switch between 0.0f and 0.5f transparency.
    public void toggleTileOverlayTransparency() {
        if (tileOverlayTransparent != null) {
            tileOverlayTransparent.setTransparency(0.5f - tileOverlayTransparent.getTransparency());
        }
    }


    // 开始回放：每隔一定时间更新两个轨迹的动态标记位置
    private void startReplay() {
        if ((gnssPositions == null || gnssPositions.isEmpty()) && (pdrPositions == null || pdrPositions.isEmpty()))
            return;
        isPlaying = true;
        btnPlayPause.setText("Pause");

        playbackRunnable = new Runnable() {
            @Override
            public void run() {
                // 更新 GNSS 标记
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
                // 更新 PDR 标记
                if (pdrPositions != null && currentPdrIndex < pdrPositions.size()) {
                    Traj.Pdr_Sample sample = pdrPositions.get(currentPdrIndex);
                    // 假设 PDR 数据起点与 GNSS 数据起点一致
                    LatLng pdrStart = (gnssPositions != null && !gnssPositions.isEmpty())
                            ? new LatLng(gnssPositions.get(0).getLatitude(), gnssPositions.get(0).getLongitude())
                            : new LatLng(0, 0);

                    //LatLng latLng = UtilFunctions.offsetLatLng(pdrStart, sample.getX(), sample.getY());
                    float[] pdrOffset = new float[]{ sample.getX(), sample.getY() };
                    LatLng latLng = UtilFunctions.calculateNewPos(pdrStart, pdrOffset);

                    if (pdrMarker != null) {
                        pdrMarker.setPosition(latLng);
                    } else {
                        pdrMarker = mMap.addMarker(new MarkerOptions().position(latLng).title("PDR Position")
                                .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_RED)));
                    }
                    currentPdrIndex++;
                }
                // 更新进度条，取两者的平均进度（或根据实际需求修改）
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

    // 暂停回放
    private void pauseReplay() {
        isPlaying = false;
        btnPlayPause.setText("Play");
        if (playbackRunnable != null) {
            playbackHandler.removeCallbacks(playbackRunnable);
        }
    }

    // 重启回放：从头开始播放
    private void restartReplay() {
        pauseReplay();
        currentGnssIndex = 0;
        currentPdrIndex = 0;
        progressBar.setProgress(0);
        startReplay();
    }

    // 快进到末尾：直接显示最后一个位置
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

    // 退出回放：返回上一级界面
    private void exitReplay() {
        pauseReplay();
        getActivity().onBackPressed();
    }

    // MapView 生命周期方法
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
        // 播放结束后删除临时文件以节约存储空间
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
