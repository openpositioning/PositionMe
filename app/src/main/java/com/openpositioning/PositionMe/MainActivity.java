package com.openpositioning.PositionMe;

import android.app.FragmentManager;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.openpositioning.PositionMe.fragments.AccountFragment;
import com.openpositioning.PositionMe.fragments.FilesFragment;
import com.openpositioning.PositionMe.fragments.HomeFragment;
import com.openpositioning.PositionMe.fragments.PositionFragment;

public class MainActivity extends AppCompatActivity {
    public OnBackPressedCallback onBackPressedCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);

        // 默认加载 HomeFragment
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, new HomeFragment())
                .commit();

        onBackPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 默认不执行任何返回操作
            }
        };
        getOnBackPressedDispatcher().addCallback(this, onBackPressedCallback);


        bottomNavigationView.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;

            if (item.getItemId() == R.id.nav_home) {
                selectedFragment = new HomeFragment();
            } else if (item.getItemId() == R.id.nav_position) {
                selectedFragment = new PositionFragment();
            } else if (item.getItemId() == R.id.nav_files) {
                selectedFragment = new FilesFragment();
            } else if (item.getItemId() == R.id.nav_account) {
                selectedFragment = new AccountFragment();
            }

            if (selectedFragment != null) {
                FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
                transaction.setCustomAnimations(
                        R.anim.slide_in_right,      // 新 Fragment 进入时的动画
                        R.anim.slide_out_right,    // 当前 Fragment 退出时的动画
                        R.anim.slide_in_right,  // 按返回键时，新 Fragment 的进入动画（可选）
                        R.anim.slide_out_right    // 按返回键时，当前 Fragment 的退出动画（可选）
                );
                transaction.replace(R.id.fragment_container, selectedFragment);
                transaction.commit();
            }

            return true;
        });
    }
}
