package com.openpositioning.PositionMe.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Particle filter with hard wall-crossing constraints.
 */
public class MapConstrainedPF {

    public static class Wall {
        public final double x1;
        public final double y1;
        public final double x2;
        public final double y2;

        public Wall(double x1, double y1, double x2, double y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }
    }

    public static class Particle {
        double x;
        double y;
        double theta;
        double weight;

        Particle(double x, double y, double theta, double weight) {
            this.x = x;
            this.y = y;
            this.theta = theta;
            this.weight = weight;
        }

        Particle copy() {
            return new Particle(x, y, theta, weight);
        }
    }

    private static final int NUM_PARTICLES = 200;
    private static final double DEFAULT_OBS_SIGMA = 2.0;
    private static final double STEP_NOISE_SIGMA = 0.10;
    private static final double THETA_NOISE_SIGMA = Math.toRadians(12.0);
    private static final double RESAMPLE_JITTER = 0.03;
    private static final double EPS = 1e-12;

    private final Random random = new Random();
    private final List<Particle> particles = new ArrayList<>(NUM_PARTICLES);
    private List<Wall> walls = new ArrayList<>();
    private double obsSigma = DEFAULT_OBS_SIGMA;

    public MapConstrainedPF(List<Wall> walls) {
        if (walls != null) {
            this.walls = new ArrayList<>(walls);
        }
        reset(0.0, 0.0, 0.0);
    }

    public synchronized void setWalls(List<Wall> walls) {
        this.walls = (walls == null) ? new ArrayList<>() : new ArrayList<>(walls);
    }

    public synchronized void setObservationSigma(double sigma) {
        this.obsSigma = Math.max(0.3, sigma);
    }

    public synchronized void setHeading(double headingRad) {
        double wrapped = wrapAngle(headingRad);
        for (Particle p : particles) {
            p.theta = wrapped + random.nextGaussian() * Math.toRadians(2.0);
        }
    }

    public synchronized void reset(double x, double y, double theta) {
        particles.clear();
        double w = 1.0 / NUM_PARTICLES;
        for (int i = 0; i < NUM_PARTICLES; i++) {
            double px = x + random.nextGaussian() * 0.2;
            double py = y + random.nextGaussian() * 0.2;
            double pt = theta + random.nextGaussian() * Math.toRadians(15.0);
            particles.add(new Particle(px, py, wrapAngle(pt), w));
        }
    }

    /**
     * Force-relocate the particle cloud around a trusted absolute observation.
     * This is used when WiFi/GNSS repeatedly indicates the user is far from the
     * current particle cloud (e.g., at building exits where map constraints can trap particles).
     */
    public synchronized void relocalize(double x, double y, double theta) {
        reset(x, y, theta);
    }

    public synchronized void predict(double stepLen, double deltaTheta) {
        for (Particle p : particles) {
            if (p.weight <= 0.0) {
                continue;
            }

            double oldX = p.x;
            double oldY = p.y;

            double noisyStep = Math.max(0.0, stepLen + random.nextGaussian() * STEP_NOISE_SIGMA);
            double noisyDelta = deltaTheta + random.nextGaussian() * THETA_NOISE_SIGMA;
            double nextTheta = wrapAngle(p.theta + noisyDelta);

            // Android azimuth convention (0=north, clockwise positive):
            // east = step * sin(theta), north = step * cos(theta)
            double newX = p.x + noisyStep * Math.sin(nextTheta);
            double newY = p.y + noisyStep * Math.cos(nextTheta);

            boolean hitWall = false;
            for (Wall wall : walls) {
                if (segmentsIntersect(oldX, oldY, newX, newY, wall.x1, wall.y1, wall.x2, wall.y2)) {
                    hitWall = true;
                    break;
                }
            }

            if (hitWall) {
                p.weight *= 0.1; // Severely penalize but do not insta-kill
                p.theta = nextTheta; // Allow particle to turn away from wall next step
                continue; // Cancel the forward step
            }

            p.x = newX;
            p.y = newY;
            p.theta = nextTheta;
        }
    }

    public synchronized void update(double obsX, double obsY, double score) {
        double safeScore = Math.max(0.05, Math.min(1.0, score));
        double dynamicSigma = obsSigma / safeScore;
        double denom = 2.0 * dynamicSigma * dynamicSigma;
        for (Particle p : particles) {
            if (p.weight <= 0.0) {
                continue;
            }
            double dx = p.x - obsX;
            double dy = p.y - obsY;
            double dist2 = dx * dx + dy * dy;
            p.weight = p.weight * Math.exp(-dist2 / denom);
        }

        normalizeWeights();
        rouletteWheelResample();
    }

    public synchronized double getX() {
        double[] mean = weightedMean();
        return mean[0];
    }

    public synchronized double getY() {
        double[] mean = weightedMean();
        return mean[1];
    }

    public synchronized double getConfidence() {
        double[] mean = weightedMean();
        double mx = mean[0];
        double my = mean[1];

        double wsum = 0.0;
        double var = 0.0;
        for (Particle p : particles) {
            if (p.weight <= 0.0) continue;
            double dx = p.x - mx;
            double dy = p.y - my;
            var += p.weight * (dx * dx + dy * dy);
            wsum += p.weight;
        }

        if (wsum <= EPS) {
            return Double.POSITIVE_INFINITY;
        }
        return var / wsum;
    }

    private double[] weightedMean() {
        double x = 0.0;
        double y = 0.0;
        double wsum = 0.0;
        for (Particle p : particles) {
            if (p.weight <= 0.0) continue;
            x += p.weight * p.x;
            y += p.weight * p.y;
            wsum += p.weight;
        }
        if (wsum <= EPS) {
            double uwx = 0.0, uwy = 0.0;
            for (Particle p : particles) {
                uwx += p.x;
                uwy += p.y;
            }
            if (particles.isEmpty()) return new double[]{0.0, 0.0};
            return new double[]{uwx / particles.size(), uwy / particles.size()};
        }
        return new double[]{x / wsum, y / wsum};
    }

    private void normalizeWeights() {
        double sum = 0.0;
        for (Particle p : particles) {
            sum += Math.max(0.0, p.weight);
        }

        if (sum <= EPS) {
            double w = 1.0 / NUM_PARTICLES;
            for (Particle p : particles) {
                p.weight = w;
            }
            return;
        }

        for (Particle p : particles) {
            p.weight = Math.max(0.0, p.weight) / sum;
        }
    }

    private void rouletteWheelResample() {
        List<Particle> alive = new ArrayList<>();
        for (Particle p : particles) {
            if (p.weight > 0.0) {
                alive.add(p);
            }
        }

        if (alive.isEmpty()) {
            double[] center = weightedMean();
            reset(center[0], center[1], 0.0);
            return;
        }

        double[] cumulative = new double[alive.size()];
        cumulative[0] = alive.get(0).weight;
        for (int i = 1; i < alive.size(); i++) {
            cumulative[i] = cumulative[i - 1] + alive.get(i).weight;
        }
        double total = cumulative[cumulative.length - 1];
        if (total <= EPS) {
            Collections.shuffle(alive, random);
            double w = 1.0 / NUM_PARTICLES;
            particles.clear();
            for (int i = 0; i < NUM_PARTICLES; i++) {
                Particle seed = alive.get(i % alive.size()).copy();
                seed.weight = w;
                particles.add(seed);
            }
            return;
        }

        List<Particle> resampled = new ArrayList<>(NUM_PARTICLES);
        for (int i = 0; i < NUM_PARTICLES; i++) {
            double r = random.nextDouble() * total;
            int idx = lowerBound(cumulative, r);
            Particle base = alive.get(idx);
            Particle np = new Particle(
                    base.x + random.nextGaussian() * RESAMPLE_JITTER,
                    base.y + random.nextGaussian() * RESAMPLE_JITTER,
                    wrapAngle(base.theta + random.nextGaussian() * Math.toRadians(5.0)),
                    1.0 / NUM_PARTICLES);
            resampled.add(np);
        }

        particles.clear();
        particles.addAll(resampled);
    }

    private int lowerBound(double[] arr, double target) {
        int lo = 0;
        int hi = arr.length - 1;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (arr[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static double wrapAngle(double a) {
        while (a > Math.PI) a -= 2.0 * Math.PI;
        while (a < -Math.PI) a += 2.0 * Math.PI;
        return a;
    }

    private static boolean segmentsIntersect(
            double ax, double ay, double bx, double by,
            double cx, double cy, double dx, double dy) {

        double o1 = cross(ax, ay, bx, by, cx, cy);
        double o2 = cross(ax, ay, bx, by, dx, dy);
        double o3 = cross(cx, cy, dx, dy, ax, ay);
        double o4 = cross(cx, cy, dx, dy, bx, by);

        if (o1 * o2 < 0 && o3 * o4 < 0) {
            return true;
        }

        return isCollinearOnSegment(ax, ay, bx, by, cx, cy, o1)
                || isCollinearOnSegment(ax, ay, bx, by, dx, dy, o2)
                || isCollinearOnSegment(cx, cy, dx, dy, ax, ay, o3)
                || isCollinearOnSegment(cx, cy, dx, dy, bx, by, o4);
    }

    private static double cross(double ax, double ay, double bx, double by, double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    private static boolean isCollinearOnSegment(
            double ax, double ay, double bx, double by,
            double px, double py, double crossVal) {
        if (Math.abs(crossVal) > 1e-9) {
            return false;
        }
        double minX = Math.min(ax, bx) - 1e-9;
        double maxX = Math.max(ax, bx) + 1e-9;
        double minY = Math.min(ay, by) - 1e-9;
        double maxY = Math.max(ay, by) + 1e-9;
        return px >= minX && px <= maxX && py >= minY && py <= maxY;
    }
}
