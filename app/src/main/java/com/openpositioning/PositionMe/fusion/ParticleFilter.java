package com.openpositioning.PositionMe.fusion;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.utils.GeometryUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Particle filter used to fuse PDR motion with map and measurement constraints. */
public class ParticleFilter {

    private List<Particle> particles;
    private final int numParticles;
    private final Random random = new Random();

    /**
     * Creates a particle filter with a fixed number of particles.
     *
     * @param numParticles number of particles maintained by the filter
     */
    public ParticleFilter(int numParticles) {
        this.numParticles = numParticles;
        this.particles = new ArrayList<>(numParticles);
    }

    /**
     * Initializes particles uniformly within the provided map bounds.
     *
     * @param mapBounds rectangular initialization bounds in local map coordinates
     */
    public void initialize(MapBounds mapBounds) {
        particles.clear();
        float width = mapBounds.maxX - mapBounds.minX;
        float height = mapBounds.maxY - mapBounds.minY;

        for (int i = 0; i < numParticles; i++) {
            float randomX = mapBounds.minX + random.nextFloat() * width;
            float randomY = mapBounds.minY + random.nextFloat() * height;
            particles.add(new Particle(randomX, randomY, 0));
        }
    }

    /**
     * Applies a PDR motion update to all particles.
     *
     * @param movement PDR motion in local map coordinates
     */
    public void predict(PDRMovement movement) {
        predict(movement, 1);
    }

    /**
     * Applies a subdivided PDR motion update to all particles.
     *
     * @param movement PDR motion in local map coordinates
     * @param subdivisions number of substeps used for this update
     */
    public void predict(PDRMovement movement, int subdivisions) {
        int safeSubdivisions = Math.max(1, subdivisions);
        float dx = movement.deltaX / safeSubdivisions;
        float dy = movement.deltaY / safeSubdivisions;
        float noiseStdDev = 0.03f / (float) Math.sqrt(safeSubdivisions);

        for (Particle particle : particles) {
            particle.move(dx, dy, this.random, noiseStdDev);
        }
    }

    /**
     * Updates particle weights using an external position measurement.
     *
     * @param measurement measured position and uncertainty
     * @param walls unused, kept to match the current call sites
     * @param startLocation unused, kept to match the current call sites
     */
    public void updateWeights(
            Measurement measurement,
            List<FloorplanApiClient.MapShapeFeature> walls,
            LatLng startLocation) {
        double totalWeight = 0;

        for (Particle particle : particles) {
            double distanceSquared =
                    Math.pow(particle.x - measurement.x, 2)
                            + Math.pow(particle.y - measurement.y, 2);
            double sigma = measurement.accuracy;
            double likelihood = Math.exp(-distanceSquared / (2 * Math.pow(sigma, 2)));
            particle.weight *= likelihood;
            totalWeight += particle.weight;
        }

        for (Particle particle : particles) {
            if (totalWeight > 0) {
                particle.weight /= totalWeight;
            } else {
                particle.weight = 1.0 / numParticles;
            }
        }
    }

    /** Resamples particles using roulette-wheel sampling. */
    public void resample() {
        List<Particle> newParticles = new ArrayList<>(numParticles);

        double maxWeight = 0;
        for (Particle particle : particles) {
            if (particle.weight > maxWeight) {
                maxWeight = particle.weight;
            }
        }

        if (maxWeight == 0) {
            return;
        }

        double beta = 0.0;
        int index = random.nextInt(numParticles);

        for (int i = 0; i < numParticles; i++) {
            beta += random.nextDouble() * 2.0 * maxWeight;

            while (beta > particles.get(index).weight) {
                beta -= particles.get(index).weight;
                index = (index + 1) % numParticles;
            }

            Particle selected = particles.get(index);
            newParticles.add(new Particle(selected.x, selected.y, selected.floor));
        }

        this.particles = newParticles;
    }

    /**
     * Returns the weighted mean particle position.
     *
     * @return estimated position in local map coordinates
     */
    public Position getEstimatedPosition() {
        if (particles == null || particles.isEmpty()) {
            return new Position(0, 0);
        }

        float expectedX = 0;
        float expectedY = 0;
        double totalWeight = 0;

        for (Particle particle : particles) {
            expectedX += particle.x * particle.weight;
            expectedY += particle.y * particle.weight;
            totalWeight += particle.weight;
        }

        if (totalWeight == 0) {
            float sumX = 0;
            float sumY = 0;
            for (Particle particle : particles) {
                sumX += particle.x;
                sumY += particle.y;
            }
            return new Position(sumX / particles.size(), sumY / particles.size());
        }

        return new Position((float) (expectedX / totalWeight), (float) (expectedY / totalWeight));
    }

    /**
     * Returns a map-aware estimated position.
     *
     * <p>If the weighted mean falls inside a blocked polygon, the filter falls back to the
     * highest-weight valid particle.
     *
     * @param walls blocking map features
     * @param startLocation origin used to convert local coordinates to latitude and longitude
     * @return estimated position in local map coordinates
     */
    public Position getEstimatedPosition(
            List<FloorplanApiClient.MapShapeFeature> walls, LatLng startLocation) {
        Position estimate = getEstimatedPosition();
        if (walls == null || startLocation == null || walls.isEmpty()) {
            return estimate;
        }

        LatLng estimatedLatLng = toLatLng(estimate.x, estimate.y, startLocation);
        if (!isBlockedPosition(estimatedLatLng, walls)) {
            return estimate;
        }

        Particle bestParticle = null;
        for (Particle particle : particles) {
            LatLng particleLatLng = toLatLng(particle.x, particle.y, startLocation);
            if (isBlockedPosition(particleLatLng, walls)) {
                continue;
            }
            if (bestParticle == null || particle.weight > bestParticle.weight) {
                bestParticle = particle;
            }
        }

        if (bestParticle != null) {
            return new Position(bestParticle.x, bestParticle.y);
        }

        return estimate;
    }

    /**
     * Returns the current estimated position converted to geographic coordinates.
     *
     * @param walls blocking map features
     * @param startLocation origin used to convert local coordinates to latitude and longitude
     * @return estimated latitude and longitude, or {@code null} if the start location is unavailable
     */
    public LatLng getEstimatedLatLng(
            List<FloorplanApiClient.MapShapeFeature> walls, LatLng startLocation) {
        if (startLocation == null) {
            return null;
        }
        Position estimate = getEstimatedPosition(walls, startLocation);
        return toLatLng(estimate.x, estimate.y, startLocation);
    }

    /**
     * Returns a horizontal motion scale for special indoor areas.
     *
     * @param features current floor map features
     * @param startLocation origin used to convert local coordinates to latitude and longitude
     * @param elevatorDetected whether elevator motion is currently detected
     * @return horizontal motion scale applied before prediction
     */
    public float getHorizontalMovementScale(
            List<FloorplanApiClient.MapShapeFeature> features,
            LatLng startLocation,
            boolean elevatorDetected) {
        if (features == null || startLocation == null || features.isEmpty()) {
            return 1.0f;
        }

        Position estimate = getEstimatedPosition(features, startLocation);
        LatLng estimatedLatLng = toLatLng(estimate.x, estimate.y, startLocation);

        if (isInsideIndoorType(estimatedLatLng, features, "elevator")) {
            return elevatorDetected ? 0.05f : 0.15f;
        }
        if (isInsideIndoorType(estimatedLatLng, features, "stairs")) {
            return 0.18f;
        }
        return 1.0f;
    }

    /**
     * Suppresses particles that cross blocking geometry or end inside blocked polygons.
     *
     * @param walls blocking map features
     * @param startLocation origin used to convert local coordinates to latitude and longitude
     */
    public void applyMapMatching(
            List<FloorplanApiClient.MapShapeFeature> walls, LatLng startLocation) {
        if (walls == null || startLocation == null || particles.isEmpty()) {
            return;
        }

        boolean allDead = true;

        for (Particle particle : particles) {
            LatLng oldPos = toLatLng(particle.oldX, particle.oldY, startLocation);
            LatLng newPos = toLatLng(particle.x, particle.y, startLocation);
            boolean hitWall = isPathBlocked(oldPos, newPos, walls)
                    || isBlockedPosition(newPos, walls);

            if (hitWall) {
                particle.weight = 0.0;
            } else {
                allDead = false;
            }
        }

        if (allDead) {
            for (Particle particle : particles) {
                particle.x = particle.oldX + (float) (random.nextGaussian() * 0.1);
                particle.y = particle.oldY + (float) (random.nextGaussian() * 0.1);
                particle.weight = 1.0;
            }
            return;
        }

        double totalWeight = 0;
        for (Particle particle : particles) {
            totalWeight += particle.weight;
        }

        if (totalWeight > 0 && totalWeight < particles.size()) {
            for (Particle particle : particles) {
                particle.weight /= totalWeight;
            }
            resample();
            android.util.Log.d("MapMatch", "Blocked particles were removed and resampled.");
        }
    }

    private LatLng toLatLng(float x, float y, LatLng startLocation) {
        double radius = 6378137.0;
        double lat0Rad = Math.toRadians(startLocation.latitude);
        double dLat = y / radius;
        double dLng = x / (radius * Math.cos(lat0Rad));
        return new LatLng(
                startLocation.latitude + Math.toDegrees(dLat),
                startLocation.longitude + Math.toDegrees(dLng));
    }

    private boolean isPathBlocked(
            LatLng oldPos, LatLng newPos, List<FloorplanApiClient.MapShapeFeature> walls) {
        for (FloorplanApiClient.MapShapeFeature wall : walls) {
            if (!isBlockingFeature(wall)) {
                continue;
            }
            for (List<LatLng> part : wall.getParts()) {
                if (part == null || part.size() < 2) {
                    continue;
                }
                for (int i = 0; i < part.size() - 1; i++) {
                    if (GeometryUtils.doSegmentsIntersect(oldPos, newPos, part.get(i), part.get(i + 1))) {
                        return true;
                    }
                }
                if (part.size() > 2
                        && GeometryUtils.doSegmentsIntersect(
                                oldPos, newPos, part.get(part.size() - 1), part.get(0))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isBlockedPosition(
            LatLng position, List<FloorplanApiClient.MapShapeFeature> walls) {
        for (FloorplanApiClient.MapShapeFeature wall : walls) {
            if (!isBlockingFeature(wall)) {
                continue;
            }
            if (!"Polygon".equals(wall.getGeometryType())
                    && !"MultiPolygon".equals(wall.getGeometryType())) {
                continue;
            }
            for (List<LatLng> part : wall.getParts()) {
                if (part != null && part.size() >= 3
                        && GeometryUtils.isPointInPolygon(position, part)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInsideIndoorType(
            LatLng position,
            List<FloorplanApiClient.MapShapeFeature> features,
            String indoorType) {
        for (FloorplanApiClient.MapShapeFeature feature : features) {
            if (!indoorType.equals(feature.getIndoorType())) {
                continue;
            }
            if (!"Polygon".equals(feature.getGeometryType())
                    && !"MultiPolygon".equals(feature.getGeometryType())) {
                continue;
            }
            for (List<LatLng> part : feature.getParts()) {
                if (part != null && part.size() >= 3
                        && GeometryUtils.isPointInPolygon(position, part)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isBlockingFeature(FloorplanApiClient.MapShapeFeature feature) {
        return "wall".equals(feature.getIndoorType())
                || "unaccessible".equals(feature.getIndoorType());
    }
}
