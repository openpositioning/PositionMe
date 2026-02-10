package com.openpositioning.PositionMe.utils;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.openpositioning.PositionMe.R;

/**
 * Bottom sheet dialog that prompts user to view weather forecast
 * Shows when user is near building entrance/outside
 */
public class WeatherPromptDialog {
    private final Activity activity;
    private BottomSheetDialog promptDialog;
    private BottomSheetDialog weatherDialog;

    public WeatherPromptDialog(Activity activity) {
        this.activity = activity;
    }

    /**
     * Show the weather prompt at the bottom of the screen
     */
    public void showWeatherPrompt() {
        if (activity == null || activity.isFinishing()) return;

        // Create bottom sheet dialog
        promptDialog = new BottomSheetDialog(activity);
        View view = LayoutInflater.from(activity).inflate(
            R.layout.dialog_weather_prompt, null);

        TextView titleView = view.findViewById(R.id.weather_prompt_title);
        TextView messageView = view.findViewById(R.id.weather_prompt_message);
        MaterialButton yesButton = view.findViewById(R.id.weather_yes_button);
        MaterialButton noButton = view.findViewById(R.id.weather_no_button);

        titleView.setText("🌤️ Weather Forecast");
        messageView.setText("You're near the building entrance.\nWould you like to see the Edinburgh weather forecast?");

        yesButton.setOnClickListener(v -> {
            promptDialog.dismiss();
            showWeatherForecast();
        });

        noButton.setOnClickListener(v -> promptDialog.dismiss());

        promptDialog.setContentView(view);
        promptDialog.show();
    }

    /**
     * Fetch and display weather forecast
     */
    private void showWeatherForecast() {
        // Create weather display dialog
        weatherDialog = new BottomSheetDialog(activity);
        View view = LayoutInflater.from(activity).inflate(
            R.layout.dialog_weather_forecast, null);

        TextView weatherText = view.findViewById(R.id.weather_info_text);
        ProgressBar progressBar = view.findViewById(R.id.weather_progress);
        MaterialButton openBrowserButton = view.findViewById(R.id.open_browser_button);
        MaterialButton closeButton = view.findViewById(R.id.close_weather_button);

        // Show loading
        weatherText.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);

        weatherDialog.setContentView(view);
        weatherDialog.show();

        // Fetch weather data
        WeatherFetcher fetcher = new WeatherFetcher();
        fetcher.fetchWeather(new WeatherFetcher.WeatherCallback() {
            @Override
            public void onWeatherFetched(String weatherInfo) {
                if (activity.isFinishing()) return;

                activity.runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    weatherText.setVisibility(View.VISIBLE);
                    weatherText.setText(weatherInfo);
                });
            }

            @Override
            public void onError(String error) {
                if (activity.isFinishing()) return;

                activity.runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    weatherText.setVisibility(View.VISIBLE);
                    weatherText.setText("Failed to fetch weather.\n\nView forecast at:\nwww.bbc.co.uk/weather/2650225");
                    Toast.makeText(activity, "Error: " + error, Toast.LENGTH_SHORT).show();
                });
            }
        });

        // Open browser button
        openBrowserButton.setOnClickListener(v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.bbc.co.uk/weather/2650225"));
            activity.startActivity(browserIntent);
        });

        // Close button
        closeButton.setOnClickListener(v -> weatherDialog.dismiss());
    }

    /**
     * Dismiss all dialogs
     */
    public void dismiss() {
        if (promptDialog != null && promptDialog.isShowing()) {
            promptDialog.dismiss();
        }
        if (weatherDialog != null && weatherDialog.isShowing()) {
            weatherDialog.dismiss();
        }
    }
}
