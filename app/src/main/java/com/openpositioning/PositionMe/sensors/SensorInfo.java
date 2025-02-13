package com.openpositioning.PositionMe.sensors;

import androidx.annotation.NonNull;

/**
 * The Sensor Info object holds physical properties of a sensor in the device.
 *
 * It contains it's name, vendor, resolution, power, version and type, which vary across devices.
 *
 * @author Virginia Cangelosi
 * @author Mate Stodulka
 */
public class SensorInfo {
    private final String name;
    private final String vendor;
    private final float resolution;
    private final float power;
    private final int version;
    private final int type;

    /**
     * Public default constructor of the Sensor Info object.
     *
     * Should be initialised with all its parameters, typically from {@link MovementSensor}.
     *
     * @param name          name string of the sensor. Unique for a particular sensor type.
     * @param vendor        vendor string of this sensor.
     * @param resolution    resolution of the sensor in the sensor's unit.
     * @param power         the power in mA used by this sensor while in use.
     * @param version       version of the sensor's module.
     * @param type          generic type of this sensor.
     */
    public SensorInfo(String name, String vendor, float resolution, float power, int version, int type) {
        this.name = name;
        this.vendor = vendor;
        this.resolution = resolution;
        this.power = power;
        this.version = version;
        this.type = type;
    }


    //region Getters

    public String getName() {
        return name;
    }

    public String getVendor() {
        return vendor;
    }

    public float getResolution() {
        return resolution;
    }

    public float getPower() {
        return power;
    }

    public int getVersion() {
        return version;
    }

    public int getType() {
        return type;
    }

    //endregion

    /**
     * {@inheritDoc}
     * Basic string representation for debug
     */
    @NonNull
    @Override
    public String toString() {
        return "SensorInfo{" +
                "name='" + name + '\'' +
                ", vendor='" + vendor + '\'' +
                ", resolution=" + resolution +
                ", power=" + power +
                ", version=" + version +
                ", type=" + type +
                '}';
    }
}
