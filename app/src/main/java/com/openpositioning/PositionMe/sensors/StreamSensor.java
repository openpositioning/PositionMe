package com.openpositioning.PositionMe.sensors;

/**
 * Additional sensor types to cover those not defined in
 * Sensor.
 *
 * Define an enum such that we can ensure only valid 'Streaming' sensors are used.
 *
 * @author Philip Heptonstall
 */
public enum StreamSensor {
    WIFI,
    GNSS
}
