# Branch Change Summary

## Scope
- Base merge commit: `591e07c05682e9acccd974b5bb6e4ca049c20a9b` (latest merge by fzampella)
- Current HEAD: `8959b1b`
- Compared range: `591e07c..8959b1b`

## Overall Stats
- Commits (no merges): 25
- Files changed: 36
- Insertions: 2003
- Deletions: 183

## Biggest Files By Churn
- `app/src/main/java/com/openpositioning/PositionMe/sensors/PositionFusionEngine.java`: +883 / -0
- `app/src/main/java/com/openpositioning/PositionMe/presentation/fragment/TrajectoryMapFragment.java`: +237 / -21
- `app/src/main/java/com/openpositioning/PositionMe/utils/IndoorMapManager.java`: +121 / -6
- `app/src/main/res/layout/fragment_trajectory_map.xml`: +97 / -1
- `app/src/main/java/com/openpositioning/PositionMe/sensors/SensorFusion.java`: +92 / -1
- `app/src/main/java/com/openpositioning/PositionMe/sensors/SensorEventHandler.java`: +86 / -1
- `app/src/main/java/com/openpositioning/PositionMe/sensors/WifiPositionManager.java`: +85 / -3
- `app/src/main/java/com/openpositioning/PositionMe/sensors/WiFiPositioning.java`: +73 / -57

## High-Level Changes

### 1. Particle filter fusion added
- New core files:
  - `app/src/main/java/com/openpositioning/PositionMe/sensors/PositionFusionEngine.java`
  - `app/src/main/java/com/openpositioning/PositionMe/sensors/PositionFusionEstimate.java`
- Integrated into sensor pipeline:
  - `app/src/main/java/com/openpositioning/PositionMe/sensors/SensorFusion.java`
  - `app/src/main/java/com/openpositioning/PositionMe/sensors/SensorEventHandler.java`
  - `app/src/main/java/com/openpositioning/PositionMe/sensors/SensorState.java`

#### Nature of this change
- Added a full local-coordinate particle filter pipeline (predict/update/resample/smooth).
- Introduced explicit fusion estimate object (`PositionFusionEstimate`) to decouple algorithm internals from UI consumers.
- Reworked sensor flow so PDR displacement, GNSS fixes, WiFi fixes, and elevation/floor cues are all fused in one state machine.

### 2. Map matching and indoor constraints
- Indoor wall/connector constraints and building-floor parsing integrated into fusion.
- Related indoor overlay updates:
  - `app/src/main/java/com/openpositioning/PositionMe/utils/IndoorMapManager.java`

#### Nature of this change
- Added building-aware map context ingestion from floorplan API features.
- Enforced wall crossing checks in prediction to keep trajectories topologically plausible indoors.
- Added connector-aware floor transitions (stairs/lifts) rather than unconstrained floor jumps.
- Included iterative tuning/rollback around floor support injection and GNSS downweight strategy.

### 3. Recording/replay trajectory path changes
- Replay parsing and trajectory-source behavior updated:
  - `app/src/main/java/com/openpositioning/PositionMe/data/local/TrajParser.java`
- Recording and sensor write path touched:
  - `app/src/main/java/com/openpositioning/PositionMe/sensors/TrajectoryRecorder.java`
  - `app/src/main/java/com/openpositioning/PositionMe/sensors/SensorFusion.java`

#### Nature of this change
- Shifted replay behavior toward corrected/fused trajectory support for higher-fidelity playback.
- Kept compatibility path for legacy recordings that only contain PDR/GNSS structures.
- Clarified separation between live fused display, correction view behavior, and replay parsing path.

### 4. Trajectory map visualization improvements
- GNSS/WiFi/PDR observation rendering, auto-floor behavior, and map interaction updates:
  - `app/src/main/java/com/openpositioning/PositionMe/presentation/fragment/TrajectoryMapFragment.java`
  - `app/src/main/res/layout/fragment_trajectory_map.xml`
- New UI resources for legend/spinner visuals:
  - `app/src/main/res/drawable/bg_map_switch_spinner.xml`
  - `app/src/main/res/drawable/legend_circle_blue.xml`
  - `app/src/main/res/drawable/legend_circle_green.xml`
  - `app/src/main/res/drawable/legend_circle_orange.xml`
  - `app/src/main/res/layout/item_map_type_spinner.xml`
  - `app/src/main/res/layout/item_map_type_spinner_dropdown.xml`

#### Nature of this change
- Added richer visual telemetry layers (GNSS points, WiFi/PDR feedback markers, legends).
- Introduced auto-floor defaulting and related UX flow to reduce manual floor switching overhead.
- Refined map controls and spinner styling for readability and theme compatibility.

### 5. Theme system and app-wide appearance
- New helper:
  - `app/src/main/java/com/openpositioning/PositionMe/utils/ThemePreferences.java`
- Theme and preference wiring updates:
  - `app/src/main/java/com/openpositioning/PositionMe/presentation/activity/MainActivity.java`
  - `app/src/main/java/com/openpositioning/PositionMe/presentation/activity/RecordingActivity.java`
  - `app/src/main/java/com/openpositioning/PositionMe/presentation/activity/ReplayActivity.java`
  - `app/src/main/java/com/openpositioning/PositionMe/presentation/fragment/SettingsFragment.java`
  - `app/src/main/res/values/themes.xml`
  - `app/src/main/res/values-night/themes.xml`
  - `app/src/main/res/xml/root_preferences.xml`
  - `app/src/main/res/values/arrays.xml`
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/AndroidManifest.xml`

#### Nature of this change
- Migrated from simple dark toggle patterns to full theme-mode control including system default.
- Removed hardcoded light-only visual elements that caused UI inconsistencies.
- Propagated theme handling across main, recording, replay, toolbar, and preference surfaces.

### 6. Home screen cleanup
- Indoor positioning button removal and related layout cleanups:
  - `app/src/main/java/com/openpositioning/PositionMe/presentation/fragment/HomeFragment.java`
  - `app/src/main/res/layout/fragment_home.xml`
  - `app/src/main/res/layout-small/fragment_home.xml`

#### Nature of this change
- Simplified entry navigation and removed dead-end/placeholder interaction path.
- Cleaned remaining layout constraints and references after button removal.

## Behavioral Before/After (Net)

### Localization/Fusion
- Before: stronger reliance on PDR + discrete sensor paths, less explicit fusion architecture.
- After: unified particle-filter fusion layer with map context and floor logic, consumed by recording UI.

### Indoor Constraints
- Before: limited geometric indoor constraints in final path.
- After: walls/connectors parsed and enforced in fusion prediction/floor transitions.

### Replay Fidelity
- Before: replay primarily followed parsed PDR path.
- After: replay path logic evolved toward corrected/fused support (with backward compatibility fallback).

### Recording UX
- Before: more basic trajectory visualization controls.
- After: richer observation overlays, legend support, and auto-floor usability changes.

### App Appearance
- Before: mixed hardcoded/light-biased UI states.
- After: centralized theme preference model and app-wide theme consistency.

## Chronological Evolution (Commit Groups)

### Phase A: Fusion foundation
- `256c94d`, `1c260e9`, `8a8afd8`
- Set up PF scaffolding, diagnostics/logging, then map matching introduction.

### Phase B: Display and diagnostics iteration
- `4d920dc`, `d1a91e3`, `f385387`, `a133ba4`, `1bb0d48`, `ada43df`
- Added and refined displayed telemetry, GNSS point visibility, ordering/cleanup.

### Phase C: Motion/orientation and feedback loops
- `21e62fd`, `6b49717`, `f391336`, `dea8a9d`
- Orientation handling revisions and PDR feedback experiments.

### Phase D: Weighting/injection tuning
- `62ee107`, `a90de60`, `52ed55a`, `7133efb`, `6700ca2`, `6e77cd1`
- Multiple tuning passes for GNSS weighting, smoothing, and floor-injection strategy.
- Final net state removes floor injection; GNSS behavior ends on source/accuracy-based handling.

### Phase E: UX polish and productization
- `b018151`, `68052e9`, `34edc9f`, `39be0e5`, `58197cc`, `8959b1b`
- Auto-floor default, local PDR updates, theming, home cleanup, replay corrected-path support, and documentation/comments.

## Net-Effect Notes
- Floor injection appeared in intermediate commits and was later removed; current branch state has it removed.
- GNSS downweight behavior changed during branch history; current net behavior is source/accuracy driven rather than explicit indoor-only downweighting.
- A meaningful portion of churn is iterative tuning rather than brand-new feature area expansion.
- The branch combines algorithmic changes (fusion/map matching) and substantial UI/UX product work (themes, controls, overlays).

## Commit Subjects In Range
- `8959b1b` added comments
- `58197cc` replay with corrected pos
- `6e77cd1` removed floor injection
- `39be0e5` removed indoor positioning button
- `34edc9f` dark mode implemented, including system default
- `68052e9` local pdr updates
- `b018151` autofloor is on by default
- `6700ca2` removed redundant pdr feedback
- `7133efb` Removed GNSS downweight again
- `52ed55a` re-added gnss downweighting
- `a90de60` added a smoothing output filter
- `62ee107` Removed GNSS downweighting and Floor injection support
- `dea8a9d` using francisco suggested method of orientation
- `f391336` feedback turned off for now, added legend for data points
- `6b49717` added pdr feedback from fusion
- `21e62fd` orientation handler
- `ada43df` ordering fixed
- `1bb0d48` Showing GNSS points
- `a133ba4` further logging and fixing the bestFloor functionality
- `f385387` different indoor map colour for clarity
- `d1a91e3` data display v2
- `4d920dc` dummy data display
- `8a8afd8` Map matching
- `1c260e9` Logging to test PF
- `256c94d` dummy sensing fusion
