package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import com.openpositioning.PositionMe.processing.GNSSDataProcessor;
import com.openpositioning.PositionMe.processing.WifiDataProcessor;
import com.openpositioning.PositionMe.sensors.SensorData.PhysicalSensorData;
import com.openpositioning.PositionMe.sensors.SensorData.SensorData;
import com.openpositioning.PositionMe.sensors.SensorListeners.SensorDataListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SensorHub implements SensorEventListener {

  // Singleton instance of SensorHub
  private static SensorHub instance = null;

  private static final Map<Integer, int[]> SENSOR_DEFAULTS = new HashMap<>();

  static {
    SENSOR_DEFAULTS.put(Sensor.TYPE_ACCELEROMETER, new int[]{10000, (int) 1e6});
    SENSOR_DEFAULTS.put(Sensor.TYPE_LINEAR_ACCELERATION, new int[]{10000, (int) 1e6});
    SENSOR_DEFAULTS.put(Sensor.TYPE_GRAVITY, new int[]{10000, (int) 1e6});
    SENSOR_DEFAULTS.put(Sensor.TYPE_PRESSURE, new int[]{(int) 1e6, 0});
    SENSOR_DEFAULTS.put(Sensor.TYPE_GYROSCOPE, new int[]{10000, (int) 1e6});
    SENSOR_DEFAULTS.put(Sensor.TYPE_LIGHT, new int[]{(int) 1e6, 0});
    SENSOR_DEFAULTS.put(Sensor.TYPE_PROXIMITY, new int[]{(int) 1e6, 0});
    SENSOR_DEFAULTS.put(Sensor.TYPE_MAGNETIC_FIELD, new int[]{10000, (int) 1e6});
    SENSOR_DEFAULTS.put(Sensor.TYPE_STEP_DETECTOR, new int[]{SensorManager.SENSOR_DELAY_NORMAL, 0});
    SENSOR_DEFAULTS.put(Sensor.TYPE_ROTATION_VECTOR, new int[]{(int) 1e6, 0});
  }
  private Context context; // The application context with which this SensorHub was initialized.
  private final SensorManager sensorManager;

  // Store listeners for all physical sensors as defined by android.hardware.Sensor
  private final Map<Integer, List<SensorDataListener<? extends SensorData>>> listeners =
      new HashMap<>();
  private final Map<Integer, Sensor> sensors =
      new HashMap<>();

  // Store all StreamSensor types.
  private final Map<StreamSensor, SensorModule<?>> sensorModules = new HashMap<>();


  // Store listeners for sensors defined by StreamSensors
  private final Map<StreamSensor, List<SensorDataListener<?>>> streamSensorListeners =
      new HashMap<>();

  private SensorHub(Context context) {
    this.context = context;
    this.sensorManager =
        (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
  }

  /**
   * Get the singleton instance of SensorHub.
   * @param context the application context
   * @return the singleton instance of SensorHub
   */
  public static SensorHub getInstance(Context context) {
    if (instance == null) {
      instance = new SensorHub(context);
    }
    return instance;
  }

  /**
   * Get the sensor of the sensorType, defined from Sensor.TYPE_*
   * @return the sensor of the sensorType
   *         null if the sensor is not available, or not registered
   */
  public Sensor getSensor(int sensorType) {
    return sensors.get(sensorType);
  }

  // Subscribe to a specific sensor type.
  public <T extends SensorData> void addListener(int sensorType,
      SensorDataListener<T> listener) {
    // Register sensor if not already initialized
    if (!listeners.containsKey(sensorType)) {
      registerSensor(sensorType);
    }

    // Add listener
    listeners.computeIfAbsent(sensorType, k -> new ArrayList<>()).add(listener);
  }

  public <T extends SensorData> void addListener(StreamSensor sensorType,
      SensorDataListener<T> listener) {
    // Register sensor if not already initialized
    if (!streamSensorListeners.containsKey(sensorType)) {
      SensorModule<?> module = createAndRegisterStreamSensor(sensorType);
      sensorModules.put(sensorType, module);
    }
    streamSensorListeners.computeIfAbsent(sensorType, k -> new ArrayList<>()).add(listener);
  }

  // Unsubscribe to your sensor type.
  public <T extends SensorData> void removeListener(int sensorType,
      SensorDataListener<T> listener) {
    List<SensorDataListener<? extends SensorData>> list = listeners.get(sensorType);
    if (list != null) {
      list.remove(listener);
      if (list.isEmpty()) {
        unregisterSensor(sensorType);
      }
    }
  }

  public <T extends SensorData> void removeListener(StreamSensor sensorType,
      SensorDataListener<T> listener) {
    List<SensorDataListener<?>> list = streamSensorListeners.get(sensorType);
    if (list != null) {
      list.remove(listener);
      if (list.isEmpty()) {
        unregisterStreamSensor(sensorType);
      }
    }
  }

  // Register a new sensor in SensorHub.
  public void registerSensor(int sensorType) {
    Sensor sensor = sensorManager.getDefaultSensor(sensorType);
    if (sensor != null) {
      int[] defaults = SENSOR_DEFAULTS.
          getOrDefault(sensorType, new int[]{SensorManager.SENSOR_DELAY_NORMAL, 0});
      sensorManager.registerListener(this, sensor, defaults[0], defaults[1]);
      sensors.put(sensorType, sensor);
    } else {
      Log.i("SensorHub", "Cannot initialize sensor of type " + sensorType);
    }
  }

  // Stop listening to a physical sensor
  public void unregisterSensor(int sensorType) {
    Sensor sensor = sensorManager.getDefaultSensor(sensorType);
    if (sensor != null) {
      sensorManager.unregisterListener(this, sensor);
      sensors.remove(sensorType);
    } else {
      Log.i("SensorHub", "Null sensor of type " + sensorType);
    }
  }
  private SensorModule<?> createAndRegisterStreamSensor(StreamSensor sensorType) {
    SensorModule<?> module = switch (sensorType) {
      case WIFI -> new WifiDataProcessor(this.context, this);
      case GNSS -> new GNSSDataProcessor(this.context, this);
    };
    if (module != null) {
      module.start();  // Start the sensor module when registered
    }
    return module;
  }

  private void unregisterStreamSensor(StreamSensor sensorType) {
    SensorModule<?> module = sensorModules.get(sensorType);
    if (module != null) {
      module.stop();  // Stop the sensor module when unregistered
      sensorModules.remove(sensorType);
    }
  }

  public List<SensorInfo> getSensorInfoList() {
    List<SensorInfo> sensorInfoList = new ArrayList<>();
    for (Sensor sensor: sensors.values()) {
      SensorInfo sensorInfo = new SensorInfo(
          sensor.getName(),
          sensor.getVendor(),
          sensor.getResolution(),
          sensor.getPower(),
          sensor.getVersion(),
          sensor.getType()
      );
      sensorInfoList.add(sensorInfo);
    }
    return sensorInfoList;
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
