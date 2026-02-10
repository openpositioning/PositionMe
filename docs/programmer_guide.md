# PositionMe - Programmer's Guide
**Student Name:** [Your Name] | **Repository:** [Link to Fork]

## 1. Application Interface and Functionality Introduction (20%)
* **Architecture Overview:** [Briefly explain the app structure. [cite_start]Mention that it is based on the OpenPositioning open-source project and utilizes a Gitflow workflow[cite: 6, 41].]
* [cite_start]**Live Positioning View:** [Explain the technical implementation of the new map view using the Google Maps SDK and `SupportMapFragment`[cite: 108, 110].]
* [cite_start]**Differentiation:** [Briefly explain how your implementation differs/improves upon existing literature or the base repository[cite: 63].]

## 2. Sensor Data Management (25%)
* [cite_start]**Protobuf Updates:** [Explain how you updated the data format to the latest protobuf specifications[cite: 11].]
    * [cite_start]*Trajectory & Orientation:* [Describe implementation of trajectory naming and initial orientation[cite: 12, 17].]
    * [cite_start]*WiFi/BLE Fingerprints:* [Detail how you ensured WiFi throttling is disabled, prevented repeated fingerprints, and added WiFi RTT flags/UUIDs[cite: 13, 14, 15, 16].]
* [cite_start]**Test Points:** [Explain the logic for handling timestamped markers during recording[cite: 19, 20].]

## 3. User Information & API Usage (25%)
* [cite_start]**User Data Flow:** [Describe how user inputs (e.g., clicking a venue) translate to data collection parameters[cite: 26, 28].]
* [cite_start]**API Integration:** [Explain how the app queries the OpenPositioning API (`api/live/floorplan/request`) using the current position to fetch nearby maps[cite: 24].]
* [cite_start]**Map Rendering:** [Explain how venue outlines are drawn and how `GroundOverlay` is used to render floorplans upon selection[cite: 25, 29, 160].]

## 4. Principal Methods and Listeners (20%)
* [cite_start]**`onMapReady`:** [Explain how you initialize the Google Map, markers, and polylines[cite: 131, 133].]
* [cite_start]**`addGroundOverlay`:** [Describe the method used to render the indoor map images[cite: 163].]
* [cite_start]**Sensor Listeners:** [Detail the specific listeners used for gathering sensor data and detecting button presses for test points[cite: 4, 19].]

## 5. Coding Style (4%)
* [cite_start]**Conventions:** [Describe your adherence to modularity, naming conventions, and commenting standards used throughout the code[cite: 70].]

## 6. Extra Features (4%)
* [If you added features beyond the core requirements, list them here with a brief technical explanation. If not, remove this section to save space.]

## 7. References (2%)
* [1] Google Maps SDK Documentation.
* [2] OpenPositioning API Documentation.
* [cite_start][3] [Any specific literature regarding indoor positioning you referenced[cite: 63].]