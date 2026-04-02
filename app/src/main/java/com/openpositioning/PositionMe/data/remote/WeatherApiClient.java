package com.openpositioning.PositionMe.data.remote;

import static com.openpositioning.PositionMe.BuildConstants.DEBUG;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches current weather from the Open-Meteo API (free, no key required).
 * Returns WMO weather code and temperature in Celsius via callback.
 */
public class WeatherApiClient {

    private static final String TAG = "WeatherApiClient";
    private static final String BASE_URL = "https://api.open-meteo.com/v1/forecast";

    private final OkHttpClient client = new OkHttpClient();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Callback for asynchronous weather fetch results. */
    public interface WeatherCallback {
        /** Called on the main thread with the weather code and temperature. */
        void onWeatherResult(int wmoCode, double temperatureC);
        /** Called on the main thread when the request fails. */
        void onError(String message);
    }

    /**
     * Fetch current weather for the given coordinates.
     * Callback is delivered on the main thread.
     */
    public void fetchWeather(double latitude, double longitude, WeatherCallback callback) {
        String url = String.format(Locale.US,
                "%s?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code",
                BASE_URL, latitude, longitude);

        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (DEBUG) Log.w(TAG, "Weather fetch failed", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        mainHandler.post(() -> callback.onError("HTTP " + response.code()));
                        return;
                    }
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONObject current = json.getJSONObject("current");
                    double temp = current.getDouble("temperature_2m");
                    int code = current.getInt("weather_code");

                    if (DEBUG) Log.d(TAG, "Weather: code=" + code + " temp=" + temp + "°C");
                    mainHandler.post(() -> callback.onWeatherResult(code, temp));
                } catch (Exception e) {
                    if (DEBUG) Log.w(TAG, "Weather parse failed", e);
                    mainHandler.post(() -> callback.onError(e.getMessage()));
                } finally {
                    response.close();
                }
            }
        });
    }
}
