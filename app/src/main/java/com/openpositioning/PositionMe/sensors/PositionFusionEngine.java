package com.openpositioning.PositionMe.sensors;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * SIR particle filter fusion engine in local East/North coordinates.
 *
 * <p>The filter predicts with step-based PDR displacement and updates particle
 * weights with GNSS/WiFi absolute fixes. Resampling is triggered when
 * the effective particle count falls below a threshold.</p>
 */
public class PositionFusionEngine {

    private static final String TAG = "PositionFusionPF";
    private static final boolean DEBUG_LOGS = true;

    private static final double EARTH_RADIUS_M = 6378137.0;

    private static final int PARTICLE_COUNT = 220;
    private static final double RESAMPLE_RATIO = 0.5;
    private static final double PDR_NOISE_STD_M = 0.45;
    private static final double INIT_STD_M = 2.0;
    private static final double ROUGHEN_STD_M = 0.15;
    private static final double WIFI_SIGMA_M = 8.0;
    private static final double EPS = 1e-300;

    private final float floorHeightMeters;
    private final Random random = new Random();

    // Local tangent frame anchor
    private double anchorLatDeg;
    private double anchorLonDeg;
    private boolean hasAnchor;

    private final List<Particle> particles = new ArrayList<>(PARTICLE_COUNT);
    private int fallbackFloor;
    private long updateCounter;

    private static final class Particle {
        double xEast;
        double yNorth;
        int floor;
        double weight;
    }

    public PositionFusionEngine(float floorHeightMeters) {
        this.floorHeightMeters = floorHeightMeters > 0f ? floorHeightMeters : 4f;
    }

    public synchronized void reset(double latDeg, double lonDeg, int initialFloor) {
        anchorLatDeg = latDeg;
        anchorLonDeg = lonDeg;
        hasAnchor = true;

        fallbackFloor = initialFloor;
        initParticlesAtOrigin(initialFloor);
        if (DEBUG_LOGS) {
            Log.i(TAG, String.format(Locale.US,
                    "Reset anchor=(%.7f, %.7f) floor=%d particles=%d",
                    latDeg, lonDeg, initialFloor, PARTICLE_COUNT));
        }
    }

    public synchronized void updatePdrDisplacement(float dxEastMeters, float dyNorthMeters) {
        if (!hasAnchor || particles.isEmpty()) {
            return;
        }

        for (Particle p : particles) {
            p.xEast += dxEastMeters + random.nextGaussian() * PDR_NOISE_STD_M;
            p.yNorth += dyNorthMeters + random.nextGaussian() * PDR_NOISE_STD_M;
        }

        if (DEBUG_LOGS) {
            Log.d(TAG, String.format(Locale.US,
                    "Predict dPDR=(%.2fE, %.2fN) noiseStd=%.2f",
                    dxEastMeters, dyNorthMeters, PDR_NOISE_STD_M));
        }
    }

    public synchronized void updateGnss(double latDeg, double lonDeg, float accuracyMeters) {
        double sigma = Math.max(accuracyMeters, 3.0f);
        if (DEBUG_LOGS) {
            Log.d(TAG, String.format(Locale.US,
                    "GNSS update lat=%.7f lon=%.7f acc=%.2f sigma=%.2f",
                    latDeg, lonDeg, accuracyMeters, sigma));
        }
        applyAbsoluteFix(latDeg, lonDeg, sigma, null);
    }

    public synchronized void updateWifi(double latDeg, double lonDeg, int wifiFloor) {
        if (DEBUG_LOGS) {
            Log.d(TAG, String.format(Locale.US,
                    "WiFi update lat=%.7f lon=%.7f floor=%d sigma=%.2f",
                    latDeg, lonDeg, wifiFloor, WIFI_SIGMA_M));
        }
        applyAbsoluteFix(latDeg, lonDeg, WIFI_SIGMA_M, wifiFloor);
    }

    public synchronized void updateElevation(float elevationMeters) {
        int floorFromBarometer = Math.round(elevationMeters / floorHeightMeters);
        fallbackFloor = floorFromBarometer;
        if (!particles.isEmpty()) {
            for (Particle p : particles) {
                p.floor = floorFromBarometer;
            }
        }
    }

    public synchronized PositionFusionEstimate getEstimate() {
        if (!hasAnchor || particles.isEmpty()) {
            return new PositionFusionEstimate(null, fallbackFloor, false);
        }

        double meanX = 0.0;
        double meanY = 0.0;
        Map<Integer, Double> floorWeights = new HashMap<>();

        for (Particle p : particles) {
            meanX += p.weight * p.xEast;
            meanY += p.weight * p.yNorth;
            floorWeights.put(p.floor, floorWeights.getOrDefault(p.floor, 0.0) + p.weight);
        }

        int bestFloor = fallbackFloor;
        double bestFloorWeight = -1.0;
        for (Map.Entry<Integer, Double> entry : floorWeights.entrySet()) {
            if (entry.getValue() > bestFloorWeight) {
                bestFloor = entry.getKey();
                bestFloorWeight = entry.getValue();
            }
        }

        LatLng latLng = toLatLng(meanX, meanY);
        return new PositionFusionEstimate(latLng, bestFloor, true);
    }

    private void applyAbsoluteFix(double latDeg, double lonDeg, double sigmaMeters, Integer floorHint) {
        if (!hasAnchor) {
            reset(latDeg, lonDeg, 0);
            return;
        }

        if (particles.isEmpty()) {
            initParticlesAtOrigin(fallbackFloor);
        }

        double[] z = toLocal(latDeg, lonDeg);
    double effectiveBefore = computeEffectiveSampleSize();

        double sigma2 = sigmaMeters * sigmaMeters;
        double maxLogWeight = Double.NEGATIVE_INFINITY;
        double[] logWeights = new double[particles.size()];

        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            double dx = p.xEast - z[0];
            double dy = p.yNorth - z[1];
            double distance2 = dx * dx + dy * dy;
            double logLikelihood = -0.5 * (distance2 / sigma2);

            if (floorHint != null) {
                // Soft floor gating: keep mismatch possible, but less probable.
                logLikelihood += (p.floor == floorHint) ? Math.log(0.90) : Math.log(0.10);
            }

            double logWeight = Math.log(Math.max(p.weight, EPS)) + logLikelihood;
            logWeights[i] = logWeight;
            if (logWeight > maxLogWeight) {
                maxLogWeight = logWeight;
            }
        }

        double sumW = 0.0;
        for (int i = 0; i < particles.size(); i++) {
            double normalized = Math.exp(logWeights[i] - maxLogWeight);
            particles.get(i).weight = Math.max(normalized, EPS);
            sumW += particles.get(i).weight;
        }

        if (sumW <= 0.0) {
            reinitializeAroundMeasurement(z[0], z[1], floorHint != null ? floorHint : fallbackFloor);
            return;
        }

        for (Particle p : particles) {
            p.weight /= sumW;
        }

        if (floorHint != null) {
            fallbackFloor = floorHint;
        }

        double effectiveN = computeEffectiveSampleSize();
        boolean resampled = false;
        if (effectiveN < PARTICLE_COUNT * RESAMPLE_RATIO) {
            resampleSystematic();
            roughenParticles();
            resampled = true;
        }

        updateCounter++;
        logUpdateSummary(z[0], z[1], sigmaMeters, floorHint, effectiveBefore, effectiveN, resampled);
    }

    private void initParticlesAtOrigin(int initialFloor) {
        particles.clear();
        double w = 1.0 / PARTICLE_COUNT;
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            Particle p = new Particle();
            p.xEast = random.nextGaussian() * INIT_STD_M;
            p.yNorth = random.nextGaussian() * INIT_STD_M;
            p.floor = initialFloor;
            p.weight = w;
            particles.add(p);
        }
    }

    private void reinitializeAroundMeasurement(double x, double y, int floor) {
        particles.clear();
        double w = 1.0 / PARTICLE_COUNT;
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            Particle p = new Particle();
            p.xEast = x + random.nextGaussian() * INIT_STD_M;
            p.yNorth = y + random.nextGaussian() * INIT_STD_M;
            p.floor = floor;
            p.weight = w;
            particles.add(p);
        }
    }

    private double computeEffectiveSampleSize() {
        double sumSquared = 0.0;
        for (Particle p : particles) {
            sumSquared += p.weight * p.weight;
        }
        if (sumSquared <= 0.0) {
            return 0.0;
        }
        return 1.0 / sumSquared;
    }

    private void resampleSystematic() {
        List<Particle> resampled = new ArrayList<>(PARTICLE_COUNT);
        double step = 1.0 / PARTICLE_COUNT;
        double u = random.nextDouble() * step;
        double cdf = particles.get(0).weight;
        int idx = 0;

        for (int m = 0; m < PARTICLE_COUNT; m++) {
            double threshold = u + m * step;
            while (threshold > cdf && idx < particles.size() - 1) {
                idx++;
                cdf += particles.get(idx).weight;
            }

            Particle src = particles.get(idx);
            Particle copy = new Particle();
            copy.xEast = src.xEast;
            copy.yNorth = src.yNorth;
            copy.floor = src.floor;
            copy.weight = step;
            resampled.add(copy);
        }

        particles.clear();
        particles.addAll(resampled);
    }

    private void roughenParticles() {
        for (Particle p : particles) {
            p.xEast += random.nextGaussian() * ROUGHEN_STD_M;
            p.yNorth += random.nextGaussian() * ROUGHEN_STD_M;
        }
    }

    private double[] toLocal(double latDeg, double lonDeg) {
        double lat0Rad = Math.toRadians(anchorLatDeg);
        double dLat = Math.toRadians(latDeg - anchorLatDeg);
        double dLon = Math.toRadians(lonDeg - anchorLonDeg);

        double east = dLon * EARTH_RADIUS_M * Math.cos(lat0Rad);
        double north = dLat * EARTH_RADIUS_M;
        return new double[]{east, north};
    }

    private LatLng toLatLng(double eastMeters, double northMeters) {
        double lat0Rad = Math.toRadians(anchorLatDeg);

        double dLat = northMeters / EARTH_RADIUS_M;
        double cosLat = Math.cos(lat0Rad);
        if (Math.abs(cosLat) < 1e-9) {
            cosLat = 1e-9;
        }
        double dLon = eastMeters / (EARTH_RADIUS_M * cosLat);

        double lat = anchorLatDeg + Math.toDegrees(dLat);
        double lon = anchorLonDeg + Math.toDegrees(dLon);

        return new LatLng(lat, lon);
    }

    private void logUpdateSummary(double zEast, double zNorth,
                                  double sigmaMeters,
                                  Integer floorHint,
                                  double effectiveBefore,
                                  double effectiveAfter,
                                  boolean resampled) {
        if (!DEBUG_LOGS || particles.isEmpty()) {
            return;
        }

        double minW = Double.POSITIVE_INFINITY;
        double maxW = 0.0;
        double entropy = 0.0;
        double meanX = 0.0;
        double meanY = 0.0;
        int bestFloor = fallbackFloor;
        Map<Integer, Double> floorWeights = new HashMap<>();

        Particle bestParticle = particles.get(0);
        for (Particle p : particles) {
            minW = Math.min(minW, p.weight);
            maxW = Math.max(maxW, p.weight);
            if (p.weight > bestParticle.weight) {
                bestParticle = p;
            }

            if (p.weight > 0.0) {
                entropy -= p.weight * Math.log(p.weight);
            }

            meanX += p.weight * p.xEast;
            meanY += p.weight * p.yNorth;
            floorWeights.put(p.floor, floorWeights.getOrDefault(p.floor, 0.0) + p.weight);
        }

        double bestFloorWeight = -1.0;
        for (Map.Entry<Integer, Double> entry : floorWeights.entrySet()) {
            if (entry.getValue() > bestFloorWeight) {
                bestFloorWeight = entry.getValue();
                bestFloor = entry.getKey();
            }
        }

        double entropyNorm = entropy / Math.log(PARTICLE_COUNT);
        String source = floorHint == null ? "GNSS" : "WiFi";
        Log.i(TAG, String.format(Locale.US,
                "u=%d src=%s z=(%.2fE,%.2fN) sigma=%.2f floorHint=%s Neff=%.1f->%.1f resampled=%s w[min=%.5f max=%.5f Hn=%.3f] mean=(%.2fE,%.2fN) bestP=(%.2fE,%.2fN,f=%d,w=%.5f) bestFloor=%d(%.3f)",
                updateCounter,
                source,
                zEast,
                zNorth,
                sigmaMeters,
                floorHint == null ? "-" : String.valueOf(floorHint),
                effectiveBefore,
                effectiveAfter,
                String.valueOf(resampled),
                minW,
                maxW,
                entropyNorm,
                meanX,
                meanY,
                bestParticle.xEast,
                bestParticle.yNorth,
                bestParticle.floor,
                bestParticle.weight,
                bestFloor,
                bestFloorWeight));
    }
}
