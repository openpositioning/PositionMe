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
    public final float[] startLocation = new float[2];

    // Step counting
    public volatile int stepCounter;

    // Last PDR position for step length calculation (used by map matching)
    public volatile float lastPdrX = 0f;
    public volatile float lastPdrY = 0f;
}
