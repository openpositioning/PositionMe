package com.openpositioning.PositionMe.sensors;
/**
 * Interface for observable class.
 *
 * Simplified version of default Observable interface, with only the resgisterObserver and
 * notifyObservers methods included see {@link java.util.Observable}.
 *
 * @author Virginia Cangelosi
 * @author Mate Stodulka
 */
public interface Observable {
    /**
     * Register an object implementing the {@link Observer} interface to listen for updates.
     *
     * @param o instance of a class implementing the <code>Observer</code> interface
     */
    public void registerObserver(com.openpositioning.PositionMe.sensors.Observer o);

    /**
     * Notify observers of changes to relevant data structures. If there are multiple data structures
     * and not all are relevant to all observers, use the input to differentiate.
     *
     * @param idx   int index signaling which data-structure was updated.
     */
    public void notifyObservers(int idx);
}

