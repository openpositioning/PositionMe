**PositionMe** is an indoor positioning data collection application initially developed for the University of Edinburgh's Embedded Wireless course. The application now includes enhanced features, including **trajectory playback**, improved UI design, and comprehensive location tracking.

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

## Requirements

- **Android Studio 4.2** or later
- **Android SDK 30** or later

## Installation

1. **Clone the repository.**
2. **Open the project in Android Studio**.
3. Add your own API key for Google Maps in AndroidManifest.xml
4. Set the website where you want to send your data. The application was built for use with [openpositioning.org](http://openpositioning.org/).
5. **Build and run the project on your Android device**.

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

## Contributors