# PositionMe — Indoor Positioning System

An Android indoor positioning application developed for the University of Edinburgh's Embedded Wireless (EWireless) course, Assignment 2. The app fuses multiple sensor sources through a particle filter to provide real-time indoor navigation with metre-level accuracy.

## Key Features

### Positioning Fusion
- **500-particle SIR Particle Filter** combining PDR, WiFi API, GNSS, and local calibration database
- **Weighted K-Nearest Neighbours (WKNN)** fingerprint matching against 395 collected reference points (K=10, floor-aware)
- **10-second initial calibration** using multi-source weighted averaging (CalDB × 3.0, WiFi × 1.0, GNSS × 1.0)
- **Adaptive heading bias correction** with early-phase (15 steps, ±5°/step) and normal-phase (EMA α=0.10) modes
- **ENU coordinate system** (CoordinateTransform) for all fusion computations

### Map Matching
- **Wall constraint enforcement** via axis-aligned segments from Floorplan API GeoJSON data
- **Stairs step reduction** (×0.5 horizontal displacement) when inside stairs polygons
- **Floor change spatial gating** — transitions only allowed within 5m of stairs/lift areas
- **Elevator vs stairs classification** using barometric elevation change rate
- **3-second debounce** on floor transitions to prevent jitter

### Data Display
- **Three trajectory paths**: PDR (red), Fused (purple), Smooth (green, moving average window=40)
- **Colour-coded observation markers**: GNSS (cyan, last 3), WiFi (green, last 3), Fused (red, last 10)
- **Estimated position marker** (grey) showing forward-120° reference point weighted average
- **WiFi signal quality indicator** (3-bar, based on CalDB match quality)
- **Compass and weather overlays**
- **Collapsible toolbar** with 10 toggle switches and map style selector (Hybrid/Normal-Pink/Satellite)

### Indoor Positioning Mode
- One-tap entry from home screen with auto building detection
- Auto trajectory naming, auto start location, auto recording
- Indoor-optimised defaults (compass, weather, smooth path, auto floor, navigating ON)
- `forceSetBuilding()` bypasses polygon detection for immediate indoor map loading

### Recording & Upload
- Protobuf trajectory with IMU, PDR, WiFi, GNSS, BLE, barometer, and test points
- IMU timestamp rescaling for server frequency compliance (≥50Hz)
- Sensor info fields (max_range, frequency) included in protobuf
- Post-recording correction screen with trajectory review and map type toggle

## Technical Details

- **Target SDK**: 35
- **Min SDK**: 28
- **Language**: Java 17
- **Code Style**: Google Java Style Guide
- **Unit Tests**: 32 JVM-based tests (JUnit 4)
- **Debug Control**: Compile-time `BuildConstants.DEBUG` flag eliminates all debug logging in release

## Building

1. Clone the repository
2. Add your API keys to `secrets.properties`:
   ```
   MAPS_API_KEY=your_google_maps_key
   OPENPOSITIONING_API_KEY=your_openpositioning_key
   OPENPOSITIONING_MASTER_KEY=your_master_key
   ```
3. Open in Android Studio and sync Gradle
4. Build and run on a physical device (sensors required)

## Architecture

See `Programmers_Guide.md` for detailed system architecture, data flow diagrams, and design decisions.
