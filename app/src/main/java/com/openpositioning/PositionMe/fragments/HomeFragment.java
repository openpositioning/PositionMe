package com.openpositioning.PositionMe.fragments;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.openpositioning.PositionMe.viewitems.HomePagerAdapter;
import com.openpositioning.PositionMe.R;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class HomeFragment extends Fragment {

    private TabLayout tabLayout;
    private ViewPager2 viewPager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        tabLayout = view.findViewById(R.id.tab_layout);
        viewPager = view.findViewById(R.id.view_pager);

        // 设置 ViewPager 适配器
        // Set up the ViewPager adapter
        HomePagerAdapter adapter = new HomePagerAdapter(this);
        viewPager.setAdapter(adapter);

        // 关联 TabLayout 和 ViewPager2
        // Associate TabLayout with ViewPager2
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            if (position == 0) {
                tab.setText("Sensors");
            } else {
                tab.setText("WIFI");
            }
        }).attach();

        // 设置 Tab 切换监听，改变背景颜色
        // Set up Tab switching monitoring and change the background color
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                updateTabColors();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        // 初始颜色
        // Initial color
        updateTabColors();

        return view;
    }

    private void updateTabColors() {
        for (int i = 0; i < tabLayout.getTabCount(); i++) {
            TabLayout.Tab tab = tabLayout.getTabAt(i);
            if (tab != null) {
                View tabView = ((ViewGroup) tabLayout.getChildAt(0)).getChildAt(i);
                if (tabView instanceof TextView) {
                    ((TextView) tabView).setTextColor(i == tabLayout.getSelectedTabPosition() ?
                            Color.WHITE : Color.GRAY);
                    tabView.setBackgroundColor(i == tabLayout.getSelectedTabPosition() ?
                            getResources().getColor(R.color.tab_selected) : getResources().getColor(R.color.tab_unselected));
                }
            }
        }
    }
}

