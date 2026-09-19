package com.yagay.floatlens.hook;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import org.junit.Test;

import java.lang.reflect.Method;

public class GoogleHookCapabilityMatrixTest {
    private static GoogleSelectionAdapter.Binding selection(boolean available) {
        return new GoogleSelectionAdapter.Binding() {
            @Override public boolean available() { return available; }
            @Override public Method method() { return null; }
            @Override public GoogleSelectionAdapter.Snapshot snapshot(Object metadata, Context context) {
                return new GoogleSelectionAdapter.Snapshot("", null, "", "", false);
            }
            @Override public String detail() { return "test"; }
            @Override public String source() { return available ? "test" : "none"; }
            @Override public int confidence() { return available ? 100 : 0; }
        };
    }

    private static GoogleLensFrameCapture.Binding frame(boolean available) {
        return available
                ? new GoogleLensFrameCapture.Binding(true, false, false, false, 35, "test")
                : new GoogleLensFrameCapture.Binding(false, false, false, false, 0, "test");
    }

    private static GoogleRegionGestureHook.Binding gesture(boolean available) throws Exception {
        Method method = String.class.getDeclaredMethod("length");
        return available
                ? new GoogleRegionGestureHook.Binding(
                        Object.class, new Method[]{method}, 95, "test", "test")
                : new GoogleRegionGestureHook.Binding(null, null, 0, "none", "test");
    }

    private static GoogleLensViewportHook.Binding viewport() {
        return new GoogleLensViewportHook.Binding(
                Object.class, Object.class, Object.class, 100, "test", "test");
    }

    @Test public void fullCanonicalRequiresSelectionFrameAndGesture() throws Exception {
        GoogleHookCapabilityMatrix matrix = new GoogleHookCapabilityMatrix(
                selection(true), frame(true), gesture(true), viewport());
        assertEquals(GoogleHookCapabilityMatrix.Mode.FULL_CANONICAL, matrix.mode);
    }

    @Test public void missingGestureFallsBackWithoutLosingCanonicalFrame() throws Exception {
        GoogleHookCapabilityMatrix matrix = new GoogleHookCapabilityMatrix(
                selection(true), frame(true), gesture(false), viewport());
        assertEquals(GoogleHookCapabilityMatrix.Mode.CANONICAL_NO_GESTURE, matrix.mode);
    }

    @Test public void selectionAloneRemainsUsable() throws Exception {
        GoogleHookCapabilityMatrix matrix = new GoogleHookCapabilityMatrix(
                selection(true), frame(false), gesture(false), viewport());
        assertEquals(GoogleHookCapabilityMatrix.Mode.SELECTION_ONLY, matrix.mode);
    }

    @Test public void missingSelectionDisablesGoogleBridgeCapability() throws Exception {
        GoogleHookCapabilityMatrix matrix = new GoogleHookCapabilityMatrix(
                selection(false), frame(true), gesture(true), viewport());
        assertEquals(GoogleHookCapabilityMatrix.Mode.UNAVAILABLE, matrix.mode);
    }
}
