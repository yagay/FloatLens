package com.yagay.floatlens;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HookReloadManagerTest {
    @Test public void staleTargetAlwaysNeedsReload() {
        assertTrue(HookReloadManager.needsReloadForUpdate(
                100L, 100L, true, true));
    }

    @Test public void sameVersionDebugInstallNeedsReloadWhenTargetIsRunning() {
        assertTrue(HookReloadManager.needsReloadForUpdate(
                200L, 100L, true, false));
    }

    @Test public void updatedApkDoesNotNeedKillWhenTargetIsNotRunning() {
        assertFalse(HookReloadManager.needsReloadForUpdate(
                200L, 100L, false, false));
    }

    @Test public void matchingApkUpdateMarkerDoesNotNeedReload() {
        assertFalse(HookReloadManager.needsReloadForUpdate(
                200L, 200L, true, false));
    }
}
