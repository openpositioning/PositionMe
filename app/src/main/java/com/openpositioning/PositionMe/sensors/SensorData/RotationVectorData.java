package com.openpositioning.PositionMe.sensors.SensorData;

import android.hardware.SensorManager;

public class RotationVectorData extends PhysicalSensorData {
  public final float[] rotation;
  public final float[] orientation = new float[3];

  public RotationVectorData(float[] values) {
    this.rotation = values.clone();
    float[] rotationVectorDcm = new float[9];
    SensorManager.getRotationMatrixFromVector(rotationVectorDcm, this.rotation);
    SensorManager.getOrientation(rotationVectorDcm, this.orientation);
  }

  /**
   * Method used for converting an array of orientation angles into a rotation matrix.
   *
   * @param o An array containing orientation angles in radians
   * @return resultMatrix representing the orientation angles
   */
  private float[] getRotationMatrixFromOrientation(float[] o) {
    float[] xM = new float[9];
    float[] yM = new float[9];
    float[] zM = new float[9];

    float sinX = (float) Math.sin(o[1]);
    float cosX = (float) Math.cos(o[1]);
    float sinY = (float) Math.sin(o[2]);
    float cosY = (float) Math.cos(o[2]);
    float sinZ = (float) Math.sin(o[0]);
    float cosZ = (float) Math.cos(o[0]);

    // rotation about x-axis (pitch)
    xM[0] = 1.0f;
    xM[1] = 0.0f;
    xM[2] = 0.0f;
    xM[3] = 0.0f;
    xM[4] = cosX;
    xM[5] = sinX;
    xM[6] = 0.0f;
    xM[7] = -sinX;
    xM[8] = cosX;

    // rotation about y-axis (roll)
    yM[0] = cosY;
    yM[1] = 0.0f;
    yM[2] = sinY;
    yM[3] = 0.0f;
    yM[4] = 1.0f;
    yM[5] = 0.0f;
    yM[6] = -sinY;
    yM[7] = 0.0f;
    yM[8] = cosY;

    // rotation about z-axis (azimuth)
    zM[0] = cosZ;
    zM[1] = sinZ;
    zM[2] = 0.0f;
    zM[3] = -sinZ;
    zM[4] = cosZ;
    zM[5] = 0.0f;
    zM[6] = 0.0f;
    zM[7] = 0.0f;
    zM[8] = 1.0f;

    // rotation order is y, x, z (roll, pitch, azimuth)
    float[] resultMatrix = matrixMultiplication(xM, yM);
    resultMatrix = matrixMultiplication(zM, resultMatrix);
    return resultMatrix;
  }

  /**
   * Performs and matrix multiplication of two 3x3 matrices and returns the product.
   *
   * @param A An array representing a 3x3 matrix
   * @param B An array representing a 3x3 matrix
   * @return result representing the product of A and B
   */
  private float[] matrixMultiplication(float[] A, float[] B) {
    float[] result = new float[9];

    result[0] = A[0] * B[0] + A[1] * B[3] + A[2] * B[6];
    result[1] = A[0] * B[1] + A[1] * B[4] + A[2] * B[7];
    result[2] = A[0] * B[2] + A[1] * B[5] + A[2] * B[8];

    result[3] = A[3] * B[0] + A[4] * B[3] + A[5] * B[6];
    result[4] = A[3] * B[1] + A[4] * B[4] + A[5] * B[7];
    result[5] = A[3] * B[2] + A[4] * B[5] + A[5] * B[8];

    result[6] = A[6] * B[0] + A[7] * B[3] + A[8] * B[6];
    result[7] = A[6] * B[1] + A[7] * B[4] + A[8] * B[7];
    result[8] = A[6] * B[2] + A[7] * B[5] + A[8] * B[8];

    return result;
  }
}
