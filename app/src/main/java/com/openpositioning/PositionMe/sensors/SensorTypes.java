package com.openpositioning.PositionMe.sensors;

/**
 * Enum of the sensor types.
 *
 * Simplified version of default Android Sensor.TYPE, with the order matching the table layout for
 * the {@link com.openpositioning.PositionMe.fragments.MeasurementsFragment}. Includes virtual sensors and other
 * data providing devices as well as derived data.
 *
 * @author Mate Stodulka
 */
public enum SensorTypes {
    ACCELEROMETER,
    GRAVITY,
    MAGNETICFIELD,
    GYRO,
    LIGHT,
    PRESSURE,
    PROXIMITY,
    GNSSLATLONG,
    PDR;
}
