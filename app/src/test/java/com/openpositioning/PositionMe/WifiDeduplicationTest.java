package com.openpositioning.PositionMe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class WifiDeduplicationTest {

    @Test
    public void testFingerprintHashCalculation() {
        // Simulate two identical scans
        long[] macs1 = {123456789L, 987654321L};
        int[] rssis1 = {-50, -60};

        long[] macs2 = {123456789L, 987654321L};
        int[] rssis2 = {-50, -60};

        int hash1 = calculateHash(macs1, rssis1);
        int hash2 = calculateHash(macs2, rssis2);

        assertEquals("Identical scans should have same hash", hash1, hash2);
    }

    @Test
    public void testDifferentScansHaveDifferentHashes() {
        long[] macs1 = {123456789L};
        int[] rssis1 = {-50};

        long[] macs2 = {123456789L};
        int[] rssis2 = {-55}; // Different RSSI

        int hash1 = calculateHash(macs1, rssis1);
        int hash2 = calculateHash(macs2, rssis2);

        assertNotEquals("Different scans should have different hashes", hash1, hash2);
    }

    private int calculateHash(long[] macs, int[] rssis) {
        int hash = 0;
        for (int i = 0; i < macs.length; i++) {
            hash = 31 * hash + (int) (macs[i] ^ (macs[i] >>> 32));
            hash = 31 * hash + rssis[i];
        }
        return hash;
    }
}
