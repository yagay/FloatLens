package com.yagay.floatlens.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GoogleLens1758ProfileTest {
    @Test public void resultCompletionRequiresImageAndAnyPresentInteraction() {
        assertFalse(GoogleLens1758Profile.isCompleteState(false, false, false));
        assertTrue(GoogleLens1758Profile.isCompleteState(true, false, false));
        assertFalse(GoogleLens1758Profile.isCompleteState(true, true, false));
        assertTrue(GoogleLens1758Profile.isCompleteState(true, true, true));
    }
}
