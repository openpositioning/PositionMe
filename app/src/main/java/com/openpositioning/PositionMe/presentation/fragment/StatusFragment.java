package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.openpositioning.PositionMe.R;

public class StatusFragment extends Fragment {
    private ProgressBar circularProgressBar;
    private ImageView recordingIcon;
    private TextView elevationTextView;
    private TextView gnssErrorTextView;

    private int progress = 0;
    private int maxProgress = 100;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate new layout for the status fragment
        return inflater.inflate(R.layout.fragment_status, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        circularProgressBar = view.findViewById(R.id.circularProgressBar);
        recordingIcon = view.findViewById(R.id.recordingIcon);
        elevationTextView = view.findViewById(R.id.elevationTextView);
        gnssErrorTextView = view.findViewById(R.id.gnssErrorTextView);
        startBlinkingAnimation();
    }

    private void startBlinkingAnimation() {
        Animation blinking = new AlphaAnimation(1, 0);
        blinking.setDuration(800);
        blinking.setInterpolator(new LinearInterpolator());
        blinking.setRepeatCount(Animation.INFINITE);
        blinking.setRepeatMode(Animation.REVERSE);
        recordingIcon.startAnimation(blinking);
    }

    public void updateElevation(String elevation) {
        if (elevationTextView != null) {
            elevationTextView.setText(getString(R.string.elevation, elevation));
        }
    }

    public void updateGnssError(String error, int visibility) {
        if (gnssErrorTextView != null) {
            gnssErrorTextView.setVisibility(visibility);
            gnssErrorTextView.setText(error);
        }
    }

    public void setMaxProgress(int max) {
        this.maxProgress = max;
        if (circularProgressBar != null) {
            circularProgressBar.setMax(max);
        }
    }

    public void setProgress(int progress) {
        this.progress = progress;
        if (circularProgressBar != null) {
            circularProgressBar.setProgress(progress);
        }
    }

    public void incrementProgress() {
        setProgress(++this.progress);
    }

    public void setScaleY(float scale) {
        if (circularProgressBar != null) {
            circularProgressBar.setScaleY(scale);
        }
    }
}
