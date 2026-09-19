package com.yagay.floatlens;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class OverlayRegistryTest {
    @Test public void registeredOwnerReceivesBothLifecycleSignals() {
        AtomicInteger host = new AtomicInteger();
        AtomicInteger geometry = new AtomicInteger();
        OverlayRegistry.Owner owner = new OverlayRegistry.Owner() {
            @Override public void onAccessibilityHostChanged(boolean available) {
                host.addAndGet(available ? 1 : 10);
            }
            @Override public void onDisplayGeometryChanged() {
                geometry.incrementAndGet();
            }
        };

        OverlayRegistry.register("test-owner", owner);
        OverlayRegistry.onAccessibilityHostChanged(true);
        OverlayRegistry.onDisplayGeometryChanged();
        assertEquals(1, host.get());
        assertEquals(1, geometry.get());

        OverlayRegistry.unregister("test-owner", owner);
        OverlayRegistry.onAccessibilityHostChanged(false);
        OverlayRegistry.onDisplayGeometryChanged();
        assertEquals(1, host.get());
        assertEquals(1, geometry.get());
    }
}
