from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SESSION = ROOT / 'app/src/main/java/com/yagay/floatlens/CircleRecognitionSession.java'
OVERLAY = ROOT / 'app/src/main/java/com/yagay/floatlens/CircleSelectOverlay.java'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'{label}: block not found')
    return text.replace(old, new, 1)


def patch_session():
    text = SESSION.read_text()
    text = replace_once(
        text,
        '    enum Stage { ACCESSIBILITY, FAST_MLKIT, ROI_PRECISE }',
        '    enum Stage { ACCESSIBILITY, FAST_MLKIT, FULL_DETECTOR, ROI_PRECISE }',
        'stage enum')
    text = replace_once(
        text,
        '    private TextRecognizer fastRecognizer;\n',
        '    private TextRecognizer fastRecognizer;\n    private boolean fullDetectorStarted;\n',
        'detector field')

    text = replace_once(
        text,
        '                            emit(current, Stage.FAST_MLKIT, true);\n',
        '                            emit(current, Stage.FAST_MLKIT, true);\n                            startFullScreenDetector(run);\n',
        'fast success')
    text = replace_once(
        text,
        '                            emitFailure(Stage.FAST_MLKIT, error, true);\n',
        '                            emitFailure(Stage.FAST_MLKIT, error, true);\n                            startFullScreenDetector(run);\n',
        'fast failure')
    text = replace_once(
        text,
        '            emitFailure(Stage.FAST_MLKIT, t, true);\n',
        '            emitFailure(Stage.FAST_MLKIT, t, true);\n            startFullScreenDetector(run);\n',
        'fast init failure')

    marker = '    private void closeFastRecognizer(TextRecognizer recognizer) {'
    detector_method = r'''    /**
     * One deep full-screen detector pass supplements the fast ML Kit cache. This runs only when a
     * local PP-OCR model is already installed; it never replaces View text and never waits for a
     * user tap/line before looking for image text.
     */
    private void startFullScreenDetector(long run) {
        if (!isCurrent(run) || fullDetectorStarted || screenshot == null || screenshot.isRecycled()) return;
        int model = OcrModelManager.isReady(app, OcrModelManager.MEDIUM)
                ? OcrModelManager.MEDIUM
                : OcrModelManager.isReady(app, OcrModelManager.SMALL)
                ? OcrModelManager.SMALL : -1;
        if (model < 0) {
            DiagnosticLog.i(app, "CIRCLE_INDEX", "full detector skip=no_local_model");
            return;
        }
        fullDetectorStarted = true;
        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(app, "CIRCLE_INDEX", "full detector start model=" + model
                + " image=" + screenshot.getWidth() + "x" + screenshot.getHeight());
        PaddleOcrBridge.recognize(app, screenshot, model, new PaddleOcrBridge.Callback() {
            @Override public void onSuccess(OcrDocument document, long totalMs, int lineCount) {
                if (!isCurrent(run)) return;
                int before = current == null ? 0 : current.chars().size();
                current = mergeSupplementalOcr(current, document);
                DiagnosticLog.i(app, "CIRCLE_INDEX", "full detector ready model=" + model
                        + " detectorChars=" + (document == null ? 0 : document.chars().size())
                        + " mergedBefore=" + before
                        + " mergedAfter=" + (current == null ? 0 : current.chars().size())
                        + " lines=" + lineCount + " totalMs=" + totalMs
                        + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
                emit(current, Stage.FULL_DETECTOR, true);
            }

            @Override public void onFailure(String message) {
                if (!isCurrent(run)) return;
                DiagnosticLog.i(app, "CIRCLE_INDEX", "full detector failed model=" + model
                        + " message=" + message);
            }
        });
    }

'''
    text = replace_once(text, marker, detector_method + marker, 'insert detector method')

    hydrate_marker = '    /**\n     * Use OCR only as a ruler for a View string that lacks platform character locations.'
    supplemental = r'''    /** Merge an additional full-screen OCR detector into the persistent cache. */
    private static OcrDocument mergeSupplementalOcr(OcrDocument base, OcrDocument extra) {
        if (extra == null || extra.lines().isEmpty()) return base;
        if (base == null || base.lines().isEmpty()) return extra;

        ArrayList<OcrDocument.Line> lines = new ArrayList<>(base.lines());
        int added = 0;
        int hydrated = 0;
        int duplicate = 0;
        for (OcrDocument.Line extraLine : extra.lines()) {
            if (extraLine == null || extraLine.bounds().isEmpty() || extraLine.text().isBlank()) continue;

            int matchingView = -1;
            for (int i = 0; i < lines.size(); i++) {
                OcrDocument.Line line = lines.get(i);
                if (line != null && line.source() == OcrDocument.Source.VIEW
                        && sameViewAndOcrText(line, extraLine)) {
                    matchingView = i;
                    break;
                }
            }
            if (matchingView >= 0) {
                OcrDocument.Line viewLine = lines.get(matchingView);
                if (viewLine.chars().isEmpty()) {
                    OcrDocument.Line mapped = hydrateViewGeometry(viewLine, extraLine);
                    if (mapped != null) {
                        lines.set(matchingView, mapped);
                        hydrated++;
                    }
                }
                duplicate++;
                continue;
            }

            boolean alreadyKnown = false;
            for (OcrDocument.Line known : lines) {
                if (known != null && known.source() == OcrDocument.Source.OCR
                        && sameOcrVisualLine(known, extraLine)) {
                    alreadyKnown = true;
                    break;
                }
            }
            if (alreadyKnown) {
                duplicate++;
            } else {
                lines.add(extraLine);
                added++;
            }
        }

        return documentFromLines(lines,
                base.engine() + "+" + extra.engine() + "-full-cache",
                Math.max(base.confidence(), extra.confidence()),
                Math.max(base.imageWidth(), extra.imageWidth()),
                Math.max(base.imageHeight(), extra.imageHeight()));
    }

    private static boolean sameOcrVisualLine(OcrDocument.Line a, OcrDocument.Line b) {
        if (a == null || b == null || a.bounds().isEmpty() || b.bounds().isEmpty()) return false;
        Rect overlap = new Rect();
        Rect ar = a.bounds();
        Rect br = b.bounds();
        if (!overlap.setIntersect(ar, br)) return false;
        long overlapArea = Math.max(0L, (long) overlap.width() * overlap.height());
        long minArea = Math.max(1L, Math.min((long) ar.width() * ar.height(),
                (long) br.width() * br.height()));
        if (overlapArea / (float) minArea < 0.45f) return false;
        String ac = compact(a.text());
        String bc = compact(b.text());
        if (!ac.isEmpty() && ac.equals(bc)) return true;
        String as = semanticCompact(a.text());
        String bs = semanticCompact(b.text());
        return !as.isEmpty() && as.equals(bs);
    }

'''
    text = replace_once(text, hydrate_marker, supplemental + hydrate_marker, 'insert supplemental merge')
    SESSION.write_text(text)


def patch_overlay():
    text = OVERLAY.read_text()
    old_constants = '''        private static final float LINE_REFINE_ROI_PAD_X_DP = 20f;\n        private static final float LINE_REFINE_ROI_HALF_HEIGHT_DP = 42f;\n        private static final float LINE_REFINE_SELECTION_HALF_HEIGHT_DP = 14f;\n'''
    new_constants = '''        private static final float CACHE_TAP_SNAP_DISTANCE_DP = 34f;\n        private static final float LINE_CACHE_SELECTION_HALF_HEIGHT_DP = 18f;\n        private static final float LINE_REFINE_MIN_PAD_X_DP = 64f;\n        private static final float LINE_REFINE_MIN_HALF_HEIGHT_DP = 72f;\n        private static final float LINE_REFINE_MAX_HALF_HEIGHT_DP = 128f;\n        private static final float TAP_REFINE_HALF_WIDTH_DP = 160f;\n        private static final float TAP_REFINE_HALF_HEIGHT_DP = 96f;\n'''
    text = replace_once(text, old_constants, new_constants, 'overlay constants')

    start = text.index('        private void startLineRefinement(List<PointF> gesture) {')
    end = text.index('        private boolean isTapLike(List<PointF> points) {', start)
    new_line_method = r'''        private void startLineRefinement(List<PointF> gesture) {
            if (recognitionSession == null || refinementMode != REFINE_NONE || closed
                    || gesture == null || gesture.size() < 2) {
                invalidate();
                return;
            }
            PointF first = gesture.get(0);
            PointF last = gesture.get(gesture.size() - 1);
            if (first == null || last == null) {
                invalidate();
                return;
            }

            refinementLineStartX = first.x;
            refinementLineStartY = first.y;
            refinementLineEndX = last.x;
            refinementLineEndY = last.y;

            float left = Math.min(first.x, last.x);
            float right = Math.max(first.x, last.x);
            float centerY = (first.y + last.y) * 0.5f;

            // Google-like first step: hit the persistent full-screen View/OCR cache before doing
            // any new recognition work. A horizontal stroke should select already-known text
            // immediately instead of launching a tiny ROI OCR request.
            RectF selectionBand = new RectF(
                    Math.max(0f, left),
                    Math.max(0f, centerY - dp(LINE_CACHE_SELECTION_HALF_HEIGHT_DP)),
                    Math.min(getWidth(), right),
                    Math.min(getHeight(), centerY + dp(LINE_CACHE_SELECTION_HALF_HEIGHT_DP)));
            Rect cachedBand = imageRectFromView(selectionBand);
            refinementLineSelectionImageRect = cachedBand;
            if (cachedBand != null && !cachedBand.isEmpty() && selection.selectIntersecting(cachedBand)) {
                int selectedChars = selection.selectionIndices().size();
                refinementLineSelectionImageRect = null;
                DiagnosticLog.i(context, "CIRCLE_SELECT", "line cache hit chars=" + selectedChars
                        + " band=" + cachedBand.toShortString()
                        + " from=" + Math.round(first.x) + "," + Math.round(first.y)
                        + " to=" + Math.round(last.x) + "," + Math.round(last.y));
                invalidate();
                post(WorkspaceView.this::showSelectionMenu);
                return;
            }

            Rect region = adaptiveLineRefineRegion(first, last);
            if (region == null || region.isEmpty()) {
                refinementLineSelectionImageRect = null;
                invalidate();
                return;
            }

            refinementMode = REFINE_LINE;
            DiagnosticLog.i(context, "CIRCLE_SELECT", "line cache miss -> adaptive roi "
                    + region.toShortString() + " cacheChars=" + selection.size()
                    + " from=" + Math.round(first.x) + "," + Math.round(first.y)
                    + " to=" + Math.round(last.x) + "," + Math.round(last.y));
            invalidate();
            recognitionSession.refine(region);
        }

'''
    text = text[:start] + new_line_method + text[end:]

    tap_start = text.index('        private void startTapRefinement(float viewX, float viewY) {')
    tap_end = text.index('        private void updateSelectionEndpoint(int hit) {', tap_start)
    new_tap_methods = r'''        private void startTapRefinement(float viewX, float viewY) {
            if (recognitionSession == null || refinementMode != REFINE_NONE || closed) {
                invalidate();
                return;
            }

            // ACTION_DOWN already tried the strict hit radius. Before OCR, allow a modest cache
            // snap so small image text discovered by the full-screen detector can be activated.
            int cached = selection.findSelectionWord(viewX, viewY, getWidth(), getHeight(),
                    dp(CACHE_TAP_SNAP_DISTANCE_DP));
            if (cached >= 0) {
                selection.selectSingle(cached);
                DiagnosticLog.i(context, "CIRCLE_SELECT", "tap cache hit index=" + cached
                        + " cacheChars=" + selection.size());
                invalidate();
                post(WorkspaceView.this::showSelectionMenu);
                return;
            }

            Rect region = adaptiveTapRefineRegion(viewX, viewY);
            if (region == null || region.isEmpty()) {
                invalidate();
                return;
            }
            refinementTapX = viewX;
            refinementTapY = viewY;
            refinementMode = REFINE_TAP;
            DiagnosticLog.i(context, "CIRCLE_SELECT", "tap cache miss -> adaptive roi "
                    + region.toShortString() + " cacheChars=" + selection.size());
            invalidate();
            recognitionSession.refine(region);
        }

        private Rect adaptiveLineRefineRegion(PointF first, PointF last) {
            if (first == null || last == null || getWidth() <= 0 || getHeight() <= 0) return null;
            float left = Math.min(first.x, last.x);
            float right = Math.max(first.x, last.x);
            float span = Math.max(dp(1f), right - left);
            float centerY = (first.y + last.y) * 0.5f;
            float padX = Math.max(dp(LINE_REFINE_MIN_PAD_X_DP), span * 0.30f);
            float halfHeight = Math.max(dp(LINE_REFINE_MIN_HALF_HEIGHT_DP),
                    Math.min(dp(LINE_REFINE_MAX_HALF_HEIGHT_DP), span * 0.18f));
            RectF fitted = fitViewRect(left - padX, centerY - halfHeight,
                    right + padX, centerY + halfHeight);
            return imageRectFromView(fitted);
        }

        private Rect adaptiveTapRefineRegion(float viewX, float viewY) {
            if (getWidth() <= 0 || getHeight() <= 0) return null;
            float halfW = Math.max(dp(TAP_REFINE_HALF_WIDTH_DP), getWidth() * 0.28f);
            float halfH = Math.max(dp(TAP_REFINE_HALF_HEIGHT_DP), getHeight() * 0.08f);
            RectF fitted = fitViewRect(viewX - halfW, viewY - halfH,
                    viewX + halfW, viewY + halfH);
            return imageRectFromView(fitted);
        }

        /** Preserve the requested context size near screen edges by shifting instead of clipping. */
        private RectF fitViewRect(float left, float top, float right, float bottom) {
            float vw = Math.max(1f, getWidth());
            float vh = Math.max(1f, getHeight());
            float width = Math.min(vw, Math.max(1f, right - left));
            float height = Math.min(vh, Math.max(1f, bottom - top));
            float cx = (left + right) * 0.5f;
            float cy = (top + bottom) * 0.5f;
            float l = cx - width * 0.5f;
            float t = cy - height * 0.5f;
            float r = l + width;
            float b = t + height;
            if (l < 0f) { r -= l; l = 0f; }
            if (r > vw) { l -= (r - vw); r = vw; }
            if (t < 0f) { b -= t; t = 0f; }
            if (b > vh) { t -= (b - vh); b = vh; }
            l = Math.max(0f, l);
            t = Math.max(0f, t);
            r = Math.min(vw, Math.max(l + 1f, r));
            b = Math.min(vh, Math.max(t + 1f, b));
            return new RectF(l, t, r, b);
        }

'''
    text = text[:tap_start] + new_tap_methods + text[tap_end:]

    text = text.replace(
        'else if (refinementMode == REFINE_LINE) status = "正在精识别横划区域… · 圈画仍是截图";',
        'else if (refinementMode == REFINE_LINE) status = "全屏索引未命中 · 正在补识别横划区域…";')
    text = text.replace(
        'else if (refinementMode == REFINE_TAP) status = "正在精识别点击位置… · 圈画仍是截图";',
        'else if (refinementMode == REFINE_TAP) status = "全屏索引未命中 · 正在补识别点击区域…";')

    OVERLAY.write_text(text)


patch_session()
patch_overlay()
print('circle OCR cache/adaptive ROI patch applied')
