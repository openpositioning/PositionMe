package com.openpositioning.PositionMe.viewitems;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.openpositioning.PositionMe.fragments.SensorsFragment;
import com.openpositioning.PositionMe.fragments.WifiFragment;

public class HomePagerAdapter extends FragmentStateAdapter {

    public HomePagerAdapter(Fragment fragment) {
        super(fragment);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 0) {
            return new SensorsFragment();
        } else {
            return new WifiFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 2; // 2个Fragment (Sensors, Wifi)
    }
}

