# Programmer's Guide — PositionMe Indoor Positioning App

## 1. Project Overview

PositionMe is an Android indoor positioning application developed for the University of Edinburgh Embedded Wireless course. The app records multi-sensor trajectories (IMU, GNSS, WiFi, BLE, barometer), visualises real-time position on Google Maps, supports indoor floor plan display via the OpenPositioning API, and uploads trajectory data for server-side analysis.

**Target platform:** Android 9+ (API 28)
**Language:** Java
**Build system:** Gradle with protobuf plugin
**Key dependencies:** Google Maps SDK, Volley, OkHttp, Protocol Buffers (protobuf-javalite)

---

## 2. Architecture

### 2.1 High-Level Module Diagram

```
┌──────────────────────────────────────────────────────────┐
│                    Presentation Layer                     │
│  HomeFragment → RecordingActivity → RecordingFragment    │
│                    ↕ (child)                              │
│              TrajectoryMapFragment                        │
│                    ↓                                      │
│              CorrectionFragment → Upload                  │
│              FilesFragment → Download / Replay            │
├──────────────────────────────────────────────────────────┤
│                     Sensor Layer                          │
│  SensorFusion (singleton) ← MovementSensor (×10)         │
│       ← WifiDataProcessor    ← GNSSDataProcessor         │
│       ← BleDataProcessor     ← WifiRttProcessor          │
│       ← WiFiPositioning (API client)                     │
│       ← FloorplanAPI (indoor maps)                       │
├──────────────────────────────────────────────────────────┤
│                      Data Layer                           │
│  traj.proto → Traj.java (generated)                      │
│  ServerCommunications (OkHttp upload/download)            │
│  download_records.json (local metadata)                   │
└──────────────────────────────────────────────────────────┘
```

### 2.2 Design Patterns

| Pattern | Where | Purpose |
|---------|-------|---------|
| **Singleton** | `SensorFusion` | Single point of sensor data access across all fragments |
| **Observer** | `WifiDataProcessor`, `BleDataProcessor`, `ServerCommunications` | Async notification of scan results / API responses |
| **Builder** | `Traj.Trajectory.Builder` | Incremental protobuf construction during recording |
| **MVC** | Fragment ↔ SensorFusion ↔ Traj | UI separated from data model and sensor logic |

---

## 3. Sensor Layer

### 3.1 SensorFusion (singleton)

**File:** `sensors/SensorFusion.java`

Central hub for all sensor data. Registers hardware sensors via `MovementSensor`, processes raw data, and stores it in a `Traj.Trajectory.Builder`.

**Key responsibilities:**
- Sensor registration and lifecycle (`setContext()`, `startRecording()`, `stopRecording()`)
- Periodic data storage via `storeDataInTrajectory` TimerTask (10ms period, 1s actual storage rate throttled by `lastStoreTimeMs`)
- PDR (Pedestrian Dead Reckoning) via `PdrProcessing`
- WiFi fingerprint construction for the positioning API
- WiFi deduplication (skip duplicate scan if MAC list unchanged)
- Barometer-based floor estimation with hysteresis
- Test point recording with timestamps and floor labels
- Protobuf serialisation using nested `Vector3`, `Quaternion`, `GNSSPosition` types (required by server wire format)

**Barometer floor detection** (`calibrateBarometerForFloor`, `getEstimatedFloor`):
- Calibrates from user's current displayed floor, computing `estimatedGFPressure`
- Uses measured Murchison House pressure boundaries (offset from GF reference):
  - LG: >+0.20 hPa, GF: +0.20 to -0.235, 1F: -0.235 to -0.565, 2F: -0.565 to -0.93, 3F: <-0.93

**WiFi deduplication** (`handleWifiUpdate`):
- Builds sorted MAC list from each scan
- Compares with previous snapshot; skips storage if identical
- Prevents redundant fingerprints in trajectory data

### 3.2 MovementSensor

**File:** `sensors/MovementSensor.java`

Wrapper around Android `SensorManager` for a single sensor type. Handles registration, unregistration, and sensor info extraction (name, vendor, resolution, power, max_range, frequency).

### 3.3 WifiDataProcessor

**File:** `sensors/WifiDataProcessor.java`

Periodic WiFi scanning (1-second interval). Follows Observer pattern to notify `SensorFusion` of new scan results. Also stores raw `ScanResult` objects for WiFi RTT ranging.

### 3.4 BleDataProcessor

**File:** `sensors/BleDataProcessor.java`

Periodic BLE scanning (2-second interval, 1.5s scan window). Deduplicates by MAC within each scan window (keeps latest/strongest RSSI). Notifies `SensorFusion` via Observer pattern. Handles Android 12+ permission model (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`).

### 3.5 WifiRttProcessor

**File:** `sensors/WifiRttProcessor.java`

WiFi RTT (802.11mc / Fine Time Measurement) ranging. Requires API 28+ and `PackageManager.FEATURE_WIFI_RTT`. Accepts raw `ScanResult` objects for RTT-capable APs, performs ranging via `WifiRttManager`, stores results (distance in mm, stddev, RSSI) in a thread-safe buffer. Results are drained by `SensorFusion` during protobuf storage.

### 3.6 WiFiPositioning

**File:** `sensors/WiFiPositioning.java`

HTTP client for the OpenPositioning WiFi positioning API (`POST /api/position/fine`). Sends WiFi fingerprint as `{"wf": {mac_int: rssi, ...}}`. Returns `{lat, lon, floor}`. Timestamps each successful response for freshness checking.

### 3.7 FloorplanAPI

**File:** `sensors/FloorplanAPI.java`

HTTP client for the OpenPositioning indoor map API (`POST /api/live/floorplan/request/{api_key}?key={master_key}`). Sends GPS coordinates and WiFi MAC addresses. Parses GeoJSON response into `Venue` objects containing:
- `outlineCoords`: building outline polygon
- `floorShapes`: per-floor wall polygon data (indoor_type, coordinates)
- `availableFloors`: sorted list of floor numbers
- `floorNameMap`: floor number to API key name mapping (e.g. 0→"G", -1→"B1")

Floor name parsing (`parseFloorName`): "B1"→-1, "G"/"GF"→0, "1"/"F1"→1, etc.

---

## 4. Presentation Layer

### 4.1 Recording Flow

```
HomeFragment
  ├── [Start Recording] → RecordingActivity → StartLocationFragment → RecordingFragment
  └── [Indoor Positioning] → RecordingActivity (INDOOR_MODE) → RecordingFragment (auto-select venue)
```

**Indoor mode** (`HomeFragment.indoorButton`):
1. Checks if GPS position falls inside any known building polygon (`isInsideAnyBuilding`)
2. If outdoor: shows warning dialog
3. If indoor: launches `RecordingActivity` with `INDOOR_MODE=true`, which skips `StartLocationFragment`, auto-sets GPS start position, starts recording, and passes `INDOOR_MODE` to `RecordingFragment`
4. `RecordingFragment` sets `autoSelectVenue=true` on `TrajectoryMapFragment`, which auto-selects the first nearby building when API data loads

### 4.2 RecordingFragment

**File:** `presentation/fragment/RecordingFragment.java`

Manages the recording UI: elevation, distance, complete/cancel buttons, recording icon animation. Contains the PDR-WiFi sensor fusion loop.

**PDR-WiFi Fusion** (`updateUIandPosition`, 200ms cycle):
1. Get PDR delta from `SensorFusion` and apply to `rawPdrPosition`
2. Get WiFi absolute position and display on map
3. If WiFi is enabled, fresh (<10s), and changed: pull `rawPdrPosition` toward WiFi by `WIFI_CORRECTION_ALPHA` (0.15)
4. Pass corrected position to `TrajectoryMapFragment` for display
5. Store point for correction screen replay

**Trajectory naming**: On "Complete" button, shows dialog for user to name the trajectory. Name is stored in both `trajectory_name` (field 27) and `trajectory_id` (field 3) of the protobuf.

### 4.3 TrajectoryMapFragment

**File:** `presentation/fragment/TrajectoryMapFragment.java`

Google Maps fragment managing all map visualisation during recording.

**Sections:**
- **Map setup**: Type spinner (Hybrid/Normal/Satellite), gesture settings, indoor manager
- **Polylines**: PDR (red/purple when indoor), GNSS (blue), WiFi (green) — all at zIndex 10
- **GNSS/WiFi toggles**: Switch controls enabling/disabling display and fusion
- **Indoor Map**: Requests nearby buildings via `FloorplanAPI`, draws clickable outlines, handles venue selection
- **Floor display**: Blueprint-style rendering (light background + dark wall fills), floor label bitmap at building centre, up/down buttons for manual switching
- **Auto floor**: Barometer + WiFi floor estimation, calibrates on toggle enable, recalibrates on manual floor change
- **Test points**: FAB button adds numbered marker at current position, stores in `SensorFusion`, captures WiFi fingerprint for radiomap
- **Radiomap upload**: Collects WiFi fingerprints at test point locations, uploads batch to `/api/radiomap/upload/`

### 4.4 CorrectionFragment

**File:** `presentation/fragment/CorrectionFragment.java`

Post-recording review screen. Displays:
- Full trajectory polyline (blue)
- Start marker (green) and end marker (red) with floor/time/position info
- Test point markers (orange) with timestamps, floor, coordinates
- Overlapping markers are offset slightly for visibility (`offsetIfOverlapping`)
- Zoom to fit all points

### 4.5 FilesFragment / TrajDownloadListAdapter

**Files:** `presentation/fragment/FilesFragment.java`, `presentation/viewitems/TrajDownloadListAdapter.java`

History screen listing all uploaded trajectories. Supports:
- Download via `ServerCommunications.downloadTrajectory()` (ID-based zip entry matching)
- Display: user-given name as title, "#id date" as subtitle (falls back to extracting `trajectoryName` from downloaded JSON)
- Button states: download (arrow icon), downloading (spinner, yellow), downloaded (play icon, primary colour)
- `FileObserver` monitors `download_records.json` changes for real-time UI updates

---

## 5. Data Layer

### 5.1 Protobuf Schema (traj.proto)

The trajectory data format uses Protocol Buffers v3. Key design decisions:

- **Nested types**: `Motion_Sample` uses `Vector3` for acc/gyr and `Quaternion` for rotation. `GNSS_Sample` uses nested `GNSSPosition`. This matches the server's expected wire format.
- **Timestamps**: `start_timestamp` is absolute UNIX ms; all sub-message timestamps are relative to start.
- **WiFi fingerprints**: `WiFi_Sample` contains `repeated Mac_Scan` with integer-encoded MACs and RSSI.
- **Test points**: `repeated GNSSPosition test_points` (field 26) with optional floor string.
- **BLE/RTT**: `repeated WiFi_Sample ble_fingerprints` (field 14), `repeated WiFiRTTReading wifi_rtt_data` (field 13), `repeated BleData ble_data` (field 15).
- **Metadata**: `trajectory_name` (field 27) for user-given name, `trajectory_id` (field 3) also set to user name.

### 5.2 ServerCommunications

**File:** `data/remote/ServerCommunications.java`

Handles all HTTP communication via OkHttp:

- **Upload** (`sendTrajectory`): POST protobuf binary to `/api/live/trajectory/upload/murchison_house/{api_key}/?key={master_key}`
- **Download** (`downloadTrajectory`): GET zip from `/api/live/trajectory/download/{api_key}?skip=0&limit=200&key={master_key}`, find entry by `{id}.pkt` filename, parse protobuf, convert to JSON via `JsonFormat`, save locally
- **Info** (`sendInfoRequest`): GET trajectory metadata list
- **Download records**: `download_records.json` stored in app-specific Downloads, maps trajectory ID to file name, trajectory name, and download timestamp

### 5.3 Magnetometer Clamping

Before upload, magnetometer values are clamped to [-999, 999] μT to pass server validation (server rejects values outside [-1000, 1000]).

---

## 6. Key Algorithms

### 6.1 PDR-WiFi Sensor Fusion

Located in `RecordingFragment.updateUIandPosition()`.

```
Each 200ms cycle:
  1. rawPdrPosition += PDR delta (from step detection + heading)
  2. wifiPosition = WiFiPositioning API result
  3. if WiFi is ON and data is fresh (<10s) and changed:
       rawPdrPosition += 0.15 * (wifiPosition - rawPdrPosition)
  4. Display rawPdrPosition on map
```

The fusion is a feedback loop: WiFi continuously corrects PDR drift, while PDR provides smooth inter-WiFi-update tracking. Alpha=0.15 provides gentle correction without snapping.

### 6.2 Heading Calibration

When GNSS speed > 1.5 m/s, compute `headingOffset = GNSS_bearing - sensor_heading`. Apply offset to all PDR heading estimates. This calibrates the magnetometer-based heading using GPS ground truth.

### 6.3 Complementary Filter (Heading)

Fuses gyroscope (fast, drifts) with magnetometer (slow, noisy):
```
fusedHeading = FILTER_COEFFICIENT * (fusedHeading + gyroDelta) + (1 - FILTER_COEFFICIENT) * magHeading
```
With `FILTER_COEFFICIENT = 0.96`, gyroscope dominates short-term, magnetometer corrects long-term drift.

### 6.4 Floor Estimation

Combines barometer pressure readings with WiFi floor API response:
1. User enables auto-floor switch → calibrates barometer from current displayed floor
2. Each update: compute pressure offset from estimated GF reference
3. Apply threshold boundaries (with hysteresis from calibration)
4. WiFi floor from API updates `wifiFloor` in `SensorFusion`
5. `getEstimatedFloor()` returns barometer-based floor (more responsive than WiFi)

---

## 7. New Files Added

| File | Purpose |
|------|---------|
| `sensors/FloorplanAPI.java` | Indoor map API client, GeoJSON parsing |
| `sensors/BleDataProcessor.java` | BLE scanning with Observer pattern |
| `sensors/WifiRttProcessor.java` | WiFi RTT (802.11mc) ranging |

---

## 8. Modified Files Summary

| File | Changes |
|------|---------|
| `traj.proto` | Added nested Vector3/Quaternion/GNSSPosition types, WiFi RTT, BLE, proximity, test points, trajectory name fields |
| `SensorFusion.java` | TestPoint class, barometer floor detection, WiFi dedup, BLE/RTT integration, nested proto builders, trajectory naming, complementary filter heading |
| `RecordingFragment.java` | PDR-WiFi fusion feedback loop, trajectory naming dialog, indoor mode argument handling |
| `TrajectoryMapFragment.java` | Indoor map display (building outlines, floor shapes, floor controls), test point markers, WiFi/GNSS polylines, radiomap collection, auto-select venue |
| `CorrectionFragment.java` | Trajectory polyline, test point markers with timestamps and floor info, start/end markers with details |
| `ServerCommunications.java` | Upload URL with campaign parameter, ID-based zip matching for download (limit 200), download records with trajectory name, Toast feedback |
| `RecordingActivity.java` | Indoor mode intent handling, skip-to-recording flow |
| `HomeFragment.java` | Indoor positioning button with GPS-based indoor detection |
| `WiFiPositioning.java` | Added lastUpdateTime tracking for freshness checking |
| `WifiDataProcessor.java` | Scan interval reduced to 1s, RTT-capable ScanResult storage |
| `MovementSensor.java` | Extract max_range and frequency from Sensor for SensorInfo proto |
| `TrajDownloadListAdapter.java` | Display trajectory name as title, ID-based download matching |
| `fragment_trajectory_map.xml` | WiFi switch, auto-floor switch, test point FAB, radiomap upload button |
| `fragment_home.xml` | Indoor positioning button styling |

---

## 9. API Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/position/fine` | POST | WiFi positioning (fingerprint → lat/lon/floor) |
| `/api/live/floorplan/request/{key}?key={master}` | POST | Indoor map data (GPS + MACs → building outlines + floor shapes) |
| `/api/live/trajectory/upload/{campaign}/{key}/?key={master}` | POST | Upload protobuf trajectory |
| `/api/live/trajectory/download/{key}?skip=0&limit=200&key={master}` | GET | Download trajectory zip |
| `/api/live/trajectory/info/{key}/?key={master}` | GET | List uploaded trajectories |
| `/api/radiomap/upload/?key={master}` | POST | Upload WiFi radiomap reference points |

**Campaign:** `murchison_house`
**User key:** `MShXCzrAnhyDauNeeP_O8g`
**Master key:** `ewireless`

---

## 10. Build & Run

```bash
# Build (from project root)
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

**Requirements:**
- Android Studio with SDK 28+
- Google Maps API key in `secrets.properties` (format: `MAPS_API_KEY=your_key_here`)
- Protobuf Gradle plugin (configured in `app/build.gradle`)

**Permissions required:**
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`
- `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`
- `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (Android 12+)
- `INTERNET`, `ACCESS_NETWORK_STATE`
- `ACTIVITY_RECOGNITION` (step detection)

---

## 11. Testing

Test points provide ground truth during recording:
1. Stand at a known position indoors
2. Tap the test point FAB → captures GPS coordinates, floor, timestamp, and WiFi fingerprint
3. Repeat at multiple known locations
4. After recording, the correction screen shows all test points with their coordinates
5. Upload includes test points in protobuf for server-side accuracy evaluation

**Data completeness verification:**
- Each trajectory should contain IMU data (30s+ duration), PDR samples, WiFi fingerprints, barometer readings, GNSS fixes, and optionally BLE/RTT data
- Server validates: IMU duration >= ~30s, magnetometer values in [-1000, 1000] μT, rotation quaternion norm ~1.0
