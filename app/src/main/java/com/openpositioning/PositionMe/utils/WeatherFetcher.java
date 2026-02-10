package com.openpositioning.PositionMe.utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches weather forecast data from BBC Weather for Edinburgh
 */
public class WeatherFetcher {
    private static final String TAG = "WeatherFetcher";
    private static final String BBC_WEATHER_URL = "https://www.bbc.co.uk/weather/2650225";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface WeatherCallback {
        void onWeatherFetched(String weatherInfo);
        void onError(String error);
    }

    /**
     * Fetch weather forecast from BBC Weather
     * @param callback Callback to receive weather data
     */
    public void fetchWeather(WeatherCallback callback) {
        executor.execute(() -> {
            try {
                // Fetch the BBC Weather page
                URL url = new URL(BBC_WEATHER_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    // Parse the HTML to extract weather info
                    String weatherInfo = parseWeatherFromHTML(response.toString());

                    mainHandler.post(() -> callback.onWeatherFetched(weatherInfo));
                } else {
                    mainHandler.post(() -> callback.onError("HTTP " + responseCode));
                }
                connection.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error fetching weather: " + e.getMessage());
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    /**
     * Parse weather information from BBC Weather HTML
     * This is a simple parser - BBC might change their format
     */
    private String parseWeatherFromHTML(String html) {
        try {
            // Look for temperature
            String temp = extractBetween(html, "wr-value--temperature--c\">", "°");

            // Look for weather description
            String description = extractBetween(html, "wr-day__weather-type-description\">", "</");

            // Look for wind speed
            String wind = extractBetween(html, "wr-value--windspeed wr-value--windspeed--mph\">", "<");

            if (temp != null && !temp.isEmpty()) {
                StringBuilder weather = new StringBuilder();
                weather.append("Edinburgh Weather\n\n");
                weather.append("🌡️ Temperature: ").append(temp).append("°C\n");

                if (description != null && !description.isEmpty()) {
                    weather.append("☁️ Conditions: ").append(description).append("\n");
                }

                if (wind != null && !wind.isEmpty()) {
                    weather.append("💨 Wind: ").append(wind).append(" mph");
                }

                return weather.toString();
            }

            return "Weather data available at:\n" + BBC_WEATHER_URL;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing weather HTML: " + e.getMessage());
            return "Edinburgh Weather\n\nView forecast at:\n" + BBC_WEATHER_URL;
        }
    }

    private String extractBetween(String text, String start, String end) {
        try {
            int startIdx = text.indexOf(start);
            if (startIdx == -1) return null;
            startIdx += start.length();

            int endIdx = text.indexOf(end, startIdx);
            if (endIdx == -1) return null;

            return text.substring(startIdx, endIdx).trim();
        } catch (Exception e) {
            return null;
        }
    }
}
