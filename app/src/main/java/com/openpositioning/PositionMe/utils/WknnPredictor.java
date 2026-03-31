package com.openpositioning.PositionMe.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Weighted KNN indoor fingerprint predictor.
 */
public class WknnPredictor {

    public static class Fingerprint {
        public final double x;
        public final double y;
        public final Map<String, Integer> macRssiMap;

        public Fingerprint(double x, double y, Map<String, Integer> macRssiMap) {
            this.x = x;
            this.y = y;
            this.macRssiMap = new HashMap<>(macRssiMap);
        }
    }

    public static class WknnResult {
        public final double x;
        public final double y;
        public final double score;

        public WknnResult(double x, double y, double score) {
            this.x = x;
            this.y = y;
            this.score = score;
        }
    }

    private static class DistanceItem {
        final Fingerprint fingerprint;
        final double distance;
        final int matchedMacCount;

        DistanceItem(Fingerprint fingerprint, double distance, int matchedMacCount) {
            this.fingerprint = fingerprint;
            this.distance = distance;
            this.matchedMacCount = matchedMacCount;
        }
    }

    private final List<Fingerprint> fingerprintDb = new ArrayList<>();
    private final int k;
    private final double epsilon;
    private final double lambda;
    private final int missingRssiPenalty;

    public WknnPredictor(int k, double epsilon, double lambda, int missingRssiPenalty) {
        this.k = Math.max(1, k);
        this.epsilon = Math.max(1e-9, epsilon);
        this.lambda = Math.max(0.0, lambda);
        this.missingRssiPenalty = missingRssiPenalty;
    }

    public WknnPredictor() {
        this(4, 1e-6, 0.05, -100);
    }

    public synchronized void clear() {
        fingerprintDb.clear();
    }

    public synchronized void addFingerprint(Fingerprint fingerprint) {
        if (fingerprint == null || fingerprint.macRssiMap == null || fingerprint.macRssiMap.isEmpty()) {
            return;
        }
        fingerprintDb.add(fingerprint);
    }

    public synchronized int size() {
        return fingerprintDb.size();
    }

    public synchronized WknnResult predictPosition(Map<String, Integer> currentScan) {
        if (currentScan == null || currentScan.isEmpty() || fingerprintDb.isEmpty()) {
            return null;
        }

        List<DistanceItem> ranked = new ArrayList<>(fingerprintDb.size());
        for (Fingerprint fp : fingerprintDb) {
            double sumSq = 0.0;
            int matched = 0;

            for (Map.Entry<String, Integer> entry : currentScan.entrySet()) {
                String mac = entry.getKey();
                int curRssi = entry.getValue();
                Integer libRssi = fp.macRssiMap.get(mac);
                int refRssi = (libRssi != null) ? libRssi : missingRssiPenalty;
                if (libRssi != null) {
                    matched++;
                }
                double diff = curRssi - refRssi;
                sumSq += diff * diff;
            }

            double distance = Math.sqrt(sumSq);
            ranked.add(new DistanceItem(fp, distance, matched));
        }

        Collections.sort(ranked, Comparator.comparingDouble(item -> item.distance));

        int usedK = Math.min(k, ranked.size());
        double weightedX = 0.0;
        double weightedY = 0.0;
        double weightSum = 0.0;

        for (int i = 0; i < usedK; i++) {
            DistanceItem item = ranked.get(i);
            double w = 1.0 / (item.distance + epsilon);
            weightedX += w * item.fingerprint.x;
            weightedY += w * item.fingerprint.y;
            weightSum += w;
        }

        if (weightSum <= 0.0) {
            return null;
        }

        double xWknn = weightedX / weightSum;
        double yWknn = weightedY / weightSum;

        DistanceItem best = ranked.get(0);
        double macRatio = (double) best.matchedMacCount / (double) currentScan.size();
        // Use RMS RSSI error (per AP) rather than raw L2 distance so confidence remains
        // meaningful as the number of scanned APs changes.
        double rmsDistance = best.distance / Math.sqrt(Math.max(1, currentScan.size()));
        double score = macRatio * Math.exp(-lambda * rmsDistance);
        if (score < 0.0) score = 0.0;
        if (score > 1.0) score = 1.0;

        return new WknnResult(xWknn, yWknn, score);
    }
}
