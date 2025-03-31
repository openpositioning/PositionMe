package com.openpositioning.PositionMe.sensors;

import com.openpositioning.PositionMe.presentation.fragment.MeasurementsFragment;

/**
 * Enum of the sensor types.
 *
 * Simplified version of default Android Sensor.TYPE, with the order matching the table layout for
 * the {@link MeasurementsFragment}. Includes virtual sensors and other
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
    PDR,
    GNSS_OUTLIER,
    WIFI,
    WIFI_FLOOR,
    WIFI_OUTLIER,
    FUSED;
}
