package com.openpositioning.PositionMe.presentation.activity;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.Objects;

public class MainActivity extends AppCompatActivity implements Observer {

    private NavController navController;
    private ActivityResultLauncher<String[]> multiplePermissionsLauncher;

    private SensorFusion sensorFusion;
    private Handler httpResponseHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        setContentView(R.layout.activity_main);

        // 初始化 NavController
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        navController = Objects.requireNonNull(navHostFragment).getNavController();

        // 手动管理底部导航栏点击，控制导航行为
        BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            int currentId = navController.getCurrentDestination() != null
                    ? navController.getCurrentDestination().getId() : -1;

            if (itemId == currentId) return true;

            if (itemId == R.id.recordingFragment) {
                // 每次都销毁再进入
                NavOptions options = new NavOptions.Builder()
                        .setPopUpTo(R.id.recordingFragment, true)
                        .build();
                navController.navigate(R.id.recordingFragment, null, options);
            } else {
                navController.navigate(itemId); // 正常切换
            }
            return true;
        });

        // 权限回调 launcher
        multiplePermissionsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean locationGranted = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                    boolean activityGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                            || result.getOrDefault(Manifest.permission.ACTIVITY_RECOGNITION, false);

                    if (locationGranted && activityGranted) {
                        allPermissionsObtained();
                    } else {
                        Toast.makeText(this,
                                "Location or Physical Activity permission denied. Some features may not work.",
                                Toast.LENGTH_LONG).show();
                    }
                });

        // 初始化 SensorFusion
        sensorFusion = SensorFusion.getInstance();
        sensorFusion.setContext(getApplicationContext());

        // 注册主线程 Toast handler
        httpResponseHandler = new Handler();

        // 清除永久拒绝 flag
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean("permanentDeny", false).apply();
    }

    @Override
    protected void onResume() {
        super.onResume();

        new Handler().postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                boolean locationGranted = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

                boolean activityGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                        || ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;

                if (!locationGranted || !activityGranted) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        multiplePermissionsLauncher.launch(new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACTIVITY_RECOGNITION
                        });
                    } else {
                        multiplePermissionsLauncher.launch(new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION
                        });
                    }
                } else {
                    allPermissionsObtained();
                }
            }
        }, 300);

        if (sensorFusion != null) {
            sensorFusion.resumeListening();
        }
    }

    private void allPermissionsObtained() {
        sensorFusion.registerForServerUpdate(this);
    }

    @Override
    protected void onDestroy() {
        // 可选：sensorFusion.stopListening();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (navController.getCurrentDestination() != null &&
                navController.getCurrentDestination().getId() == R.id.recordingFragment) {
            new AlertDialog.Builder(this)
                    .setTitle("Confirm Exit")
                    .setMessage("Are you sure you want to exit the app?")
                    .setPositiveButton("Yes", (dialog, which) -> {
                        dialog.dismiss();
                        finish();
                    })
                    .setNegativeButton("No", (dialog, which) -> dialog.dismiss())
                    .create()
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    // Observer 接口回调，上传成功 / 失败时显示 Toast
    @Override
    public void update(Object[] objList) {
        if ((Boolean) objList[0]) {
            httpResponseHandler.post(() ->
                    Toast.makeText(this, "Trajectory uploaded", Toast.LENGTH_SHORT).show());
        } else {
            httpResponseHandler.post(() ->
                    Toast.makeText(this, "Failed to complete trajectory upload", Toast.LENGTH_SHORT).show());
        }
    }
}
