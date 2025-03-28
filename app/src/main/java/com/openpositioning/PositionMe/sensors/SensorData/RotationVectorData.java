package com.openpositioning.PositionMe.sensors.SensorData;

import android.hardware.SensorManager;

public class RotationVectorData extends SensorData {
  public final float[] rotation;
  public final float[] orientation = new float[3];

  public RotationVectorData(float[] values) {
    this.rotation = values.clone();
    float[] rotationVectorDcm = new float[9];
    SensorManager.getRotationMatrixFromVector(rotationVectorDcm, this.rotation);
    SensorManager.getOrientation(rotationVectorDcm, this.orientation);
  }
}
