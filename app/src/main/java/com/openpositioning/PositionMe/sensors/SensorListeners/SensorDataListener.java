package com.openpositioning.PositionMe.sensors.SensorListeners;

import com.openpositioning.PositionMe.sensors.SensorData.SensorData;

/**
 * Interface for sensor data listeners.
 * <p>
 * This interface defines methods for receiving sensor data and controlling the listener's lifecycle.
 * </p>
 *
 * @param <T> The type of sensor data.
 *
 * @author Philip Heptonstall
 */
public interface SensorDataListener<T extends SensorData> {
    /**
     * Called when sensor data is received.
     *
     * @param data The received sensor data.
     */
    void onSensorDataReceived(T data);

    /**
     * Stops the sensor data listener.
     */
    void stop();

    /**
     * Starts the sensor data listener.
     */
    void start();
}