package com.openpositioning.PositionMe.data.local;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.RequiresApi;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * DataFileManager encapsulates all file I/O and buffering logic.
 * It creates a new output file in the public Downloads folder, buffers JSON records,
 * automatically flushes them when a threshold is reached, and finalizes the file on close.
 */
public class DataFileManager {
    private static final String TAG = "DataFileManager";
    private static final int FLUSH_THRESHOLD = 50;

    private Context context;
    private Uri outputFileUri;
    private OutputStream outStream;
    private List<JSONObject> dataBuffer;

    public DataFileManager(Context context) {
        this.context = context;
        this.dataBuffer = new ArrayList<>();
        prepareLocalFetch();
    }

    private void prepareLocalFetch() {
        outputFileUri = getDownloadOutputFile(context);
        if (outputFileUri != null) {
            Log.d(TAG, "Output file created: " + outputFileUri.toString());
            try {
                outStream = context.getContentResolver().openOutputStream(outputFileUri, "w");
                Log.d(TAG, "Output stream opened successfully.");
            } catch (IOException e) {
                Log.e(TAG, "Error opening output stream", e);
            }
        } else {
            Log.e(TAG, "Failed to create output file URI.");
        }
    }

    /**
     * Adds a JSON record to the buffer. Flushes to file if the threshold is reached.
     */
    public void addRecord(JSONObject record) {
        dataBuffer.add(record);
        if (dataBuffer.size() >= FLUSH_THRESHOLD) {
            flushBuffer();
        }
    }

    /**
     * Flushes all buffered records to the output file and clears the buffer.
     */
    public void flushBuffer() {
        if (dataBuffer.isEmpty() || outStream == null) {
            Log.d(TAG, "Flush skipped; buffer empty or output stream is null.");
            return;
        }
        try {
            for (JSONObject obj : dataBuffer) {
                String jsonLine = obj.toString() + "\n";
                outStream.write(jsonLine.getBytes());
            }
            outStream.flush();
            Log.d(TAG, "Flushed " + dataBuffer.size() + " records to file.");
            dataBuffer.clear();
        } catch (IOException e) {
            Log.e(TAG, "Error flushing data", e);
        }
    }

    /**
     * Closes the output stream after flushing any remaining data.
     * For API >= Q, it also updates the file's pending flag so it becomes visible.
     */
    public void close() {
        flushBuffer();
        if (outStream != null) {
            try {
                outStream.close();
                Log.d(TAG, "Output stream closed.");
            } catch (IOException e) {
                Log.e(TAG, "Error closing output stream", e);
            }
        }
        if (outputFileUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(outputFileUri, values, null, null);
            Log.d(TAG, "Output file updated to not pending.");
        }
    }

    private Uri getDownloadOutputFile(Context context) {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmm").format(new Date());
        String fileName = "collection_data_" + Build.MODEL + "_" + timeStamp + ".json";
        Log.d(TAG, "Creating output file: " + fileName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return getDownloadOutputFileForQ(context, fileName);
        } else {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File file = new File(downloadsDir, fileName);
            Log.d(TAG, "Legacy output file path: " + file.getAbsolutePath());
            return Uri.fromFile(file);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private Uri getDownloadOutputFileForQ(Context context, String fileName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri fileUri = context.getContentResolver().insert(collection, values);
        if (fileUri != null) {
            Log.d(TAG, "MediaStore file created (pending): " + fileUri.toString());
        } else {
            Log.e(TAG, "Failed to create MediaStore file.");
        }
        return fileUri;
    }
}
