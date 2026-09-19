package com.yagay.floatlens.hook;

/**
 * Pure-Java state owner for one FloatLens-marked Google CTS session.
 *
 * <p>All Google hook subsystems observe one generation-bound object instead of a collection of
 * independent volatile flags. Delayed callbacks must capture {@link #generation()} and verify
 * {@link #matches(long, String)} before mutating the session.</p>
 */
final class GoogleCtsSessionState {
    enum Phase {
        IDLE,
        ACTIVE,
        FRAME_READY,
        SELECTING,
        CONFIRM_PENDING,
        COMMITTED,
        FINISHED
    }

    static final class Bounds {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        boolean valid() { return right > left && bottom > top; }
    }

    private long generation;
    private String token = "";
    private long activeUntil;
    private int showSessionId = -1;
    private Phase phase = Phase.IDLE;
    private boolean frameQueued;
    private boolean selectionSeen;
    private boolean regionSelectionActive;
    private String selectionText = "";
    private Bounds selectionBounds;

    synchronized long begin(String nextToken, int nextShowSessionId, long until) {
        generation++;
        token = nextToken == null ? "" : nextToken;
        activeUntil = Math.max(0L, until);
        showSessionId = nextShowSessionId;
        phase = token.isBlank() ? Phase.IDLE : Phase.ACTIVE;
        frameQueued = false;
        selectionSeen = false;
        regionSelectionActive = false;
        selectionText = "";
        selectionBounds = null;
        return generation;
    }

    synchronized void extend(long until) {
        activeUntil = Math.max(activeUntil, until);
    }

    synchronized void setShowSessionId(int id) {
        if (id >= 0) showSessionId = id;
    }

    synchronized void onFrameQueued() {
        if (terminal()) return;
        frameQueued = true;
        if (phase == Phase.ACTIVE) phase = Phase.FRAME_READY;
    }

    synchronized void onSelection(String text, Bounds bounds, boolean region) {
        if (terminal()) return;
        selectionSeen = true;
        selectionText = text == null ? "" : text;
        if (bounds != null && bounds.valid()) selectionBounds = bounds;
        regionSelectionActive = region;
        phase = region ? Phase.CONFIRM_PENDING : Phase.SELECTING;
    }

    synchronized void onRegionGesture(boolean active) {
        if (terminal()) return;
        regionSelectionActive = active || regionSelectionActive;
        if (active && selectionSeen) phase = Phase.SELECTING;
        else if (!active && selectionBounds != null) phase = Phase.CONFIRM_PENDING;
    }

    synchronized boolean canConfirm(String expectedToken) {
        return !terminal()
                && expectedToken != null
                && expectedToken.equals(token)
                && selectionSeen
                && selectionBounds != null
                && selectionBounds.valid();
    }

    synchronized boolean markCommitted(String expectedToken) {
        if (terminal() || expectedToken == null || !expectedToken.equals(token)) return false;
        phase = Phase.COMMITTED;
        regionSelectionActive = false;
        return true;
    }

    synchronized void finish() {
        phase = Phase.FINISHED;
        activeUntil = 0L;
        frameQueued = false;
        regionSelectionActive = false;
    }

    synchronized boolean active(long nowElapsed) {
        return !token.isBlank() && !terminal() && nowElapsed < activeUntil;
    }

    synchronized boolean matches(long expectedGeneration, String expectedToken) {
        return generation == expectedGeneration
                && expectedToken != null
                && expectedToken.equals(token)
                && !terminal();
    }

    synchronized boolean terminal() {
        return phase == Phase.IDLE || phase == Phase.FINISHED;
    }

    synchronized long generation() { return generation; }
    synchronized String token() { return token; }
    synchronized long activeUntil() { return activeUntil; }
    synchronized int showSessionId() { return showSessionId; }
    synchronized Phase phase() { return phase; }
    synchronized boolean frameQueued() { return frameQueued; }
    synchronized boolean selectionSeen() { return selectionSeen; }
    synchronized boolean regionSelectionActive() { return regionSelectionActive; }
    synchronized String selectionText() { return selectionText; }
    synchronized Bounds selectionBounds() { return selectionBounds; }
}
