package com.yagay.floatlens;

/**
 * Selection operation type retained for source compatibility.
 *
 * The old 24dp owner-icon overlay implementation was removed. Direct FV-style selection now has a
 * single visual implementation in FvPointerOperationHintOverlay, anchored to FvProbePointOverlay.
 */
final class FvOperationHintOverlay {
    enum Mode { TEXT, IMAGE, SCREENSHOT }

    private FvOperationHintOverlay() {}
}
