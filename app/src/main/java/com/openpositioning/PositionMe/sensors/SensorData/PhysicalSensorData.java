package com.openpositioning.PositionMe.sensors.SensorData;

import android.hardware.Sensor;
import android.hardware.SensorEvent;

/**
 * Abstract class representing physical sensor data.
 * <p>
 * This class extends the SensorData class and provides a method to create specific sensor data objects
 * from a SensorEvent.
 * </p>
 *
 * @author Philip Heptonstall
 */
public abstract class PhysicalSensorData extends SensorData {

  /**
   * Creates a specific PhysicalSensorData object from a SensorEvent.
   * <p>
   * This method uses a switch statement to determine the type of sensor and create the appropriate
   * sensor data object.
   * </p>
   *
   * @param event SensorEvent containing the sensor data.
   * @return A specific PhysicalSensorData object corresponding to the sensor type.
   * @throws IllegalArgumentException if the sensor type is unsupported.
   */
  public static PhysicalSensorData fromEvent(SensorEvent event) {
    return switch (event.sensor.getType()) {
      case Sensor.TYPE_ACCELEROMETER -> new AccelerometerData(event.values);
      case Sensor.TYPE_PRESSURE -> new PressureData(event.values[0]);
      case Sensor.TYPE_GYROSCOPE -> new GyroscopeData(event.values);
      case Sensor.TYPE_LINEAR_ACCELERATION -> new LinearAccelerationData(event.values);
      case Sensor.TYPE_GRAVITY -> new GravityData(event.values);
      case Sensor.TYPE_LIGHT -> new LightData(event.values[0]);
      case Sensor.TYPE_PROXIMITY -> new ProximityData(event.values[0]);
      case Sensor.TYPE_MAGNETIC_FIELD -> new MagneticFieldData(event.values);
      case Sensor.TYPE_ROTATION_VECTOR -> new RotationVectorData(event.values);
      case Sensor.TYPE_STEP_DETECTOR -> new StepDetectorData();
      default ->
          throw new IllegalArgumentException("Unsupported sensor type: " + event.sensor.getType());
    };
  }
}