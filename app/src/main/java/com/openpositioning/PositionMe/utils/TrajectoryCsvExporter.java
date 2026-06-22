package com.openpositioning.PositionMe.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.openpositioning.PositionMe.Traj;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Exports trajectory protobuf data into CSV files for offline analysis.
 *
 * <p>The exported CSV contains PDR samples, nearest GNSS readings, and extra
 * rows for test-point error checks when ground-truth test points were recorded.</p>
 */
public final class TrajectoryCsvExporter {

    private static final double EARTH_RADIUS_METERS = 6378137.0;
    private static final long TEST_POINT_MATCH_WINDOW_MS = 1500;
    private static final long CORRECTED_POSITION_MATCH_WINDOW_MS = 1500;
    private static final String PUBLIC_DOWNLOAD_SUBDIR = "PositionMe";

    private TrajectoryCsvExporter() {}

    /**
     * Writes a CSV copy into the app-specific Downloads folder and into the
     * public Downloads/PositionMe folder visible to file-manager apps.
     *
     * @param context Android context
     * @param trajectory trajectory to export
     * @param csvFileName output file name ending in .csv
     * @return user-facing public download path
     * @throws IOException if either write fails
     */
    public static String exportToDownloads(Context context, Traj.Trajectory trajectory,
                                           String csvFileName) throws IOException {
        String csv = buildCsv(trajectory);
        writeAppSpecificCopy(context, csvFileName, csv);
        writePublicCopy(context, csvFileName, csv);
        return Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_DOWNLOAD_SUBDIR + "/" + csvFileName;
    }

    /**
     * Builds CSV text from a trajectory.
     */
    public static String buildCsv(Traj.Trajectory trajectory) {
        StringBuilder csv = new StringBuilder();
        appendRow(csv,
                "row_type",
                "trajectory_id",
                "start_timestamp_ms",
                "relative_timestamp_ms",
                "elapsed_s",
                "pdr_x_m",
                "pdr_y_m",
                "pdr_latitude",
                "pdr_longitude",
                "kf_pdr_relative_timestamp_ms",
                "kf_pdr_x_m",
                "kf_pdr_y_m",
                "kf_pdr_latitude",
                "kf_pdr_longitude",
                "gnss_relative_timestamp_ms",
                "gnss_latitude",
                "gnss_longitude",
                "gnss_accuracy_m",
                "gnss_provider",
                "test_point_relative_timestamp_ms",
                "test_point_latitude",
                "test_point_longitude",
                "time_delta_to_reference_ms",
                "position_error_m",
                "kf_position_error_m");

        boolean hasInitialPosition = hasUsableInitialPosition(trajectory);
        Traj.GNSSPosition initialPosition = hasInitialPosition
                ? trajectory.getInitialPosition() : null;
        List<Traj.GNSSReading> gnssReadings = trajectory.getGnssDataList();
        List<Traj.GNSSPosition> testPoints = trajectory.getTestPointsList();
        List<Traj.GNSSPosition> correctedPositions = trajectory.getCorrectedPositionsList();

        for (Traj.RelativePosition pdr : trajectory.getPdrDataList()) {
            double[] pdrLatLng = hasInitialPosition ? pdrToLatLng(initialPosition, pdr) : null;
            Traj.GNSSPosition nearestCorrected = findClosestPositionWithin(
                    correctedPositions, pdr.getRelativeTimestamp(),
                    CORRECTED_POSITION_MATCH_WINDOW_MS);
            double[] correctedMeters = nearestCorrected != null && hasInitialPosition
                    ? latLngToMeters(initialPosition, nearestCorrected) : null;
            Traj.GNSSReading nearestGnss = findClosestGnss(gnssReadings,
                    pdr.getRelativeTimestamp());
            Traj.GNSSPosition nearestTestPoint = findClosestPosition(testPoints,
                    pdr.getRelativeTimestamp());

            Long testPointDelta = null;
            Double errorMeters = null;
            Double kfErrorMeters = null;
            if (nearestTestPoint != null && pdrLatLng != null) {
                testPointDelta = pdr.getRelativeTimestamp()
                        - nearestTestPoint.getRelativeTimestamp();
                if (Math.abs(testPointDelta) <= TEST_POINT_MATCH_WINDOW_MS) {
                    errorMeters = distanceMeters(
                            pdrLatLng[0], pdrLatLng[1],
                            nearestTestPoint.getLatitude(),
                            nearestTestPoint.getLongitude());
                    if (nearestCorrected != null) {
                        kfErrorMeters = distanceMeters(
                                nearestCorrected.getLatitude(),
                                nearestCorrected.getLongitude(),
                                nearestTestPoint.getLatitude(),
                                nearestTestPoint.getLongitude());
                    }
                } else {
                    nearestTestPoint = null;
                    testPointDelta = null;
                }
            }

            appendTrajectoryRow(csv, "pdr_sample", trajectory, pdr, pdrLatLng,
                    nearestCorrected, correctedMeters, nearestGnss, nearestTestPoint,
                    testPointDelta, errorMeters, kfErrorMeters);
        }

        for (Traj.GNSSPosition testPoint : testPoints) {
            Traj.RelativePosition nearestPdr = findClosestPdr(trajectory.getPdrDataList(),
                    testPoint.getRelativeTimestamp());
            double[] pdrLatLng = nearestPdr != null && hasInitialPosition
                    ? pdrToLatLng(initialPosition, nearestPdr) : null;
            Traj.GNSSPosition nearestCorrected = findClosestPositionWithin(
                    correctedPositions, testPoint.getRelativeTimestamp(),
                    CORRECTED_POSITION_MATCH_WINDOW_MS);
            double[] correctedMeters = nearestCorrected != null && hasInitialPosition
                    ? latLngToMeters(initialPosition, nearestCorrected) : null;
            Traj.GNSSReading nearestGnss = findClosestGnss(gnssReadings,
                    testPoint.getRelativeTimestamp());

            Long testPointDelta = null;
            Double errorMeters = null;
            Double kfErrorMeters = null;
            if (nearestPdr != null) {
                testPointDelta = nearestPdr.getRelativeTimestamp()
                        - testPoint.getRelativeTimestamp();
                if (pdrLatLng != null) {
                    errorMeters = distanceMeters(
                            pdrLatLng[0], pdrLatLng[1],
                            testPoint.getLatitude(),
                            testPoint.getLongitude());
                }
            }
            if (nearestCorrected != null) {
                kfErrorMeters = distanceMeters(
                        nearestCorrected.getLatitude(),
                        nearestCorrected.getLongitude(),
                        testPoint.getLatitude(),
                        testPoint.getLongitude());
            }

            appendTrajectoryRow(csv, "test_point_error", trajectory, nearestPdr, pdrLatLng,
                    nearestCorrected, correctedMeters, nearestGnss, testPoint,
                    testPointDelta, errorMeters, kfErrorMeters);
        }

        return csv.toString();
    }

    private static void appendTrajectoryRow(StringBuilder csv, String rowType,
                                            Traj.Trajectory trajectory,
                                            Traj.RelativePosition pdr,
                                            double[] pdrLatLng,
                                            Traj.GNSSPosition correctedPosition,
                                            double[] correctedMeters,
                                            Traj.GNSSReading gnss,
                                            Traj.GNSSPosition testPoint,
                                            Long referenceDeltaMs,
                                            Double errorMeters,
                                            Double kfErrorMeters) {
        long relativeTimestamp = "test_point_error".equals(rowType) && testPoint != null
                ? testPoint.getRelativeTimestamp()
                : pdr != null
                ? pdr.getRelativeTimestamp()
                : testPoint != null ? testPoint.getRelativeTimestamp() : 0;

        appendRow(csv,
                rowType,
                trajectory.getTrajectoryId(),
                longString(trajectory.getStartTimestamp()),
                longString(relativeTimestamp),
                doubleString(relativeTimestamp / 1000.0, 3),
                pdr != null ? doubleString(pdr.getX(), 4) : "",
                pdr != null ? doubleString(pdr.getY(), 4) : "",
                pdrLatLng != null ? doubleString(pdrLatLng[0], 8) : "",
                pdrLatLng != null ? doubleString(pdrLatLng[1], 8) : "",
                correctedPosition != null
                        ? longString(correctedPosition.getRelativeTimestamp()) : "",
                correctedMeters != null ? doubleString(correctedMeters[0], 4) : "",
                correctedMeters != null ? doubleString(correctedMeters[1], 4) : "",
                correctedPosition != null ? doubleString(correctedPosition.getLatitude(), 8) : "",
                correctedPosition != null ? doubleString(correctedPosition.getLongitude(), 8) : "",
                gnss != null ? longString(gnss.getPosition().getRelativeTimestamp()) : "",
                gnss != null ? doubleString(gnss.getPosition().getLatitude(), 8) : "",
                gnss != null ? doubleString(gnss.getPosition().getLongitude(), 8) : "",
                gnss != null ? doubleString(gnss.getAccuracy(), 3) : "",
                gnss != null ? gnss.getProvider() : "",
                testPoint != null ? longString(testPoint.getRelativeTimestamp()) : "",
                testPoint != null ? doubleString(testPoint.getLatitude(), 8) : "",
                testPoint != null ? doubleString(testPoint.getLongitude(), 8) : "",
                referenceDeltaMs != null ? longString(referenceDeltaMs) : "",
                errorMeters != null ? doubleString(errorMeters, 3) : "",
                kfErrorMeters != null ? doubleString(kfErrorMeters, 3) : "");
    }

    private static void writeAppSpecificCopy(Context context, String fileName, String csv)
            throws IOException {
        File downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir == null) {
            downloadsDir = context.getFilesDir();
        }
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw new IOException("Could not create app downloads directory");
        }

        File csvFile = new File(downloadsDir, fileName);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(csvFile), StandardCharsets.UTF_8)) {
            writer.write(csv);
        }
    }

    private static void writePublicCopy(Context context, String fileName, String csv)
            throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_DOWNLOAD_SUBDIR);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Could not create public CSV file");
            }

            try (OutputStream outputStream = resolver.openOutputStream(uri)) {
                if (outputStream == null) {
                    throw new IOException("Could not open public CSV file");
                }
                outputStream.write(csv.getBytes(StandardCharsets.UTF_8));
            }
        } else {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            File outputDir = new File(downloadsDir, PUBLIC_DOWNLOAD_SUBDIR);
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                throw new IOException("Could not create public downloads directory");
            }

            File csvFile = new File(outputDir, fileName);
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(csvFile), StandardCharsets.UTF_8)) {
                writer.write(csv);
            }
        }
    }

    private static boolean hasUsableInitialPosition(Traj.Trajectory trajectory) {
        return trajectory.hasInitialPosition()
                && (trajectory.getInitialPosition().getLatitude() != 0
                || trajectory.getInitialPosition().getLongitude() != 0);
    }

    private static double[] pdrToLatLng(Traj.GNSSPosition origin,
                                        Traj.RelativePosition pdr) {
        double latRadians = Math.toRadians(origin.getLatitude());
        double latitude = origin.getLatitude()
                + Math.toDegrees(pdr.getY() / EARTH_RADIUS_METERS);
        double longitude = origin.getLongitude()
                + Math.toDegrees(pdr.getX()
                / (EARTH_RADIUS_METERS * Math.cos(latRadians)));
        return new double[]{latitude, longitude};
    }

    private static double[] latLngToMeters(Traj.GNSSPosition origin,
                                           Traj.GNSSPosition position) {
        double latRadians = Math.toRadians(origin.getLatitude());
        double x = Math.toRadians(position.getLongitude() - origin.getLongitude())
                * EARTH_RADIUS_METERS * Math.cos(latRadians);
        double y = Math.toRadians(position.getLatitude() - origin.getLatitude())
                * EARTH_RADIUS_METERS;
        return new double[]{x, y};
    }

    private static Traj.GNSSReading findClosestGnss(List<Traj.GNSSReading> readings,
                                                    long timestamp) {
        Traj.GNSSReading closest = null;
        long bestDelta = Long.MAX_VALUE;
        for (Traj.GNSSReading reading : readings) {
            long delta = Math.abs(reading.getPosition().getRelativeTimestamp() - timestamp);
            if (delta < bestDelta) {
                bestDelta = delta;
                closest = reading;
            }
        }
        return closest;
    }

    private static Traj.GNSSPosition findClosestPosition(List<Traj.GNSSPosition> positions,
                                                         long timestamp) {
        Traj.GNSSPosition closest = null;
        long bestDelta = Long.MAX_VALUE;
        for (Traj.GNSSPosition position : positions) {
            long delta = Math.abs(position.getRelativeTimestamp() - timestamp);
            if (delta < bestDelta) {
                bestDelta = delta;
                closest = position;
            }
        }
        return closest;
    }

    private static Traj.GNSSPosition findClosestPositionWithin(
            List<Traj.GNSSPosition> positions, long timestamp, long maxDeltaMs) {
        Traj.GNSSPosition closest = findClosestPosition(positions, timestamp);
        if (closest == null) {
            return null;
        }
        long delta = Math.abs(closest.getRelativeTimestamp() - timestamp);
        return delta <= maxDeltaMs ? closest : null;
    }

    private static Traj.RelativePosition findClosestPdr(List<Traj.RelativePosition> pdrPoints,
                                                        long timestamp) {
        Traj.RelativePosition closest = null;
        long bestDelta = Long.MAX_VALUE;
        for (Traj.RelativePosition pdr : pdrPoints) {
            long delta = Math.abs(pdr.getRelativeTimestamp() - timestamp);
            if (delta < bestDelta) {
                bestDelta = delta;
                closest = pdr;
            }
        }
        return closest;
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);

        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(lat1Rad) * Math.cos(lat2Rad)
                * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return EARTH_RADIUS_METERS * c;
    }

    private static void appendRow(StringBuilder csv, String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) csv.append(',');
            appendCsvField(csv, fields[i]);
        }
        csv.append('\n');
    }

    private static void appendCsvField(StringBuilder csv, String field) {
        if (field == null) field = "";
        boolean quote = field.contains(",") || field.contains("\"")
                || field.contains("\n") || field.contains("\r");
        if (!quote) {
            csv.append(field);
            return;
        }

        csv.append('"');
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == '"') csv.append("\"\"");
            else csv.append(c);
        }
        csv.append('"');
    }

    private static String longString(long value) {
        return String.valueOf(value);
    }

    private static String doubleString(double value, int digits) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "";
        return String.format(Locale.US, "%." + digits + "f", value);
    }
}
