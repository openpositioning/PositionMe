package com.openpositioning.PositionMe.data.local;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.SensorTypes;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.openpositioning.PositionMe.Traj;
// Parses trajectory protobuf data into structures used by replay and charts.
public class TrajParser {

    private static final String TAG = "TrajParser";
    private static final String DIAG_TAG = "ReplayDiag";
    private Traj.Trajectory trajectory;

    public TrajParser(Traj.Trajectory trajectory) {
        this.trajectory = trajectory;
    }

    public static class ReplayPoint {
        public LatLng pdrLocation;
        public float orientation;
        public LatLng gnssLocation;
        public LatLng wifiLocation;

        public ReplayPoint(LatLng pdr, float ori, LatLng gnss, LatLng wifi) {
            this.pdrLocation = pdr;
            this.orientation = ori;
            this.gnssLocation = gnss;
            this.wifiLocation = wifi;
        }
    }

    private static class TimedLocation {
        long timestamp;
        LatLng location;

        TimedLocation(long timestamp, LatLng location) {
            this.timestamp = timestamp;
            this.location = location;
        }
    }

    // Builds replay points from one trajectory file.
    // When PDR exists, replay follows the PDR timestamp sequence and movement path.
    // GNSS and WiFi samples are aligned by time and attached as nearest historical references.
    // If PDR is unavailable, replay is generated from GNSS points.
    // If both timelines are missing but initialPosition is valid, a single anchor point is returned.
    public static List<ReplayPoint> parseTrajectoryData(String filePath, Context context, float startLat, float startLon) {
        List<ReplayPoint> replayPoints = new ArrayList<>();
        File file = new File(filePath);

        if (!file.exists()) {
            Log.e(TAG, "File not found: " + filePath);
            Log.e(DIAG_TAG, "status=FILE_BAD reason=NOT_FOUND path=" + filePath);
            return replayPoints;
        }

        if (!file.canRead()) {
            Log.e(TAG, "File is not readable: " + filePath);
            Log.e(DIAG_TAG, "status=FILE_BAD reason=NOT_READABLE path=" + filePath);
            return replayPoints;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            Traj.Trajectory traj = Traj.Trajectory.parseFrom(fis);
            List<Traj.RelativePosition> pdrList = traj.getPdrDataList();
            List<TimedLocation> gnssTimedLocations = extractGnssTimedLocations(traj);
            List<TimedLocation> wifiTimedLocations = extractWifiTimedLocations(traj);
            int wifiFingerprintCount = traj.getWifiFingerprintsCount();
            int wifiWithPositionCount = wifiTimedLocations.size();
            boolean hasInitialPosition = traj.hasInitialPosition();

            Log.i(DIAG_TAG,
                    "status=PARSED file=" + file.getName()
                            + " pdr=" + pdrList.size()
                            + " gnss=" + gnssTimedLocations.size()
                            + " wifi_fp=" + wifiFingerprintCount
                            + " wifi_pos=" + wifiWithPositionCount
                            + " initial=" + hasInitialPosition);

            double originLat = startLat;
            double originLon = startLon;
            if (!isValidLatLon(originLat, originLon)) {
                if (traj.hasInitialPosition()) {
                    originLat = traj.getInitialPosition().getLatitude();
                    originLon = traj.getInitialPosition().getLongitude();
                } else if (!gnssTimedLocations.isEmpty()) {
                    originLat = gnssTimedLocations.get(0).location.latitude;
                    originLon = gnssTimedLocations.get(0).location.longitude;
                }
            }

            // Ensure origin is valid before converting PDR x/y meters to latitude/longitude.
            // If no valid source exists, use (0,0) so replay generation can still proceed.
            if (!isValidLatLon(originLat, originLon)) {
                originLat = 0.0;
                originLon = 0.0;
                Log.w(TAG, "Origin coordinates missing/invalid; falling back to (0,0) for PDR replay");
            }

            Log.d(TAG, "Parsing trajectory: PDR points=" + pdrList.size()
                    + ", GNSS points=" + gnssTimedLocations.size()
                    + ", WiFi points=" + wifiTimedLocations.size()
                    + ", origin=" + originLat + "," + originLon);

            // PDR is treated as the primary movement path when available.
            // GNSS/WiFi are attached as auxiliary references for each replay point.
            if (!pdrList.isEmpty()) {
                Log.d(TAG, "Using PDR data as primary timeline (legacy-compatible)");

                double metersPerDegLat = 111132.954 - 559.822 * Math.cos(2 * originLat * Math.PI / 180);
                double metersPerDegLon = 111412.84 * Math.cos(originLat * Math.PI / 180);
                if (Math.abs(metersPerDegLon) < 1e-6) {
                    metersPerDegLon = 1e-6;
                }

                int gnssIndex = 0;
                int wifiIndex = 0;
                LatLng matchedGnss = null;
                LatLng matchedWifi = null;
                float lastOrientation = 0f;

                for (int i = 0; i < pdrList.size(); i++) {
                    Traj.RelativePosition pdr = pdrList.get(i);
                    long pdrTime = pdr.getRelativeTimestamp();

                    // Convert relative PDR displacement (meters) into global coordinates.
                    double deltaLat = pdr.getY() / metersPerDegLat;
                    double deltaLon = pdr.getX() / metersPerDegLon;
                    LatLng currentPdrLatLng = new LatLng(originLat + deltaLat, originLon + deltaLon);

                    // Estimate heading from the vector between consecutive PDR points.
                    float orientation = lastOrientation;
                    if (i > 0) {
                        Traj.RelativePosition prev = pdrList.get(i - 1);
                        double dx = pdr.getX() - prev.getX();
                        double dy = pdr.getY() - prev.getY();
                        if (Math.hypot(dx, dy) > 1e-4) {
                            orientation = (float) Math.toDegrees(Math.atan2(dx, dy));
                            lastOrientation = orientation;
                        }
                    }

                    // Advance GNSS pointer to the latest sample not newer than this PDR timestamp.
                    while (gnssIndex < gnssTimedLocations.size()
                            && gnssTimedLocations.get(gnssIndex).timestamp <= pdrTime) {
                        matchedGnss = gnssTimedLocations.get(gnssIndex).location;
                        gnssIndex++;
                    }

                    // Advance WiFi pointer using the same time-alignment rule.
                    while (wifiIndex < wifiTimedLocations.size()
                            && wifiTimedLocations.get(wifiIndex).timestamp <= pdrTime) {
                        matchedWifi = wifiTimedLocations.get(wifiIndex).location;
                        wifiIndex++;
                    }

                    replayPoints.add(new ReplayPoint(currentPdrLatLng, orientation, matchedGnss, matchedWifi));
                }
                Log.d(TAG, "Created " + replayPoints.size() + " replay points from PDR timeline");

            } else if (!gnssTimedLocations.isEmpty()) {
                // If PDR is unavailable, replay directly on GNSS positions.
                Log.d(TAG, "No PDR data, falling back to GNSS as primary trajectory");
                for (int i = 0; i < gnssTimedLocations.size(); i++) {
                    LatLng gnssLatLng = gnssTimedLocations.get(i).location;
                    
                    float orientation = 0f;
                    if (i > 0) {
                        LatLng prev = gnssTimedLocations.get(i - 1).location;
                        double dx = gnssLatLng.longitude - prev.longitude;
                        double dy = gnssLatLng.latitude - prev.latitude;
                        orientation = (float) Math.toDegrees(Math.atan2(dx, dy));
                    }
                    
                    replayPoints.add(new ReplayPoint(gnssLatLng, orientation, gnssLatLng, null));
                }
                Log.d(TAG, "Created " + replayPoints.size() + " replay points from GNSS data");
            } else if (traj.hasInitialPosition() && isValidLatLon(traj.getInitialPosition().getLatitude(), traj.getInitialPosition().getLongitude())) {
                // Minimal replay output for files that only contain an initial position.
                LatLng init = new LatLng(traj.getInitialPosition().getLatitude(), traj.getInitialPosition().getLongitude());
                replayPoints.add(new ReplayPoint(init, 0f, init, null));
                Log.w(TAG, "No PDR/GNSS timeline found; created single-point replay from initial position");
            } else {
                Log.e(TAG, "No PDR or GNSS data found in trajectory!");
            }

            if (replayPoints.isEmpty()) {
                boolean hasReplaySourceData = !pdrList.isEmpty() || !gnssTimedLocations.isEmpty() || hasInitialPosition;
                if (hasReplaySourceData) {
                    Log.e(DIAG_TAG,
                            "status=PARSER_EMPTY_OUTPUT reason=HAS_SOURCE_DATA_BUT_NO_POINTS"
                                    + " pdr=" + pdrList.size()
                                    + " gnss=" + gnssTimedLocations.size()
                                    + " initial=" + hasInitialPosition);
                } else {
                    Log.e(DIAG_TAG,
                            "status=FILE_NO_REPLAY_DATA reason=NO_PDR_NO_GNSS_NO_INITIAL"
                                    + " wifi_fp=" + wifiFingerprintCount
                                    + " wifi_pos=" + wifiWithPositionCount);
                }
            } else {
                Log.i(DIAG_TAG, "status=OK replay_points=" + replayPoints.size());
            }
        } catch (IOException e) {
            Log.e(TAG, "Error parsing trajectory file", e);
            Log.e(DIAG_TAG, "status=FILE_BAD reason=PARSE_EXCEPTION path=" + filePath + " error=" + e.getClass().getSimpleName() + ":" + e.getMessage());
        }
        return replayPoints;
    }

    private static List<TimedLocation> extractGnssTimedLocations(Traj.Trajectory traj) {
        List<TimedLocation> timedLocations = new ArrayList<>();
        // Keep only GNSS entries with valid coordinates for replay matching.
        for (Traj.GNSSReading reading : traj.getGnssDataList()) {
            if (!reading.hasPosition()) {
                continue;
            }
            Traj.GNSSPosition pos = reading.getPosition();
            if (!isValidLatLon(pos.getLatitude(), pos.getLongitude())) {
                continue;
            }
            timedLocations.add(new TimedLocation(
                    pos.getRelativeTimestamp(),
                    new LatLng(pos.getLatitude(), pos.getLongitude())
            ));
        }
        return timedLocations;
    }

    private static boolean isValidLatLon(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            return false;
        }
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            return false;
        }
        return !(Math.abs(lat) < 1e-8 && Math.abs(lon) < 1e-8);
    }

    private static List<TimedLocation> extractWifiTimedLocations(Traj.Trajectory traj) {
        List<TimedLocation> timedLocations = new ArrayList<>();
        // A WiFi fingerprint can contain multiple RF scans.
        // Replay uses the first scan that includes a position payload.
        for (Traj.Fingerprint fingerprint : traj.getWifiFingerprintsList()) {
            LatLng bestLocation = null;
            for (Traj.RFScan scan : fingerprint.getRfScansList()) {
                if (scan.hasPosition()) {
                    Traj.GNSSPosition pos = scan.getPosition();
                    bestLocation = new LatLng(pos.getLatitude(), pos.getLongitude());
                    break;
                }
            }
            if (bestLocation != null) {
                timedLocations.add(new TimedLocation(fingerprint.getRelativeTimestamp(), bestLocation));
            }
        }
        return timedLocations;
    }

    // Returns true if the trajectory contains at least one GNSS reading.
    public static boolean hasGnssData(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) return false;

        try (FileInputStream fis = new FileInputStream(file)) {
            Traj.Trajectory traj = Traj.Trajectory.parseFrom(fis);
            return !traj.getGnssDataList().isEmpty();
        } catch (IOException e) {
            Log.e(TAG, "Error parsing trajectory file for GNSS check", e);
            return false;
        }
    }

    // Returns the first GNSS coordinate in the trajectory, or null when unavailable.
    public static LatLng getFirstGnssPoint(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) return null;

        try (FileInputStream fis = new FileInputStream(file)) {
            Traj.Trajectory traj = Traj.Trajectory.parseFrom(fis);
            List<Traj.GNSSReading> gnssList = traj.getGnssDataList();
            if (!gnssList.isEmpty()) {
                Traj.GNSSReading first = gnssList.get(0);
                if (first.hasPosition()) {
                    return new LatLng(first.getPosition().getLatitude(), first.getPosition().getLongitude());
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error parsing trajectory file for first GNSS point", e);
        }
        return null;
    }

    // Returns the recorded initial position from the trajectory header when available.
    public static LatLng getRecordedInitialPoint(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) return null;

        try (FileInputStream fis = new FileInputStream(file)) {
            Traj.Trajectory traj = Traj.Trajectory.parseFrom(fis);
            if (traj.hasInitialPosition()) {
                double lat = traj.getInitialPosition().getLatitude();
                double lon = traj.getInitialPosition().getLongitude();
                if (isValidLatLon(lat, lon)) {
                    return new LatLng(lat, lon);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error parsing trajectory file for recorded initial point", e);
        }
        return null;
    }

    // Extracts sensor-specific arrays for chart rendering and diagnostics.
    public List<Object[]> parse(SensorTypes type) {
        List<Object[]> dataList = new ArrayList<>();
        if (trajectory == null) return dataList;

        switch (type) {
            case ACCELEROMETER:
                for (Traj.IMUReading r : trajectory.getImuDataList()) {
                    if (r.hasAcc()) dataList.add(new Object[]{r.getRelativeTimestamp(), r.getAcc().getX(), r.getAcc().getY(), r.getAcc().getZ()});
                }
                break;
            case GYRO:
                for (Traj.IMUReading r : trajectory.getImuDataList()) {
                    if (r.hasGyr()) dataList.add(new Object[]{r.getRelativeTimestamp(), r.getGyr().getX(), r.getGyr().getY(), r.getGyr().getZ()});
                }
                break;
            case GNSSLATLONG:
                for (Traj.GNSSReading r : trajectory.getGnssDataList()) {
                    if (r.hasPosition()) dataList.add(new Object[]{r.getPosition().getRelativeTimestamp(), r.getPosition().getLatitude(), r.getPosition().getLongitude()});
                }
                break;
            case WIFI:
                for (Traj.Fingerprint fp : trajectory.getWifiFingerprintsList()) {
                    dataList.add(new Object[]{fp.getRelativeTimestamp(), (float) fp.getRfScansCount()});
                }
                break;
            case PRESSURE:
                for (Traj.BarometerReading r : trajectory.getPressureDataList()) {
                    dataList.add(new Object[]{r.getRelativeTimestamp(), r.getPressure()});
                }
                break;
            case LIGHT:
                for (Traj.LightReading r : trajectory.getLightDataList()) {
                    dataList.add(new Object[]{r.getRelativeTimestamp(), r.getLight()});
                }
                break;
            case PDR:
                for (Traj.RelativePosition r : trajectory.getPdrDataList()) {
                    dataList.add(new Object[]{r.getRelativeTimestamp(), r.getX(), r.getY()});
                }
                break;
        }
        return dataList;
    }

    public long getStartTimestamp() {
        return trajectory != null ? trajectory.getStartTimestamp() : 0;
    }
}

