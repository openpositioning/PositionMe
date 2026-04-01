package com.openpositioning.PositionMe.utils;

import com.openpositioning.PositionMe.Traj;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates a {@link Traj.Trajectory} before upload, checking for minimum data quality
 * thresholds that the server requires or that indicate a meaningful recording.
 *
 * <p>Returns a {@link ValidationResult} containing categorised issues (errors and warnings)
 * so the UI can present a summary dialog and let the user decide whether to proceed.</p>
 */
public class TrajectoryValidator {

  // Minimum thresholds
  private static final int MIN_IMU_COUNT = 100;           // ~1 second at 100 Hz
  private static final long MIN_DURATION_MS = 3_000;      // 3 seconds
  private static final int MIN_WIFI_SCANS = 1;            // at least one WiFi fingerprint
  private static final int MIN_MAGNETOMETER_COUNT = 10;   // basic magnetometer coverage

  private TrajectoryValidator() { /* utility class */ }

  /**
   * Validates the given trajectory against quality thresholds.
   *
   * @param trajectory the built protobuf trajectory to validate
   * @return a {@link ValidationResult} summarising all issues found
   */
  public static ValidationResult validate(Traj.Trajectory trajectory) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    // 1. start_timestamp must be set and positive
    if (trajectory.getStartTimestamp() <= 0) {
      errors.add("Missing start timestamp");
    }

    // 2. trajectory_id should be set
    if (trajectory.getTrajectoryId() == null || trajectory.getTrajectoryId().isEmpty()) {
      warnings.add("Trajectory ID is not set");
    }

    // 3. initial_position should be set
    if (!trajectory.hasInitialPosition()
        || (trajectory.getInitialPosition().getLatitude() == 0
          && trajectory.getInitialPosition().getLongitude() == 0)) {
      warnings.add("Initial position is missing or at (0,0)");
    }

    // 4. IMU data count
    int imuCount = trajectory.getImuDataCount();
    if (imuCount == 0) {
      errors.add("No IMU data recorded");
    } else if (imuCount < MIN_IMU_COUNT) {
      warnings.add("Low IMU data count (" + imuCount
          + " samples, expected >=" + MIN_IMU_COUNT + ")");
    }

    // 5. Recording duration (based on last IMU timestamp)
    if (imuCount > 0) {
      long lastImuTs = trajectory.getImuData(imuCount - 1).getRelativeTimestamp();
      if (lastImuTs < MIN_DURATION_MS) {
        warnings.add("Recording duration too short ("
            + (lastImuTs / 1000) + "s, expected >="
            + (MIN_DURATION_MS / 1000) + "s)");
      }
    }

    // 6. Magnetometer data
    if (trajectory.getMagnetometerDataCount() < MIN_MAGNETOMETER_COUNT) {
      warnings.add("Low magnetometer data ("
          + trajectory.getMagnetometerDataCount() + " samples)");
    }

    // 7. WiFi fingerprints
    int wifiCount = trajectory.getWifiFingerprintsCount();
    if (wifiCount < MIN_WIFI_SCANS) {
      warnings.add("No WiFi fingerprints recorded ("
          + wifiCount + " scans)");
    }

    // 8. Pressure data (barometer)
    if (trajectory.getPressureDataCount() == 0) {
      warnings.add("No barometer data recorded");
    }

    // 9. PDR data
    if (trajectory.getPdrDataCount() == 0) {
      warnings.add("No PDR data — user may not have walked");
    }

    boolean passed = errors.isEmpty();
    return new ValidationResult(passed, errors, warnings);
  }

  /**
   * Holds the result of a trajectory validation.
   */
  public static class ValidationResult {
    private final boolean passed;
    private final List<String> errors;
    private final List<String> warnings;

    public ValidationResult(boolean passed, List<String> errors, List<String> warnings) {
      this.passed = passed;
      this.errors = errors;
      this.warnings = warnings;
    }

    /** True if no blocking errors were found (warnings may still exist). */
    public boolean isPassed() {
      return passed;
    }

    /** Blocking issues that indicate the trajectory is unlikely to be accepted by the server. */
    public List<String> getErrors() {
      return errors;
    }

    /** Non-blocking quality issues that the user should be aware of. */
    public List<String> getWarnings() {
      return warnings;
    }

    /** True if there are no errors and no warnings. */
    public boolean isClean() {
      return errors.isEmpty() && warnings.isEmpty();
    }

    /** Builds a human-readable summary of all issues. */
    public String buildSummary() {
      StringBuilder sb = new StringBuilder();
      if (!errors.isEmpty()) {
        sb.append("Errors:\n");
        for (String e : errors) {
          sb.append("  \u2022 ").append(e).append("\n");
        }
      }
      if (!warnings.isEmpty()) {
        if (sb.length() > 0) sb.append("\n");
        sb.append("Warnings:\n");
        for (String w : warnings) {
          sb.append("  \u2022 ").append(w).append("\n");
        }
      }
      return sb.toString().trim();
    }
  }
}
