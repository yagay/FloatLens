package com.yagay.floatlens;

/**
 * Temporary source-compatibility shim for callers that only need to dismiss the active Circle UI.
 * The legacy Circle Select overlay implementation was removed; all behavior delegates to the
 * single Google Circle workspace.
 */
@Deprecated
final class CircleSelectOverlay {
    static void dismissActive(String reason) {
        GoogleCircleInlineOverlay.dismissActive(reason);
    }

    private CircleSelectOverlay() {}
}
