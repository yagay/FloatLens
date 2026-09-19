package com.yagay.floatlens;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HookReloadManagerTest {
    @Test public void unchangedHookNeverNeedsReload() {
        assertFalse(HookReloadManager.fingerprintNeedsReload(
                "hook-a", "hook-a", true));
    }

    @Test public void appOnlyUpdateDoesNotMatterWhenHookFingerprintMatches() {
        assertFalse(HookReloadManager.fingerprintNeedsReload(
                "same-hook", "same-hook", true));
    }

    @Test public void changedHookNeedsReloadOnlyWhenOldTargetIsRunning() {
        assertTrue(HookReloadManager.fingerprintNeedsReload(
                "hook-b", "hook-a", true));
        assertFalse(HookReloadManager.fingerprintNeedsReload(
                "hook-b", "hook-a", false));
    }

    @Test public void missingFingerprintDoesNotForceRestart() {
        assertFalse(HookReloadManager.fingerprintNeedsReload(
                "", "hook-a", true));
    }
}
