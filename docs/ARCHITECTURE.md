# FloatLens Architecture

FloatLens ordinary mode follows a **single-owner rule**: interaction surfaces may have different UI and lifetimes, but they must not reimplement the same semantics, capture, OCR parsing, overlay hosting, workflow state, system-panel handling, coordinate mapping, or result routing.

The ordinary pipeline is:

```text
Input
  ↓
Screen semantics / cached selection
  ↓
Capture or recognition
  ↓
ResultSession
  ↓
One ResultActivity / UnifiedResultDialogFragment
```

Root and LSPosed are optional providers. They may enhance a backend, but they must not create an alternate application flow.

## 1. Floating input

### `FloatIconView`
Owns one touch stream only:

- tap / double tap;
- configured long press;
- directional gesture;
- temporary-follow drag;
- Direct selection dwell;
- explicit icon-position move.

Direct uses the verified single dwell gate (~400 ms, ~3 dp re-arm). A long-press timer is armed only when a real long action is configured; `ActionId.NONE` must never steal Direct ownership.

Do not add Accessibility traversal, screenshot backend selection, OCR parsing, result UI, or feature-specific Circle state here.

### `GestureClassifier` / `GestureActionMapper` / `ActionRegistry`
Gesture classification, gesture-to-preference mapping, and action execution are separate layers. New actions belong in `ActionRegistry`; do not add feature-local action catalogs.

### `FloatingIconLayoutPolicy` / `FloatVisibilityController`
Layout/persistence and visibility reasons each have one owner. `FloatService` applies decisions; it should not grow duplicate position or hidden-state policy.

## 2. Screen geometry and system bars

### `ScreenGeometry`
Single owner of physical display bounds and density.

### `CaptureSystemBarsPolicy`
Single owner of status/navigation capture bounds. Screenshot, region capture and Circle use this same policy.

Absolute screen coordinates are canonical for View/Accessibility data. Convert to view/bitmap coordinates only at explicit transform/crop boundaries.

## 3. Accessibility semantics

### `AccessibilityNodeSemantics`
Single definition of what an Accessibility node means.

Important rule:

- `AccessibilityNodeInfo.getText()` = visible selectable View text.
- `contentDescription`, hint and state description = semantic labels only.

Semantic labels may describe a View or icon, but they must never be promoted to visible screen text.

This class also owns normal image/View/fullscreen classification helpers.

### `AccessibilityCandidateCollector`
Single normal-mode Accessibility tree walker. It is interruption-aware and produces cached `ScreenCandidate` objects.

Consumers include Direct selection, the explicit View picker, region View-text extraction, Circle snapshots, and compatibility point-picker APIs in `LensAccessibilityService`.

`LensAccessibilityService.collectCandidatesAt/findViewAt` are compatibility delegates only. They must not regain their own recursive collector.

### `ScreenSelectionModel`
Single cached candidate ranking/hit-test owner. MOVE-time selection performs geometry lookup only; it never walks live Accessibility nodes.

## 4. Direct selection

### `ViewSelectionEngine`
Owns Direct after the floating input dwell.

Candidate preparation starts at touch start and is cancellable. The 400 ms dwell decides only when Direct owns the gesture; it does not start a second late tree scan.

Routing semantics:

- visible TEXT -> View text result;
- VIEW / image -> View image result;
- explicit dragged region -> region screenshot;
- ROOT/fallback -> screenshot semantics only where explicitly intended.

A cached candidate change after Direct activation never creates another dwell.

### `ViewHoverOverlay`
Passive cached candidate highlight layer only.

### `ViewSelectionOverlay`
Explicit full-screen View picker. Its lifetime differs from same-pointer Direct, but it consumes the same `AccessibilityCandidateCollector` and `ScreenSelectionModel` semantics. Its async tree task must be cancellable.

Native Accessibility text is a `VIEW_TEXT` result, not fake OCR.

## 5. Overlay hosting

### `FlOverlayWindowHost`
Single owner of ordinary overlay add/update/remove/migration behavior.

Normal feature overlays must not call `WindowManager.addView/removeView` directly unless an Android component has a documented token/window reason. Floating icon helpers, View picker, region selectors and Circle surfaces use this shared host.

`LensAccessibilityService` may directly add/remove `TYPE_ACCESSIBILITY_OVERLAY` because it is one of the host implementations used by `FlOverlayWindowHost`; feature controllers must not copy that code.

## 6. Screenshot lifecycle

### `ScreenCaptureBackend`
Backend selector only. Ordinary Accessibility screenshot is the core path; optional providers may be selected only through their explicit gates.

### `ScreenshotCaptureSession`
Single ordinary capture lifecycle:

```text
acquire hide leases
  ↓
settle
  ↓
ScreenCaptureBackend
  ↓
restore leases
```

### `ScreenshotHideCoordinator`
Reference-counted floating-icon hiding. Never replace this with a plain boolean; overlapping capture flows must not reveal FloatLens early.

### `ScreenshotGeometry` / `CropMath` / `SelectionCropper`
`CropMath` owns pure coordinate mapping. `ScreenshotGeometry` delegates display bounds to `ScreenGeometry` and owns screen-rectangle screenshot cropping. `SelectionCropper` owns rectangle/freehand bitmap crop/masking and independent result-bitmap ownership.

A successful bounds crop is independently owned. Once a feature has produced that crop and closes its source workspace, it must release the original full-screen bitmap.

### `ScreenshotController`
Business routing only: full save, region editor, View capture, OCR capture and result delivery. It must not duplicate backend selection or result-window implementation.

Circle’s initial frozen frame can use the raw backend only because `CircleSelectController` already owns its hide lease and frame lifetime.

## 7. OCR

### `MlKitTextCore`
Single ML Kit foundation:

- Text block/line/element/symbol -> `OcrDocument` geometry;
- shared character fallback splitting.

No feature may add another ML Kit -> `OcrDocument` parser.

### `OcrEngine`
Normal OCR strategy only:

- PP-OCR Small/Medium policy and escalation;
- ML Kit multi-pass/preprocessing planning;
- quality scoring/early stop;
- UI/document generations;
- final dispatch.

### `CircleRecognitionSession`
Circle scheduling only:

- frozen View snapshot;
- fast full-frame ML Kit;
- precise ROI ML Kit.

It uses `MlKitTextCore`; it does not own a separate ML Kit parser. Circle may intentionally use a lighter recognition schedule than normal OCR without duplicating the parsing layer.

### `CircleTextIndex`
Circle merge policy only. Full-frame merge may prefer native View text, but an explicit user ROI refinement is authoritative inside that requested region so stale approximate View geometry cannot suppress the refinement.

## 8. Circle snapshots and UI

### `CircleViewTextSnapshot`
Captures visible native View text before the Circle overlay exists. It uses shared Accessibility semantics and is interruption-aware so superseded scans stop promptly.

When Android exposes exact character-location extra data, it is retained. Otherwise View character geometry is approximate and may be refined by ML Kit.

### `CircleViewImageSnapshot`
Uses ordinary `AccessibilityNodeSemantics.isImage()` in normal mode. It must not redefine image semantics or silently depend on LSPosed metadata.

### `CircleSelectController`
The only ordinary Circle controller. It owns one generation, one cancellable View snapshot task and one screenshot-hide lease. A newer generation cancels/releases the older one.

The old same-touch `CircleLiveController` path has been removed. Do not recreate a second Circle capture/controller pipeline.

### `CircleSelectOverlay`
Owns Circle UI/input only. It must not grow another Accessibility collector, OCR parser, capture backend, system-panel algorithm or result system.

## 9. Recognition workflow state

### `RecognitionWorkflowState`
Single recognition lifecycle owner:

```text
IDLE -> CAPTURING -> RECOGNIZING -> RESULTS -> IDLE
```

Starting recognition/results directly from IDLE creates a generation; phase changes inside one workflow keep the generation.

### Temporary compatibility
`CircleStateMachine` is deprecated and delegates to `RecognitionWorkflowState`. It contains no independent state or policy. Remove the facade after remaining service-facing call sites can be migrated safely; do not add behavior to it.

## 10. Results

Every official screenshot, View and OCR result converges here:

```text
ResultSession
  ↓
ResultController
  ↓
singleTop ResultActivity
  ↓
UnifiedResultDialogFragment
  ↓
UnifiedResultPanel / TextSelectionSurface
```

### `ResultSession`
Single business-state/bitmap-ownership object for screenshot, View text, View image and OCR.

### `ResultController`
Single pending-token/Activity launch boundary.

### `ResultSurfaceRouter`
Thin result-construction facade only. It may create the proper `ResultSession` and delegate to `ResultController`; it must not host another result UI.

### `UnifiedResultDialogFragment`
Single visible result lifecycle. Close button, outside-tap cancellation, dismiss and destruction all converge on one workflow teardown path.

The old `ResultOverlay` compatibility class has been removed. Do not add another result Activity/overlay for a new capture/OCR feature.

### `OcrResultDispatcher`
One OCR output boundary: active inline sink when appropriate, otherwise the normal result pipeline.

## 11. Region selection

### `RegionOverlay`
Simple region-selection interaction only. Capture, crop and result delivery stay in shared owners.

### `EditableRegionOverlay`
Editable rectangle interaction and AUTO/View/OCR choice only. It uses:

- `FlOverlayWindowHost` for hosting;
- `AccessibilityCandidateCollector` for View text;
- `ScreenshotGeometry` for screen-to-bitmap crop;
- `OcrEngine` for OCR;
- `ResultSurfaceRouter` for View results.

It must release the source full-screen bitmap after a successful independent crop is handed off.

## 12. Notification shade / system panel

### `FlSystemPanelController`
Single owner of SystemUI notification-shade detection, dismissal, fallback, and overlay-ready completion/recheck timing.

`CircleSelectController`, result flows and capture flows call this owner directly. The old `OverlayShadeCoordinator` compatibility class has been removed; do not recreate feature-local shade retry timers.

`ResultReadyCoordinator` only determines when a captured ResultActivity is visibly ready.

## 13. Ordinary mode vs privileged providers

Ordinary mode must remain complete using Accessibility + normal overlay permission.

Privileged providers are optional adapters:

- Root may provide an alternate capture backend when explicitly enabled.
- LSPosed may provide secure-capture or other explicitly gated capabilities.

Rules:

1. provider code cannot redefine View/text semantics;
2. provider code cannot create another result pipeline;
3. provider code cannot fork gesture/Direct/Circle state machines;
4. disabling providers must return to exactly the ordinary pipeline, not a separate fallback implementation.

Privileged-specific details live in `docs/PRIVILEGED_MODE.md` and `docs/SECURE_SCREENSHOT.md`.

## 14. Compatibility facades allowed temporarily

Compatibility APIs may remain while callers migrate, but they must only delegate:

- `CircleStateMachine` -> `RecognitionWorkflowState`;
- `LensAccessibilityService.collectCandidatesAt/findViewAt` -> `AccessibilityCandidateCollector`;
- `ResultSurfaceRouter` -> `ResultController`.

Deleted compatibility layers such as `CircleLiveController`, `OverlayShadeCoordinator` and `ResultOverlay` must not be reintroduced.

If a compatibility class starts accumulating business logic again, move that logic to the listed single owner instead.

## 15. Regression gates

`.github/workflows/debug.yml` is required after structural changes:

1. `testDebugUnitTest`;
2. `lintDebug`;
3. `assembleDebug`;
4. upload the debug APK.

Prefer pure Java policy/geometry tests over JVM tests that directly invoke Android framework methods.

Device validation after a large refactor should cover:

- tap/double tap/long press;
- directional gestures;
- temporary icon follow and explicit position move;
- red probe -> 400 ms yellow Direct;
- text/View/image Direct release;
- explicit View picker;
- full/region screenshot and system-bar options;
- editable region AUTO/View/OCR and repeated open/close ownership;
- OCR and screenshot -> inline OCR;
- notification-shade capture;
- Circle open/close/navigation, View text, image hit, fast OCR and ROI refinement;
- rapid repeated Direct/View/Circle starts to verify stale async scans are cancelled.

## 16. Rule for future changes

**Extend the owner; do not clone the implementation.**

When two features need the same low-level behavior, the correct change is normally to extract or extend a shared core and leave feature-specific classes responsible only for scheduling/UI differences.
