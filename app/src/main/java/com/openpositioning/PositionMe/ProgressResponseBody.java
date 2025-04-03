package com.openpositioning.PositionMe;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

/**
 * A response body wrapper that reports download progress
 */
public class ProgressResponseBody extends ResponseBody {
    private final ResponseBody responseBody;
    private final ProgressListener progressListener;
    private BufferedSource bufferedSource;

    public ProgressResponseBody(ResponseBody responseBody, ProgressListener progressListener) {
        this.responseBody = responseBody;
        this.progressListener = progressListener;
    }

    @Override
    public MediaType contentType() {
        return responseBody.contentType();
    }

    @Override
    public long contentLength() {
        return responseBody.contentLength();
    }

    @Override
    public BufferedSource source() {
        if (bufferedSource == null) {
            bufferedSource = Okio.buffer(source(responseBody.source()));
        }
        return bufferedSource;
    }

    private Source source(Source source) {
        return new ForwardingSource(source) {
            long totalBytesRead = 0L;

            @Override
            public long read(Buffer sink, long byteCount) throws IOException {
                long bytesRead = super.read(sink, byteCount);
                // Increment the total bytes read
                totalBytesRead += bytesRead != -1 ? bytesRead : 0;
                
                // Calculate progress percentage
                long contentLength = responseBody.contentLength();
                float progress = contentLength > 0 ? (100f * totalBytesRead) / contentLength : -1;
                
                // Report progress to the listener
                if (progressListener != null) {
                    progressListener.update(totalBytesRead, contentLength, bytesRead == -1);
                }
                return bytesRead;
            }
        };
    }

    /**
     * Interface for reporting download progress
     */
    public interface ProgressListener {
        void update(long bytesRead, long contentLength, boolean done);
    }
}