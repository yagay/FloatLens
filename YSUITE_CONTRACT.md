# YSuite Integration Contract

This project must remain independently buildable while exposing the same reusable `feature` source to YSuite.

Before refactoring, preserve these rules:

- Keep standalone `app` and reusable `feature` responsibilities separate.
- Do not copy feature source into YSuite or maintain a long-lived adapter mirror.
- In YSuite, reuse shared Root/LSPosed/log/diagnostics infrastructure wherever practical; standalone remains first-class.
- Keep architecture simple and make the module easy to add/remove.
- Floating UI must avoid unnecessary periodic redraw/flicker.

## YFloat requirements

- Preserve FloatLens-style text-selection overlay behavior with rounded frame and outside mask.
- Selection visuals should follow the selected region dynamically where the platform permits.
- Preserve the stronger colorful border/glow direction rather than reverting to the abandoned Google-style imitation.
- Keep floating controls responsive without periodic refresh merely to keep them visible.

Canonical cross-project requirements live in `yagay/YSuite/docs/PRODUCT_REQUIREMENTS.md` and `MODULE_REQUIREMENTS.md`.
