package com.openpositioning.PositionMe.sensors;

/**
 * Thread-safe holder for live sensor readings shared between the sensor
 * event handler (main thread) and the trajectory recording timer (background thread).
 *
 * <p>All arrays are allocated once and written element-by-element. Individual
 * float writes are atomic on the JVM, so explicit synchronization is not
 * required for single-element access. The {@code volatile} modifier on
 * scalar fields ensures visibility across threads.</p>
 */
public class SensorState {

  // IMU arrays
  public final float[] acceleration = new float[3];
  public final float[] filteredAcc = new float[3];
  public final float[] gravity = new float[3];
  public final float[] magneticField = new float[3];
  public final float[] angularVelocity = new float[3];
  public final float[] orientation = new float[3];
  public final float[] R = new float[9];

  // Rotation vector: volatile because TYPE_ROTATION_VECTOR replaces via clone()
  public volatile float[] rotation = new float[]{0, 0, 0, 1.0f};

  // Scalar sensors
  public volatile float pressure;
  public volatile float light;
  public volatile float proximity;

  // Derived values from PDR
  public volatile float elevation;
  public volatile boolean elevator;

  // GNSS location
  public volatile float latitude;
  public volatile float longitude;
  public volatile float gnssAccuracy = 8.0f; // metres, from Location.getAccuracy()
  public final float[] startLocation = new float[2];

  // Step counting
  public volatile int stepCounter;

  // Gyro-based heading (TYPE_GAME_ROTATION_VECTOR — no magnetometer, no magnetic interference)
  // headingOffset aligns game heading to magnetic north at recording start; NaN = not calibrated
  public volatile float gameHeading = 0f;
  public volatile float headingOffset = Float.NaN;
}
