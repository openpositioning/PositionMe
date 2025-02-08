package com.openpositioning.PositionMe.fragments;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
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
    private TextView progressText;
    private Switch switch1;  // 新增 Switch 用來切換顯示全部軌跡
    private String filePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_replay);

        // 取得 Intent 傳遞的檔案路徑
        filePath = getIntent().getStringExtra("filePath");

        // 初始化 UI 组件
        seekBar = findViewById(R.id.seekBar);
        playButton = findViewById(R.id.playPauseButton);
        fastRewind = findViewById(R.id.fastRewindButton);
        fastForward = findViewById(R.id.fastForwardButton);
        gotoStartButton = findViewById(R.id.goToStartButton);
        gotoEndButon = findViewById(R.id.goToEndButton);
        progressText = findViewById(R.id.currentTime);
        switch1 = findViewById(R.id.switch1); // 請確認 layout 中有此 ID 的 Switch

        // 設定 switch1 的監聽器，切換軌跡顯示模式
        switch1.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mMap == null || polyline == null || trackPoints.isEmpty()) return;
            if (isChecked) {
                // 開啟：直接顯示完整軌跡
                polyline.setPoints(trackPoints);
            } else {
                // 關閉：若正在播放則顯示已播放段落；若未播放則僅顯示起點
                if (isPlaying && currentIndex > 0) {
                    polyline.setPoints(new ArrayList<>(trackPoints.subList(0, currentIndex)));
                } else {
                    ArrayList<LatLng> startOnly = new ArrayList<>();
                    startOnly.add(trackPoints.get(0));
                    polyline.setPoints(startOnly);
                }
            }
        });

        // 初始化 Google Map
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        // 讀取軌跡資料（protobuf 格式）
        Traj.Trajectory trajectory = readTrajectoryFromFile(this, filePath);
        if (trajectory != null) {
            trackPoints = convertTrajectoryToLatLng(trajectory);
            seekBar.setMax(trackPoints.size());
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
                    currentIndex = progress;
                    updateMapPosition();
                    // 如果 switch1 為關閉狀態，也更新 polyline 只顯示播放到的部分
                    if (!switch1.isChecked() && polyline != null) {
                        ArrayList<LatLng> playedPoints = new ArrayList<>();
                        playedPoints.add(trackPoints.get(0));
                        if (currentIndex > 0) {
                            playedPoints = new ArrayList<>(trackPoints.subList(0, currentIndex));
                        }
                        polyline.setPoints(playedPoints);
                    }
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
        drawTrack();
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
            Log.e(TAG, "Protobuf 解析失敗", e);
        } catch (IOException e) {
            Log.e(TAG, "文件讀取失敗", e);
        }
        return null;
    }

    // 將軌跡資料轉換為 LatLng 點集合
    private List<LatLng> convertTrajectoryToLatLng(Traj.Trajectory trajectory) {
        List<LatLng> points = new ArrayList<>();
        double R = 6378137; // 地球半徑（公尺）
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
            double dLat = trackY / R;
            double dLon = trackX / (R * Math.cos(Math.toRadians(lat0)));
            double lat = lat0 + Math.toDegrees(dLat);
            double lon = lon0 + Math.toDegrees(dLon);
            points.add(new LatLng(lat, lon));
        }
        return points;
    }

    // 畫出軌跡：依據 switch1 的狀態決定初始時繪製全部或僅起點
    private void drawTrack() {
        if (mMap != null && !trackPoints.isEmpty()) {
            PolylineOptions polylineOptions = new PolylineOptions()
                    .width(10)
                    .color(0xFFFF00FF) // 軌跡顏色
                    .geodesic(true);
            if (switch1 != null && switch1.isChecked()) {
                // 開關打開時，直接顯示完整軌跡
                polylineOptions.addAll(trackPoints);
            } else {
                // 否則僅顯示起點
                polylineOptions.add(trackPoints.get(0));
            }
            polyline = mMap.addPolyline(polylineOptions);
            // 添加起點標記
            currentMarker = mMap.addMarker(new MarkerOptions().position(trackPoints.get(0)).title("起點"));
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(trackPoints.get(0), 20));
        }
    }

    private void startPlayback() {
        if (trackPoints.isEmpty()) return;
        isPlaying = true;
        playButton.setImageResource(R.drawable.baseline_pause_24);

        // 若 switch1 為關閉狀態，重置 polyline 只顯示起點；如果開啟則已顯示完整軌跡，不用變更
        if (!switch1.isChecked() && polyline != null) {
            List<LatLng> currentPath = new ArrayList<>();
            currentPath.add(trackPoints.get(0));
            polyline.setPoints(currentPath);
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currentIndex < trackPoints.size() && isPlaying) {
                    updateMapPosition();
                    // 若開關為關閉狀態，則逐步更新 polyline 顯示已播放的段落
                    if (!switch1.isChecked() && polyline != null) {
                        List<LatLng> points = new ArrayList<>(polyline.getPoints());
                        points.add(trackPoints.get(currentIndex));
                        polyline.setPoints(points);
                    }
                    seekBar.setProgress(currentIndex);
                    currentIndex++;
                    handler.postDelayed(this, 300);  // 每 300 毫秒更新一次
                } else {
                    pausePlayback();
                }
            }
        }, 300);
    }

    private void pausePlayback() {
        isPlaying = false;
        handler.removeCallbacksAndMessages(null);
        playButton.setImageResource(R.drawable.baseline_play_arrow_24);
    }

    private void fastForward() {
        if (currentIndex + 5 < trackPoints.size()) {
            currentIndex += 5;
            updateMapPosition();
            seekBar.setProgress(currentIndex);
            // 若 switch1 為關閉狀態，更新已播放的部分
            if (!switch1.isChecked() && polyline != null) {
                polyline.setPoints(new ArrayList<>(trackPoints.subList(0, currentIndex)));
            }
        } else {
            gotoEnd();
        }
    }

    private void fastRewind() {
        if (currentIndex - 5 >= 0) {
            currentIndex -= 5;
            updateMapPosition();
            seekBar.setProgress(currentIndex);
            if (!switch1.isChecked() && polyline != null) {
                polyline.setPoints(new ArrayList<>(trackPoints.subList(0, currentIndex)));
            }
        } else {
            gotoStart();
        }
    }

    private void gotoStart() {
        currentIndex = 0;
        updateMapPosition();
        seekBar.setProgress(currentIndex);
        if (!switch1.isChecked() && polyline != null) {
            ArrayList<LatLng> startOnly = new ArrayList<>();
            startOnly.add(trackPoints.get(0));
            polyline.setPoints(startOnly);
        }
    }

    private void gotoEnd() {
        currentIndex = trackPoints.size() - 1;
        updateMapPosition();
        seekBar.setProgress(currentIndex);
        if (!switch1.isChecked() && polyline != null) {
            // 顯示已播放部分：整個軌跡播放完即只顯示全部已播放段落
            polyline.setPoints(new ArrayList<>(trackPoints.subList(0, currentIndex + 1)));
        }
    }

    private void updateMapPosition() {
        if (mMap != null && currentIndex < trackPoints.size()) {
            LatLng point = trackPoints.get(currentIndex);
            mMap.moveCamera(CameraUpdateFactory.newLatLng(point));
            if (currentMarker != null) {
                currentMarker.setPosition(point);
            }
            if (progressText != null) {
                progressText.setText("Progress：" + (currentIndex + 1) + "/" + trackPoints.size());
            } else {
                Log.e(TAG, "progressText is null! 請確認在 onCreate() 中正確初始化。");
            }
        }
    }
}
