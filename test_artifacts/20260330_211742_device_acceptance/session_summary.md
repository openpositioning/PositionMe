# Device Acceptance Session

- Start time: 2026-03-30
- Project: /Users/stevesong/AndroidStudioProjects/PositionMe-Private
- Target package: com.openpositioning.PositionMe
- Goal: Assignment 2 near-final real-device acceptance
- Status: in progress

## Phase A - Static Stability
- Marker window: PHASE_A_BEGIN 2026-03-30 21:23:06 BST to PHASE_A_END 2026-03-30 21:23:56 BST
- User action: held device stationary for 10s as instructed
- UI end state: Tracked path 20.57m, Elevation -0.6, Floor F1, Status Tracking (GNSS + WiFi), Confidence Low (0.32)
- Key observations:
  - Floor remained F1, no visible false floor switch
  - No stairs/lift activation evidence in UI or logs
  - Heading display stayed roughly stable around 103.38 deg display heading
  - Fused pose drifted from approximately (4.058,-11.152) to (-0.886,-10.317), about 5.014m
  - Tracked path increased by about 6m during supposed static hold
- Key logs:
  - 21:23:14 GNSS accepted while stationary=false -> fused pose jumped to (-1.109,-10.500)
  - 21:23:54 GNSS accepted while stationary=false -> fused pose (-1.104,-10.420)
  - 21:23:54 WiFi accepted while stationary=false -> fused pose (-0.886,-10.317)
- Verdict: FAIL

## Phase B - Straight Walk
- Marker window: PHASE_B_BEGIN 2026-03-30 21:25:30.649 BST to PHASE_B_END 2026-03-30 21:29:11.559 BST
- User action: walked forward in a straight line as instructed; motion appears intermittent early in the window and stronger from about 21:27:34 onward
- UI end state: Tracked path 14.19m, Elevation 0.4, Floor --, Status Tracking (GNSS + WiFi), Confidence Medium (0.43), Venue nucleus_building
- Key observations:
  - Fused marker, full trajectory, and recent Device/WiFi/PDR observation layers were visible on screen
  - Step acceptance and PDR propagation were active in logs during the walking interval
  - Map constraints were active with nonzero wall rejections during PDR updates
  - Floor display regressed from F1 to unknown (`Floor: --`) by phase end
  - Logs show UI/display floor disagreement near phase end: displayedFloor=0 while fused/WiFi evidence still referenced floor 1
  - Auto Floor was unexpectedly off by the end of the phase even though it had been enabled before Phase A and not intentionally disabled
  - Elevator raw detections were noisy, with repeated false-trigger rejections while walking
- Key logs:
  - 21:28:41 to 21:28:45 `SensorFusion: FUSION_DBG ... source=PDR ... wallReject=21/24/51/74`
  - 21:29:09 `SensorFusion: FUSION_DBG ... currentFloor=0 displayFloor=1 ... fused={... floor=1 ...}`
  - 21:29:09 `TrajectoryMapFragment: UI_DBG ... markerFloor=1 displayedFloor=0 fused={... floor=1 ...}`
  - 21:29:04 to 21:29:09 `POSE_DIAG ... floorConsensus=relative_only floorSource=relative_only currentAbsFloor=0 currentRelFloor=0`
  - multiple `SensorFusion: ELEVATOR:false_trigger_rejected ... raw=true strongBarometer=false nearLift=false`
- Verdict: SUSPECT

## Inter-phase Regression Notes
- 2026-03-30 21:32 BST baseline before Phase C: UI shows `Tracked path: 103.41 m`, `Floor: --`, `Confidence: High (0.92)`, `Status: Tracking (GNSS + WiFi)`
- `Auto Floor` is confirmed visible and `off` at this baseline despite no intentional user interaction with floor buttons; this will be treated as a genuine UI/state regression in the final report

## Phase C - Turn In Place
- Marker window: PHASE_C_BEGIN 2026-03-30 21:33:51.801 BST to PHASE_C_END 2026-03-30 21:36:40.911 BST
- User action: turned in place as instructed; the marker window includes a long stationary period before the visible turn sequence in logs around 21:35:54 to 21:36:20
- UI start state: Tracked path 118.53m, Elevation -0.5, Floor F1, Status Tracking (GNSS + WiFi), Confidence Low (0.17), Smooth display on, Auto Floor on
- UI end state: Tracked path 121.42m, Elevation -0.4, Floor F1, Status Tracking (GNSS + WiFi), Confidence Low (0.32), Smooth display on, Auto Floor on
- Key observations:
  - Floor display stayed on F1 throughout the phase and Auto Floor remained enabled after manual re-enable
  - Heading logs show a large net orientation change from about 107.9 deg to about 296.0 deg by phase end
  - During the rapid turn segment, marker rotation followed the device direction changes rather than reversing, but it exhibited several large jumps and wrap-around wobble while crossing north
  - Final heading settled near raw 296 deg / displayed 314 deg and remained stable over the last second
  - Even though this was an in-place turn task, tracked path still increased by about 2.89m
  - The system emitted heavy elevator false-trigger noise during turning despite no lift usage
- Key logs:
  - 21:35:54.810 `ARROW_DBG ... orientationDeg=123.848`
  - 21:35:55.345 `ARROW_DBG ... orientationDeg=65.863`
  - 21:35:58.716 `ARROW_DBG ... orientationDeg=186.666`
  - 21:36:07.914 `POSE_DIAG ... headingDeviceDeg=337.76196 ... floorConsensus=current_floor_locked ... currentAbsFloor=1`
  - 21:36:09.086 `POSE_DIAG ... headingDeviceDeg=357.26465 ... currentAbsFloor=1`
- 21:36:40.447 `ARROW_DBG ... rawDeg360=296.101 displayDeg360=314.201`
- 21:35:59 window onward: repeated `ELEVATOR:false_trigger_rejected ... raw=true strongBarometer=false nearLift=false`
- Verdict: SUSPECT

## Phase D - Wall / Boundary
- Marker window: PHASE_D_BEGIN 2026-03-30 21:38:44.456 BST to PHASE_D_END 2026-03-30 21:41:02.060 BST
- User action: moved slowly alongside a wall / corridor edge as instructed; the marker window again includes noticeable waiting time before clearer motion appears around 21:40:08 to 21:40:09
- UI start state: Tracked path 121.90m, Elevation -1.3, Floor F1, Status Tracking (GNSS + WiFi), Confidence Low (0.35), Smooth display on, Auto Floor on
- UI end state: Tracked path 192.34m, Elevation -2.1, Floor F1, Status Tracking (GNSS + WiFi), Confidence High (0.73), Smooth display on, Auto Floor on
- Key observations:
  - Floor display remained stable on F1 and Auto Floor stayed enabled
  - Logs show map-constraint evidence during the phase: repeated nonzero `wallReject` counts on fused updates and nonzero wall rejection on PDR near the actual movement window
  - During the late motion window, fused pose stayed between divergent raw observations rather than following the raw WiFi or raw PDR extremes directly
  - I did not capture explicit `illegal_transition` or nonzero `floorConstraintReject` events in this short wall-adjacent test
  - The tracked path still inflated far more than expected for a brief edge-walk test, which weakens confidence in demo quality
  - Elevator false-trigger noise continued even though this was only a wall-following movement
- Key logs:
  - 21:38:49.103 `FUSION_DBG ... source=WIFI ... fused={e=9.995,n=-21.144,floor=1,...} ... wallReject=12 floorConstraintReject=0`
  - 21:40:08.942 `step_event decision=accepted ... stepLenM=0.897 ... heightDeltaM=-0.931`
  - 21:40:08.976 `FUSION_DBG ... source=PDR ... fused={e=9.890,n=-26.455,floor=1,...} ... wallReject=1 floorConstraintReject=0`
  - 21:40:09.582 `step_event decision=accepted ... stepLenM=0.849 ... heightDeltaM=-0.953`
- 21:40:09.619 `FUSION_DBG ... source=PDR ... fused={e=9.397,n=-26.969,floor=1,...} ... wallReject=6 floorConstraintReject=0`
- phase window also contains repeated `ELEVATOR:false_trigger_rejected ... raw=true strongBarometer=false nearLift=false`
- Verdict: SUSPECT

## Phase E - Upstairs One Floor
- Marker window: PHASE_E_BEGIN 2026-03-30 21:43:50.623 BST to PHASE_E_END 2026-03-30 21:44:52.255 BST
- User action: started at stair entrance, walked upstairs one level without stopping, then paused on the upper floor
- UI start state: Tracked path 223.22m, Elevation -1.3, Floor F1, Status Tracking (GNSS + WiFi), Confidence High (1.00), Smooth display on, Auto Floor on
- UI end state: Tracked path 260.15m, Elevation 4.1, Floor F1, Status Tracking (GNSS + WiFi), Confidence High (0.87), Smooth display on, Auto Floor on
- Key observations:
  - Strong vertical-motion evidence exists during the climb: repeated accepted PDR steps with large signed `heightDeltaM` values and substantial barometric activity
  - Internal fusion logic did identify a stair-style legal floor change from floor 1 to floor 2
  - However, the user-facing UI never switched away from `Floor: F1` by the end of the phase, despite elevation rising to 4.1 and internal state reaching floor 2
  - Logs show a prolonged mismatch where fused pose/current absolute floor were already on floor 2 while `displayedFloor` remained 1
  - The transition was attributed to stairs tolerance rather than lift tolerance, which is the correct classifier direction
  - Elevator/lift noise remained extremely heavy during the stair test even though the accepted transition path was stair-based
- Key logs:
  - 21:44:04 to 21:44:08 repeated accepted PDR updates with `heightDeltaM=-1.019/-0.997/-1.089/-0.711/-1.481/-1.201` and nonzero wall rejects
  - 21:44:25.624 `Allowing strong-barometer floor transition via tolerance prevFloor=1 newFloor=2 toleranceM=4.0 liftTolerance=false stairsTolerance=true`
  - 21:44:33.602 `POSE_DIAG ... currentAbsFloor=2 currentRelFloor=1 ...`
- 21:44:34.488 `SensorFloorDiag: event=step1_initialization_floor_resolved ... floor=2 trusted=true ...`
- 21:44:34.510 `FUSION_DBG ... currentFloor=2 displayFloor=1 ... fused={... floor=2 ...}`
- 21:44:34 to 21:44:35 repeated `UI_DBG ... displayedFloor=1 fused={... floor=2 ...}`
- Verdict: FAIL

## Phase F - Downstairs One Floor
- Marker window: PHASE_F_BEGIN 2026-03-30 21:46:48.327 BST to PHASE_F_END 2026-03-30 21:47:52.743 BST
- User action: walked downstairs one level back to the original floor, then paused
- UI start state: Tracked path 22.79m, Elevation 4.1, Floor F1, Status Tracking (GNSS + WiFi), Confidence High (0.87), Smooth display on, Auto Floor on
- UI end state: Tracked path 46.42m, Elevation -1.2, Floor F1, Status Tracking (GNSS + WiFi), Confidence Medium (0.62), Smooth display on, Auto Floor on
- Key observations:
  - Strong reverse vertical-motion evidence exists and the system did attempt a legal stair-style floor transition from floor 2 back to floor 1
  - The classifier direction again points to stairs rather than lift, which is the correct semantic path for this movement
  - However, the floor-state plumbing remained inconsistent for an extended period: logs show `currentAbsFloor=1` and `displayedFloor=1` while the fused particle state still stayed on `floor=2`
  - This is not just a slow UI repaint. The phase contains repeated `FUSION_DBG` and `UI_DBG` lines where the fused estimate remains on floor 2 after the accepted 2 -> 1 transition logic has already fired
  - `floorConstraintReject` spikes into the high 90s or 100 immediately after the transition attempt, which suggests the reverse transition path is internally unstable
  - User-facing floor display ended on `F1`, but because the fused layer remained on floor 2 in logs, this phase is not demo-safe and cannot be counted as a clean legal floor-switch success
  - Elevator false-trigger noise persisted even though the accepted transition path was stair-based
- Key logs:
  - 21:47:24.542 `Rejected barometer-only fused floor sync prevFloor=2 newFloor=1`
  - 21:47:24.644 `Allowing strong-barometer floor transition via tolerance prevFloor=2 newFloor=1 toleranceM=4.0 liftTolerance=false stairsTolerance=true`
  - 21:47:24.725 `POSE_DIAG ... currentAbsFloor=1 currentRelFloor=0 ...`
  - 21:47:25.177 `FUSION_DBG ... currentFloor=1 displayFloor=1 ... fused={... floor=2 ...} ... floorConstraintReject=97`
  - 21:47:31.086 `FUSION_DBG ... currentFloor=1 displayFloor=1 ... fused={... floor=2 ...} ... wallReject=86 floorConstraintReject=100`
  - 21:47:31.108 to 21:47:31.665 repeated `UI_DBG ... displayedFloor=1 fused={... floor=2 ...}`
- Verdict: FAIL
