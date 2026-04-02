# PositionMe App - Complete Architecture & Algorithm Documentation

This document describes the complete technical architecture of the PositionMe indoor positioning Android app. It covers every algorithm, data flow, parameter, and design decision. Intended as a reference for anyone who needs to understand, modify, or present this system.

---

## 1. System Overview

PositionMe is a real-time indoor positioning app that fuses multiple positioning sources using a **particle filter** to estimate the user's location inside buildings. The system operates on Android and targets the Nucleus Building and Noreen & Kenneth Murray Library at the University of Edinburgh.

### High-Level Data Flow

```
Sensors (IMU, Barometer, WiFi, GNSS)
         |
         v
   SensorFusion (data collection & synchronisation)
         |
    +----+----+----+
    |    |    |    |
    v    v    v    v
  PDR  GNSS  WiFi  KNN
  (heading + step)  (API)  (fingerprint)
    |    |    |    |
    +----+----+----+
         |
         v
   ParticleFilter (500 particles, SIR)
    - Predict: PDR displacement + noise
    - Update: GNSS/WiFi/KNN likelihood weighting
    - Map matching: wall collision + projection
    - Resample: when particle diversity drops
         |
         v
   Fused Position Estimate (weighted mean)
         |
    +----+----+
    |         |
    v         v
  Display   Protobuf Recording
  (map + UI)  (upload to server)
```

---

## 2. Coordinate System

All positioning operates in a **local East-North-Up (ENU) coordinate system** centred on the particle filter's initialisation point.

### Conversion: WGS84 <-> ENU

```
metersPerDegLat = 111320.0                    (constant)
metersPerDegLng = 111320.0 * cos(originLat)   (latitude-dependent)

toEasting(lng)  = (lng - originLng) * metersPerDegLng
toNorthing(lat) = (lat - originLat) * metersPerDegLat

toLat(northing) = originLat + northing / metersPerDegLat
toLng(easting)  = originLng + easting / metersPerDegLng
```

This is a flat-Earth approximation, accurate within ~1cm for building-scale distances (<1 km).

**File:** `ParticleFilter.java:56-73`

---

## 3. Pedestrian Dead Reckoning (PDR)

PDR estimates displacement from IMU data. It is the primary motion model for the particle filter's predict step.

### 3.1 Step Detection

The app uses Android's `TYPE_STEP_DETECTOR` sensor as the primary step detector, with a 200ms debounce to prevent double-counting the same physical step. If the step detector is silent for more than 500ms, it falls back to `TYPE_STEP_COUNTER` as a backup (capped at 3 catch-up steps per event).

**File:** `SensorEventHandler.java:176-198`

### 3.2 Heading Determination

Heading is derived from the **Game Rotation Vector** sensor (gyroscope + accelerometer, NO magnetometer), which avoids indoor magnetic field interference.

At the start of recording, a one-time offset is computed:
```
headingOffset = magnetometer_orientation[0] - gameRotationHeading
```
This aligns the gyro heading to approximate magnetic north. The offset is never updated again during recording — continuous magnetometer drift correction is explicitly disabled because indoor magnetic fields are too distorted and would pull the offset in the wrong direction.

Final heading for PDR:
```
headingForPdr = gameHeading + headingOffset
```

The heading is used directly without low-pass filtering. Constants `HEADING_LPF_ALPHA` and `smoothedHeading` are defined but unused in the current code — the game rotation vector is stable enough without additional smoothing.

**File:** `SensorEventHandler.java:159-174`

### 3.3 Step Length (Weiberg Algorithm)

Step length is estimated using the Weiberg min-max formula applied to accelerometer magnitude between consecutive steps:

```
bounce = (maxAccel - minAccel) ^ 0.25
stepLength = bounce * K * 2
```

Where `K = 0.23` (tuned constant). For a typical walking acceleration bounce of ~5 m/s^2, this produces step lengths of ~0.69 m.

If the user enables manual step length in settings, a fixed value from preferences is used instead (`default = 0.75 m`).

**File:** `PdrProcessing.java:235-262`

### 3.4 PDR Position Update

Position is accumulated in ENU metres from the recording origin (0,0):
```
adaptedHeading = PI/2 - headingRad    // Convert so 0 rad = East
positionX += stepLength * cos(adaptedHeading)
positionY += stepLength * sin(adaptedHeading)
```

**File:** `PdrProcessing.java:142-178`

### 3.5 Elevator Detection

Uses decomposed gravity and linear acceleration to detect elevator motion:
- Vertical acceleration > 0.3 m/s^2 (significant vertical movement)
- Horizontal acceleration < 0.18 m/s^2 (minimal horizontal movement)
- Both conditions evaluated over a rolling buffer of 100 samples

**File:** `PdrProcessing.java:305-346`

---

## 4. Particle Filter

The core fusion algorithm is a **Sequential Importance Resampling (SIR) Particle Filter** with 500 particles.

### 4.1 Parameters

| Parameter | Value | Description |
|-----------|-------|-------------|
| N | 500 | Number of particles |
| INIT_SPREAD_M | 5.0 m | Gaussian scatter radius at initialisation |
| PDR_NOISE_SIGMA_M | 0.3 m | Base step length noise floor |
| PDR_STEP_NOISE_RATIO | 0.05 | Additional noise as 5% of step length |
| HEADING_NOISE_RAD | 15 degrees | Per-particle heading perturbation |
| WIFI_SIGMA_M | 5.0 m | WiFi API measurement uncertainty |
| GNSS_SIGMA_DEFAULT_M | 8.0 m | Default GNSS measurement uncertainty |
| WALL_WEIGHT_FACTOR | 0.01 | Weight multiplier for wall-crossing particles (99% penalty) |
| WIFI_OUTLIER_M | 50.0 m | WiFi outlier rejection distance |
| GNSS_OUTLIER_M | 40.0 m | GNSS outlier rejection distance |

**File:** `ParticleFilter.java:22-35`

### 4.2 Initialisation

```
particleFilter.initialize(initPos)
```

1. Sets coordinate origin to `initPos` (WGS84)
2. Converts `initPos` to ENU (0,0)
3. Scatters 500 particles in a Gaussian cloud with sigma = 5.0 m around (0,0)
4. Sets all weights to 1/N
5. Random heading for each particle: uniform [0, 2*PI]

**File:** `ParticleFilter.java:79-114`

### 4.3 Predict Step (PDR Motion Model)

Called every 200ms with PDR displacement `(dxMeters, dyMeters)`:

For each particle i:
1. Compute PDR heading: `pdrHeading = atan2(dy, dx)`
2. Add heading noise: `particleHeading[i] = pdrHeading + N(0, 15 degrees)`
3. Compute noisy step length: `noisyStep = max(0, stepLen + N(0, max(0.3, stepLen * 0.05)))`
4. Update position:
   ```
   east[i]  += noisyStep * cos(particleHeading[i])
   north[i] += noisyStep * sin(particleHeading[i])
   ```
5. **Wall collision check:** If the particle's movement crosses any wall segment:
   - Project particle onto nearest point on the wall
   - Multiply weight by 0.01 (99% penalty)
6. Normalize weights and resample if ESS < N/2

**File:** `ParticleFilter.java:137-183`

### 4.4 Update Step (Measurement Likelihood)

When a new GNSS, WiFi, or KNN measurement arrives:

1. **Outlier check:** If distance from current estimate to measurement > threshold, skip
2. **Wall blocking check:** If line from estimate to measurement crosses a wall, skip
3. **Apply Gaussian likelihood** to each particle:
   ```
   d2 = (east[i] - measE)^2 + (north[i] - measN)^2
   weight[i] *= exp(-0.5 * d2 / sigma^2)
   ```
4. Normalize weights
5. Resample if ESS < N/2

Measurement sigmas:
- KNN WiFi: 3.0 m (tightest, most accurate)
- WiFi API: 5.0 m
- GNSS outdoor: actual reported accuracy
- GNSS indoor: max(reported accuracy, 15.0 m) — clamped to reduce indoor pull

**File:** `ParticleFilter.java:185-242`

### 4.5 Position Estimate

Weighted mean of all particles:
```
estimateE = sum(weight[i] * east[i])
estimateN = sum(weight[i] * north[i])
```

The estimate is also wall-checked: if the new estimate would cross a wall from the previous estimate, it is projected back onto the nearest wall.

**File:** `ParticleFilter.java:244-265`

### 4.6 Resampling

**Method:** Residual resampling (Algorithm 2, Arulampalam et al.)

**Trigger:** When Effective Sample Size < N/2 = 250

```
ESS = 1 / sum(weight[i]^2)
```

Residual resampling:
1. Each particle gets `floor(N * weight[i])` deterministic copies
2. Remaining slots filled stochastically from residual weights
3. All resampled particles reset to weight = 1/N

**File:** `ParticleFilter.java:277-325`

---

## 5. KNN WiFi Positioning

A K-Nearest Neighbours algorithm that matches live WiFi scans against pre-collected fingerprint data.

### 5.1 Training Data

- Stored in `assets/knn_data/wifi_fingerprints.json`
- Each entry: `{lat, lng, floor, aps: [{bssid, rssi, ssid}, ...]}`
- Currently 351 fingerprints across Nucleus GF, F1, F2, F3, and Basement
- Collected by long-pressing on the map in the Start Location screen

### 5.2 Algorithm

1. Get current WiFi scan results from Android `WifiManager.getScanResults()`
2. Build RSSI vector: `{bssid -> rssi}` for all visible APs
3. For each training fingerprint, compute Euclidean distance:
   ```
   allKeys = currentScan.keys UNION fingerprint.keys
   distance = sqrt(sum over allKeys of (rssi_current - rssi_fingerprint)^2)
   ```
   Missing APs are filled with -100 dBm
4. Sort all fingerprints by distance
5. Take K=3 nearest, compute inverse-distance weighted average:
   ```
   weight = 1 / (distance + 1e-6)
   result = sum(weight * position) / sum(weight)
   ```

### 5.3 Floor-Specific Mode

`estimatePositionForFloor(floor)` filters training data by floor index before running KNN. Used for floor-specific positioning when the current floor is known.

**File:** `KnnPositioner.java`

---

## 6. Calibration & Initialisation

### 6.1 Calibration Phase

When recording starts, a 9-second calibration phase begins:

| Parameter | Value |
|-----------|-------|
| CALIBRATION_TIMEOUT_MS | 9000 ms |
| CALIBRATION_SAMPLES | 3 (minimum target) |

During calibration:
- PDR displacement is frozen (previousPosX/Y updated but not accumulated)
- KNN/WiFi position samples are collected
- GNSS position samples are collected separately
- Calibration ends when 3 samples collected OR 9 seconds elapsed

### 6.2 Source Selection (Automatic)

```
if (GNSS accuracy < 15m):
    initPos = average(GNSS samples)      // outdoor/entrance
else if (KNN/WiFi samples available):
    initPos = average(KNN/WiFi samples)  // indoor
else if (GNSS available):
    initPos = average(GNSS samples)      // fallback
else:
    abort (no position available)
```

No user selection is involved.

### 6.3 Re-initialisation from GNSS

If PF was initialised from poor GNSS and a better KNN/WiFi fix arrives later:
```
particleFilter.reinitializeAround(betterPos, 5.0m)
```
This re-scatters all particles around the better position.

### 6.4 KNN Warmup Period

For the first 15 seconds after PF initialisation, KNN sigma is loosened from 3.0m to 15.0m. This prevents stale WiFi scan cache from pulling the estimate back to the starting position.

```
KNN_WARMUP_MS = 15000
sigma = (sinceInit < 15s) ? 15.0 : 3.0
```

**File:** `RecordingFragment.java:338-420, 473-483`

---

## 7. Map Matching

### 7.1 Indoor Map Data

Building floor plans are loaded from a floor plan API. Each floor contains features with an `indoor_type`:

| Type | Description | Effect on Particles |
|------|-------------|-------------------|
| `"wall"` | Areas that cannot be crossed | Particles crossing walls are projected back + 99% weight penalty |
| `"room"` | Room boundaries (also walls) | Same as wall |
| `"stairs"` | Staircase zones | PDR step scaled to 20% when elevation changing |
| `"lift"` | Elevator zones | PDR step scaled to 0% when elevation changing |

### 7.2 Wall Collision Detection

Uses 2D line-segment intersection (cross-product method):
```
For each particle movement (oldE,oldN) -> (newE,newN):
    For each wall segment (wallE1,wallN1,wallE2,wallN2):
        if segments_intersect:
            project particle onto nearest point on wall
            weight *= 0.01
```

Also applied to the weighted mean estimate: if the new estimate would cross a wall from the previous estimate, it is snapped back.

Also applied to measurements: if the line from the current estimate to a measurement crosses a wall, the measurement is skipped entirely.

**File:** `ParticleFilter.java:159-167, 327-390`

### 7.3 Floor Transition Detection

Two conditions must BOTH be true:
1. User is within 5m of a stairs/lift transition zone (`getNearestTransitionZoneType`)
2. Barometer detects elevation change > 0.3m per 200ms tick

| Zone Type | Step Scale | Meaning |
|-----------|-----------|---------|
| `"lift"` + elevation changing | 0.0 | No horizontal movement (elevator) |
| `"stairs"` + elevation changing | 0.20 | 20% horizontal movement (stairs) |
| Neither / no elevation change | 1.0 | Normal walking |

**File:** `RecordingFragment.java:436-457`

### 7.4 Automatic Floor Detection

The Auto Floor system uses:
- WiFi-based floor detection from the indoor map manager
- Barometric elevation tracking relative to the start elevation
- Floor height constants: Nucleus 4.2m, Library 3.6m, Murchison 4.0m

When Auto Floor is enabled, the floor label updates automatically and wall segments are refreshed for the new floor. Manual floor buttons disable Auto Floor.

**File:** `IndoorMapManager.java`

---

## 8. Stuck Detection & Recovery

If the particle filter estimate stops moving while PDR indicates walking, particles are likely trapped behind walls.

### Detection
```
Every 200ms tick:
    if (PDR step distance > 0.2m AND estimate moved < 0.1m):
        stuckCounter++
    else:
        stuckCounter = 0

    if (stuckCounter >= 8):   // ~1.6 seconds stuck
        reinitialize particles around best KNN/WiFi position with 5m spread
        stuckCounter = 0
```

**File:** `RecordingFragment.java:490-510`

---

## 9. Display & UI

### 9.1 Position Display

The red arrow marker shows the particle filter's weighted mean estimate, updated every 200ms.

### 9.2 Smooth Data Filter (Toggle Switch)

When enabled, applies three layers:

1. **EMA (Exponential Moving Average)** on position data:
   ```
   displayLat = 0.35 * newLat + 0.65 * previousDisplayLat
   displayLng = 0.35 * newLng + 0.65 * previousDisplayLng
   ```
   Reduces single-frame jitter.

2. **Catmull-Rom Spline Interpolation** on trajectory polyline:
   - 5 subdivision points between each pair of raw trajectory points
   - Produces smooth curves through all original points
   - Converts sharp corners into natural arcs

3. **Animation Interpolation** on marker movement:
   - 180ms linear interpolation from old position to new position
   - Applied regardless of smooth toggle

**File:** `TrajectoryMapFragment.java:69-75, 397-465, 489-535`

### 9.3 Colour-Coded Observation Dots

Toggle switch "P W G" shows the last 15 observations:
- **Yellow** (P): PDR positions — `argb(200, 230, 180, 0)`
- **Green** (W): WiFi API positions — `argb(180, 0, 180, 0)`
- **Blue** (G): GNSS positions — `argb(180, 33, 100, 243)`

Each dot is a 0.5m radius circle on the map. Oldest dots are removed when count exceeds 15.

**File:** `TrajectoryMapFragment.java:604-630`

### 9.4 Particle Cloud Visualisation

Toggle switch "Particles" shows 80 randomly sampled particles as red circles, updated every 1 second to maintain performance.

### 9.5 Other Controls

| Control | Function |
|---------|----------|
| Show GNSS | Blue marker + polyline for raw GNSS path |
| WiFi | Green marker for WiFi API position |
| Auto Floor | Automatic floor detection on/off |
| Color | Toggle trajectory line red/black |
| Normal/Hybrid/Satellite | Map type selector |
| Floor Up/Down | Manual floor change (disables Auto Floor) |
| Test Point (+) | Place numbered ground truth marker |
| Info (i) | Help dialog |
| Cancel | Discard recording (with confirmation) |
| Complete | Finish recording, go to upload |

---

## 10. Data Recording & Upload

### 10.1 Recording Format

Data is stored in Protocol Buffers (protobuf) format using the `Traj.Trajectory` schema.

Recorded fields:
- `imu_data`: accelerometer, gyroscope, rotation vector, step count (50 Hz max, throttled)
- `pdr_data`: cumulative X/Y displacement in metres
- `gnss_data`: WGS84 lat/lng with accuracy, speed, bearing
- `wifi_data`: WiFi scan results with BSSIDs and RSSIs
- `pressure_data`: barometric pressure
- `magnetometer_data`: magnetic field vector
- `light_data`, `proximity_data`: ambient sensors
- `initial_position`: starting WGS84 coordinates
- `corrected_positions`: fused positions (if written)
- `test_points`: user-placed ground truth markers with timestamps

### 10.2 IMU Frequency Control

The recording timer runs at `TIME_CONST = 20ms` intervals, but a hard throttle ensures IMU writes are spaced at least 20ms apart (50 Hz maximum). This prevents Android Timer jitter from exceeding the server's frequency limit.

```java
if (currentTime - lastImuWriteTime < 20ms) return; // skip this tick
```

**File:** `TrajectoryRecorder.java:35, 548-560`

### 10.3 Upload

Trajectories are uploaded to the openpositioning API as protobuf binary. The server validates IMU frequency and rejects data exceeding its limit.

---

## 11. Building Detection & Indoor Maps

### 11.1 Building Detection

The app automatically detects which building the user is in based on GPS coordinates. Building boundaries are defined as polygons.

| Building | Code | Floor Height |
|----------|------|-------------|
| Nucleus | 1 | 4.2 m |
| Library | 2 | 3.6 m |
| Murchison | 3 | 4.0 m |

A "sticky radius" of 25m keeps the indoor map loaded even if GPS briefly places the user outside the building boundary (which happens frequently indoors due to GPS inaccuracy).

### 11.2 Floor Plans

Each building floor has:
- Wall/room boundaries (line segments for collision detection)
- Stairs zones (polygons)
- Lift zones (polygons)

Wall segments are converted from WGS84 to the particle filter's local ENU coordinates when loaded.

**File:** `IndoorMapManager.java`

---

## 12. Complete Parameter Reference

### Particle Filter
| Parameter | Value | File:Line |
|-----------|-------|-----------|
| Particles | 500 | ParticleFilter.java:23 |
| Init spread | 5.0 m | ParticleFilter.java:24 |
| PDR noise floor | 0.3 m | ParticleFilter.java:25 |
| PDR noise ratio | 5% | ParticleFilter.java:26 |
| Heading noise | 15 deg | ParticleFilter.java:27 |
| WiFi sigma | 5.0 m | ParticleFilter.java:28 |
| GNSS sigma default | 8.0 m | ParticleFilter.java:29 |
| Wall weight penalty | 0.01 | ParticleFilter.java:30 |
| WiFi outlier | 50.0 m | ParticleFilter.java:32 |
| GNSS outlier | 40.0 m | ParticleFilter.java:33 |

### KNN
| Parameter | Value | File:Line |
|-----------|-------|-----------|
| K neighbours | 3 | KnnPositioner.java:32 |
| Default RSSI | -100 dBm | KnnPositioner.java:33 |
| KNN sigma | 3.0 m | RecordingFragment.java:109 |
| KNN warmup | 15 s | RecordingFragment.java:101 |
| KNN warmup sigma | 15.0 m | RecordingFragment.java:479 |

### Calibration
| Parameter | Value | File:Line |
|-----------|-------|-----------|
| Timeout | 9000 ms | RecordingFragment.java:126 |
| Min samples | 3 | RecordingFragment.java:125 |
| GNSS reliable threshold | < 15 m | RecordingFragment.java:385 |
| Indoor GNSS min sigma | 15.0 m | RecordingFragment.java:464 |

### Stairs/Lift
| Parameter | Value | File:Line |
|-----------|-------|-----------|
| Elevation rate threshold | 0.3 m/tick | RecordingFragment.java:121 |
| Stair step scale | 0.20 | RecordingFragment.java:122 |
| Lift step scale | 0.0 | RecordingFragment.java:449 |
| Transition zone radius | 5.0 m | IndoorMapManager.java |

### PDR
| Parameter | Value | File:Line |
|-----------|-------|-----------|
| Weiberg K | 0.23 | PdrProcessing.java:31 |
| Movement threshold | 0.3 m/s^2 | PdrProcessing.java:37 |
| Stillness epsilon | 0.18 m/s^2 | PdrProcessing.java:39 |
| Heading LPF alpha | 0.85 (defined but unused) | SensorEventHandler.java:29 |
| Step debounce | 200 ms | SensorEventHandler.java:206 |
| Step fallback timeout | 500 ms | SensorEventHandler.java:48 |

### Stuck Detection
| Parameter | Value | File:Line |
|-----------|-------|-----------|
| PDR moving threshold | 0.2 m | RecordingFragment.java:492 |
| Estimate not-moving | < 0.1 m | RecordingFragment.java:497 |
| Stuck counter threshold | 8 ticks (~1.6s) | RecordingFragment.java:117 |
| Reinit spread | 5.0 m | RecordingFragment.java:502 |

### Display
| Parameter | Value | File:Line |
|-----------|-------|-----------|
| EMA alpha | 0.35 | TrajectoryMapFragment.java:72 |
| Spline subdivisions | 5 | TrajectoryMapFragment.java:75 |
| Animation duration | 180 ms | TrajectoryMapFragment.java:444 |
| Max observation dots | 15 | TrajectoryMapFragment.java:79 |
| Dot radius | 0.5 m | TrajectoryMapFragment.java |
| Camera resume delay | 5000 ms | TrajectoryMapFragment.java:386 |
| Particle draw interval | 1000 ms | TrajectoryMapFragment.java |
| UI update frequency | 200 ms | RecordingFragment.java:138 |

### Recording
| Parameter | Value | File:Line |
|-----------|-------|-----------|
| IMU write interval | 20 ms (50 Hz max) | TrajectoryRecorder.java:35 |
| IMU hard throttle | 20 ms min gap | TrajectoryRecorder.java:548 |
| Accel filter coefficient | 0.96 | TrajectoryRecorder.java:36 |

---

## 13. File Structure

```
fusion/
  ParticleFilter.java      - SIR particle filter (predict, update, resample, map matching)
  KnnPositioner.java        - KNN WiFi fingerprint positioning

sensors/
  SensorFusion.java         - Singleton sensor data aggregator
  SensorEventHandler.java   - IMU event processing, heading calibration, step detection
  SensorState.java          - Shared volatile sensor state
  TrajectoryRecorder.java   - Protobuf recording & server upload

utils/
  PdrProcessing.java        - PDR step length, elevation, floor, elevator detection
  IndoorMapManager.java     - Building detection, floor plans, wall/stair/lift features
  UtilFunctions.java        - Coordinate conversion helpers
  WifiFingerprinter.java    - WiFi fingerprint collection tool

presentation/fragment/
  RecordingFragment.java    - Main recording loop: calibration, PF orchestration, display
  TrajectoryMapFragment.java - Map display, toggles, polyline, markers, smooth filter
  StartLocationFragment.java - Pre-recording: building selection, fingerprint collection
  CorrectionFragment.java   - Post-recording: review & upload
  ReplayFragment.java        - Trajectory replay

assets/knn_data/
  wifi_fingerprints.json    - Merged KNN training data (351 fingerprints)
  wifi_fingerprintsGF.json  - Ground floor fingerprints
  wifi_fingerprintsF1.json  - Floor 1 fingerprints
  wifi_fingerprintsF2.json  - Floor 2 fingerprints
  wifi_fingerprintsNucluesF3.json - Floor 3 fingerprints
  wifi_fingerprintsNucleusBF.json - Basement fingerprints
```

---

## 14. Accuracy Results

Tested with trajectory 3677 (Nucleus F2, 4 test points against surveyed ground truth):

| Method | Mean Error | RMSE | CEP95 |
|--------|-----------|------|-------|
| **App (fused)** | **0.71 m** | **0.92 m** | **1.65 m** |
| GNSS | 9.94 m | 10.16 m | 11.79 m |
| PDR only | 18.50 m | 18.55 m | 19.92 m |

- App vs GNSS improvement: **+91%**
- App vs PDR improvement: **+95%**
