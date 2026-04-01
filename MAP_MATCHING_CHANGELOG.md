y# Map Matching Change Log (branch snapshot)

This file tracks code additions and modifications versus `main` for the map-matching/floor logic. Update it whenever related code changes.

## Added files
- `app/src/main/java/com/openpositioning/PositionMe/utils/MapMatchingConfig.java` — centralizes thresholds (baro, lift/stairs displacement, wall padding, feature proximity).
- `app/src/main/java/com/openpositioning/PositionMe/utils/CrossFloorClassifier.java` — pure function to label cross-floor moves as LIFT/STAIRS/UNKNOWN.
- `app/src/main/java/com/openpositioning/PositionMe/utils/WallCollisionCorrector.java` — segment-intersection check; blocks steps that cross wall segments.
- `app/src/test/java/com/openpositioning/PositionMe/utils/MapMatchingConfigTest.java` — verifies default/custom config values.
- `app/src/test/java/com/openpositioning/PositionMe/utils/CrossFloorClassifierTest.java` — tests lift/stairs/unknown boundaries.
- `app/src/test/java/com/openpositioning/PositionMe/utils/WallCollisionCorrectorTest.java` — tests wall-crossing block and pass-through.

## Modified files
- `MAP_MATCHING_CONSTRAINTS.md` — translated to English; added rules for minimal diffs, English comments, config centralization, and checklists.
- `app/src/main/java/com/openpositioning/PositionMe/utils/PdrProcessing.java` — accepts `MapMatchingConfig`; aligns default floor height with config; clarified units; applies `WallCollisionCorrector` when walls are provided via `setWalls(...)`.
- `app/src/main/java/com/openpositioning/PositionMe/presentation/fragment/TrajectoryMapFragment.java` — baro floor switch now requires height threshold and proximity to stairs/lift; fallback to config floor height when metadata missing.
- `app/src/main/java/com/openpositioning/PositionMe/utils/IndoorMapManager.java` — tracks last location; adds `isNearCrossFloorFeature` proximity helper and `getCurrentFloorShape`; clarifies floor height units.

## Open tasks (to keep in sync with code)
- [x] Wire `WallCollisionCorrector` using actual wall geometry from floorplan data (convert GeoJSON to meter-based polylines and align frames).
- [x] Log `CrossFloorClassifier` results for floor switches (lift vs stairs) to aid device validation.
- [ ] Device-test barometric floor switching with current thresholds; tune `crossFeatureProximity`/`baroHeightThreshold` if needed.
