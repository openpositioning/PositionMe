package com.openpositioning.PositionMe.DataRecords;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WifiLocationHistory {
    private final List<WifiLocation> history;

    public WifiLocationHistory() {
        this.history = new ArrayList<>();
    }

    public void addRecord(WifiLocation record) {
        history.add(record);
    }

    public void removeRecord(WifiLocation record) {
        history.remove(record);
    }

    public WifiLocation getRecord(int index) {
        if (index >= 0 && index < history.size()) {
            return history.get(index);
        }
        return null;
    }

    public List<WifiLocation> getAllRecords() {
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
