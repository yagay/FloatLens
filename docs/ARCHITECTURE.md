# FloatLens Architecture

This document defines the single-owner boundaries used by FloatLens. The goal is to preserve verified
FV interaction semantics while keeping each capability independently maintainable and testable.

## 1. Floating icon input

### `FloatIconView`
Owns pointer semantics only:

- immediate temporary-follow movement from `ACTION_DOWN` absolute displacement;
- verified FV 400 ms direct-selection dwell;
- 3 dp per-axis dwell re-arm box;
- long press, tap/double tap and gesture state;
- hand-off to `ViewSelectionEngine` after the Direct dwell is complete;
- explicit icon-position move mode.

Do not add image decoding, window hosting, screenshot capture, visibility policy or result UI here.

### `FloatIconRenderer`
Owns icon appearance only: default/custom/animated/slideshow rendering.

### `FloatingIconLayoutPolicy`
Owns icon size, display bounds, clamping, snap/edge-hide, mirror placement and orientation-specific
position persistence.

### `FloatVisibilityController`
Owns every reason the icon may be hidden: manual, screenshot, per-app, lock-screen and fullscreen state,
plus the environment state needed to derive visibility. `FloatService` applies the decision to Views,
wake edges and notification text.

### `FloatPreferenceImpact`
Pure routing policy for preference changes. Appearance keys may relayout windows, visibility keys only
recompute visibility, gesture keys refresh the icon touch settings, and unrelated service settings update
the `FloatSettings` snapshot without unnecessary `WindowManager` work. Position persistence keys are
ignored to prevent a save from recursively refreshing the windows.

## 2. Window hosting

### `FlOverlayWindowHost`
Shared owner of ordinary overlay add/update/remove/migration logic. It supports Accessibility overlay
(2032) and application overlay windows.

When accessibility is available, the main icon/helper stack may use the accessibility host. Passive
`NOT_TOUCHABLE` layers such as CircleLive and the gesture trail can share the host without stealing the
floating icon's active MotionEvent stream. Edge wake windows use the same host.

Specialized windows may keep direct `WindowManager` code only when their lifetime, token or touch-owner
semantics genuinely require it. Never migrate/replace the active touch-owner window in the middle of a
pointer stream merely to share hosting code.

Native Android Editor selection is Activity-only on the target OxygenOS/Android build. Do not move
selectable result text back into an overlay unless device diagnostics prove equivalent behavior.

## 3. Screenshot capture and crop

### `ScreenCaptureBackend`
The only Accessibility-vs-Root capture backend selector.

### `ScreenshotCaptureSession`
Owns hide-icon / settle / capture / restore lifecycle.

### `RootCapture`
Root backend only. `screencap -p` is decoded directly from stdout; do not reintroduce shared temporary PNG
files.

### `CropMath`
Pure Java coordinate mapping shared by screenshot and selection crop paths. This is the regression-testable
owner of view/display-space -> bitmap-space rounding and clamping rules.

### `ScreenshotGeometry`
Android/display wrapper around `CropMath`, plus status-bar policy for ordinary screenshots.

### `SelectionCropper`
Android bitmap crop/mask implementation. It delegates coordinate mapping to `CropMath` and owns the
independent-bitmap rule for results/OCR.

### `ScreenshotController`
Business routing only: full screenshot, region screenshot, View capture and save. It must not duplicate
capture backend selection or result UI.

### `CircleCropGeometry`
Circle-specific freehand-to-rectangle snap/padding policy only. Actual bitmap cropping delegates to
`SelectionCropper`.

## 4. One result system

Every official screenshot, View and OCR result uses exactly this pipeline:

```text
Screenshot / View / OCR
        ↓
ResultSession
        ↓
ResultController
        ↓
singleTop ResultActivity
        ↓
UnifiedResultPanel
        ↓
TextSelectionSurface
```

There is no official floating-result overlay host.

### `ResultSession`
The only business state for a result. A successfully delivered session owns its source bitmap. Pending
sessions close when they expire/discard, and the visible host closes a replaced/destroyed session. Do not
retain abandoned full-resolution result bitmaps until a later GC cycle.

Screenshot -> OCR mutates the same session with `applyOcr()`; it must not create another result window.

### `ResultController`
The only launch/update boundary for results. If Activity launch fails, bitmap ownership returns to the
caller for fallback save/reuse. Expired/discarded pending tokens are closed.

### `ResultSurfaceRouter`
Compatibility/business facade only; it creates the appropriate `ResultSession` and delegates to
`ResultController`.

### `ResultActivity` / `UnifiedResultPanel`
`ResultActivity` is the one official result Window and is `singleTop`. `UnifiedResultPanel` is the only
visible result UI and renders every mode through one stable hierarchy.

### `ResultReadyCoordinator`
Bridges the first real ResultActivity draw to notification-shade cleanup for captured results.

## 5. Native text selection

### `TextSelectionController`
The single owner of native Android ActionMode selection behavior: framework-menu suppression, range
tracking, selected text, anchor, select-all and stable-selection timing.

### `TextSelectionSurface`
Result-panel wrapper around ScrollView/EditText. It delegates selection lifecycle to
`TextSelectionController` with a 220 ms stable delay.

### `FloatLensApp`
Other selectable app TextViews use the same controller with immediate stable callbacks. Installation is
idempotent per TextView via a keyed tag; Activity resume/post passes must not create duplicate controller
instances or callbacks.

## 6. OCR

### `OcrEngine`
Recognition strategy only:

- PP-OCRv6 Small/Medium selection and escalation;
- ML Kit serial preprocessing passes;
- conservative completed-tier early stop;
- fallback recognition;
- stale-request suppression.

When multiple languages are enabled, early-stop is evaluated only after every enabled recognizer in the
current image tier has completed. Do not stop after only the first language returns text.

### `OcrModelManager`
Owns downloaded model files and their local SHA-256 integrity manifest. A cold PP-OCR load must verify a
model before opening it. Verification during a successful download must not be blocked by the download's
own in-progress marker.

### `OcrResultDispatcher`
The only recognition-result delivery boundary: deliver to an active inline sink when appropriate,
otherwise delegate to `ResultSurfaceRouter`.

`ppocr-sdk` owns native/model inference details and remains separate from app-level OCR policy.

## 7. Configurable actions and settings

### `ActionId` / `ActionRegistry` / `GestureActionMapper`
`ActionId` contains stable persisted IDs, `ActionRegistry` is the single action catalog/default/execution
owner, and `GestureActionMapper` maps gesture codes to preference keys.

### Settings pages
`SettingsActivity` is only the navigation/lifecycle host. Page declarations are separated into:

- `SettingsIconPage`;
- `SettingsGesturePage`;
- `SettingsCapturePage`;
- `SettingsEnvironmentPage`;
- `SettingsActionsPage`;
- `PrivilegeSettingsPanel`.

`SettingsPageUi` owns common switch/slider/spinner/OCR-model widgets and their persistence wiring. Do not
copy those helpers back into individual pages. Adding an action should still come from
`ActionId.availableIds()` / `ActionRegistry`, not another hard-coded catalog.

## 8. View selection

### Verified FV phase model

The direct-drag flow has **one** dwell gate, owned by `FloatIconView`:

```text
MOVE / temporary follow
        ↓
TRACKING red probe
        ↓ stable ~400 ms inside ±3 dp
DIRECT
        ↓
READY yellow probe
        ↓
cached TEXT / IMAGE / VIEW selection
        ↓ ACTION_UP
5 ms delayed operation
```

Changing cached candidates while already in DIRECT does **not** start another candidate-specific timer.
The obsolete `view_capture_dwell_ms` setting has been removed; do not reintroduce a second dwell preference.

### `ViewSelectionEngine`
Owns state after the icon enters DIRECT: transformed probe position, direct-region state, cancellable async
candidate preparation, cached operation choice and the verified FV 5 ms release delay. A superseded
Accessibility scan must be interrupted/cancelled rather than merely ignored after completing.

### `ViewHoverOverlay`
Candidate cache/hit-test/highlight layer only. Candidate changes preserve the supplied pointer visual state
and must not reset READY to TRACKING.

### `ViewSelectionOverlay`
Explicit full-screen picker launched by the OCR action. This intentionally remains separate because its
entry point and lifetime differ from the same-pointer Direct flow.

## 9. Circle selection

### `CircleStateMachine`
Explicit state owner for IDLE -> ACTIVE/CAPTURE/OCR/RESULTS transitions and generation tracking. The state
core is JVM-testable; Android Context is used only for diagnostics.

### `CircleLiveController`
Same-pointer-session Circle flow. Its frozen helper layer stays `NOT_TOUCHABLE`, uses shared overlay hosting
where safe, and reuses screenshot/crop services.

### `CircleSelectController` / `CircleSelectOverlay`
Frozen workspace flow. This intentionally remains separate from CircleLive because lifecycle and input
ownership differ.

Do not merge CircleLive and CircleSelect merely because both draw circles.

## 10. Menus

`FloatActionMenu` owns text-selection actions. `ImageActionMenu` owns image actions. Their business models
remain separate. `TargetMenuStore` owns share/process ordering/hide rules; `CustomMenuActionStore` owns
user-created text actions and Intent construction.

## 11. Notification shade

`FlSystemPanelController` owns live SystemUI shade detection and Activity-result FL compatibility flow.
`OverlayShadeCoordinator` is a compatibility utility for specialized overlay flows only. Official result
windows no longer use an overlay host.

## 12. Privileged providers

Root and LSPosed are optional enhancement providers, never prerequisites for core behavior.

- Root capabilities must pass `PrivilegeManager.canUseRoot(...)` and may fall back to normal providers.
- LSPosed capabilities must pass `PrivilegeManager.canUseLsposed(...)` **and** report a real provider as
  available. A preference alone is not proof that a hook exists or is controllable.
- The current libxposed API 102 entry is intentionally hook-free until a cross-process control/status
  channel exists.
- Never install a device-wide `system_server` behavior change that an in-app switch cannot actually
  disable.

See `docs/PRIVILEGED_MODE.md` for the current provider state.

## 13. Regression gates

`.github/workflows/debug.yml` is the source-side gate for every push/PR:

1. `testDebugUnitTest`;
2. `lintDebug`;
3. `assembleDebug`;
4. upload the debug APK artifact.

The JVM suite currently protects gesture-session state, verified gesture classification semantics, crop
coordinate mapping, OCR early-stop quality, preference refresh routing and Circle state transitions.
Structural changes are not complete until this workflow is green on the final HEAD.

## 14. Rules for future changes

1. Every capability has one owner; extend that owner instead of cloning an implementation.
2. Controllers coordinate; pure models/backends/geometry do reusable work.
3. OCR engines never decide result UI.
4. Screenshot UIs never select capture backends.
5. Result UI changes only in `UnifiedResultPanel`; result state changes only in `ResultSession`.
6. Result windows remain Activity-hosted to preserve native handles/magnifier on the target device.
7. Configurable actions are registered in `ActionRegistry`; do not duplicate IDs/labels/default lists.
8. Coordinate mapping belongs in `CropMath`; Android bitmap/display wrappers must delegate to it.
9. Visibility reasons belong in `FloatVisibilityController`, not new `FloatService` booleans.
10. Keep different pointer/lifecycle models separate even when their visuals are similar.
11. Preserve verified FV timing/pointer order unless diagnostics prove a mismatch; never add an unverified
    second dwell because two visuals look similar.
12. Privileged behavior must match the user-visible gate that claims to control it.
13. Keep settings-page composition out of the Activity lifecycle host.
14. Run the full CI gate after structural changes, then device-test: icon drag/dwell, View extraction,
    region screenshot, notification-shade capture, screenshot->OCR update, native selection/magnifier,
    CircleLive and CircleSelect.
