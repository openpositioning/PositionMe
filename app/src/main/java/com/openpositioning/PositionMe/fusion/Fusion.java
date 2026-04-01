package com.openpositioning.PositionMe.fusion;

import static com.openpositioning.PositionMe.fusion.FusionConstants.FLOOR_CHANGE_PERCENTAGE;
import static com.openpositioning.PositionMe.fusion.FusionConstants.FUSION_TYPE_MAX;
import static com.openpositioning.PositionMe.fusion.FusionConstants.OBSERVATION_TYPE_GNSS;
import static com.openpositioning.PositionMe.fusion.FusionConstants.OBSERVATION_TYPE_WIFI;
import static com.openpositioning.PositionMe.fusion.FusionConstants.WIFI_STD_DEV;
import static com.openpositioning.PositionMe.utils.BuildingConstants.BUILDING_ELEMENT_LIFT;
import static com.openpositioning.PositionMe.utils.BuildingConstants.BUILDING_ELEMENT_STAIRS;
import static com.openpositioning.PositionMe.utils.BuildingConstants.BUILDING_ELEMENT_WALL;
import static com.openpositioning.PositionMe.utils.BuildingConstants.BUILDING_NO_FLOOR_NAME;
import static com.openpositioning.PositionMe.utils.BuildingConstants.BUILDING_NO_FLOOR_NUMBER;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.preference.PreferenceManager;
import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.Building;
import com.openpositioning.PositionMe.utils.FloorPlan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Top-level fusion class that manages corrected position estimation.
 *
 * <p>Wraps a {@link ParticleFilter} and exposes a simplified interface for the rest of the
 * application. PDR updates are forwarded to the particle filter, and the best position estimate is
 * derived from the weighted particle population.
 *
 * @see ParticleFilter for the underlying sequential Monte Carlo implementation.
 * @see SensorFusion for the caller that drives PDR and WiFi updates.
 */
public class Fusion {
    private static final String TAG = "Fusion";
    // Current best position estimate in WGS84
    private LatLng bestEstimate;
    // Estimated floor level
    private int estimatedFloor;
    // Whether the fusion system is actively tracking
    public boolean isActive;
    // Underlying particle filter instance
    private final ParticleFilter particleFilter;
    // Map matching logic
    private final MapMatching mapMatching;
    // Building used for map matching
    private Building building;
    // Keep track of users elevation at the entry of each floor
    private float elevationAtFloorEntry;

    // Arbitrary amount to ensure only valid GNSS readings are used for initialisation
    private static final float MAX_GNSS_ACCURACY_M = 50f;

    // List of positions from different sensors readings
    private final List<LatLng> obsPositions = new ArrayList<>();
    private final List<String> obsTypes = new ArrayList<>();
    private final List<String> obsWiFiFloors = new ArrayList<>();

    // Timestamps of readings - currently not used
    private final List<Long> obsTimestamps = new ArrayList<>();
    private final List<LatLng> liveTrajectory = new ArrayList<>();

    // Set by either GNSS or Wi-Fi
    private LatLng startLocation = null;

    private int lastWiFiFloor = BUILDING_NO_FLOOR_NUMBER;
    private float sigma;
    private int maximumNumberOfObservations;
    private double refLng, refLat;

    private int previousWiFiFloor = BUILDING_NO_FLOOR_NUMBER;

    public Fusion(Context context) {
        this.mapMatching = new MapMatching();
        this.particleFilter = new ParticleFilter(context, this.mapMatching);
        updateConstants(context);
    }

    public void updateConstants(Context context) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        if (settings.getBoolean("overwrite_fusion_constants", false)) {
            maximumNumberOfObservations =
                    settings.getInt("fusion_observation_count", FUSION_TYPE_MAX);
        } else {
            maximumNumberOfObservations = FUSION_TYPE_MAX;
        }

        Log.d(TAG, "Constants updated");
        Log.d(TAG, "maximumNumberOfObservations: " + maximumNumberOfObservations);
        particleFilter.updateConstants(context);
    }

    /**
     * Allows the maximum number of {@link Particle Particles} used by the {@link ParticleFilter} to
     * be changed
     *
     * @param newMaximum The new maximum number of particles
     */
    public void updateMaximumNumberOfParticles(int newMaximum) {
        particleFilter.setMaximumNumberOfParticles(newMaximum);
    }

    /**
     * Allows the maximum number of observations saved for use in fusion algorithm to be changed
     *
     * @param newMaximum The new maximum number of observations
     */
    public void updateMaximumNumberOfObservations(int newMaximum) {
        maximumNumberOfObservations = newMaximum;
    }

    public void updateRepopulationJitter(float newJitter) {
        particleFilter.updateRepopulationJitter(newJitter);
    }

    public List<Particle> getParticles() {
        return particleFilter.getParticles();
    }

    public LatLng convertENToLatLng(double[] en) {
        return particleFilter.enToLatLng(en[0], en[1]);
    }

    /**
     * Initialises the fusion system and seeds the {@link ParticleFilter} around the given position.
     *
     * @param initialEstimate is a place holder for the starting position in WGS84 coordinates
     * @param initialElevation ???
     * @see SensorFusion#startRecording()
     */
    public void start(LatLng initialEstimate, float initialElevation) {
        isActive = true;
        this.bestEstimate = getStartLocation(initialEstimate);
        liveTrajectory.clear();
        obsPositions.clear();
        obsTypes.clear();
        obsWiFiFloors.clear();
        obsTimestamps.clear();
        particleFilter.start(bestEstimate, sigma);
        elevationAtFloorEntry = initialElevation;
        estimatedFloor = lastWiFiFloor;
        mapMatching.setFloor(estimatedFloor);
        Log.d(
                TAG,
                "Fusion started at: "
                        + initialEstimate
                        + "; sigma = "
                        + sigma
                        + "; Floor "
                        + estimatedFloor);
    }

    /** Stops the fusion system and releases the particle filter resources. */
    public void stop() {
        Log.i(TAG, "Fusion stopped");
        this.particleFilter.stop();
        isActive = false;
        startLocation = null;
        obsPositions.clear();
        obsTypes.clear();
        obsWiFiFloors.clear();
        obsTimestamps.clear();
        //        liveTrajectory.clear();
    }

    /**
     * Wrapper for {@link Fusion#addObservation(LatLng, String, double) Fusion.addObservation()} to
     * allow saving of floor name which the Wi-Fi observation is associated with
     *
     * @param pos The position of the observation
     * @param floorName The floor the Wi-Fi APS observation is associated with
     */
    private void addWiFiObservation(LatLng pos, String floorName) {
        obsWiFiFloors.add(floorName);
        addObservation(pos, OBSERVATION_TYPE_WIFI, WIFI_STD_DEV);
    }

    /**
     * Get every observation currently available of a given type
     *
     * @param type The {@link FusionConstants type} of observation requested
     * @return A {@link Map} of {@link List Lists} of positions, timestamps, and floors. Note that
     *     floors will be null if the type is not Wi-Fi
     * @see FusionConstants
     */
    public Map<String, Object> getObservationsByType(String type) {
        Map<String, Object> result = new HashMap<>();

        List<LatLng> positions = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();
        List<String> floorNames = null;

        if (type.equals(OBSERVATION_TYPE_WIFI)) {
            floorNames = new ArrayList<>();
            cleanWiFiObservationFloorNames();
        }

        for (int i = 0; i < obsPositions.size(); i++) {
            if (obsTypes.get(i).equals(type)) {
                positions.add(obsPositions.get(i));
                timestamps.add(obsTimestamps.get(i));
            }
        }

        if (type.equals(OBSERVATION_TYPE_WIFI)) {
            floorNames.addAll(obsWiFiFloors);
        }

        result.put("positions", positions);
        result.put("timestamps", timestamps);
        result.put("floor_names", floorNames);

        return result;
    }

    /**
     * Replace entries in the floor name list that are the floor's index (ie, before the {@link
     * Building} has been initialised on the start of a recording) with the floor's name.
     *
     * <p>Only replaces entries after the Fusion's {@link Building} has been initialised.
     */
    private void cleanWiFiObservationFloorNames() {
        if (building != null) {
            for (int i = 0; i < obsWiFiFloors.size(); i++) {
                String name = obsWiFiFloors.get(i);
                if (!Pattern.matches("^[A-Za-z].*", name)) {
                    int floorNumber = Integer.parseInt(name);
                    name = building.getFloorNames().get(floorNumber);
                }
                obsWiFiFloors.set(i, name);
            }
        }
    }

    /**
     * Save different types of observations, the position provided, and the timestamp
     *
     * @param pos The position of the observation
     * @param type The {@link FusionConstants type} of observation
     */
    private void addObservation(LatLng pos, String type, double sigma) {
        obsPositions.add(pos);
        obsTypes.add(type);
        obsTimestamps.add(System.currentTimeMillis());

        double[] east_north = particleFilter.latLngToEN(pos.latitude, pos.longitude);
        particleFilter.addObservation(east_north[0], east_north[1], sigma);
        // Count how many observations of this type exist and remove oldest if too many exist
        int typeCount = 0;
        for (int i = 0; i < obsTypes.size(); i++) {
            if (obsTypes.get(i).equals(type)) typeCount++;
        }

        if (typeCount > maximumNumberOfObservations) {
            // Remove the oldest observation of this type
            for (int i = 0; i < obsTypes.size(); i++) {
                if (obsTypes.get(i).equals(type)) {
                    obsPositions.remove(i);
                    obsTypes.remove(i);
                    obsTimestamps.remove(i);
                    if (type.equals(OBSERVATION_TYPE_WIFI)) {
                        obsWiFiFloors.remove(0);
                    }
                    break; // only remove one — the oldest of this type
                }
            }
        }
        if (particleFilter.isActive()) {
            particleFilter.updateOnWifiOrGNSS();
        }
    }

    /**
     * Forwards a PDR displacement to the {@link ParticleFilter} for the prediction step.
     *
     * @param stepLength ???
     * @param rawHeading ???
     * @param dx Easting displacement in metres.
     * @param dy Northing displacement in metres.
     */
    public void onPDRUpdate(float stepLength, float rawHeading, double dx, double dy) {
        particleFilter.updateWithPDR(stepLength, rawHeading, dx, dy);
        LatLng est = particleFilter.getEstimatedPosition();
        // if (est != null) addObservation(est, OBSERVATION_TYPE_PDR);
    }

    /**
     * Converts GNSS from {@link LatLng} to local EN metres and queues as a particle filter
     * observation
     *
     * <p>accuracyMetres comes from location.getAccuracy()
     *
     * <p>TODO - Finish JavaDocs
     *
     * @param pos ???
     * @param accuracyMetres ???
     */
    public void onGnssUpdate(LatLng pos, float accuracyMetres) {
        // First acceptable GNSS fix — seed particles around it and start
        if (accuracyMetres < MAX_GNSS_ACCURACY_M) {
            bestEstimate = pos;
            startLocation = pos;
            sigma = accuracyMetres;
            Log.d(TAG, "New GNSS Observation: " + pos + "; sigma = " + accuracyMetres + "m");
        }

        // TODO - Do we want to add an observation if it isn't necessarily acceptable?
        // (ie, should this not be inside the if() check?)
        double[] en = particleFilter.latLngToEN(pos.latitude, pos.longitude);
        // particleFilter.addObservation(en[0], en[1], accuracyMetres);
        addObservation(pos, OBSERVATION_TYPE_GNSS, accuracyMetres);
    }

    /**
     * Converts WiFi position from WGS84 to local EN metres and queues as a particle filter
     * observation
     *
     * <p>sigma is fixed at 10.0 m - arbitrary for now
     *
     * <p>TODO - Finish JavaDocs
     *
     * @param pos The {@link LatLng} position of the WI-Fi router
     * @param sigmaMetres ???
     * @param floor The floor the router is associated with
     */
    public void onWifiUpdate(LatLng pos, float sigmaMetres, int floor) {
        // WiFi can seed if GNSS hasn't arrived yet
        startLocation = pos;
        sigma = sigmaMetres;
        Log.d(
                TAG,
                "New Wi-Fi Observation: "
                        + pos
                        + "; sigma = "
                        + sigmaMetres
                        + "m; Floor = "
                        + floor);

        // Coursework 2 - Index floors from GF = 0 (26/03/2026)
        lastWiFiFloor = floor + 1;

        if (previousWiFiFloor == BUILDING_NO_FLOOR_NUMBER) {
            previousWiFiFloor = lastWiFiFloor;
        }

        // TODO - Is this correct?
        bestEstimate = pos;

        double[] posEastNorth = particleFilter.latLngToEN(pos.latitude, pos.longitude);
        // particleFilter.addObservation(posEastNorth[0], posEastNorth[1], sigma);

        // Default to the floor's index if name is unavailable
        String floorName;
        if (building != null) {
            floorName = building.getFloorNames().get(lastWiFiFloor);
        } else {
            floorName = String.valueOf(lastWiFiFloor);
        }

        addWiFiObservation(pos, floorName);
    }

    /**
     * Called when the barometer detects a change in altitude. Only updates the floor estimate if
     * the user is currently in a staircase or elevator.
     *
     * @param altitudeMetres current altitude from barometer
     */
    public void onAltitudeUpdate(float altitudeMetres) {
        if (!isActive) return;

        // Check if current position is in stairs/lift
        LatLng pos = particleFilter.getEstimatedPosition();
        double[] en = particleFilter.latLngToEN(pos.latitude, pos.longitude);

        // Temporary change to allow floor changing anywhere (debug)
        boolean eligible = mapMatching.isEligibleForAltitudeChange(en);
        // boolean eligible = building != null;

        if (!eligible) return;

        // Convert altitude change to floor change
        float floorHeight = building.getFloorHeight();
        float delta = altitudeMetres - elevationAtFloorEntry;
        if (Math.abs(delta) >= floorHeight * FLOOR_CHANGE_PERCENTAGE) {
            int floorChange = (delta > 0) ? 1 : -1;

            int newFloor = estimatedFloor + floorChange;
            int maxFloor = building.getFloorNames().size() - 1;
            newFloor = Math.max(0, Math.min(newFloor, maxFloor));

            if (newFloor != estimatedFloor) {
                estimatedFloor = newFloor;
                mapMatching.setFloor(newFloor);
                elevationAtFloorEntry = altitudeMetres;
                Log.d(
                        TAG,
                        "Floor via barometer changed to "
                                + estimatedFloor
                                + " ("
                                + building.getFloorNames().get(estimatedFloor)
                                + ")");
            } else if (lastWiFiFloor != previousWiFiFloor) {
                previousWiFiFloor = lastWiFiFloor;
                estimatedFloor = lastWiFiFloor;
                mapMatching.setFloor(lastWiFiFloor);
                Log.d(
                        TAG,
                        "Floor via Wifi changed to "
                                + estimatedFloor
                                + " ("
                                + building.getFloorNames().get(estimatedFloor)
                                + ")");
            } else {
                Log.d(
                        TAG,
                        "Already on floor "
                                + estimatedFloor
                                + " ("
                                + building.getFloorNames().get(estimatedFloor)
                                + ")");
            }
        } else {
            Log.d(
                    TAG,
                    Math.abs(delta)
                            + " must be at least "
                            + (floorHeight * FLOOR_CHANGE_PERCENTAGE)
                            + " to change floors");
        }
    }

    /**
     * Converts a {@link Building Building's} GeoJSON floor plan geometries from WGS84 (lat/lng)
     * into local East-North (EN) metre coordinates, then passes them to the {@link MapMatching}
     * instance for use in constraining particle positions.
     *
     * @param building The building whose floor plans are being converted
     */
    private void initialiseMapGeometries(Building building) {
        // Initialise hash maps that map floor indices to a list of floor plan elements
        Map<Integer, List<List<double[]>>> wallSegments = new HashMap<>();
        Map<Integer, List<List<double[]>>> stairPolygons = new HashMap<>();
        Map<Integer, List<List<double[]>>> elevatorPolygons = new HashMap<>();

        List<FloorPlan> floorPlans = building.getFloorPlans();
        for (FloorPlan floorPlan : floorPlans) {
            String floorName = floorPlan.getFloorName();

            List<List<LatLng>> floorWalls = floorPlan.getElementsOfType(BUILDING_ELEMENT_WALL);
            List<List<LatLng>> floorStairs = floorPlan.getElementsOfType(BUILDING_ELEMENT_STAIRS);
            List<List<LatLng>> floorLifts = floorPlan.getElementsOfType(BUILDING_ELEMENT_LIFT);

            List<List<double[]>> floorWallsEN = convertElementsLatLngToEN(floorWalls);
            List<List<double[]>> floorStairsEN = convertElementsLatLngToEN(floorStairs);
            List<List<double[]>> floorLiftsEN = convertElementsLatLngToEN(floorLifts);

            int floorIndex = building.getFloorNames().indexOf(floorName);
            wallSegments.put(floorIndex, floorWallsEN);
            stairPolygons.put(floorIndex, floorStairsEN);
            elevatorPolygons.put(floorIndex, floorLiftsEN);
        }
        this.mapMatching.setGeometries(wallSegments, stairPolygons, elevatorPolygons);
    }

    /**
     * Helper function to convert every {@link FloorPlan} element in a {@link List} from {@link
     * LatLng} to Easting-Northing
     *
     * @param elementsLatLng The list of {@link LatLng} elements
     * @return A list of Easting-Northing elements
     */
    private List<List<double[]>> convertElementsLatLngToEN(List<List<LatLng>> elementsLatLng) {
        List<List<double[]>> elementsEN = new ArrayList<>();

        for (List<LatLng> elementLatLng : elementsLatLng) {
            List<double[]> elementEN = new ArrayList<>();
            for (LatLng pointLatLng : elementLatLng) {
                double[] pointEN =
                        particleFilter.latLngToEN(pointLatLng.latitude, pointLatLng.longitude);
                elementEN.add(pointEN);
            }
            elementsEN.add(elementEN);
        }

        return elementsEN;
    }

    /**
     * Updates and returns the current fused position estimate from the particle filter.
     *
     * @return {@link LatLng} of the best estimated position.
     */
    public LatLng getBestEstimate() {
        this.bestEstimate = particleFilter.getEstimatedPosition();
        if (this.bestEstimate != null) {
            this.liveTrajectory.add(this.bestEstimate);
        }
        return bestEstimate;
    }

    public List<LatLng> getLiveTrajectory() {
        return liveTrajectory;
    }

    /**
     * TODO - Returns a uniquely fused starting position estimate. TODO - Is this ready now?
     *
     * @param initialEstimate ???
     * @return {@link LatLng} of the best start position estimate.
     */
    public LatLng getStartLocation(LatLng initialEstimate) {
        if (startLocation != null) {
            return startLocation;
        } else {
            return initialEstimate;
        }
    }

    public boolean isActive() {
        return isActive;
    }

    /**
     * Declare the {@link Building} as the current building for fusion
     *
     * @param building The building the user is currently inside of
     */
    public void setBuilding(Building building) {
        // Only proceed if building is different
        if (this.building != null && this.building.getName().equals(building.getName())) return;
        this.building = building;
        initialiseMapGeometries(this.building);
    }

    public int getEstimatedFloorNumber() {
        if (estimatedFloor != BUILDING_NO_FLOOR_NUMBER) {
            return estimatedFloor;
        } else {
            return building.getGroundFloorIndex();
        }
    }

    public String getEstimatedFloorName() {
        if (building == null) return BUILDING_NO_FLOOR_NAME;
        return building.getFloorNames().get(getEstimatedFloorNumber());
    }

    public double getElevation() {
        return elevationAtFloorEntry;
    }

    public double getOrientationError() {
        return particleFilter.getOrientationError();
    }
}
