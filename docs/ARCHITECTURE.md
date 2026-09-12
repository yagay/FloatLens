# FloatLens Architecture

This document describes the ownership boundaries that should be preserved when extending FloatLens.
The goal is to keep FV-compatible interaction semantics while avoiding parallel implementations of
window hosting, screenshot capture, OCR result delivery, text selection and geometry.

## 1. Floating icon input

### `FloatIconView`
Owns pointer semantics only:

- immediate temporary-follow movement from `ACTION_DOWN` absolute displacement;
- FV-style 400 ms direct-selection dwell;
- 3 dp per-axis dwell re-arm box;
- long press, tap/double tap and gesture state;
- hand-off to `ViewSelectionEngine`;
- explicit icon-position move mode.

Do not add image decoding, window hosting, screenshot capture or result UI here.

### `FloatIconRenderer`
Owns icon appearance only:

- default icon drawing;
- custom drawable / animated image decoding;
- slideshow image lifecycle and timer.

Changing icon artwork must not change touch timing or gesture state.

### `FloatingIconLayoutPolicy`
Owns icon window geometry and persistence only:

- icon size;
- display bounds;
- left/right side calculation;
- clamping;
- two-stage visible-edge snap + configured edge hiding;
- mirrored icon placement;
- orientation-specific position persistence.

`FloatService` decides *when* to update a window; the policy decides *where* it belongs.

## 2. Window hosting

### `FvOverlayWindowHost`
This is the shared owner of ordinary FloatLens overlay add/update/remove/migration logic.
It tracks host ownership per `View` and supports both:

- `TYPE_ACCESSIBILITY_OVERLAY` (2032), used when content must appear above SystemUI;
- `TYPE_APPLICATION_OVERLAY`, used for ordinary application overlays.

Do not duplicate Accessibility-vs-Application overlay fallback logic in feature classes.
Specialized edge/wake/system helper windows may keep direct `WindowManager` code when their lifetime
or window type is intentionally different.

Native Android Editor selection (handles, magnifier and ActionMode) is considered Activity-only on
the target OxygenOS/Android build. Do not move selectable result text back into overlay windows unless
a real device diagnostic proves equivalent framework behavior.

## 3. Screenshot capture

### `ScreenCaptureBackend`
Owns capture backend selection only:

- Accessibility screenshot;
- Root fallback;
- backend error composition.

### `ScreenshotCaptureSession`
Owns normal capture lifecycle only:

- hide FloatLens when configured;
- settle delay;
- invoke `ScreenCaptureBackend`;
- restore FloatLens after success/failure.

Circle Select intentionally captures through `ScreenCaptureBackend` directly because
`CircleSelectController` owns its own FV-compatible icon-hide timing.

### `ScreenshotGeometry`
Owns display-space to bitmap-space mapping and optional status-bar crop.
Do not reproduce display/bitmap scale calculations in individual screenshot actions.

### `ScreenshotController`
Owns business routing only: full screenshot, region screenshot, View capture, OCR capture and save.
It must not implement backend selection or duplicate overlay/result UI.

## 4. Result surfaces

### `ResultSurfaceRouter`
Single policy entry point for result destination and fallback.

- image-first screenshot results may use `FloatingResultWindow` so they can be shown above SystemUI;
- View/OCR text results use `ResultActivity` because native text selection is reliable there;
- captured screenshot/View paths keep their shade-cleanup/fallback coordination here.

The host may differ, but the visible panel must not.

### `UnifiedResultPanel`
The **only** implementation of the visible FloatLens result popup.

It owns:

- title;
- image area and image scaling;
- optional selectable text area;
- OCR / copy / save / close action row;
- shared width/height/layout policy;
- selection callback wiring to `FloatActionMenu`;
- screenshot-to-OCR visual state inside an Activity host.

`FloatingResultWindow` and `ResultActivity` must both instantiate this component instead of building
parallel title/image/text/button trees. Any visual or action-layout change belongs here first.

### `FloatingResultWindow`
Overlay host for image-first screenshot results only.

It owns:

- overlay attach/remove lifecycle;
- screenshot OCR request lifecycle;
- hand-off to `ResultActivity` once OCR text needs native selection.

It must not implement another result layout or another text-selection UI.

### `ResultActivity`
Native Activity host for selectable View/OCR results and screenshot fallback.

It owns:

- Activity Window lifecycle;
- OCR request lifetime when OCR is run inside the Activity;
- closing/cleanup.

It renders `UnifiedResultPanel`; it must not create an independent result UI.

### `ResultOverlay`
A package-private source-compatibility adapter used by `EditableRegionOverlay`. It contains no window,
selection or result implementation and immediately forwards to `ResultSurfaceRouter`. New code must
not depend on this adapter.

### `ResultUi`
Low-level sizing and widget primitives used by `UnifiedResultPanel`. Feature/host classes should not
assemble a separate result surface from these helpers.

### `TextSelectionSurface`
Single implementation of native selectable result text, selection handles and magnifier callbacks.
It must be hosted by an Activity Window on the target device family.

## 5. OCR

### `OcrEngine`
Owns recognition strategy only:

- PP-OCRv6 Small/Medium selection and escalation;
- ML Kit serial preprocessing passes;
- fallback recognition;
- stale-request generation suppression.

It must not choose an Activity/overlay or build result UI.

### `OcrResultDispatcher`
Single OCR result-delivery boundary:

- deliver to an inline result sink when a live result host is waiting;
- otherwise route through `ResultSurfaceRouter`.

Do not add another static "next OCR result" mechanism to an Activity or View.

## 6. View selection

### `ViewSelectionEngine`
Single owner of FV drag/direct-selection state:

- probe position;
- 400 ms transition into DIRECT;
- direct region gesture and `FvRegionFrameOverlay`;
- asynchronous Accessibility candidate preparation;
- operation selection (screenshot / text / image);
- FV-compatible 5 ms release action delay.

### `ViewHoverOverlay`
Candidate layer for the FV drag/direct-selection path only:

- snapshot/cache Accessibility candidates;
- cached hit-testing on MOVE;
- small candidate highlight surface;
- lightweight frame for large/full-screen candidate display.

It must not own another direct-region gesture state machine.

### `ViewSelectionOverlay`
Explicit full-screen picker launched by `ActionId.OCR`. This is a separate user entry point from the
FV drag/direct-selection state machine, not another implementation of its region state. It may scan
the live Accessibility View under the finger, but it must reuse `FvOverlayWindowHost` for hosting and
`ResultSurfaceRouter` / `ScreenshotController` for output.

## 7. Circle Select

### `CircleSelectOverlay`
Owns workspace lifecycle, drawing and input orchestration.

### `CircleTextSelectionModel`
Pure spatial-OCR text selection model:

- hit testing;
- start/end selection state;
- nearest-word handle snapping;
- selected text composition;
- CJK no-space joining;
- selection bounds.

### `CircleCropGeometry`
Pure freehand-to-rectangle and view-to-bitmap crop geometry.

### `CircleSelectFrame`
Owns full-display frozen-frame coordinate policy. Keep screenshot and overlay in the same full-display
coordinate space whenever possible; do not crop status/navigation bars before entering the workspace.

## 8. Notification shade

### `FvSystemPanelController`
Owns live SystemUI shade detection and the Activity-result FV compatibility sequence.

### `OverlayShadeCoordinator`
Owns modern overlay-result cleanup. It may use the Android 12+ dismiss-shade action and a scoped BACK
fallback only while a fresh probe still reports the shade expanded.

The BACK fallback is a FloatLens/OxygenOS compatibility adaptation, not original FV behavior.

## 9. Rules for future changes

1. Prefer adding a method to the existing owner instead of creating a second implementation.
2. Controllers coordinate; geometry/models/backends do the reusable work.
3. UI surfaces do not select screenshot/OCR backends.
4. OCR engines do not decide result UI.
5. Feature classes do not duplicate overlay-host fallback logic.
6. `UnifiedResultPanel` is the only result popup UI; hosts must not rebuild it.
7. Native selectable result text stays in an Activity Window on the target device family.
8. Keep FV touch timing and pointer order stable unless diagnostics prove a behavior mismatch.
9. Preserve Activity fallbacks until the floating path has equivalent failure handling.
10. For modern Android compatibility adaptations, document them explicitly instead of presenting them
    as FV-original behavior.
11. Run the Debug Build workflow after structural changes and test the floating icon, View extraction,
    region screenshot, notification-shade capture, OCR result, text selection/magnifier and Circle
    Select on-device.
