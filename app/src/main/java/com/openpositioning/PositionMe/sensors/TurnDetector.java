package com.openpositioning.PositionMe.sensors;

import android.util.Log;

/**
 * This class represents a Turn Detector used for monitoring user movement based on orientation data.
 * It detects turns and pseudo-turns and provides the corresponding movement type.
 *
 * @author Thomas Deppe
 * @author Alexandra Geciova
 * @author Christopher Khoo
 */
public class TurnDetector {
    //Threshold to be classed as turn
    private static final float TURN_THRESHOLD = 1.2f;
    //Threshold for small orientation changes that are not indicative of a turn or straight
    private static final float PSEUDO_TURN = 0.6f;
    //The previous value
    private float orientationPrev;
    //Whether to start monitoring, to stop unnecessary computations
    private boolean startMonitoring;
    //The users movement
    private MovementType userMovement;

    // A enum storing the users movement type
    public enum MovementType {
        STRAIGHT,
        PSEUDO_TURN,
        TURN;

        /**
         * Compares this type with another type to determine if it should be updated
         * based on specific rules. The rules are as follows:
         * 1. If the current type is TURN, it remains unchanged.
         * 2. If the current type is PSEUDO_TURN and the new type is TURN, update to TURN.
         * 3. STRAIGHT updates to any type.
         *
         * @param newType The new type to compare with the current one.
         * @return The updated type, or the current type if no update should be made according to the rules.
         */
        public MovementType compareAndUpdate(MovementType newType) {
            if (this == TURN) {
                // If the current type is TURN, it remains unchanged.
                return this;
            } else if (this == PSEUDO_TURN && newType == TURN) {
                // Allows updating from PSEUDO_TURN to TURN.
                return TURN;
            } else if (this == PSEUDO_TURN && newType == STRAIGHT){
                // If new type is STRAIGHT do not update PSEUDO_TURN.
                return this;
            }

            // STRAIGHT updates to any type.
            return newType;
        }
    }

    /**
     * Initializes a new TurnDetector instance.
     * Sets the initial orientation and user movement type.
     */
    public TurnDetector() {
        this.orientationPrev = 0;
        this.userMovement = MovementType.STRAIGHT;
    }

    /**
     * Processes orientation data to detect turns.
     * Updates the user movement type based on the change in orientation.
     *
     * @param orientationUpdate The updated orientation value.
     */
    public void ProcessOrientationData(float orientationUpdate){
        if (!this.startMonitoring) return;

        float azimuthInDegrees = (float) (Math.toDegrees(orientationUpdate) + 360) % 360;
        float azimuthChange = Math.abs(azimuthInDegrees - orientationPrev);
        //double azimuthChange = wraptopi(Math.abs(orientationUpdate - orientationPrev));
        if (azimuthChange > 180) {
            azimuthChange = 360 - azimuthChange;
        }

        if (azimuthChange > TURN_THRESHOLD){
            userMovement = userMovement.compareAndUpdate(MovementType.TURN);
        } else if (azimuthChange > PSEUDO_TURN){
            userMovement = userMovement.compareAndUpdate(MovementType.PSEUDO_TURN);
        } else if (azimuthChange < PSEUDO_TURN){
            userMovement = userMovement.compareAndUpdate(MovementType.STRAIGHT);
        }

        orientationPrev = azimuthInDegrees;
    }

    /**
     * Processes a detected step event, updating the user movement type.
     * Resets the user movement type to straight after processing.
     *
     * @param orientation The orientation at the time of the step event.
     * @return The movement type detected for the step.
     */
    public MovementType onStepDetected(float orientation){
        ProcessOrientationData(orientation);
        MovementType resultForStep = this.userMovement;
        Log.d("EKF", "OUTPUT from turn detector "+userMovement.toString());
        this.userMovement = MovementType.STRAIGHT;
        return resultForStep;
    }

    /**
     * Starts monitoring orientation changes.
     */
    public void startMonitoring(){
        this.startMonitoring = true;
    }

    /**
     * Stops monitoring orientation changes and resets the user movement type to straight.
     */
    public void stopMonitoring(){
        this.startMonitoring = false;
        this.userMovement = MovementType.STRAIGHT;

    }
}