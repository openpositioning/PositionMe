package com.openpositioning.PositionMe.fragments;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
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
    private List<LatLng> trackPoints = new ArrayList<>();
    private Polyline polyline;
    private Marker currentMarker;
    private int currentIndex = 0;
    private boolean isPlaying = false;
    private Handler handler = new Handler();

    private SeekBar seekBar;
    private ImageButton playButton, fastRewind, fastForward, gotoStartButton, gotoEndButon;
    private TextView progressText, totaltimetext;
    private Switch switch1; // 用於控制軌跡顯示模式
    private String filePath;

    private int totalDuration = 0; // 軌跡總時長（ms）
    private int playbackSpeed = 300; // 每個點的播放間隔（ms）
    private int currentTime = 0;     // 當前回放時間（ms）

    // 用於室內地圖顯示的管理器
    private IndoorMapManager indoorMapManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_replay);

        // 取得 Intent 傳遞的檔案路徑
        filePath = getIntent().getStringExtra("filePath");

        // 初始化 UI 元件
        seekBar = findViewById(R.id.seekBar);
        playButton = findViewById(R.id.playPauseButton);
        fastRewind = findViewById(R.id.fastRewindButton);
        fastForward = findViewById(R.id.fastForwardButton);
        gotoStartButton = findViewById(R.id.goToStartButton);
        gotoEndButon = findViewById(R.id.goToEndButton);
        progressText = findViewById(R.id.currentTime);
        totaltimetext = findViewById(R.id.totalTime);
        switch1 = findViewById(R.id.switch1);  // 請確保 layout 中存在此 Switch 控件

        // 當使用者切換 switch1 時，立即更新路徑顯示模式
        switch1.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (polyline == null) return;
                if (isChecked) {
                    // 開啟狀態：顯示完整路徑
                    polyline.setPoints(trackPoints);
                } else {
                    // 關閉狀態：只顯示播放進度內的部分
                    int end = Math.min(currentIndex + 1, trackPoints.size());
                    polyline.setPoints(new ArrayList<>(trackPoints.subList(0, end)));
                }
            }
        });

        // 初始化地圖
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        // 讀取軌跡資料
        Traj.Trajectory trajectory = readTrajectoryFromFile(this, filePath);
        if (trajectory != null) {
            trackPoints = convertTrajectoryToLatLng(trajectory);
            totalDuration = trackPoints.size() * playbackSpeed;
            seekBar.setMax(totalDuration);
        } else {
            Log.e(TAG, "軌跡文件解析失敗！");
        }

        // 按鈕事件
        playButton.setOnClickListener(v -> {
            if (isPlaying) {
                pausePlayback();
            } else {
                startPlayback();
            }
        });
        fastRewind.setOnClickListener(v -> fastRewind());
        fastForward.setOnClickListener(v -> fastForward());
        gotoStartButton.setOnClickListener(v -> gotoStart());
        gotoEndButon.setOnClickListener(v -> gotoEnd());

        // 進度條拖動監聽
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentTime = progress;
                    currentIndex = currentTime / playbackSpeed;
                    if (currentIndex >= trackPoints.size()) {
                        currentIndex = trackPoints.size() - 1;
                    }
                    updateMapPosition();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
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
        // 設置地圖類型為混合模式
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        // 初始化室內地圖管理器，並在內部請確保覆蓋層的 z-index 設為低於路徑（例如 500）
        indoorMapManager = new IndoorMapManager(mMap);
        indoorMapManager.setIndicationOfIndoorMap();
        // 接著畫出路徑，路徑會以 z-index 1000 顯示在室內覆蓋層之上
        drawTrack();
    }

    // 解析 Protobuf 軌跡文件
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
            Log.e(TAG, "Protobuf 解析失敗", e);
        } catch (IOException e) {
            Log.e(TAG, "文件讀取失敗", e);
        }
        return null;
    }

    // 將軌跡資料轉換為 LatLng 點集合
    private List<LatLng> convertTrajectoryToLatLng(Traj.Trajectory trajectory) {
        List<LatLng> points = new ArrayList<>();
        // 地球半徑（公尺）
        double R = 6378137;
        // 初始經緯度
        double lat0 = 0;
        double lon0 = 0;
        if (!trajectory.getGnssDataList().isEmpty()) {
            Traj.GNSS_Sample firstGnss = trajectory.getGnssDataList().get(0);
            lat0 = firstGnss.getLatitude();
            lon0 = firstGnss.getLongitude();
        }
        for (Traj.Pdr_Sample pdrSample : trajectory.getPdrDataList()) {
            double trackX = pdrSample.getX();  // 前進位移（公尺）
            double trackY = pdrSample.getY();  // 側向位移（公尺）
            // 坐標轉換
            double dLat = trackY / R;  // Y 對緯度影響
            double dLon = trackX / (R * Math.cos(Math.toRadians(lat0)));  // X 對經度影響
            double lat = lat0 + Math.toDegrees(dLat);
            double lon = lon0 + Math.toDegrees(dLon);
            points.add(new LatLng(lat, lon));
        }
        return points;
    }

    /**
     * 畫出軌跡：
     * 若 switch1 為選中狀態，則顯示完整路徑；
     * 否則只先顯示起點，待播放時根據播放進度逐步更新。
     * 這裡 PolylineOptions 中設定 .zIndex(1000) 確保路徑顯示在室內覆蓋層之上，
     * Marker 設定 .zIndex(1100) 則確保起點標記在路徑上方。
     */
    private void drawTrack() {
        if (mMap != null && !trackPoints.isEmpty()) {
            PolylineOptions polylineOptions = new PolylineOptions()
                    .width(10)
                    .color(0xFFFF00FF) // 軌跡顏色
                    .geodesic(true)
                    .zIndex(1000);    // 設定路徑 z-index 為 1000
            if (switch1 != null && switch1.isChecked()) {
                // 若 switch1 打開，顯示完整路徑
                polylineOptions.addAll(trackPoints);
            } else {
                // 否則只顯示起點
                polylineOptions.add(trackPoints.get(0));
            }
            polyline = mMap.addPolyline(polylineOptions);

            // 添加起點標記，並設定 z-index 為 1100（確保標記顯示在路徑之上）
            currentMarker = mMap.addMarker(new MarkerOptions()
                    .position(trackPoints.get(0))
                    .title("起點")
                    .zIndex(1100));
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(trackPoints.get(0), 20));
        }
    }

    /**
     * 開始回放：根據時間累加控制進度更新
     */
    private void startPlayback() {
        if (trackPoints.isEmpty()) return;
        isPlaying = true;
        playButton.setImageResource(R.drawable.baseline_pause_24);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isPlaying && currentTime < totalDuration) {
                    currentIndex = currentTime / playbackSpeed;
                    if (currentIndex >= trackPoints.size()) {
                        currentIndex = trackPoints.size() - 1;
                    }
                    updateMapPosition();
                    seekBar.setProgress(currentTime);
                    currentTime += playbackSpeed;
                    handler.postDelayed(this, playbackSpeed);
                } else {
                    isPlaying = false;
                    playButton.setImageResource(R.drawable.baseline_play_arrow_24);
                }
            }
        }, playbackSpeed);
    }

    private void pausePlayback() {
        isPlaying = false;
        handler.removeCallbacksAndMessages(null);
        playButton.setImageResource(R.drawable.baseline_play_arrow_24);
    }

    // 快進5秒
    private void fastForward() {
        int jumpTime = 5000;
        currentTime = Math.min(currentTime + jumpTime, totalDuration);
        currentIndex = currentTime / playbackSpeed;
        if (currentIndex >= trackPoints.size()) {
            currentIndex = trackPoints.size() - 1;
        }
        updateMapPosition();
        seekBar.setProgress(currentTime);
    }

    // 快退5秒
    private void fastRewind() {
        int jumpTime = 5000;
        currentTime = Math.max(currentTime - jumpTime, 0);
        currentIndex = currentTime / playbackSpeed;
        updateMapPosition();
        seekBar.setProgress(currentTime);
    }

    // 跳轉到起始位置
    private void gotoStart() {
        currentTime = 0;
        currentIndex = 0;
        updateMapPosition();
        seekBar.setProgress(currentTime);
    }

    // 跳轉到結束位置
    private void gotoEnd() {
        currentTime = totalDuration;
        currentIndex = trackPoints.size() - 1;
        updateMapPosition();
        seekBar.setProgress(currentTime);
    }

    /**
     * 更新地圖位置：
     * 1. 移動相機並更新 Marker 位置
     * 2. 若 switch1 為關閉狀態，則依據播放進度更新 Polyline（只顯示已播放路徑部分）
     * 3. 更新室內地圖顯示（請確保 IndoorMapManager 內覆蓋層的 z-index 低於 1000）
     * 4. 更新時間顯示
     */
    private void updateMapPosition() {
        if (mMap != null && currentIndex < trackPoints.size()) {
            LatLng point = trackPoints.get(currentIndex);
            mMap.moveCamera(CameraUpdateFactory.newLatLng(point));
            if (currentMarker != null) {
                currentMarker.setPosition(point);
            }
            // 更新室內地圖顯示
            if (indoorMapManager != null) {
                indoorMapManager.setCurrentLocation(point);
            }
            // 根據 switch1 狀態更新 Polyline
            if (polyline != null) {
                if (switch1 != null && switch1.isChecked()) {
                    // 顯示完整路徑
                    polyline.setPoints(trackPoints);
                } else {
                    // 只顯示從起點到當前播放點的路徑
                    int end = Math.min(currentIndex + 1, trackPoints.size());
                    polyline.setPoints(new ArrayList<>(trackPoints.subList(0, end)));
                }
            }
            // 格式化時間顯示（mm:ss / mm:ss）
            int seconds = currentTime / 1000;
            int minutes = seconds / 60;
            seconds = seconds % 60;
            int totalSeconds = totalDuration / 1000;
            int totalMinutes = totalSeconds / 60;
            totalSeconds = totalSeconds % 60;
            if (progressText != null) {
                progressText.setText(String.format("%02d:%02d", minutes, seconds));
                totaltimetext.setText(String.format("%02d:%02d", totalMinutes, totalSeconds));
            }
        }
    }
}
