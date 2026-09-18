package com.yagay.floatlens.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GoogleLens1758ProfileTest {

    private static class OptionalBase {}
    private static final class OptionalImpl extends OptionalBase {}

    @Test public void optionalRuntimeSubclassMatchesDeclaredBaseType() {
        assertTrue(GoogleLens1758Profile.hasTypeInHierarchy(
                OptionalImpl.class, OptionalBase.class.getName()));
        assertTrue(GoogleLens1758Profile.hasTypeInHierarchy(
                OptionalImpl.class, OptionalImpl.class.getName()));
        assertFalse(GoogleLens1758Profile.hasTypeInHierarchy(
                OptionalImpl.class, "missing.OptionalType"));
    }

    @Test public void resultCompletionRequiresImageAndAnyPresentInteraction() {
        assertFalse(GoogleLens1758Profile.isCompleteState(false, false, false));
        assertTrue(GoogleLens1758Profile.isCompleteState(true, false, false));
        assertFalse(GoogleLens1758Profile.isCompleteState(true, true, false));
        assertTrue(GoogleLens1758Profile.isCompleteState(true, true, true));
    }
}
