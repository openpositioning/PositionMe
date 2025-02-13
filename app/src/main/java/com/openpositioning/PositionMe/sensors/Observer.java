package com.openpositioning.PositionMe.sensors;

/**
 * Interface for observers of an observable class.
 *
 * Simplified version of default Observer interface, with only the update method included
 * see {@link java.util.Observer}.
 *
 * @author Virginia Cangelosi
 * @author Mate Stodulka
 */
public interface Observer {

    /**
     * Updates from the class implementing the {@link Observable} interface, where this instance is
     * registered as an observer.
     *
     * @param objList   an array of objects that were updated in the <code>Observable</code>
     */
    public void update(Object[] objList);
}
