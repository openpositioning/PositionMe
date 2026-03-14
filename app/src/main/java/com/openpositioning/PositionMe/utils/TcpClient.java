package com.openpositioning.PositionMe.utils;

import android.util.Log;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TcpClient {
    private Socket socket;
    private PrintWriter writer;
    // Single thread executor to handle all network I/O in order
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public TcpClient(String serverIp, int port) {
        executor.execute(() -> {
            try {
                Log.d("TCP", "Attempting to connect to " + serverIp);
                socket = new Socket(serverIp, port);
                writer = new PrintWriter(socket.getOutputStream(), true);
                Log.d("TCP", "Connected successfully!");
            } catch (Exception e) {
                Log.e("TCP", "Connection failed", e);
            }
        });
    }

    public void send(String msg) {
        // Run the network write on the background thread
        executor.execute(() -> {
            if (writer != null) {
                writer.print(msg);
                writer.flush();    // Ensure it's pushed out immediately
            } else {
                // Optional: Log that we are dropping data because not connected yet
                Log.w("TCP", "Writer null, message dropped");
            }
        });
    }

    public void stopClient() {
        executor.execute(() -> {
            try {
                if (writer != null) writer.close();
                if (socket != null) socket.close();
                executor.shutdown();
                Log.d("TCP", "Client stopped");
            } catch (Exception e) {
                Log.e("TCP", "Error closing client", e);
            }
        });
    }
}