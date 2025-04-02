package com.openpositioning.PositionMe.processing;

import android.hardware.Sensor;
import android.util.Log;
import com.openpositioning.PositionMe.sensors.SensorData.*;
import com.openpositioning.PositionMe.sensors.SensorListeners.SensorDataListener;
import com.openpositioning.PositionMe.sensors.SensorHub;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.sensors.StreamSensor;
import java.util.HashMap;
import java.util.Map;

/**
 * SensorViewListener is a class that listens to sensor data updates and notifies the callback
 * interface with the updated sensor values.
 * Primarily used in MeasurementsFragment to update the UI with sensor data.
 * @author Philip Heptonstall
 */
public class SensorViewListener implements SensorDataListener<SensorData> {

  /**
   * Callback interface for sensor data updates.
   */
  public interface SensorDataCallback {
    /**
     * Called when sensor data is updated.
     *
     * @param sensorValues Map of sensor types to their respective values.
     * @param wifiData WiFi data.
     * @param gnssLocationData GNSS location data.
     */
    void onSensorDataUpdated(Map<SensorTypes, float[]> sensorValues, WiFiData wifiData,
        GNSSLocationData gnssLocationData);
  }

  // Array of sensor types that are of interest.
  private static final int[] INTERESTED_SENSORS = {
      Sensor.TYPE_ACCELEROMETER,
      Sensor.TYPE_GRAVITY,
      Sensor.TYPE_MAGNETIC_FIELD,
      Sensor.TYPE_GYROSCOPE,
      Sensor.TYPE_LIGHT,
      Sensor.TYPE_PRESSURE,
      Sensor.TYPE_PROXIMITY,
  };

  // Array of stream sensors that are of interest.
  private static final StreamSensor[] INTERESTED_STREAM_SENSORS = {
      StreamSensor.WIFI,
      StreamSensor.GNSS,
  };

  // Sensor hub for managing sensors.
  private final SensorHub sensorHub;
  // Callback for sensor data updates.
  private final SensorDataCallback callback;

  // Map to store sensor values.
  private final Map<SensorTypes, float[]> sensorValueMap;
  // WiFi data.
  private WiFiData wifiData;
  // GNSS location data.
  private GNSSLocationData gnssLocationData;
  // Flag to indicate if the listener has started.
  private boolean started = false;

  /**
   * Constructor for SensorViewListener.
   *
   * @param sensorHub Sensor hub for managing sensors.
   * @param callback Callback for sensor data updates.
   */
  public SensorViewListener(SensorHub sensorHub, SensorDataCallback callback) {
    this.sensorHub = sensorHub;
    this.sensorValueMap = new HashMap<>();
    this.callback = callback;
    start();
  }

  /**
   * Called when sensor data is received.
   *
   * @param data Sensor data.
   */
  @Override
  public void onSensorDataReceived(SensorData data) {
    if (data instanceof AccelerometerData accelerometerData) {
      // Handle accelerometer data
      sensorValueMap.put(SensorTypes.ACCELEROMETER, accelerometerData.acceleration);
    } else if (data instanceof GravityData gravityData) {
      sensorValueMap.put(SensorTypes.GRAVITY, gravityData.gravity);
    } else if (data instanceof MagneticFieldData magnetometerData) {
      // Handle magnetometer data
      sensorValueMap.put(SensorTypes.MAGNETICFIELD, magnetometerData.magneticField);
    } else if (data instanceof GyroscopeData gyroscopeData) {
      // Handle gyroscope data
      sensorValueMap.put(SensorTypes.GYRO, gyroscopeData.angularVelocity);
    } else if (data instanceof LightData lightData) {
      // Handle light data
      sensorValueMap.put(SensorTypes.LIGHT, new float[]{lightData.light});
    } else if (data instanceof PressureData pressureData) {
      // Handle pressure data
      sensorValueMap.put(SensorTypes.PRESSURE, new float[]{pressureData.pressure});
    } else if (data instanceof ProximityData proximityData) {
      // Handle proximity data
      sensorValueMap.put(SensorTypes.PROXIMITY, new float[]{proximityData.proximity});
    } else if (data instanceof WiFiData wifiData) {
      this.wifiData = wifiData;
    } else if (data instanceof GNSSLocationData gnssLocationData) {
      this.gnssLocationData = gnssLocationData;
      sensorValueMap.put(SensorTypes.GNSSLATLONG,
          new float[]{gnssLocationData.latitude, gnssLocationData.longitude});
    } else {
      Log.w("SensorViewListener", "Unknown sensor data type: " + data.getClass().getSimpleName());
    }
    callback.onSensorDataUpdated(sensorValueMap, wifiData, gnssLocationData);
  }

  /**
   * Starts the sensor data listener.
   */
  @Override
  public void start() {
    if(!started) {
      for (int sensorType : INTERESTED_SENSORS) {
        sensorHub.addListener(sensorType, this);
      }
      for (StreamSensor streamSensor : INTERESTED_STREAM_SENSORS) {
        sensorHub.addListener(streamSensor, this);
      }
    }
    started = true;
  }

  /**
   * Stops the sensor data listener.
   */
  @Override
  public void stop() {
    if(!started) {
      return;
    }
    for (int sensorType : INTERESTED_SENSORS) {
      sensorHub.removeListener(sensorType, this);
    }
    for (StreamSensor streamSensor : INTERESTED_STREAM_SENSORS) {
      sensorHub.removeListener(streamSensor, this);
    }
  }

}