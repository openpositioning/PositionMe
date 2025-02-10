package com.openpositioning.PositionMe.fragments;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.SeekBar;
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
        // 接著畫出軌跡，軌跡會以 z-index 1000 顯示在室內覆蓋層之上
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
        double R = 6378137; // 地球半径
        double lat0 = 0;
        double lon0 = 0;
        // 尝试找到第一个有效的 GNSS 样本（例如经纬度均不为 0）
        for (Traj.GNSS_Sample sample : trajectory.getGnssDataList()) {
            if (sample.getLatitude() != 0 && sample.getLongitude() != 0) {
                lat0 = sample.getLatitude();
                lon0 = sample.getLongitude();
                break;
            }
        }
        // 如果所有 GNSS 样本都无效，则你可能需要给出一个提示或使用一个默认起点
        if (lat0 == 0 && lon0 == 0) {
            Log.e(TAG, "未找到有效的 GNSS 数据，使用默认起点 (0,0)！");
        }
        for (Traj.Pdr_Sample pdrSample : trajectory.getPdrDataList()) {
            double trackX = pdrSample.getX();  // 前进位移（米）
            double trackY = pdrSample.getY();  // 侧向位移（米）
            double dLat = trackY / R;
            double dLon = trackX / (R * Math.cos(Math.toRadians(lat0)));
            double lat = lat0 + Math.toDegrees(dLat);
            double lon = lon0 + Math.toDegrees(dLon);
            points.add(new LatLng(lat, lon));
        }
        return points;
    }


    /**
     * 畫出軌跡：
     * 若 switch1 為選中狀態，則顯示完整軌跡；
     * 否則只先顯示起點，待播放時根據播放進度逐步更新。
     * 這裡 PolylineOptions 中設定 .zIndex(1000) 確保軌跡顯示在室內覆蓋層之上，
     * Marker 設定 .zIndex(1100) 並設置 flat(true) 則確保指針顯示在軌跡上方且能正確旋轉。
     */
    private void drawTrack() {
        if (mMap != null && !trackPoints.isEmpty()) {
            PolylineOptions polylineOptions = new PolylineOptions()
                    .width(10)
                    .color(0xFFFF00FF) // 軌跡顏色
                    .geodesic(true)
                    .zIndex(1000);    // 設定軌跡 z-index 為 1000
            if (switch1 != null && switch1.isChecked()) {
                // 若 switch1 打開，顯示完整軌跡
                polylineOptions.addAll(trackPoints);
            } else {
                // 否則只顯示起點
                polylineOptions.add(trackPoints.get(0));
            }
            polyline = mMap.addPolyline(polylineOptions);

            // 添加起點指針 Marker，設置 flat(true) 以便旋轉
            currentMarker = mMap.addMarker(new MarkerOptions()
                    .position(trackPoints.get(0))
                    .title("起點")
                    .flat(true)  // 使 Marker 平貼地圖，便於旋轉
                    // 使用 ic_baseline_navigation_24 作為指針圖標（轉換 vector 為 bitmap）
                    .icon(bitmapDescriptorFromVector(this, R.drawable.ic_baseline_navigation_24))
                    .zIndex(1100));
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(trackPoints.get(0), 20));
        }
    }

    /**
     * 將 vector drawable 轉換成 BitmapDescriptor
     * @param context Context
     * @param vectorResId vector drawable 資源 id
     * @return BitmapDescriptor 對象
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
     * 1. 移動相機並更新 Marker 位置與方向
     * 2. 若 switch1 為關閉狀態，則依據播放進度更新 Polyline（只顯示已播放軌跡部分）
     * 3. 更新室內地圖顯示（請確保 IndoorMapManager 內覆蓋層的 z-index 低於 1000）
     * 4. 更新時間顯示
     */
    private void updateMapPosition() {
        if (mMap != null && currentIndex < trackPoints.size()) {
            LatLng point = trackPoints.get(currentIndex);
            mMap.moveCamera(CameraUpdateFactory.newLatLng(point));
            if (currentMarker != null) {
                currentMarker.setPosition(point);
                // 計算朝向：若有上一個點則以前一點到當前點的方位作為指針旋轉角度
                if (currentIndex > 0) {
                    LatLng prevPoint = trackPoints.get(currentIndex - 1);
                    float bearing = computeBearing(prevPoint, point);
                    currentMarker.setRotation(bearing);
                }
            }
            // 更新室內地圖顯示
            if (indoorMapManager != null) {
                indoorMapManager.setCurrentLocation(point);
            }
            // 根據 switch1 狀態更新 Polyline
            if (polyline != null) {
                if (switch1 != null && switch1.isChecked()) {
                    // 顯示完整軌跡
                    polyline.setPoints(trackPoints);
                } else {
                    // 只顯示從起點到當前播放點的軌跡
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

    /**
     * 根據兩個 LatLng 計算方位角（bearing）
     * @param from 起點
     * @param to 終點
     * @return 方位角，單位度（0~360）
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
        // 將結果正規化到 0-360 度之間
        return (float)((bearing + 360) % 360);
    }
}
