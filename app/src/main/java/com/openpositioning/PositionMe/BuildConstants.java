package com.openpositioning.PositionMe;

/**
 * Compile-time constants shared across the application.
 *
 * <p>When {@link #DEBUG} is {@code false} the compiler eliminates all
 * guarded {@code Log} calls, producing zero runtime overhead.</p>
 */
public final class BuildConstants {

    /** Master debug switch. Set {@code true} during development, {@code false} for release. */
    public static final boolean DEBUG = false;

    private BuildConstants() { /* non-instantiable */ }
}
