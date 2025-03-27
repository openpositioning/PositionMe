package com.openpositioning.PositionMe.processing.filters;

import org.ejml.simple.SimpleMatrix;

/**
 * A common interface for XY position estimation filters. More derails here:
 * https://en.wikipedia.org/wiki/Filtering_problem_(stochastic_processes)
 *
 * @author Wojciech Boncela
 */
public interface Filter {
    int STATE_SIZE = 2;  // We only estimate XY position

    /**
     * Reset the estimated state of the filter to a specific value
     * @param pos the new position
     * @param cov initial covariance of the new state
     */
    void resetState(double[] pos, SimpleMatrix cov);

    /**
     * @return The current estimated position
     */
    double[] getPosition();

    /**
     * @return The current position covariance
     */
    SimpleMatrix getCovariance();
}
