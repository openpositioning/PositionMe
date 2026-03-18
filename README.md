# Embedded Mobile and Wireless Systems (EWireless) 5 2025/26
## Coursework 2 - Group 13

[![Android 9](https://github.com/sa-fx/ENG-EMWSE5/actions/workflows/android-9.yml/badge.svg)](https://github.com/sa-fx/ENG-EMWSE5/actions/workflows/android-9.yml) [![Android 16](https://github.com/sa-fx/ENG-EMWSE5/actions/workflows/android-16.yml/badge.svg)](https://github.com/sa-fx/ENG-EMWSE5/actions/workflows/android-16.yml)

**PositionMe** is an indoor positioning data collection application initially developed for the [University of Edinburgh's Embedded Wireless course](https://www.drps.ed.ac.uk/25-26/dpt/cxelee11083.htm). The application now includes enhanced features, including **trajectory playback**, improved UI design, and comprehensive location tracking.

## Features

- **Real-time Sensor Data Collection**: Captures sensor, location, and GNSS data.
- **Trajectory Playback**: Simulates recorded movement from previously saved trajectory files (Trajectory proto files).
- **Interactive Map Display**:
    - Visualizes the user's **PDR trajectory/path**.
    - Displays **received GNSS locations**.
    - Supports **floor changes and indoor maps** for a seamless experience.
- **Playback Controls**:
    - **Play/Pause, Exit, Restart, Jump to End**.
    - **Progress bar for tracking playback status**.
- **Redesigned UI**: Modern and user-friendly interface for enhanced usability.

## Usage

1. **Install the application** using Android Studio.
2. **Launch the application** on your Android device.
3. **Grant necessary permissions** when prompted:
    - Sensor access
    - Location services
    - Internet connectivity
4. **Collect real-time positioning data**:
    - Follow on-screen instructions to record sensor data.
5. **Replay previously recorded trajectories**:
    - Navigate to the **Files** section.
    - Select a saved trajectory and press **Play**.
    - The recorded trajectory will be simulated and displayed on the map.
6. **Control playback**:
    - Pause, restart, or jump to the end using playback controls.

## Requirements

- **Android Studio 4.2** or later
- **Android SDK 28** or later

For developers, the latest commits have been tested using the following configuration:
- [Android Studio 2024.2.1 Patch 1 ('Ladybird')](https://developer.android.com/studio/archive)
  - Android Gradle Plugin 8.7.3
  - Gradle 8.10.2
  - Google Services 4.4.2
  - [Spotless](https://github.com/diffplug/spotless) 8.3.0
- Android SDK 28 ('Android 9')

## Installation

1. Clone the repository (`git clone`).
2. Open the project in Android Studio.
3. Add your own API keys for Google Maps and OpenPositioning in `secrets.properties`.
4. Build and run the project on your Android device.

## Build

This project uses [Spotless](https://github.com/diffplug/spotless) for automatic linting against the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html). To build the project with linting, run:

```bash
$ chmod +x ./gradlew
$ ./gradlew clean build
```

This will run `spotlessCheck` as a pre-requisite for `build`. Any lint errors will cause the build to fail.

If there are lint errors, you can either resolve these manually or run:

```bash
$ ./gradlew spotlessApply
```

This will use Spotless to let you resolve the errors identified during `spotlessCheck`.
