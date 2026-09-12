# FloatLens Naming

FloatLens-owned implementation names use the **FL / Fl / fl** prefix.

Examples:

- Java classes: `FlOverlayWindowHost`, `FlSystemPanelController`, `FlProbePointOverlay`
- diagnostic categories/constants: `FL_WINDOW`, `FL_SHADE`, `FL_PROBE`
- Android resources: `fl_pointer_text`, `fl_pointer_image`, `fl_pointer_screenshot`
- internal inspector protocol: `FL_HOOK_LOG`, `FL_HOOK_COMMAND`

The **FV** name is reserved for references to the external fooView/FV application and reverse-engineering evidence. Therefore real target symbols such as `com.fooview.android.fooview.fvprocess`, `FVCandidateAdapter`, `FVMediaProjectionService`, and comments that explicitly describe verified FV behavior must keep their original names.

This boundary makes it clear whether a name belongs to FloatLens itself or documents the reference implementation.
