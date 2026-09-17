# FloatLens Naming

Use names by **responsibility**, not by historical feature origin.

## `Fl*` / `FL_*` / `fl_*`

Reserve the FloatLens prefix for low-level FloatLens-owned platform helpers and diagnostics where the prefix prevents confusion with Android/SystemUI concepts.

Examples:

- overlay/system helpers: `FlOverlayWindowHost`, `FlSystemPanelController`, `FlProbePointOverlay`;
- diagnostic categories/constants: `FL_WINDOW`, `FL_SHADE`, `FL_PROBE`;
- Android resources: `fl_pointer_text`, `fl_pointer_image`, `fl_pointer_screenshot`.

Do **not** add `Fl` mechanically to every business class. Shared owners should use direct responsibility names such as `ScreenGeometry`, `ScreenshotController`, `RecognitionWorkflowState`, `SelectionGeometry`, `FloatingMenuUi` and `OcrCanonicalGeometry`.

## Feature names

Use one stable feature family for each real workflow:

- ordinary floating/Direct selection: `FloatIcon*`, `ViewSelection*`;
- current Circle workflow: `GoogleCircle*` for the frozen Circle workspace boundary, with shared `Circle*` selection/OCR helpers only where they are genuinely Circle-specific;
- results: `Result*` / `UnifiedResult*`;
- settings: `FloatSettings`, `Settings*`;
- shared OCR: `Ocr*`, `MlKitTextCore`, `PaddleOcrBridge`.

Do not create a second class family just to preserve an old implementation name. Compatibility shims should be removed after callers migrate.

## External FV/fooView references

The **FV** name is reserved for the external fooView/FV reference application and reverse-engineering evidence. Real target symbols such as `com.fooview.android.fooview.fvprocess`, `FVCandidateAdapter`, `FVMediaProjectionService`, and comments that explicitly document verified FV behavior should keep their original names.

This boundary makes it clear whether a name is a FloatLens owner, a feature-specific implementation, or external FV evidence.
