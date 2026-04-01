package com.openpositioning.PositionMe.sensors.fusion;

import static org.junit.Assert.*;
import org.junit.Test;

import com.openpositioning.PositionMe.sensors.Wifi;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link CalibrationManager}.
 * Verifies WKNN matching boundary conditions and null safety.
 *
 * <p>Note: Tests that require {@code LatLng} (Google Maps) are excluded
 * from local JVM tests to avoid Android stub exceptions.</p>
 */
public class CalibrationManagerTest {

    @Test
    public void getRecordCount_emptyByDefault() {
        CalibrationManager cm = new CalibrationManager();
        assertEquals(0, cm.getRecordCount());
    }

    @Test
    public void findBestMatch_emptyRecords_returnsNull() {
        CalibrationManager cm = new CalibrationManager();
        List<Wifi> scan = createMockScan(5);
        assertNull(cm.findBestMatch(scan));
    }

    @Test
    public void findBestMatch_nullScan_returnsNull() {
        CalibrationManager cm = new CalibrationManager();
        assertNull(cm.findBestMatch(null));
    }

    @Test
    public void findBestMatch_emptyScan_returnsNull() {
        CalibrationManager cm = new CalibrationManager();
        assertNull(cm.findBestMatch(new ArrayList<>()));
    }

    @Test
    public void findCalibrationPosition_emptyRecords_returnsNull() {
        CalibrationManager cm = new CalibrationManager();
        List<Wifi> scan = createMockScan(5);
        assertNull(cm.findCalibrationPosition(scan, 0));
    }

    @Test
    public void findCalibrationPosition_nullScan_returnsNull() {
        CalibrationManager cm = new CalibrationManager();
        assertNull(cm.findCalibrationPosition(null, 0));
    }

    /**
     * Creates a mock WiFi scan with the specified number of APs.
     */
    private List<Wifi> createMockScan(int apCount) {
        List<Wifi> scan = new ArrayList<>();
        for (int i = 0; i < apCount; i++) {
            Wifi w = new Wifi();
            w.setBssid(i + 1);
            w.setBssidString(String.format("00:11:22:33:44:%02x", i));
            w.setSsid("TestAP_" + i);
            w.setLevel(-50 - i);
            w.setFrequency(2400 + i * 5);
            w.setRttEnabled(false);
            scan.add(w);
        }
        return scan;
    }
}
