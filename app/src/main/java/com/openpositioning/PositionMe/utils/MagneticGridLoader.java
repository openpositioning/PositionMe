package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Loads magnetic grid compensation data from assets JSON.
public final class MagneticGridLoader {
    private static final String TAG = "MagneticGridLoader";

    private MagneticGridLoader() {
    }

    public static MagneticCompensation loadFromAssets(Context context,
                                                      String assetFileName,
                                                      double maxLookupDistanceMeters,
                                                      float maxCorrectionRad) {
        if (context == null) {
            return MagneticCompensation.empty();
        }

        try {
            String json = readAssetText(context, assetFileName);
            JSONArray cellsArray = parseGridArray(json);
            List<MagneticGridCell> cells = new ArrayList<>();
            for (int i = 0; i < cellsArray.length(); i++) {
                JSONObject item = cellsArray.optJSONObject(i);
                if (item == null) {
                    continue;
                }

                double x = item.optDouble("x", Double.NaN);
                double y = item.optDouble("y", Double.NaN);
                float correctionRad = (float) item.optDouble("correctionRad", 0.0);
                float confidence = (float) item.optDouble("confidence", 1.0);
                int sampleCount = item.optInt("sampleCount", 0);
                long updatedAt = item.optLong("updatedAt", 0L);

                if (Double.isNaN(x) || Double.isNaN(y) || Float.isNaN(correctionRad) || Float.isInfinite(correctionRad)) {
                    continue;
                }

                cells.add(new MagneticGridCell(x, y, correctionRad, confidence, sampleCount, updatedAt));
            }

            Log.i(TAG, "Loaded magnetic grid cells: " + cells.size());
            return new MagneticCompensation(cells, maxLookupDistanceMeters, maxCorrectionRad);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load magnetic grid from assets, compensation disabled", e);
            return MagneticCompensation.empty();
        }
    }

    private static String readAssetText(Context context, String assetFileName) throws IOException {
        try (InputStream inputStream = context.getAssets().open(assetFileName);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static JSONArray parseGridArray(String json) throws JSONException {
        String trimmed = json == null ? "" : json.trim();
        if (trimmed.startsWith("[")) {
            return new JSONArray(trimmed);
        }

        JSONObject root = new JSONObject(trimmed);
        JSONArray grid = root.optJSONArray("grid");
        if (grid != null) {
            return grid;
        }

        return new JSONArray();
    }
}

