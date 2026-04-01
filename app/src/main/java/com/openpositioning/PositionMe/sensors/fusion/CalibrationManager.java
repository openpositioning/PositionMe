package com.openpositioning.PositionMe.sensors.fusion;

import static com.openpositioning.PositionMe.BuildConstants.DEBUG;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.Wifi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Weighted K-Nearest Neighbours (WKNN) matcher for locally collected
 * calibration points. Uses WiFi fingerprint similarity to find the
 * most likely true position.
 *
 * <h3>Improvements over 1-NN:</h3>
 * <ul>
 *   <li>Top-K (k=3) inverse-distance weighted position</li>
 *   <li>Floor-aware matching: same-floor priority</li>
 *   <li>Quality scoring: ambiguity detection via distance ratio</li>
 * </ul>
 */
public class CalibrationManager {

    private static final String TAG = "CalibrationManager";

    private static final int ABSENT_RSSI = -100;
    private static final int MIN_COMMON_APS = 6;
    private static final double MAX_MATCH_DISTANCE = 20.0;
    private static final double BASE_UNCERTAINTY = 3.0;
    private static final int K = 10;

    // ---- stored records ------------------------------------------------------

    private final List<CalibrationRecord> records = new ArrayList<>();

    private static class CalibrationRecord {
        final double trueLat;
        final double trueLng;
        final int floor;
        final Map<String, Integer> fingerprint;

        CalibrationRecord(double trueLat, double trueLng, int floor,
                          Map<String, Integer> fingerprint) {
            this.trueLat = trueLat;
            this.trueLng = trueLng;
            this.floor = floor;
            this.fingerprint = fingerprint;
        }
    }

    /** Internal: fingerprint comparison result. */
    private static class FpMatch {
        final CalibrationRecord record;
        final double distance;
        final int commonAps;

        FpMatch(CalibrationRecord record, double distance, int commonAps) {
            this.record = record;
            this.distance = distance;
            this.commonAps = commonAps;
        }
    }

    /** Result of a WKNN fingerprint match, including position, quality, and uncertainty. */
    public static class MatchResult {
        public final LatLng truePosition;
        public final double rssiDistance;
        public final double uncertainty;
        public final double distanceRatio;
        public final int commonApCount;
        public final int matchCount;
        public final String quality; // "GOOD", "AMBIGUOUS", "WEAK"

        MatchResult(LatLng truePosition, double rssiDistance, double uncertainty,
                    double distanceRatio, int commonApCount, int matchCount,
                    String quality) {
            this.truePosition = truePosition;
            this.rssiDistance = rssiDistance;
            this.uncertainty = uncertainty;
            this.distanceRatio = distanceRatio;
            this.commonApCount = commonApCount;
            this.matchCount = matchCount;
            this.quality = quality;
        }
    }

    // ---- loading -------------------------------------------------------------

    /**
     * Loads calibration records from JSON files in external storage.
     *
     * @param context application context for resolving file paths
     */
    public void loadFromFile(Context context) {
        records.clear();

        File primary = new File(context.getExternalFilesDir(null), "calibration_records.json");
        File backup  = new File(context.getExternalFilesDir(null), "calibration_records.json.bak");

        // Try primary file first, then backup
        for (File file : new File[]{primary, backup}) {
            if (!file.exists() || file.length() == 0) continue;
            try {
                FileInputStream fis = new FileInputStream(file);
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                fis.close();

                JSONArray arr = new JSONArray(new String(data));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    double lat = obj.getDouble("true_lat");
                    double lng = obj.getDouble("true_lng");
                    int floor = obj.optInt("floor", 0);

                    Map<String, Integer> fp = new HashMap<>();
                    JSONArray wifi = obj.getJSONArray("wifi");
                    for (int j = 0; j < wifi.length(); j++) {
                        JSONObject ap = wifi.getJSONObject(j);
                        String bssid = ap.optString("bssid", "");
                        int rssi = ap.optInt("rssi", ABSENT_RSSI);
                        if (!bssid.isEmpty()) {
                            fp.put(bssid, rssi);
                        }
                    }
                    if (!fp.isEmpty()) {
                        records.add(new CalibrationRecord(lat, lng, floor, fp));
                    }
                }
                if (DEBUG) Log.i(TAG, "Loaded " + records.size() + " calibration records from " + file.getName());
                return; // success — stop trying
            } catch (Exception e) {
                Log.e(TAG, "Failed to load " + file.getName() + ", trying next source", e);
                records.clear(); // reset for next attempt
            }
        }
        if (DEBUG) Log.w(TAG, "No calibration data loaded from primary or backup");
    }

    /** Returns the number of loaded calibration records. */
    public int getRecordCount() {
        return records.size();
    }

    // ---- matching ------------------------------------------------------------

    /** Backwards-compatible overload (floor unknown). */
    public MatchResult findBestMatch(List<Wifi> currentScan) {
        return findBestMatch(currentScan, -1);
    }

    /**
     * WKNN match: finds top-K calibration records by WiFi fingerprint
     * similarity, returns inverse-distance weighted position.
     *
     * @param currentScan current WiFi scan
     * @param currentFloor current floor index, or -1 if unknown
     * @return match result, or null if no suitable match
     */
    public MatchResult findBestMatch(List<Wifi> currentScan, int currentFloor) {
        if (records.isEmpty() || currentScan == null || currentScan.isEmpty()) {
            return null;
        }

        // Build live fingerprint
        Map<String, Integer> liveFp = new HashMap<>();
        for (Wifi w : currentScan) {
            String bssid = w.getBssidString();
            if (bssid != null && !bssid.isEmpty()) {
                liveFp.put(bssid, w.getLevel());
            }
        }
        if (liveFp.isEmpty()) return null;

        // Compute distance to all records
        List<FpMatch> allMatches = new ArrayList<>();
        for (CalibrationRecord rec : records) {
            int[] commonOut = new int[1];
            double dist = fingerprintDistance(liveFp, rec.fingerprint, commonOut);
            if (dist < MAX_MATCH_DISTANCE) {
                allMatches.add(new FpMatch(rec, dist, commonOut[0]));
            }
        }

        if (allMatches.isEmpty()) return null;

        // Sort by distance
        Collections.sort(allMatches, Comparator.comparingDouble(m -> m.distance));

        // Pass 1: same-floor only (if floor known)
        List<FpMatch> topK;
        boolean floorFiltered = false;
        if (currentFloor >= 0) {
            List<FpMatch> sameFloor = new ArrayList<>();
            for (FpMatch m : allMatches) {
                if (m.record.floor == currentFloor) sameFloor.add(m);
            }
            if (!sameFloor.isEmpty()) {
                topK = sameFloor.subList(0, Math.min(K, sameFloor.size()));
                floorFiltered = true;
            } else {
                // Pass 2 fallback: all floors
                topK = allMatches.subList(0, Math.min(K, allMatches.size()));
            }
        } else {
            topK = allMatches.subList(0, Math.min(K, allMatches.size()));
        }

        // Inverse-distance weighted average
        double sumW = 0, wLat = 0, wLng = 0;
        for (FpMatch m : topK) {
            double w = 1.0 / Math.max(m.distance, 0.001);
            wLat += m.record.trueLat * w;
            wLng += m.record.trueLng * w;
            sumW += w;
        }
        double avgLat = wLat / sumW;
        double avgLng = wLng / sumW;

        // Quality scoring
        double bestDist = topK.get(0).distance;
        double secondDist = (topK.size() > 1) ? topK.get(1).distance : bestDist * 2;
        double distanceRatio = bestDist / Math.max(secondDist, 0.001);
        int bestCommonAps = topK.get(0).commonAps;

        String quality;
        double sigma = BASE_UNCERTAINTY;

        // Reject if too few common APs — fingerprint not reliable
        if (bestCommonAps < 3) {
            if (DEBUG) Log.d(TAG, String.format("[Match] REJECTED commonAPs=%d < 3 bestDist=%.1f",
                    bestCommonAps, bestDist));
            return null;
        }

        if (distanceRatio > 0.90) {
            // Nearly identical top matches → very ambiguous, reject
            quality = "REJECTED";
            if (DEBUG) Log.d(TAG, String.format("[Match] REJECTED ratio=%.2f bestDist=%.1f",
                    distanceRatio, bestDist));
            return null;
        } else if (distanceRatio > 0.7) {
            quality = "AMBIGUOUS";
            sigma *= 1.5;
        } else {
            quality = "GOOD";
        }

        // Scale sigma by match distance
        sigma *= (1.0 + bestDist / MAX_MATCH_DISTANCE);

        // Penalize cross-floor fallback
        if (!floorFiltered && currentFloor >= 0) {
            sigma *= 2.0;
        }

        // Penalize low common AP count
        if (bestCommonAps < 5) {
            sigma *= 1.3;
        }

        if (DEBUG) Log.d(TAG, String.format("[Match] quality=%s k=%d ratio=%.2f commonAPs=%d sigma=%.1f bestDist=%.1f floor=%s",
                quality, topK.size(), distanceRatio, bestCommonAps, sigma,
                bestDist, floorFiltered ? "same" : "any"));

        return new MatchResult(
                new LatLng(avgLat, avgLng),
                bestDist,
                sigma,
                distanceRatio,
                bestCommonAps,
                topK.size(),
                quality);
    }

    /**
     * Returns the inverse-distance weighted average position of the top-K
     * WiFi-fingerprint matches. No quality filtering — used during initial
     * calibration when we just need a rough position from nearby reference points.
     *
     * @param currentScan current WiFi scan
     * @param currentFloor current floor index, or -1 if unknown
     * @return weighted average LatLng, or null if no matches
     */
    public LatLng findCalibrationPosition(List<Wifi> currentScan, int currentFloor) {
        if (records.isEmpty() || currentScan == null || currentScan.isEmpty()) {
            return null;
        }

        Map<String, Integer> liveFp = new HashMap<>();
        for (Wifi w : currentScan) {
            String bssid = w.getBssidString();
            if (bssid != null && !bssid.isEmpty()) {
                liveFp.put(bssid, w.getLevel());
            }
        }
        if (liveFp.isEmpty()) return null;

        List<FpMatch> allMatches = new ArrayList<>();
        for (CalibrationRecord rec : records) {
            int[] commonOut = new int[1];
            double dist = fingerprintDistance(liveFp, rec.fingerprint, commonOut);
            if (commonOut[0] >= 2) { // at least 2 common APs
                allMatches.add(new FpMatch(rec, dist, commonOut[0]));
            }
        }
        if (allMatches.isEmpty()) return null;

        Collections.sort(allMatches, Comparator.comparingDouble(m -> m.distance));

        // Prefer same floor, fall back to all
        List<FpMatch> topK;
        if (currentFloor >= 0) {
            List<FpMatch> sameFloor = new ArrayList<>();
            for (FpMatch m : allMatches) {
                if (m.record.floor == currentFloor) sameFloor.add(m);
            }
            topK = !sameFloor.isEmpty()
                    ? sameFloor.subList(0, Math.min(K, sameFloor.size()))
                    : allMatches.subList(0, Math.min(K, allMatches.size()));
        } else {
            topK = allMatches.subList(0, Math.min(K, allMatches.size()));
        }

        double sumW = 0, wLat = 0, wLng = 0;
        for (FpMatch m : topK) {
            double w = 1.0 / Math.max(m.distance, 0.001);
            wLat += m.record.trueLat * w;
            wLng += m.record.trueLng * w;
            sumW += w;
        }

        LatLng result = new LatLng(wLat / sumW, wLng / sumW);
        if (DEBUG) Log.i(TAG, String.format("[CalibrationPosition] k=%d pos=(%.6f,%.6f) bestDist=%.1f commonAPs=%d",
                topK.size(), result.latitude, result.longitude,
                topK.get(0).distance, topK.get(0).commonAps));
        return result;
    }

    // ---- spatial query (for heading correction) --------------------------------

    /**
     * Returns all calibration record positions on the given floor as LatLng list.
     * Used by FusionManager to find nearby reference points for heading correction.
     */
    public List<LatLng> getRecordPositions(int floor) {
        List<LatLng> positions = new ArrayList<>();
        for (CalibrationRecord rec : records) {
            if (rec.floor == floor) {
                positions.add(new LatLng(rec.trueLat, rec.trueLng));
            }
        }
        return positions;
    }

    // ---- fingerprint distance ------------------------------------------------

    /**
     * Euclidean distance in RSSI space, normalized by common AP count.
     *
     * @param commonOut output: number of common APs (both sides observed)
     */
    private double fingerprintDistance(Map<String, Integer> fp1,
                                      Map<String, Integer> fp2,
                                      int[] commonOut) {
        Set<String> allBssids = new HashSet<>(fp1.keySet());
        allBssids.addAll(fp2.keySet());

        int commonCount = 0;
        double sumSq = 0;
        for (String bssid : allBssids) {
            int r1 = fp1.getOrDefault(bssid, ABSENT_RSSI);
            int r2 = fp2.getOrDefault(bssid, ABSENT_RSSI);
            if (r1 != ABSENT_RSSI || r2 != ABSENT_RSSI) {
                sumSq += (r1 - r2) * (r1 - r2);
                if (r1 != ABSENT_RSSI && r2 != ABSENT_RSSI) {
                    commonCount++;
                }
            }
        }

        commonOut[0] = commonCount;

        if (commonCount < MIN_COMMON_APS) {
            return Double.MAX_VALUE;
        }

        return Math.sqrt(sumSq) / commonCount;
    }
}
