package com.openpositioning.PositionMe.sensors;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 针对 WifiDataProcessor 中纯函数的单元测试，验证 BSSID 解析与字段规范化逻辑。
 */
public class WifiDataProcessorTest {

    @Test
    public void convertBssidToLong_validColonSeparated_returnsHexValue() {
        long value = WifiDataProcessor.convertBssidToLong("aa:bb:cc:dd:ee:ff");
        assertEquals(0xAABBCCDDEEFFL, value);
    }

    @Test
    public void convertBssidToLong_upperCaseHandled() {
        long value = WifiDataProcessor.convertBssidToLong("AA:BB:CC:DD:EE:FF");
        assertEquals(0xAABBCCDDEEFFL, value);
    }

    @Test
    public void convertBssidToLong_invalidLength_returnsZero() {
        long value = WifiDataProcessor.convertBssidToLong("aabbcc");
        assertEquals(0L, value);
    }

    @Test
    public void convertBssidToLong_null_returnsZero() {
        long value = WifiDataProcessor.convertBssidToLong(null);
        assertEquals(0L, value);
    }

    @Test
    public void normalizeSsid_handlesUnknownAndQuotes() {
        assertEquals("hidden", WifiDataProcessor.normalizeSsid(null));
        assertEquals("hidden", WifiDataProcessor.normalizeSsid("<unknown ssid>"));
        assertEquals("MyWifi", WifiDataProcessor.normalizeSsid("\"MyWifi\""));
    }

    @Test
    public void normalizeSsid_plainStringKeepsValue() {
        assertEquals("Cafe", WifiDataProcessor.normalizeSsid("Cafe"));
    }

    @Test
    public void normalizeFrequency_negativeToZero() {
        assertEquals(0, WifiDataProcessor.normalizeFrequency(-10));
    }

    @Test
    public void normalizeFrequency_positiveKeepsValue() {
        assertEquals(5180, WifiDataProcessor.normalizeFrequency(5180));
    }
}
