package com.openpositioning.PositionMe.presentation.activity;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.ReplayFragment;

public class ReplayActivity extends AppCompatActivity {

    public static final String EXTRA_TRAJECTORY_FILE_PATH = "extra_trajectory_file_path";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_replay);

        // 从 Intent 中获取传入的轨迹文件路径
        String filePath = getIntent().getStringExtra(EXTRA_TRAJECTORY_FILE_PATH);
        if (filePath == null || filePath.isEmpty()) {
            // 如果没有传入，则设置一个默认路径（或显示错误提示）
            filePath = "/storage/emulated/0/Download/trajectory_default.txt";
        }

        if (savedInstanceState == null) {
            showReplayFragment(filePath);
        }
    }

    /**
     * 显示 ReplayFragment，并将轨迹文件路径作为参数传递过去。
     */
    public void showReplayFragment(String filePath) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ReplayFragment replayFragment = new ReplayFragment();
        // 通过 Bundle 传递文件路径
        Bundle args = new Bundle();
        args.putString(EXTRA_TRAJECTORY_FILE_PATH, filePath);
        replayFragment.setArguments(args);
        ft.replace(R.id.replayActivityContainer, replayFragment);
        ft.commit();
    }

    /**
     * 完成回放流程时调用
     */
    public void finishFlow() {
        finish();
    }
}
