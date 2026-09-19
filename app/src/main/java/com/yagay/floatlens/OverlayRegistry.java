package com.yagay.floatlens;

import java.util.LinkedHashMap;
import java.util.Map;

/** Process-wide lifecycle registry for transient FloatLens overlay owners. */
final class OverlayRegistry {
    interface Owner {
        void onAccessibilityHostChanged(boolean available);
        void onDisplayGeometryChanged();
    }

    private static final Object LOCK = new Object();
    private static final Map<String, Owner> OWNERS = new LinkedHashMap<>();

    static void register(String key, Owner owner) {
        if (key == null || key.isBlank() || owner == null) return;
        synchronized (LOCK) { OWNERS.put(key, owner); }
    }

    static void unregister(String key, Owner owner) {
        if (key == null || key.isBlank()) return;
        synchronized (LOCK) {
            Owner current = OWNERS.get(key);
            if (owner == null || current == owner) OWNERS.remove(key);
        }
    }

    static void onAccessibilityHostChanged(boolean available) {
        for (Owner owner : snapshot()) {
            try { owner.onAccessibilityHostChanged(available); }
            catch (Throwable ignored) { }
        }
    }

    static void onDisplayGeometryChanged() {
        for (Owner owner : snapshot()) {
            try { owner.onDisplayGeometryChanged(); }
            catch (Throwable ignored) { }
        }
    }

    static int ownerCount() {
        synchronized (LOCK) { return OWNERS.size(); }
    }

    private static Owner[] snapshot() {
        synchronized (LOCK) { return OWNERS.values().toArray(new Owner[0]); }
    }

    private OverlayRegistry() {}
}
