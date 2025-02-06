# PositionMe
Indoor poistioning data collection application created for the University of Edinburgh's Embedded Wireless course. 

## Requirements

- Android Studio 4.2 or later
- Android SDK 30 or later

## Installation

1. Clone the repository.
2. Open the project in Android Studio.
3. Add your own API key for Google Maps in AndroidManifest.xml
4. Set the website where you want to send your data. The application was built for use with openpositioning.org.
5. Build and run the project on your device.

## Usage

1. Install the application on a compatible device using Android Studio.
2. Launch the application on your device.
3. Allow sensor, location and internet permissions when asked.
4. Follow the instructions on the screen to start collecting sensor data.

## Creators

### Original contributors ([CloudWalk](https://github.com/openpositioning/DataCollectionTeam6))
- Virginia Cangelosi (virginia-cangelosi)
- Michal Dvorak (dvoramicha)
- Mate Stodulka (stodimp)

### New contributors
- Francisco Zampella (fzampella-huawei)

### Note by Group2
- Bug/Reason/Fix

1.  B: Map api key not working
    R: Var for storaging api key in secret.properties file is not called
    F: In app level build.gradle (:app), "secret" part is uncommented, and moved inside of android block.
2.  B: Crush for Android 12+ (?) version
    R: In Android, java, "PdrProcessing.java", block "weibergMinMax" (after line 222), "Collections.max(accelMagnitude)" causing crash.
    in "Collections.max(accelMagnitude)", accelMagnitude could be undefined, causing error for Collections.max()
    F: Add if statement to give accelMagnitude 0 value if it is undefined.


### Here is a test for merging and switching branches