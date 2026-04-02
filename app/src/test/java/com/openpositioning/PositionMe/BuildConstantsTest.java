package com.openpositioning.PositionMe;

import static org.junit.Assert.*;
import org.junit.Test;

/**
 * Verifies {@link BuildConstants} configuration for release.
 */
public class BuildConstantsTest {

    @Test
    public void debug_isDisabledForRelease() {
        assertFalse("DEBUG must be false for release builds", BuildConstants.DEBUG);
    }
}
