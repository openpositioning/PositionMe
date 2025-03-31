package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;

/**
 * Movement sensor class representing all Sensor Manager based devices.
 *
 * The class is initialised with the application context to be used for permissions and hardware
 * access. Using the context, it adds a Sensor Manager, as well as a Sensor and {@link SensorInfo}
 * instance, with the type of the sensor determined upon initialisation of the class.
 *
 * @see SensorFusion where instances of this class are intended to be used.
 *
 * @author Mate Stodulka
 */
public class MovementSensor {
    // Application context for permissions and hardware access
    protected Context context;
    // Sensor Manager from the android hardware manager
    protected SensorManager sensorManager;
    // The Sensor instance determined by the type upon initialisation
    protected Sensor sensor;
    // Information about the sensor stored in a SensorInfo object
    protected SensorInfo sensorInfo;


    /**
     * Public default constructor for the Movement Sensor class.
     *
     * It calls the superclass constructor with context, and then initialises local properties.
     *
     * @param context       Application context used to check permissions and access devices.
     * @param sensorType    Type of the sensor to be created, using Sensor.TYPE constants.
     *
     * @see SensorInfo objects holding physical sensors properties.
     */
    public MovementSensor(Context context, int sensorType) {
        this.context = context;

        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.sensor = sensorManager.getDefaultSensor(sensorType);

        if (sensor != null) {
            this.sensorInfo = new SensorInfo(
                    sensor.getName(),
                    sensor.getVendor(),
                    sensor.getResolution(),
                    sensor.getPower(),
                    sensor.getVersion(),
                    sensor.getType()
            );
            System.err.println(sensorInfo);
        } else {
            this.sensorInfo = new SensorInfo(
                    "Not available",
                    "-",
                    -1.0f,
                    0.0f,
                    0,
                    0
            );

        }
    }

}
