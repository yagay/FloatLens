package com.yagay.floatlens.hook;

/** Single resolved capability snapshot for one loaded Google App process. */
final class GoogleHookCapabilityMatrix {
    enum Mode {
        FULL_CANONICAL,
        CANONICAL_NO_GESTURE,
        SELECTION_ONLY,
        UNAVAILABLE
    }

    final GoogleSelectionAdapter.Binding selection;
    final GoogleLensFrameCapture.Binding frame;
    final GoogleRegionGestureHook.Binding regionGesture;
    final GoogleLensViewportHook.Binding viewport;
    final Mode mode;

    GoogleHookCapabilityMatrix(
            GoogleSelectionAdapter.Binding selection,
            GoogleLensFrameCapture.Binding frame,
            GoogleRegionGestureHook.Binding regionGesture,
            GoogleLensViewportHook.Binding viewport) {
        this.selection = selection;
        this.frame = frame;
        this.regionGesture = regionGesture;
        this.viewport = viewport;
        boolean hasSelection = selection != null && selection.available();
        boolean hasFrame = frame != null && frame.available();
        boolean hasGesture = regionGesture != null && regionGesture.available();
        if (hasSelection && hasFrame && hasGesture) mode = Mode.FULL_CANONICAL;
        else if (hasSelection && hasFrame) mode = Mode.CANONICAL_NO_GESTURE;
        else if (hasSelection) mode = Mode.SELECTION_ONLY;
        else mode = Mode.UNAVAILABLE;
    }

    static GoogleHookCapabilityMatrix resolve(ClassLoader loader) {
        return new GoogleHookCapabilityMatrix(
                GoogleSelectionAdapter.resolve(loader),
                GoogleLensFrameCapture.resolve(loader),
                GoogleRegionGestureHook.resolve(loader),
                GoogleLensViewportHook.resolve(loader));
    }

    String detail() {
        return "mode=" + mode
                + " selection=" + source(selection == null ? null : selection.source(),
                        selection == null ? 0 : selection.confidence())
                + " frame=" + source(frame == null ? null : frame.source(),
                        frame == null ? 0 : frame.confidence)
                + " gesture=" + source(regionGesture == null ? null : regionGesture.source,
                        regionGesture == null ? 0 : regionGesture.confidence)
                + " viewport=" + source(viewport == null ? null : viewport.source,
                        viewport == null ? 0 : viewport.confidence);
    }

    private static String source(String source, int confidence) {
        String value = source == null || source.isBlank() ? "none" : source;
        return value + "@" + confidence;
    }
}
