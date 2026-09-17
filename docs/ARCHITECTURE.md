# FloatLens Architecture

FloatLens follows a **single-owner rule**: different interaction surfaces may have different UI and lifetimes, but the same low-level behavior must have one implementation and one policy owner.

The normal rule for future changes is simple:

> **Extend the owner; do not clone the implementation.**

Root and LSPosed are optional providers. They may enhance a backend, but they must not create another gesture, capture, OCR, selection, result, settings, or workflow pipeline.

## 1. Core ownership map

```text
Floating input
  FloatIconView
  GestureClassifier / GestureActionMapper / ActionRegistry
  ViewSelectionEngine

Screen semantics
  AccessibilityNodeSemantics
  AccessibilityCandidateCollector
  ScreenSelectionModel

Geometry
  ScreenGeometry
  AppSystemBarInsets
  CoordinateMapper
  ScreenBitmapTransform
  ScreenshotGeometry
  SelectionGeometry
  CropMath + SelectionCropper

Capture
  ScreenshotHideCoordinator
  ScreenshotCaptureSession
  ScreenCaptureBackend
  ScreenshotController

OCR
  MlKitTextCore
  PaddleOcrBridge
  OcrCanonicalGeometry
  OcrEngine

Circle
  GoogleCircleController
  GoogleCircleCapture
  GoogleCircleInlineOverlay
  GoogleCircleTextResolver
  CircleStableOcr
  CircleSelectionPlanner
  CircleGestureTextSelector
  CircleTextSelectionModel

Results
  ResultSession
  ResultController
  ResultSurfaceRouter
  UnifiedResultDialogFragment
  UnifiedResultPanel
  TextSelectionSurface

Menus
  FloatingMenuUi
  FloatingMenuPositioner
  TextActionMenuController
  FloatActionMenu / ImageActionMenu

Settings
  FloatSettings
  SettingsPageUi

Workflow state
  RecognitionWorkflowState
```

## 2. Floating input and gesture ownership

### `FloatIconView`
Owns one touch stream only:

- tap / double tap;
- configured long press;
- directional gesture;
- temporary-follow drag;
- Direct selection dwell;
- explicit icon-position move.

It must not grow Accessibility traversal, screenshot backend selection, OCR parsing, result UI, or Circle recognition logic.

### `GestureClassifier` / `GestureActionMapper` / `ActionRegistry`
These respectively own gesture classification, preference mapping, and action execution. New actions belong in `ActionRegistry`; do not create feature-local action catalogs.

### `FloatingIconLayoutPolicy` / `FloatVisibilityController`
Layout/persistence and visibility reasons remain separate owners. `FloatService` applies their decisions and must not reimplement their policies.

## 3. Screen geometry

### `ScreenGeometry`
Single owner of physical display bounds, usable system-bar-excluded bounds, density, dp conversion and integer clamping.

Feature code must not call `WindowManager.getCurrentWindowMetrics()` merely to rediscover screen width/height.

### `AppSystemBarInsets`
Owns Activity-content insets only. Android 15+ edge-to-edge Activity layout is a different boundary from screenshot/overlay geometry and must stay separate.

### `CoordinateMapper`
Generic Matrix-backed mapping between two rectangular coordinate spaces.

### `ScreenBitmapTransform`
Single frame-specific mapping owner between:

- frozen screenshot bitmap pixels;
- absolute SCREEN coordinates;
- overlay View coordinates;
- OCR document geometry.

Callers must not manually add status-bar offsets or multiply screen/bitmap ratios.

### `ScreenshotGeometry`
Owns screen-rectangle screenshot cropping and system-bar-aware screenshot cropping. It delegates coordinate conversion to `ScreenBitmapTransform`.

### `CropMath` / `SelectionCropper`
`CropMath` is intentionally narrow: pure View-space -> bitmap-space rectangle math used by `SelectionCropper` only. It must not regain screen/bitmap mapping; that belongs to `CoordinateMapper` / `ScreenBitmapTransform`.

`SelectionCropper` owns rectangle/freehand selection crop and masking plus independent result-bitmap ownership.

### `SelectionGeometry`
Single owner of text/View selection anchor geometry used by selection and menus. Do not reintroduce global menu-anchor state.

## 4. Accessibility semantics

### `AccessibilityNodeSemantics`
Single definition of what an Accessibility node means.

Rules:

- `AccessibilityNodeInfo.getText()` is visible View text;
- content description, hint and state description are semantic labels;
- semantic labels must not silently become visually rendered text.

### `AccessibilityCandidateCollector`
Single ordinary Accessibility tree walker. It produces `ScreenCandidate` objects for Direct, explicit View picker and explicit region View-text extraction.

### `ScreenSelectionModel`
Single cached candidate ranking/hit-test owner. MOVE-time Direct selection is geometry-only and must not repeatedly walk the live Accessibility tree.

## 5. Direct selection

### `ViewSelectionEngine`
Owns same-touch Direct selection after the dwell gate.

Routing:

- visible TEXT -> View text result;
- VIEW/image -> View image result;
- dragged Direct region -> screenshot;
- ROOT/fallback -> screenshot only where explicitly intended.

### `ViewHoverOverlay`
Passive cached-candidate highlight layer only.

### `ViewSelectionOverlay`
Explicit full-screen View picker. Its lifetime differs from Direct, but it consumes the same Accessibility semantics/candidates.

Native Accessibility text is a View-text result, not OCR.

## 6. Overlay hosting

### `FlOverlayWindowHost`
Single owner of ordinary overlay add/update/remove/migration behavior.

Feature controllers must not copy WindowManager hosting code. AccessibilityService may directly host accessibility overlays because it is one implementation used by this shared host.

## 7. Screenshot lifecycle

### `ScreenCaptureBackend`
Backend selector only. Accessibility is the normal backend; Root/LSPosed alternatives are used only through explicit gates.

### `ScreenshotCaptureSession`
Single normal capture lifecycle:

```text
acquire hide lease(s)
  -> settle
  -> ScreenCaptureBackend
  -> restore lease(s)
```

### `ScreenshotHideCoordinator`
Reference-counted icon hiding. Never replace this with one boolean because overlapping capture flows must not reveal FloatLens early.

### `CaptureSystemBarsPolicy`
Single status/navigation capture policy shared by screenshot and Circle.

### `ScreenshotController`
Business routing only:

- full screenshot save;
- region selector;
- editable-region capture;
- View capture;
- OCR capture;
- bounds crop delivery.

Screenshot region mode and OCR region mode share the same internal selector flow and differ only by mode.

## 8. OCR

### `MlKitTextCore`
Single ML Kit parser/foundation:

- recognizer creation/selection;
- Text block/line/element/symbol -> `OcrDocument`;
- shared fallback character splitting.

No feature may add another ML Kit -> `OcrDocument` parser.

### `PaddleOcrBridge`
Single PP-OCR adapter. It performs recognition and converts engine-native output to `OcrDocument`, then immediately normalizes through `OcrCanonicalGeometry`.

There is no separate detector-only application pipeline.

### `OcrCanonicalGeometry`
Single engine-neutral normalization contract. PP output is normalized to the same stable line/element/symbol ordering semantics used by the ML path without invoking ML Kit again.

### `OcrEngine`
Normal OCR strategy and lifecycle owner:

- PP Small/Medium policy/escalation;
- ML Kit script fusion;
- quality selection;
- UI/document generations;
- UI recognition lifecycle notifications;
- final dispatch.

Region UI must submit a bitmap to `OcrEngine`; it must not separately maintain OCR-start/success/failure state.

## 9. Circle: one OCR-only pipeline

Normal Circle is deliberately **OCR-only**. It does not traverse Accessibility Views for text, merge View text into OCR, mask View bounds, or run a parallel TextMap detector pipeline.

The only normal Circle chain is:

```text
GoogleCircleController
  -> GoogleCircleCapture
  -> GoogleCircleInlineOverlay
  -> GoogleCircleTextResolver
  -> CircleStableOcr
  -> CircleSelectionPlanner
  -> CircleGestureTextSelector
  -> CircleTextSelectionModel
```

### `GoogleCircleController`
Owns Circle generation, frozen-workspace launch, screenshot-hide lease, border/shade lifecycle and close cleanup.

### `GoogleCircleCapture`
Owns the frozen `Frame`: bitmap + exact screen bounds + one `ScreenBitmapTransform`.

### `GoogleCircleInlineOverlay`
Owns Circle UI/input only: tap, stroke/scribble, editable screenshot rectangle, selection handles, confirmation and close UI.

It must not create another OCR parser or capture backend.

### `GoogleCircleTextResolver`
Owns one cached full-screen OCR document per frozen frame and optional per-gesture correction scheduling.

Full-screen OCR is single-flight/latest-pending and cached. Optional PP correction receives a planner-owned ROI; it cannot reinterpret the user's selection range.

### `CircleStableOcr`
Circle OCR-engine adapter only. Full-frame engine and correction engine are independently selectable. It returns `OcrDocument`; it does not own gesture semantics.

### `CircleSelectionPlanner`
Sole owner of Circle text selection semantics and correction ROI.

### `CircleGestureTextSelector`
Owns low-level OCR geometry hit/range mechanics used by the planner.

### `CircleTextSelectionModel`
Owns editable selected-text state and handles inside the Circle workspace.

Removed experimental/legacy paths must not be reintroduced:

- `CircleViewTextSnapshot`;
- `ViewTextOcrMask`;
- `ViewTextGeometryRefiner`;
- `CircleTextMap`;
- detector-only `PaddleTextDetectorBridge`;
- `CircleSelectOverlay` compatibility shim.

## 10. Recognition workflow state

### `RecognitionWorkflowState`
Single screenshot/OCR/recognition lifecycle state owner:

```text
IDLE -> CAPTURING -> RECOGNIZING -> RESULTS -> IDLE
```

`FloatService` talks to this class directly. The old `CircleStateMachine` compatibility facade has been removed.

A new generation begins only from IDLE; phase transitions inside the same workflow keep the generation.

## 11. Results

Every official screenshot, View and OCR result converges here:

```text
ResultSession
  -> ResultController
  -> singleTop ResultActivity
  -> UnifiedResultDialogFragment
  -> UnifiedResultPanel / TextSelectionSurface
```

### `ResultSession`
Single business state / bitmap-ownership object for screenshot, View text, View image and OCR.

### `ResultController`
Single pending-token and Activity-launch boundary.

### `ResultSurfaceRouter`
Thin construction facade only. It creates the proper session and delegates to `ResultController`; it must not host another result UI.

### `UnifiedResultDialogFragment` / `UnifiedResultPanel`
Single visible result system. Close, outside tap, dismiss and destruction converge on one teardown path.

### `OcrResultDispatcher`
Single OCR output boundary: active inline sink when appropriate, otherwise the normal result system.

## 12. Floating action menus

### `FloatingMenuUi`
Single visual owner for text and image floating menus: surface, text, ripple, rows and icon sizing.

### `FloatingMenuPositioner`
Single menu placement/clamp owner: around-anchor placement, fallback position and locked submenu row.

### `TextActionMenuController`
Single text-menu entry point from selection surfaces. It receives immutable `SelectionSnapshot` state.

### `FloatActionMenu` / `ImageActionMenu`
Own action-specific content only. They must not duplicate palette, ripple, screen-inset or placement algorithms.

Global menu-anchor state has been removed; anchors are passed explicitly.

## 13. Region selection

### `RegionOverlay`
Simple rectangle/freehand region interaction only.

### `EditableRegionOverlay`
Editable rectangle interaction and AUTO/View/OCR choice only.

### `RegionContentResolver`
Single explicit-region View-text hit/filter/sort/dedupe owner.

This View-text resolver is intentionally separate from Circle. Explicit region View extraction may use Accessibility; normal Circle may not.

## 14. Settings

### `FloatSettings`
Single normal preference read/write boundary. UI code should use typed getters/setters and compound operations rather than direct `SharedPreferences.Editor` calls.

The deprecated `prefs()` escape hatch is reserved for migration, listener registration and truly atomic legacy cases.

Compound state such as custom-icon URI + style and slideshow URI list + style is written atomically inside `FloatSettings`.

### `SettingsPageUi`
Single reusable settings-control builder for switches, sliders, spinners, OCR models and language selection.

Feature pages declare settings; they do not reimplement storage listeners or common control construction.

## 15. System panel / notification shade

### `FlSystemPanelController`
Single owner of notification-shade detection, dismissal, fallback and overlay-ready timing.

`ResultReadyCoordinator` only determines when a captured ResultActivity is visibly ready.

## 16. Privileged providers

Ordinary mode remains complete with Accessibility + normal overlay permission.

Optional Root/LSPosed providers may enhance a backend only when explicitly enabled.

They may not:

1. redefine View/text semantics;
2. create another result pipeline;
3. fork gesture/Direct/Circle state machines;
4. fork settings storage;
5. create a parallel capture policy.

Disabling privileged providers must return to the same ordinary pipeline.

## 17. Regression gates

`.github/workflows/debug.yml` must remain the structural-change gate:

1. `testDebugUnitTest`;
2. `lintDebug`;
3. `assembleDebug`;
4. upload debug APK.

Device validation after a large refactor should cover:

- tap/double tap/long press and directional gestures;
- temporary icon follow and explicit position move;
- Direct text/View/image and dragged region;
- explicit View picker;
- full/region screenshot and system-bar options;
- editable-region AUTO/View/OCR;
- normal OCR and result actions;
- notification-shade capture;
- Circle open/close, tap/range text selection, correction, screenshot rectangle and handles;
- rapid repeated starts to verify stale async work is cancelled.

## 18. Future-change rule

Before adding a helper, controller, state class, geometry function, menu style, settings writer or OCR wrapper, first check whether an owner above already exists.

If two features need the same low-level behavior, extract or extend the shared owner and leave feature classes responsible only for their true UI/scheduling differences.
