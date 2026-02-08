**Date**: 2026-02-08

### New Fields Added
1. **trajectory_id** (string) - For naming/identifying trajectories
2. **initial_position** (GNSSPosition) - Starting position of trajectory
3. **test_points** (repeated GNSSPosition) - Test points for positioning algorithms
4. **sensor_info** - Information for 7 sensors:
    - accelerometer_info
    - gyroscope_info
    - rotation_vector_info
    - magnetometer_info
    - barometer_info
    - light_sensor_info
    - proximity_info
5. **WiFiAPData.rtt_enabled** (bool) - RTT support flag
6. **BleData** enhanced with:
    - mac_address, name, tx_power_level
    - advertise_flags, service_uuids, manufacturer_data

---

### Breaking Changes - API Updates

### Class Name Changes

| Old Name | New Name |
|----------|----------|
| `Pdr_Sample` | `RelativePosition` |
| `GNSS_Sample` | `GNSSReading` |
| `WiFi_Sample` | `Fingerprint` |
| `Mac_Scan` | `RFScan` |
| `Motion_Sample` | `IMUReading` |
| `Position_Sample` | `MagnetometerReading` |
| `Pressure_Sample` | `BarometerReading` |
| `Light_Sample` | `LightReading` |
| `AP_Data` | `WiFiAPData` |

### Method Name Changes

| Old Method | New Method |
|------------|------------|
| `addMacScans()` | `addRfScans()` |
| `addWifiData()` | `addWifiFingerprints()` |
| `addPositionData()` | `addMagnetometerData()` |
| `getPositionDataCount()` | `getMagnetometerDataCount()` |
| `getWifiDataCount()` | `getWifiFingerprintsCount()` |

### Structure Changes

**IMU Data** - Now uses Vector3/Quaternion:
```java
// OLD
.setAccX(x).setAccY(y).setAccZ(z)

// NEW
.setAcc(Traj.Vector3.newBuilder().setX(x).setY(y).setZ(z).build())
```

**GNSS Data** - Position nested in GNSSPosition:
```java
// OLD
.setLatitude(lat).setLongitude(lon).setAltitude(alt)

// NEW
.setPosition(Traj.GNSSPosition.newBuilder()
    .setLatitude(lat).setLongitude(lon).setAltitude(alt).build())
```

---

### Files Modified

- `Traj.java` - Regenerated from proto
- `SensorFusion.java` - Updated API calls
- `ServerCommunications.java` - Updated API calls
- `app/build.gradle` - Updated protobuf dependency to 4.29.6

---