package com.openpositioning.PositionMe.processing.filters;

import org.ejml.simple.SimpleMatrix;

/**
 * An adapter class that allows to interact with a Filter object using PDR data and
 * position observations (e.g. WiFi, GNSS). https://refactoring.guru/design-patterns/adapter
 *
 * @author Wojciech Boncela
 */
public interface FilterAdapter {
    /**
     * Discard the previous observations, and reinitialize the filter with a known position
     * @param pos The new position
     * @param cov Initial covariance
     * @param pdrPos A PDR reading recorded at the same time as the new position
     * @param timestamp The timestamp of the new position
     * @return true if succeeded, false otherwise
     */
    boolean reset(double[] pos, SimpleMatrix cov, double[] pdrPos, double timestamp);

    /**
     * Updates the current position and covariance using the PDR data and a new observation
     * @param newPdrPos A PDR reading recorded at the same time as the observation
     * @param observedPos A new observation
     * @param timestamp The timestamp of the new observation
     * @param observedCov The observation's covariance
     * @return true if succeeded, false otherwise
     */
    boolean update(double[] newPdrPos, double[] observedPos, double timestamp,
                SimpleMatrix observedCov);

    /**
     * @return the current position
     */
    double[] getPos();

    /**
     * @return the current covariance
     */
    SimpleMatrix getCovariance();
}
