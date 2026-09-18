package com.yagay.floatlens;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import org.junit.Test;

public class GoogleCtsContractTest {
    @Test public void markerRequiresTriggerAndToken() {
        Bundle b = new Bundle();
        assertFalse(GoogleCtsContract.isFloatLensSession(b));
        b.putBoolean(GoogleCtsContract.K_TRIGGER, true);
        assertFalse(GoogleCtsContract.isFloatLensSession(b));
        b.putString(GoogleCtsContract.K_SESSION_TOKEN, "abc");
        assertTrue(GoogleCtsContract.isFloatLensSession(b));
    }
}
