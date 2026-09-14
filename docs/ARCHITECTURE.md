# FloatLens Architecture

This document defines the single-owner boundaries used by FloatLens. The goal is to keep FL
interaction semantics while making each capability easy to add, remove and maintain without parallel
implementations.

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
Owns every reason the icon may be hidden:

- manual hide;
- screenshot hide;
- per-app hide;
- lock-screen hide;
- fullscreen hide;
- current top package;
- IME state;
- notification/status-bar state.

`FloatService` applies the resulting decision to Views, wake edges and notification text. New hide rules
belong in this controller instead of adding another boolean to the service.

## 2. Window hosting

### `FlOverlayWindowHost`
Shared owner of ordinary overlay add/update/remove/migration logic. It supports Accessibility overlay
(2032) and application overlay windows.

The verified FV helper/highlight behavior prefers the accessibility overlay layer when that host is
available. Passive `NOT_TOUCHABLE` helper layers such as the CircleLive frozen frame may therefore use
`FlOverlayWindowHost` without changing the floating icon that owns the active MotionEvent stream.

Specialized windows may keep direct `WindowManager` code only when their lifetime, token or touch-owner
semantics genuinely require it. Never migrate or replace the actual touch-owner window in the middle of
one pointer stream merely to share hosting code.

Native Android Editor selection is Activity-only on the target OxygenOS/Android build. Do not place
selectable result text back into an overlay unless device diagnostics prove equivalent framework behavior.

## 3. Screenshot capture and crop

### `ScreenCaptureBackend`
The only Accessibility-vs-Root capture backend selector.

### `ScreenshotCaptureSession`
Normal hide-icon / settle / capture / restore lifecycle.

### `ScreenshotGeometry`
Display-space to bitmap-space mapping and status-bar policy for ordinary screenshots.

### `SelectionCropper`
Shared pure bitmap crop implementation for selection UIs:

- rectangular view-space -> bitmap-space crop;
- freehand white-background masked crop.

### `ScreenshotController`
Business routing only: full screenshot, region screenshot, View capture and save. It must not duplicate
capture backend selection or result UI.

### `CircleCropGeometry`
Circle-specific freehand-to-rectangle snap/padding policy only. Bitmap cropping delegates to
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
The only business state for a result:

- origin mode and current mode;
- source bitmap;
- anchor;
- text and OCR blocks;
- View metadata;
- derived capabilities such as canOCR/canCopy/canSave.

A successfully delivered `ResultSession` owns its source bitmap. Pending sessions must close when they
expire or are discarded, and the visible result host must close a session when it is replaced/destroyed.
Do not retain several abandoned full-resolution result bitmaps until a later GC cycle.

Screenshot -> OCR mutates the same session with `applyOcr()`; it must not create another result window.

### `ResultController`
The only launch/update boundary for results. It stores pending sessions, launches the singleTop
`ResultActivity`, and integrates captured results with `ResultReadyCoordinator`.

If Activity launch fails, ownership is returned to the caller so a screenshot caller can still save the
same bitmap as its fallback. If a pending token expires/is discarded, `ResultController` closes it.

### `ResultSurfaceRouter`
Compatibility/business facade only. It creates the proper `ResultSession` and delegates to
`ResultController`; it must not choose between multiple result hosts.

### `ResultActivity`
The one official result Window. `onNewIntent()` reuses the existing Activity and panel instead of opening
a second popup. It owns only Activity lifecycle and OCR request lifetime.

### `UnifiedResultPanel`
The only visible result UI. Its hierarchy is fixed for every mode:

- title;
- image slot;
- selectable text slot;
- fixed OCR / Copy / Save / Close row.

Buttons are never added/removed by mode; only text/enabled state changes. `render(ResultSession)` is the
only way result content changes.

### `ResultUi`
Low-level result geometry/widget primitives only.

### `ResultReadyCoordinator`
Bridges the first real ResultActivity draw to notification-shade cleanup for captured results.

## 5. Native text selection

### `TextSelectionController`
The single owner of native Android ActionMode selection behavior:

- clear framework contextual menu;
- selection-range tracking;
- selected-text extraction;
- anchor calculation;
- select-all;
- stable-selection callback timing.

### `TextSelectionSurface`
Result-panel UI wrapper around ScrollView/EditText. It delegates all selection lifecycle behavior to
`TextSelectionController` with a 220 ms stable delay.

### `FloatLensApp`
For other selectable app TextViews, installs the same `TextSelectionController` with immediate stable
callbacks. It must not contain another independent ActionMode implementation.

## 6. OCR

### `OcrEngine`
Recognition strategy only:

- PP-OCRv6 Small/Medium selection and escalation;
- ML Kit serial preprocessing passes;
- fallback recognition;
- stale-request suppression.

It must not decide which window/UI displays results.

### `OcrResultDispatcher`
The only recognition-result delivery boundary:

- deliver to an active inline sink when the current result session is waiting;
- otherwise delegate to `ResultSurfaceRouter`.

Do not add another static "next OCR result" mechanism.

`ppocr-sdk` owns native/model inference details and remains separate from app-level OCR policy.

## 7. Configurable actions

### `ActionId`
Stable persisted action IDs only.

### `ActionRegistry`
The single action catalog:

- display labels;
- available action order;
- preference defaults;
- execution behavior.

### `ActionExecutor`
Compatibility facade only; delegates execution to `ActionRegistry`.

### `GestureActionMapper`
Maps gesture codes to preference keys and asks `ActionRegistry` for defaults.

### `SettingsActivity`
Builds action pickers from `ActionId.availableIds()` / `ActionRegistry`. Adding an action should not
require another hard-coded settings list.

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

Changing from one cached candidate to another while already in DIRECT does **not** start another 400 ms
candidate-specific timer. Do not reintroduce a second red -> yellow confirmation phase.

### `ViewSelectionEngine`
Owns the state after `FloatIconView` has entered DIRECT: transformed probe position, direct-region state,
async candidate preparation, cached candidate operation choice and the verified FV 5 ms release delay.

### `ViewHoverOverlay`
Candidate cache/hit-test/highlight layer only. It preserves the current pointer visual state supplied by
the engine; candidate changes must not independently reset READY back to TRACKING. It must not own
another direct-region gesture state or dwell timer.

### `ViewSelectionOverlay`
Explicit full-screen picker launched by the OCR action. This intentionally remains separate from
`ViewSelectionEngine` because it is a different user entry point/lifetime; it must reuse shared hosting,
screenshot and result services.

## 9. Circle selection

### `CircleLiveController`
Same-pointer-session Circle flow. Its frozen layer remains `NOT_TOUCHABLE` so the floating icon retains
the active MotionEvent stream. The passive frozen layer may use `FlOverlayWindowHost` to prefer an
Accessibility overlay host without stealing pointer ownership. It reuses `ScreenCaptureBackend` and
`SelectionCropper`.

### `CircleSelectController` / `CircleSelectOverlay`
Frozen workspace flow. This intentionally remains separate from CircleLive because the lifecycle and
input ownership are different.

### `CircleTextSelectionModel`
Pure spatial OCR text-selection model.

### `CircleCropGeometry`
Circle rectangle policy; delegates actual bitmap crop to `SelectionCropper`.

### `CircleSelectFrame`
Full-display frozen-frame coordinate policy.

Do not merge CircleLive and CircleSelect merely because both draw circles.

## 10. Menus

### `FloatActionMenu`
Owns text-selection business actions and target/custom-action navigation.

### `ImageActionMenu`
Owns image-specific actions.

Their business models remain separate. Shared visual/window chrome may be extracted, but do not merge
text and image action semantics into one switch.

### `TargetMenuStore`
Share/process target ordering and hide rules.

### `CustomMenuActionStore`
User-created text actions and Intent construction.

These stores are intentionally separate because their persisted data semantics differ.

## 11. Notification shade

### `FlSystemPanelController`
Live SystemUI shade detection and Activity-result FL compatibility sequence.

### `OverlayShadeCoordinator`
Compatibility utility for specialized overlay flows only. Official result windows no longer use an
overlay host.

The scoped BACK fallback is a FloatLens/OxygenOS adaptation.

## 12. Privileged providers

Root and LSPosed are optional enhancement providers, never prerequisites for core behavior.

- Root capabilities must pass `PrivilegeManager.canUseRoot(...)` and may fall back to normal providers.
- LSPosed capabilities must pass `PrivilegeManager.canUseLsposed(...)` **and** report a real provider as
  available. A user preference alone is not proof that a Hook exists or is controllable.
- The current libxposed API 102 entry is intentionally hook-free until a cross-process control/status
  channel exists.
- Never install a device-wide `system_server` behavior change that an in-app switch cannot actually
  disable.

See `docs/PRIVILEGED_MODE.md` for the current provider state.

## 13. Rules for future changes

1. Every capability has one owner; add a method/state to that owner instead of cloning an implementation.
2. Controllers coordinate; pure models/backends/geometry do reusable work.
3. OCR engines never decide result UI.
4. Screenshot UIs never select capture backends.
5. Result UI changes only in `UnifiedResultPanel`; result state changes only in `ResultSession`.
6. Result windows are Activity-hosted to preserve native handles/magnifier on the target device.
7. Configurable actions are registered in `ActionRegistry`; do not duplicate IDs/labels/defaults lists.
8. Selection crop math belongs in `SelectionCropper`/`ScreenshotGeometry`, not feature Views.
9. Visibility reasons belong in `FloatVisibilityController`, not new `FloatService` booleans.
10. Keep different pointer/lifecycle models separate even when their visuals are similar.
11. Preserve verified FV touch timing and pointer order unless diagnostics prove a mismatch; never add an
    unverified second dwell merely because two visuals look similar.
12. Privileged behavior must match the user-visible gate that claims to control it.
13. Document modern Android/OxygenOS adaptations explicitly.
14. Run Debug Build after structural changes and test: icon drag/dwell, View extraction, region screenshot,
    notification-shade capture, screenshot->OCR in-place update, native text selection/magnifier,
    CircleLive and CircleSelect.
