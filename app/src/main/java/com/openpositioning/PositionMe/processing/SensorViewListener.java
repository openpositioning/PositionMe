package com.openpositioning.PositionMe.processing;

import android.hardware.Sensor;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.openpositioning.PositionMe.sensors.SensorData.*;
import com.openpositioning.PositionMe.sensors.SensorListeners.SensorDataListener;
import com.openpositioning.PositionMe.sensors.SensorHub;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.sensors.StreamSensor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SensorViewListener implements SensorDataListener<SensorData> {
  public interface SensorDataCallback {
    void onSensorDataUpdated(Map<SensorTypes, float[]> sensorValues, WiFiData wifiData,
        GNSSLocationData gnssLocationData);
  }
  private static final int[] INTERESTED_SENSORS = {
      Sensor.TYPE_ACCELEROMETER,
      Sensor.TYPE_GRAVITY,
      Sensor.TYPE_MAGNETIC_FIELD,
      Sensor.TYPE_GYROSCOPE,
      Sensor.TYPE_LIGHT,
      Sensor.TYPE_PRESSURE,
      Sensor.TYPE_PROXIMITY,
  };
  private static final StreamSensor[] INTERESTED_STREAM_SENSORS = {
      StreamSensor.WIFI,
      StreamSensor.GNSS,
  };

  private final SensorHub sensorHub;
  private final SensorDataCallback callback;
  private static final int REFRESH_TIME = 1000; // Refresh rate in milliseconds


  private final Map<SensorTypes, float[]> sensorValueMap;
  private WiFiData wifiData;
  private GNSSLocationData gnssLocationData;
  private boolean started = false;

  public SensorViewListener(SensorHub sensorHub, SensorDataCallback callback) {
    this.sensorHub = sensorHub;
    this.sensorValueMap = new HashMap<>();
    this.callback = callback;
    start();
  }

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
