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

    // Game rotation vector (no magnetometer) for diagnostic comparison
    public final float[] gameOrientation = new float[3];

    // Heading calibration: offset from game rotation to magnetic north (radians)
    // calibratedHeading = gameOrientation[0] + headingOffset
    public volatile float headingOffset = 0f;
    public volatile boolean headingCalibrated = false;

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
    public final float[] startLocation = new float[2];

    // Step counting
    public volatile int stepCounter;
}
