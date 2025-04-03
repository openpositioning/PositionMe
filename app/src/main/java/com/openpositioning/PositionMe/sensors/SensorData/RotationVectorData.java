package com.openpositioning.PositionMe.sensors.SensorData;

import android.hardware.SensorManager;

/**
 * Class representing rotation vector data.
 * <p>
 * This class extends the PhysicalSensorData class and holds the rotation vector and orientation values.
 * It also provides methods to convert orientation angles into a rotation matrix and to perform matrix multiplication.
 * </p>
 *
 * @author Philip Heptonstall
 */
public class RotationVectorData extends PhysicalSensorData {
  // Array to store rotation vector values
  public final float[] rotation;
  // Array to store orientation values
  public final float[] orientation = new float[3];

  /**
   * Constructor for RotationVectorData.
   * <p>
   * Initializes the rotation vector values and calculates the orientation values.
   * </p>
   *
   * @param values Array containing rotation vector values.
   */
  public RotationVectorData(float[] values) {
    this.rotation = values.clone();
    float[] rotationVectorDcm = new float[9];
    SensorManager.getRotationMatrixFromVector(rotationVectorDcm, this.rotation);
    SensorManager.getOrientation(rotationVectorDcm, this.orientation);
  }
}