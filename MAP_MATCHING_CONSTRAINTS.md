# Map Matching Constraints (Java project)

Rules to follow when implementing or modifying indoor map matching and floor estimation. Review this file before any change touching positioning, motion model, or map data.

## Scope
- New or changed logic for position estimation, floor estimation, trajectory correction, or sensor fusion (IMU, barometer).
- Reading, storing, or updating building map data with `wall` / `stairs` / `lift` features.

## Core data assumptions
- Map is layered by floor; each floor has its own feature set. Feature types are limited to `wall`, `stairs`, `lift`.
- Coordinate system and units must stay consistent with existing map data (typically meters). Always state units and reference frame.
- `wall`: impassable area; corrected paths must not intersect wall geometry.
- `stairs`: combine horizontal displacement with height change; horizontal step length must be respected.
- `lift`: height change with near‑zero horizontal displacement or constrained inside the elevator shaft footprint.

## Motion model and wall constraints
- Predict position with the motion model first; if the prediction intersects a `wall`, apply collision correction (projection/slide/constraint inside walkable polygon).
- No teleport through walls: if a step crosses a wall, truncate or slide along the wall instead of jumping past it.
- Wall handling must work with polygons/segments; do not assume rectangles.

## Floor switching with barometer
- Allow floor changes only when the current position is near a `stairs` or `lift` feature **and** the barometric height delta exceeds the configured threshold.
- Thresholds must come from project configuration; if hardcoded, place them in a single config file and document the source.
- If no nearby cross‑floor feature exists, ignore height changes and mark the reading as low confidence instead of switching floors.

## Distinguish lift vs stairs
- Using motion cues:
  - Horizontal displacement < `liftHorizontalMax` with significant height change → lift event.
  - Horizontal displacement ≥ `stairsHorizontalMin` with stair‑height accumulation → stairs event.
- Record every event with start/end time, height delta, horizontal distance, and candidate feature ID.
- When both fit, pick the type whose feature is geometrically closer.

## Error handling and fallback
- On sensor anomalies (pressure spikes, IMU saturation), keep the last trusted state and mark the current estimate as low confidence; do not write permanent tracks.
- If map lookup fails or is missing, do not disable wall checks; provide a degraded but explainable path (motion model only, flagged unreliable).

## Implementation requirements (Java)
- New classes/methods must have unit tests covering: wall crossing correction, floor change rejection, and lift vs stairs boundary cases.
- Use existing geometry libraries where possible; new libs require license/size review and must be recorded in dependency notes.
- Centralize parameters (e.g., `config/map_matching.properties` or DI config); no scattered magic numbers.
- Public methods must declare input/output units and coordinate frame; state whether height is relative or absolute (sea level vs floor datum).
- **Do not modify unrelated code when adding/updating map-matching logic. Keep diffs minimal and targeted.**

## Debugging and logging
- Log at controllable levels for key decisions: wall collision handling, accepted/rejected floor switches, lift vs stairs classification.
- Logs should include floor index, position, feature ID, and summarized sensor values; avoid dumping full raw streams.

## Change checklist (self‑review before commit)
- [ ] No path passes through walls; add regression/simulation if needed.
- [ ] Floor changes happen only near `stairs`/`lift` and when height thresholds are met.
- [ ] Lift/stairs decision is explainable via horizontal displacement or feature distance.
- [ ] All new parameters are centralized with defaults; no magic numbers.
- [ ] Unit tests cover core rules and pass.
- [ ] New or edited comments are concise, in English, and style‑consistent.
- [ ] Update `MAP_MATCHING_CHANGELOG.md` with added/modified files and open tasks whenever map-matching code changes.

Exceptions to these constraints must be documented in design notes and approved before merging.
