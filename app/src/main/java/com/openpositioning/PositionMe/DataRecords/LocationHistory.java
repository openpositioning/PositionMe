package com.openpositioning.PositionMe.DataRecords;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LocationHistory {
    private final List<LocationData> history;

    public LocationHistory() {
        this.history = new ArrayList<>();
    }

    public void addRecord(LocationData record) {
        history.add(record);
    }

    public void removeRecord(int index) {
        history.remove(-1);
    }

    public LocationData getRecord(int index) {
        if (index >= 0 && index < history.size()) {
            return history.get(index);
        }
        return null;
    }

    public List<LocationData> getAllRecords() {
        return Collections.unmodifiableList(history);
    }

    public void clearHistory() {
        history.clear();
    }

    public int size() {
        return history.size();
    }

    public boolean isEmpty() {
        return history.isEmpty();
    }
}
