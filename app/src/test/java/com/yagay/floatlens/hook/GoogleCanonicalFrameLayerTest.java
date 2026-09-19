package com.yagay.floatlens.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GoogleCanonicalFrameLayerTest {
    @Test public void sameSizeCandidateNeverReplacesCanonicalFrame() {
        assertFalse(GoogleCanonicalFrameLayer.shouldReplace(1272, 2772, 1272, 2772));
    }

    @Test public void smallerCandidateNeverReplacesCanonicalFrame() {
        assertFalse(GoogleCanonicalFrameLayer.shouldReplace(1272, 2772, 1080, 2340));
    }

    @Test public void largerCandidateCanUpgradeIncompleteFrame() {
        assertTrue(GoogleCanonicalFrameLayer.shouldReplace(720, 1568, 1272, 2772));
    }

    @Test public void missingCurrentFrameAcceptsFirstCandidate() {
        assertTrue(GoogleCanonicalFrameLayer.shouldReplace(0, 0, 1272, 2772));
    }
}
