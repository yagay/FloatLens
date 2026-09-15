package com.yagay.floatlens;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LsposedRuntimeConfigTest {
    @Test public void providerRequiresBothSwitches() {
        assertFalse(LsposedRuntimeConfig.isEnabled(false, false));
        assertFalse(LsposedRuntimeConfig.isEnabled(true, false));
        assertFalse(LsposedRuntimeConfig.isEnabled(false, true));
        assertTrue(LsposedRuntimeConfig.isEnabled(true, true));
    }
}
