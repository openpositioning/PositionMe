package com.openpositioning.PositionMe.fragments;

import static android.content.ContentValues.TAG;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.protobuf.InvalidProtocolBufferException;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.IndoorMapManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Replay extends AppCompatActivity implements OnMapReadyCallback {

    private GoogleMap mMap;
    // 使用 TimedLatLng 来存储 PDR 样本的坐标和归一化后的时间戳（单位：毫秒）
    private List<TimedLatLng> trackPoints = new ArrayList<>();
    private Polyline polyline;
    private Marker currentMarker;  // 当前 PDR 位置的 Marker（显示方向）
    private Marker gnssMarker;     // 用于显示 GNSS 的位置

    // 播放控制变量
    private boolean isPlaying = false;
    private Handler handler = new Handler();

    private SeekBar seekBar;
    private ImageButton playButton, fastRewind, fastForward, gotoStartButton, gotoEndButon;
    private TextView progressText, totaltimetext;
    private Switch switch1; // 控制是否显示完整轨迹
    private String filePath;

    // 播放相关的时间参数（单位：毫秒）
    private long totalDuration = 0; // 轨迹总时长，根据所有 PDR 样本中最大的归一化时间戳决定
    // currentTime 表示当前播放到的时间戳（归一化时间）
    private long currentTime = 0;

    // 播放速度因子（1.0 表示以录制真实时长播放，可根据需要加速播放）
    private double playbackSpeedFactor = 1.0;

    // 用于室内地图显示的管理器
    private IndoorMapManager indoorMapManager;

    // 保存解析后的 Trajectory 数据，用于获取 GNSS 数据
    private Traj.Trajectory trajectoryData;

    // Switch 用于自动楼层切换及楼层切换按钮
    private Switch autoFloor;
    public FloatingActionButton floorUpButton;
    public FloatingActionButton floorDownButton;
    private Spinner mapTypeSpinner;

    private int currentFloor;

    float elevationVal;

    // 当前播放到的 pdr 样本索引（用于遍历 trackPoints）
    private int currentIndex = 0;

    // 记录播放开始的系统时间（ms）
    private long playbackStartTime = 0;

    /**
     * 内部类，用于存储 PDR 计算得到的地理位置及其对应的归一化时间戳（单位：毫秒）
     */
    public static class TimedLatLng {
        public LatLng point;
        public long relativeTimestamp; // 使用 long 表示毫秒时间戳

        public TimedLatLng(LatLng point, long relativeTimestamp) {
            this.point = point;
            this.relativeTimestamp = relativeTimestamp;
        }
    }

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_replay);

        // 从 Intent 获取文件路径
        filePath = getIntent().getStringExtra("filePath");

        // 初始化 UI 控件
        seekBar = findViewById(R.id.seekBar);
        playButton = findViewById(R.id.playPauseButton);
        fastRewind = findViewById(R.id.fastRewindButton);
        fastForward = findViewById(R.id.fastForwardButton);
        gotoStartButton = findViewById(R.id.goToStartButton);
        gotoEndButon = findViewById(R.id.goToEndButton);
        progressText = findViewById(R.id.currentTime);
        totaltimetext = findViewById(R.id.totalTime);
        switch1 = findViewById(R.id.switch1);  // 请确保 layout 中存在此 Switch 控件
        autoFloor = findViewById(R.id.autoFloor2);
        autoFloor.setChecked(false);
        floorUpButton = findViewById(R.id.floorUpButton2);
        floorDownButton = findViewById(R.id.floorDownButton2);

        floorUpButton.setOnClickListener(view -> {
            autoFloor.setChecked(false);
            indoorMapManager.increaseFloor();
        });
        floorDownButton.setOnClickListener(view -> {
            autoFloor.setChecked(false);
            indoorMapManager.decreaseFloor();
        });

        mapTypeSpinner = findViewById(R.id.mapTypeSpinner);
        setupMapTypeSpinner();

        // 切换 switch1 时更新轨迹显示：显示完整轨迹或只显示当前播放时间之前的轨迹
        switch1.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (polyline == null) return;
            if (isChecked) {
                List<LatLng> allPoints = new ArrayList<>();
                for (TimedLatLng tl : trackPoints) {
                    allPoints.add(tl.point);
                }
                polyline.setPoints(allPoints);
            } else {
                List<LatLng> partial = new ArrayList<>();
                for (TimedLatLng tl : trackPoints) {
                    if (tl.relativeTimestamp <= currentTime) {
                        partial.add(tl.point);
                    } else {
                        break;
                    }
                }
                if (partial.isEmpty() && !trackPoints.isEmpty()) {
                    partial.add(trackPoints.get(0).point);
                }
                polyline.setPoints(partial);
            }
        });

        // 初始化地图 fragment
        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        // 解析轨迹文件（Protobuf 格式）
        Traj.Trajectory trajectory = readTrajectoryFromFile(this, filePath);
        if (trajectory != null) {
            trajectoryData = trajectory;
            // 转换 PDR 数据为归一化后的 TimedLatLng 列表
            trackPoints = convertTrajectoryToTimedLatLng(trajectory);
            if (!trackPoints.isEmpty()) {
                // 将轨迹总时长设置为最后一个 PDR 样本的归一化时间戳
                totalDuration = trackPoints.get(trackPoints.size() - 1).relativeTimestamp;
                // 进度条最大值为 totalDuration（假设总时长在 int 范围内）
                seekBar.setMax((int) totalDuration);
            }
        } else {
            Log.e(TAG, "轨迹文件解析失败！");
        }

        // 按钮点击事件
        playButton.setOnClickListener(v -> {
            if (isPlaying) {
                pausePlayback();
            } else {
                // 当播放时，不重置 currentTime 和 currentIndex，
                // 而是根据当前 seekBar 上的值继续播放
                playbackStartTime = System.currentTimeMillis() - (long)(currentTime / playbackSpeedFactor);
                startPlayback();
            }
        });
        fastRewind.setOnClickListener(v -> fastRewind());
        fastForward.setOnClickListener(v -> fastForward());
        gotoStartButton.setOnClickListener(v -> gotoStart());
        gotoEndButon.setOnClickListener(v -> gotoEnd());

        // 进度条拖动监听：在拖动时暂停自动更新，在拖动结束后更新 playbackStartTime 以从新位置开始播放
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentTime = progress; // progress 为 int，直接赋值给 long
                    // 更新当前播放样本索引：找第一个样本的归一化时间戳 >= currentTime
                    for (int i = 0; i < trackPoints.size(); i++) {
                        if (trackPoints.get(i).relativeTimestamp >= currentTime) {
                            currentIndex = i;
                            break;
                        }
                    }
                    updateMapPosition();
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 如果正在播放，暂停自动播放
                if (isPlaying) {
                    handler.removeCallbacks(playbackRunnable);
                }
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 如果播放状态仍然有效，更新 playbackStartTime 以从当前进度继续播放
                if (isPlaying) {
                    playbackStartTime = System.currentTimeMillis() - (long)(currentTime / playbackSpeedFactor);
                    handler.post(playbackRunnable);
                }
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        indoorMapManager = new IndoorMapManager(mMap);
        indoorMapManager.setIndicationOfIndoorMap();
        drawTrack();
        setFloorButtonVisibility(View.GONE);
    }

    // 解析 Protobuf 轨迹文件
    public static Traj.Trajectory readTrajectoryFromFile(Context context, String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            Log.e(TAG, "文件不存在");
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return Traj.Trajectory.parseFrom(data);
        } catch (InvalidProtocolBufferException e) {
            Log.e(TAG, "Protobuf 解析失败", e);
        } catch (IOException e) {
            Log.e(TAG, "文件读取失败", e);
        }
        return null;
    }

    /**
     * 将轨迹数据转换为 TimedLatLng 列表（基于 PDR 数据），对时间戳进行归一化处理。
     * 归一化方法：以第一个有效 GNSS 样本的 relativeTimestamp 作为基准 baseline，
     * 这样第一个 GNSS 数据和对应 PDR 数据的归一化时间均为 0，从而避免开局时路径跳跃。
     */
    private List<TimedLatLng> convertTrajectoryToTimedLatLng(Traj.Trajectory trajectory) {
        List<TimedLatLng> points = new ArrayList<>();
        double R = 6378137; // 地球半径（米）
        double lat0 = 0;
        double lon0 = 0;
        long baseline = 0;  // 归一化基准

        // 找到第一个有效的 GNSS 样本（经纬度不为 0），并取其 relativeTimestamp 作为基准
        for (Traj.GNSS_Sample sample : trajectory.getGnssDataList()) {
            if (sample.getLatitude() != 0 && sample.getLongitude() != 0) {
                lat0 = sample.getLatitude();
                lon0 = sample.getLongitude();
                baseline = sample.getRelativeTimestamp();
                break;
            }
        }
        if (lat0 == 0 && lon0 == 0) {
            Log.e(TAG, "未找到有效的 GNSS 数据，使用默认起点 (0,0)！");
        }
        // 遍历所有 PDR 样本，归一化时间戳，并计算坐标
        for (Traj.Pdr_Sample pdrSample : trajectory.getPdrDataList()) {
            double trackX = pdrSample.getX();  // 前进位移（米）
            double trackY = pdrSample.getY();  // 侧向位移（米）
            double dLat = trackY / R;
            double dLon = trackX / (R * Math.cos(Math.toRadians(lat0)));
            double lat = lat0 + Math.toDegrees(dLat);
            double lon = lon0 + Math.toDegrees(dLon);
            long timestamp = pdrSample.getRelativeTimestamp() - baseline;  // 归一化后，第一个样本为 0
            points.add(new TimedLatLng(new LatLng(lat, lon), timestamp));
        }
        return points;
    }

    /**
     * 根据当前播放时间，从 trajectoryData 的 GNSS 样本中选取最后一个 relativeTimestamp <= currentTime 的样本
     */
    private LatLng getCurrentGnssPosition(long currentTime, Traj.Trajectory trajectoryData) {
        Traj.GNSS_Sample bestSample = null;
        for (Traj.GNSS_Sample sample : trajectoryData.getGnssDataList()) {
            Log.d(TAG, "检查 GNSS 样本：relativeTimestamp = " + sample.getRelativeTimestamp() + ", currentTime = " + currentTime);
            if (sample.getRelativeTimestamp() <= currentTime) {
                bestSample = sample;
            } else {
                break;
            }
        }
        if (bestSample == null && trajectoryData.getGnssDataCount() > 0) {
            bestSample = trajectoryData.getGnssData(0);
        }
        if (bestSample != null) {
            Log.d(TAG, "选定 GNSS 样本：lat = " + bestSample.getLatitude() + ", lon = " + bestSample.getLongitude());
            return new LatLng(bestSample.getLatitude(), bestSample.getLongitude());
        }
        return null;
    }

    // 绘制轨迹及添加 Marker（包括用于 GNSS 地址显示的 Marker）
    private void drawTrack() {
        if (mMap != null && !trackPoints.isEmpty()) {
            PolylineOptions polylineOptions = new PolylineOptions()
                    .width(10)
                    .color(0xFFFF00FF) // 轨迹颜色
                    .geodesic(true)
                    .zIndex(1000);
            if (switch1 != null && switch1.isChecked()) {
                List<LatLng> allPoints = new ArrayList<>();
                for (TimedLatLng tl : trackPoints) {
                    allPoints.add(tl.point);
                }
                polylineOptions.addAll(allPoints);
            } else {
                polylineOptions.add(trackPoints.get(0).point);
            }
            polyline = mMap.addPolyline(polylineOptions);

            // 添加起点 Marker（用于显示 PDR 轨迹）
            currentMarker = mMap.addMarker(new MarkerOptions()
                    .position(trackPoints.get(0).point)
                    .title(String.format("Altitude: %s", trajectoryData.getGnssData(0).getAltitude()))

                    .flat(true)
                    .icon(bitmapDescriptorFromVector(this, R.drawable.ic_baseline_navigation_24))
                    .zIndex(1100));

            // 添加 GNSS Marker（使用 baseline_location_gnss 作为图标），初始位置取第一个 GNSS 样本
            if (trajectoryData != null && trajectoryData.getGnssDataCount() > 0) {
                Traj.GNSS_Sample firstSample = trajectoryData.getGnssData(0);
                LatLng gnssLatLng = new LatLng(firstSample.getLatitude(), firstSample.getLongitude());
                gnssMarker = mMap.addMarker(new MarkerOptions()
                        .position(gnssLatLng)
                        .title("GNSS")
                        .icon(bitmapDescriptorFromVector(this, R.drawable.baseline_location_gnss))
                        .zIndex(1200));
            }
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(trackPoints.get(0).point, 20));
        }
    }

    /**
     * 将 vector drawable 转换成 BitmapDescriptor
     */
    private BitmapDescriptor bitmapDescriptorFromVector(Context context, @DrawableRes int vectorResId) {
        Drawable vectorDrawable = ContextCompat.getDrawable(context, vectorResId);
        if (vectorDrawable == null) {
            Log.e(TAG, "Resource not found: " + vectorResId);
            return null;
        }
        vectorDrawable.setBounds(0, 0, vectorDrawable.getIntrinsicWidth(), vectorDrawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(vectorDrawable.getIntrinsicWidth(), vectorDrawable.getIntrinsicHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        vectorDrawable.draw(canvas);
        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    // 开始回放：使用固定定时任务每秒更新播放进度和当前播放时间
    private void startPlayback() {
        if (trackPoints.isEmpty()) return;
        // 初始化播放：不要重置 currentTime（以用户拖动的时间为准），只更新 playbackStartTime
        playbackStartTime = System.currentTimeMillis() - (long)(currentTime / playbackSpeedFactor);
        isPlaying = true;
        playButton.setImageResource(R.drawable.baseline_pause_24);
        handler.post(playbackRunnable);
    }

    // 每秒更新一次播放进度的 Runnable —— 基于系统时间更新播放进度
    private final Runnable playbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying) return;
            long elapsed = System.currentTimeMillis() - playbackStartTime;
            currentTime = (long)(elapsed * playbackSpeedFactor);
            if (currentTime > totalDuration) {
                currentTime = totalDuration;
            }
            // 根据 currentTime 查找当前播放样本索引
            for (int i = 0; i < trackPoints.size(); i++) {
                if (trackPoints.get(i).relativeTimestamp >= currentTime) {
                    currentIndex = i;
                    break;
                }
            }
            updateMapPosition();
            seekBar.setProgress((int) currentTime);
            if (currentTime < totalDuration) {
                handler.postDelayed(this, 1000);
            } else {
                isPlaying = false;
                playButton.setImageResource(R.drawable.baseline_play_arrow_24);
            }
        }
    };

    private void pausePlayback() {
        isPlaying = false;
        handler.removeCallbacksAndMessages(null);
        playButton.setImageResource(R.drawable.baseline_play_arrow_24);
    }

    // 快进 5 秒：在录制时间尺度上跳转
    private void fastForward() {
        int jumpTime = 5000; // ms
        currentTime = Math.min(currentTime + jumpTime, totalDuration);
        for (int i = 0; i < trackPoints.size(); i++) {
            if (trackPoints.get(i).relativeTimestamp >= currentTime) {
                currentIndex = i;
                break;
            }
        }
        updateMapPosition();
        seekBar.setProgress((int) currentTime);
    }

    // 快退 5 秒
    private void fastRewind() {
        int jumpTime = 5000; // ms
        currentTime = Math.max(currentTime - jumpTime, 0);
        for (int i = 0; i < trackPoints.size(); i++) {
            if (trackPoints.get(i).relativeTimestamp >= currentTime) {
                currentIndex = i;
                break;
            }
        }
        updateMapPosition();
        seekBar.setProgress((int) currentTime);
    }

    // 跳转到起始位置
    private void gotoStart() {
        currentTime = 0;
        currentIndex = 0;
        updateMapPosition();
        seekBar.setProgress((int) currentTime);
    }

    // 跳转到结束位置
    private void gotoEnd() {
        currentTime = totalDuration;
        currentIndex = trackPoints.size() - 1;
        updateMapPosition();
        seekBar.setProgress((int) currentTime);
    }

    /**
     * 更新地图位置：
     * 1. 根据当前播放时间查找对应的 PDR 样本，更新相机和 Marker 的位置与方向（由当前样本与前一个样本计算）
     * 2. 根据 switch1 状态更新 Polyline（显示当前时间之前的轨迹或完整轨迹）
     * 3. 更新时间显示
     * 4. 根据当前播放时间更新 GNSS Marker
     */
    private void updateMapPosition() {
        if (mMap != null && !trackPoints.isEmpty()) {
            TimedLatLng currentTimedPoint = trackPoints.get(0);
            int idx = 0;
            for (int i = 0; i < trackPoints.size(); i++) {
                if (trackPoints.get(i).relativeTimestamp <= currentTime) {
                    currentTimedPoint = trackPoints.get(i);
                    idx = i;
                } else {
                    break;
                }
            }
            LatLng point = currentTimedPoint.point;
            mMap.moveCamera(CameraUpdateFactory.newLatLng(point));
            if (currentMarker != null) {
                currentMarker.setPosition(point);
                currentMarker.setTitle(String.format("Elevation: %s", elevationVal));
                // 若不是第一个样本，计算方向
                if (idx > 0) {
                    LatLng prevPoint = trackPoints.get(idx - 1).point;
                    float bearing = computeBearing(prevPoint, point);
                    currentMarker.setRotation(bearing);
                }
            }
            if (indoorMapManager != null) {
                indoorMapManager.setCurrentLocation(point);
            }
            if (polyline != null) {
                if (switch1 != null && switch1.isChecked()) {
                    List<LatLng> allPoints = new ArrayList<>();
                    for (TimedLatLng tl : trackPoints) {
                        allPoints.add(tl.point);
                    }
                    polyline.setPoints(allPoints);
                } else {
                    List<LatLng> partial = new ArrayList<>();
                    for (TimedLatLng tl : trackPoints) {
                        if (tl.relativeTimestamp <= currentTime) {
                            partial.add(tl.point);
                        } else {
                            break;
                        }
                    }
                    if (partial.isEmpty() && !trackPoints.isEmpty()) {
                        partial.add(trackPoints.get(0).point);
                    }
                    polyline.setPoints(partial);
                }
            }
            int seconds = (int) (currentTime / 1000);
            int minutes = seconds / 60;
            int totalSeconds = (int) (totalDuration / 1000);
            int totalMinutes = totalSeconds / 60;
            totalSeconds = totalSeconds % 60;
            if (progressText != null) {
                progressText.setText(String.format("%02d:%02d", minutes, seconds));
                totaltimetext.setText(String.format("%02d:%02d", totalMinutes, totalSeconds));
            }
            if (trajectoryData != null && trajectoryData.getGnssDataCount() > 0 && gnssMarker != null) {
                LatLng currentGnss = getCurrentGnssPosition(currentTime, trajectoryData);
                if (currentGnss != null) {
                    gnssMarker.setPosition(currentGnss);
                    gnssMarker.setTitle(String.format("GNSS: %.6f, %.6f", currentGnss.latitude, currentGnss.longitude));
                }
            }
            // 更新室内地图显示
            if (indoorMapManager.getIsIndoorMapSet()) {
                if (autoFloor.isChecked()){
                    if (trajectoryData != null && trajectoryData.getPressureDataCount() > 0) {
                        Traj.Pressure_Sample initialPressure = trajectoryData.getPressureData(0);
                        Traj.Pressure_Sample currentPressureData = getCurrentPressuretrajectoryData(currentTime, trajectoryData);
                        if (currentPressureData != null) {
                            elevationVal = (float) calculateAltitudeDifference(initialPressure.getPressure(), currentPressureData.getPressure(), 298.15);
                            currentFloor = (int)(elevationVal / indoorMapManager.getFloorHeight());
                            indoorMapManager.setCurrentFloor(currentFloor, true);
                        }
                    }
                } else {
                    setFloorButtonVisibility(View.VISIBLE);
                }
            } else {
                setFloorButtonVisibility(View.GONE);
            }
        }

    }

    private Traj.Pressure_Sample getCurrentPressuretrajectoryData(long currentTime, Traj.Trajectory trajectoryData) {
        Traj.Pressure_Sample bestSample = null;
        for (Traj.Pressure_Sample sample : trajectoryData.getPressureDataList()) {
            Log.d(TAG, "检查 pressure 样本：relativeTimestamp = " + sample.getRelativeTimestamp() + ", currentTime = " + currentTime);
            if (sample.getRelativeTimestamp() <= currentTime) {
                bestSample = sample;
            } else {
                break;
            }
        }
        if (bestSample == null && trajectoryData.getPressureDataCount() > 0) {
            bestSample = trajectoryData.getPressureData(0);
        }
        if (bestSample != null) {
            Log.d(TAG, "选定 pressure 样本：pressure = " + bestSample.getPressure());
            return bestSample;
        }
        return null;
    }


    public static double calculateAltitudeDifference(double P0, double P, double T) {
        // 将 mbar 转换为 Pa (1 mbar = 100 Pa)
        double P0Pa = P0 * 100;
        double PPa = P * 100;
        // 使用公式： Δh = (R * T) / (M * g) * ln(P0 / P)
        // 其中 R = 8.314 J/(mol·K), M = 0.029 kg/mol, g = 9.81 m/s²
        return (8.314 * T) / (0.029 * 9.81) * Math.log(P0Pa / PPa);
    }

    /**
     * 设置楼层按钮的可见性
     */
    private void setFloorButtonVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        autoFloor.setVisibility(visibility);
    }

    /**
     * 根据两个 LatLng 计算方位角（bearing），单位：度（0~360）
     */
    private float computeBearing(LatLng from, LatLng to) {
        double lat1 = Math.toRadians(from.latitude);
        double lon1 = Math.toRadians(from.longitude);
        double lat2 = Math.toRadians(to.latitude);
        double lon2 = Math.toRadians(to.longitude);
        double dLon = lon2 - lon1;
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (float)((bearing + 360) % 360);
    }

    // 设置 Spinner 监听以切换地图类型
    private void setupMapTypeSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.map_types, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mapTypeSpinner.setAdapter(adapter);
        mapTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mMap == null) return;
                switch (position) {
                    case 0:
                        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                    case 1:
                        mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                        break;
                    case 2:
                        mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                        break;
                    default:
                        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                if (mMap != null) {
                    mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                }
            }
        });
    }
}
