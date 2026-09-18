package com.yagay.floatlens;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GoogleCtsContractTest {
    @Test public void markerRequiresTriggerAndToken() {
        assertFalse(GoogleCtsContract.isFloatLensSession(false, ""));
        assertFalse(GoogleCtsContract.isFloatLensSession(true, ""));
        assertFalse(GoogleCtsContract.isFloatLensSession(true, null));
        assertTrue(GoogleCtsContract.isFloatLensSession(true, "abc"));
    }
}
