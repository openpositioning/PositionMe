package com.openpositioning.PositionMe.sensors;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.se.omapi.Session;
import android.util.Log;
import com.openpositioning.PositionMe.sensors.SensorData.PhysicalSensorData;
import com.openpositioning.PositionMe.sensors.SensorData.SensorData;
import com.openpositioning.PositionMe.sensors.SensorListeners.SensorDataListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SensorHub implements SensorEventListener {
  private final SensorManager sensorManager;

  // Store listeners for all physical sensors as defined by android.hardware.Sensor
  private final Map<Integer, List<SensorDataListener<? extends SensorData>>> listeners =
      new HashMap<>();

  // Store all StreamSensor types.
  private final Map<StreamSensor, SensorModule<?>> sensorModules = new HashMap<>();


  // Store listeners for sensors defined by StreamSensors
  private final Map<StreamSensor, List<SensorDataListener<?>>> streamSensorListeners =
      new HashMap<>();

  public SensorHub(SensorManager sensorManager) {
    this.sensorManager = sensorManager;
  }

  // Subscribe to a specific sensor type.
  public <T extends SensorData> void addListener(int sensorType,
      SensorDataListener<T> listener) {
    // Register sensor if not already initialized
    if (!listeners.containsKey(sensorType)) {
      registerSensor(sensorType, SensorManager.SENSOR_DELAY_NORMAL);
    }

    // Add listener
    listeners.computeIfAbsent(sensorType, k -> new ArrayList<>()).add(listener);
  }

  public <T extends SensorData> void addListener(StreamSensor sensorType,
      SensorDataListener<T> listener) {
    streamSensorListeners.computeIfAbsent(sensorType, k -> new ArrayList<>()).add(listener);
  }

  // Unsubscribe to your sensor type.
  public <T extends SensorData> void removeListener(int sensorType,
      SensorDataListener<T> listener) {
    List<SensorDataListener<? extends SensorData>> list = listeners.get(sensorType);
    if (list != null) {
      list.remove(listener);
    }
  }

  public <T extends SensorData> void removeListener(StreamSensor sensorType,
      SensorDataListener<T> listener) {
    List<SensorDataListener<?>> list = streamSensorListeners.get(sensorType);
    if (list != null) {
      list.remove(listener);
    }
  }

  // Register a new sensor in SensorHub.
  public void registerSensor(int sensorType, int samplingPeriodUs) {
    Sensor sensor = sensorManager.getDefaultSensor(sensorType);
    if (sensor != null) {
      sensorManager.registerListener(this, sensor, samplingPeriodUs);
    } else {
      Log.i("SensorHub", "Cannot initialize sensor of type " + sensorType);
    }
  }

  // Stop listening to a physical sensor
  public void unregisterSensor(int sensorType) {
    Sensor sensor = sensorManager.getDefaultSensor(sensorType);
    if (sensor != null) {
      sensorManager.unregisterListener(this, sensor);
    } else {
      Log.i("SensorHub", "Null sensor of type " + sensorType);
    }
  }

  // Notify StreamSensor (WiFi/GNSS) listeners
  public <T extends SensorData> void notifyStreamSensor(StreamSensor sensorType, T data) {
    List<SensorDataListener<?>> sensorListeners = streamSensorListeners.get(sensorType);
    if (sensorListeners != null) {
      for (SensorDataListener<?> listener : sensorListeners) {
        notifyListener(listener, data);
      }
    }
  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    notifyListeners(event.sensor.getType(), PhysicalSensorData.fromEvent(event));
  }

  private void notifyListeners(Integer sensorType, SensorData data) {
    List<SensorDataListener<? extends SensorData>> sensorListeners =
        listeners.get(sensorType);
    if (sensorListeners != null) {
      for (SensorDataListener<?> listener : sensorListeners) {
        notifyListener(listener, data);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private <T extends SensorData> void notifyListener(SensorDataListener<T> listener,
      Object data) {
    listener.onSensorDataReceived((T) data);
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
    // Handle accuracy changes if necessary
  }
}
