package com.openpositioning.PositionMe;

public class SensorData {
    private String name;
    private String value;

    public SensorData(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
