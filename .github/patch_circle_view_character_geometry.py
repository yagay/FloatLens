from pathlib import Path

p = Path('app/src/main/java/com/yagay/floatlens/CircleRecognitionSession.java')
s = p.read_text(encoding='utf-8')

if 'collectAccessibilityTextNode(' in s:
    print('View character geometry patch already applied')
    raise SystemExit(0)

s = s.replace('import android.graphics.Rect;\n', '''import android.graphics.Rect;\nimport android.graphics.RectF;\nimport android.os.Build;\nimport android.os.Bundle;\nimport android.os.Parcelable;\nimport android.view.accessibility.AccessibilityNodeInfo;\nimport android.view.accessibility.AccessibilityWindowInfo;\n''')

start = s.index('    /** Build authoritative selectable text directly from Accessibility Views. */')
end = s.index('    /** Build OCR-only symbol geometry from the screenshot. */', start)
new_accessibility = r'''    /**
     * Build View text from Accessibility. Character geometry comes from the platform's
     * EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY whenever the provider supports it. We never invent
     * equal-width character boxes across a whole View: that made short selections span huge areas.
     *
     * If a View exposes text but not character locations, keep an empty-geometry VIEW line. The
     * later screenshot OCR pass may donate geometry to that line while the copied characters still
     * come from the authoritative View string.
     */
    private OcrDocument accessibilityDocument() {
        LensAccessibilityService service = LensAccessibilityService.get();
        if (service == null) return emptyDocument("accessibility");
        Rect screen = service.screenBounds();
        if (screen == null || screen.isEmpty()) {
            screen = new Rect(0, 0, screenshot.getWidth(), screenshot.getHeight());
        }

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        int[] state = new int[] { 0, 0, 0, 0, 0, 0 };
        // 0=lineId, 1=group, 2=order, 3=textNodes, 4=geometryNodes, 5=geometryChars

        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo root = null;
                    try { root = window.getRoot(); } catch (Throwable ignored) {}
                    if (root != null) {
                        collectAccessibilityTextNode(root, 0, screen, lines, seen, state);
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (lines.isEmpty()) {
            try {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root != null) collectAccessibilityTextNode(root, 0, screen, lines, seen, state);
            } catch (Throwable ignored) {}
        }

        // Some providers expose text through the candidate snapshot but do not return a traversable
        // node tree. Preserve those strings as VIEW lines with no fabricated per-character geometry;
        // OCR can later donate geometry if it sees the same text.
        if (lines.isEmpty()) {
            for (ScreenCandidate candidate : AccessibilityCandidateCollector.collect(service)) {
                if (candidate == null || candidate.type() != ScreenCandidate.Type.TEXT
                        || !candidate.hasText()) continue;
                String text = candidate.text() == null ? "" : candidate.text().trim();
                if (text.isEmpty()) continue;
                Rect mapped = mapScreenRect(candidate.bounds(), screen,
                        screenshot.getWidth(), screenshot.getHeight());
                if (mapped.isEmpty()) continue;
                String key = mapped.flattenToString() + "\u0000" + text;
                if (!seen.add(key)) continue;
                lines.add(new OcrDocument.Line(text, mapped, 1.0f, List.of(),
                        OcrDocument.Source.VIEW));
                state[0]++;
            }
        }

        DiagnosticLog.i(app, "CIRCLE_INDEX", "view geometry textNodes=" + state[3]
                + " geometryNodes=" + state[4] + " geometryChars=" + state[5]
                + " lines=" + lines.size());
        return documentFromLines(lines, "accessibility-view", 1.0f,
                screenshot.getWidth(), screenshot.getHeight());
    }

    private void collectAccessibilityTextNode(AccessibilityNodeInfo node,
                                              int depth,
                                              Rect screen,
                                              List<OcrDocument.Line> lines,
                                              Set<String> seen,
                                              int[] state) {
        if (node == null || depth > 80) return;
        try {
            if (node.isVisibleToUser()) {
                CharSequence raw = node.getText();
                if (raw != null && !raw.toString().trim().isEmpty()) {
                    addAccessibilityTextLine(node, raw.toString(), screen, lines, seen, state);
                }
            }
        } catch (Throwable ignored) {}

        int count = 0;
        try { count = node.getChildCount(); } catch (Throwable ignored) {}
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            if (child != null) collectAccessibilityTextNode(child, depth + 1, screen, lines, seen, state);
        }
    }

    private void addAccessibilityTextLine(AccessibilityNodeInfo node,
                                          String rawText,
                                          Rect screen,
                                          List<OcrDocument.Line> lines,
                                          Set<String> seen,
                                          int[] state) {
        Rect nodeBounds = new Rect();
        try { node.getBoundsInScreen(nodeBounds); } catch (Throwable ignored) {}
        Rect mappedNode = mapScreenRect(nodeBounds, screen,
                screenshot.getWidth(), screenshot.getHeight());
        if (mappedNode.isEmpty()) return;

        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) return;
        String key = mappedNode.flattenToString() + "\u0000" + text;
        if (!seen.add(key)) return;
        state[3]++;

        ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
        Parcelable[] locations = requestCharacterLocations(node, rawText);
        int localGroup = state[1]++;
        int located = 0;
        Rect union = null;

        for (int offset = 0; offset < rawText.length();) {
            int cp = rawText.codePointAt(offset);
            int charCount = Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                localGroup = state[1]++;
                offset += charCount;
                continue;
            }

            Rect mappedChar = new Rect();
            if (locations != null && offset < locations.length && locations[offset] instanceof RectF) {
                RectF rf = (RectF) locations[offset];
                if (rf != null && !rf.isEmpty()) {
                    Rect sr = new Rect((int) Math.floor(rf.left), (int) Math.floor(rf.top),
                            (int) Math.ceil(rf.right), (int) Math.ceil(rf.bottom));
                    mappedChar = mapScreenRect(sr, screen,
                            screenshot.getWidth(), screenshot.getHeight());
                }
            }

            if (!mappedChar.isEmpty()) {
                chars.add(new OcrDocument.CharUnit(new String(Character.toChars(cp)), mappedChar,
                        1.0f, state[0], localGroup, state[2]++, OcrDocument.Source.VIEW));
                if (union == null) union = new Rect(mappedChar); else union.union(mappedChar);
                located++;
            }
            offset += charCount;
        }

        int visibleChars = countVisible(rawText);
        if (located > 0) {
            state[4]++;
            state[5] += located;
        }

        // Require useful real geometry. A provider returning one location for a long label is not
        // enough to make reliable handle selection; keep the View text but let OCR donate geometry.
        boolean usefulGeometry = located > 0
                && (visibleChars <= 3 || located >= Math.max(2, Math.round(visibleChars * 0.55f)));
        if (!usefulGeometry) chars.clear();

        Rect lineBounds = !chars.isEmpty() && union != null && !union.isEmpty()
                ? union : mappedNode;
        lines.add(new OcrDocument.Line(text, lineBounds, 1.0f, chars, OcrDocument.Source.VIEW));
        state[0]++;
    }

    private static Parcelable[] requestCharacterLocations(AccessibilityNodeInfo node, String text) {
        if (node == null || text == null || text.isEmpty() || Build.VERSION.SDK_INT < 26) return null;
        try {
            int length = Math.min(text.length(), 20000);
            if (length <= 0) return null;
            Bundle args = new Bundle();
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0);
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length);
            boolean ok = node.refreshWithExtraData(
                    AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args);
            if (!ok) return null;
            return node.getExtras().getParcelableArray(
                    AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY);
        } catch (Throwable ignored) {
            return null;
        }
    }

'''
s = s[:start] + new_accessibility + s[end:]

merge_start = s.index('    private static OcrDocument mergePreferViewText(')
merge_end = s.index('    private static boolean sameViewAndOcrText(', merge_start)
new_merge = r'''    private static OcrDocument mergePreferViewText(OcrDocument viewText, OcrDocument ocr) {
        if (viewText == null || viewText.lines().isEmpty()) return ocr;
        if (ocr == null || ocr.lines().isEmpty()) return viewText;

        ArrayList<OcrDocument.Line> lines = new ArrayList<>(viewText.lines());
        for (OcrDocument.Line ocrLine : ocr.lines()) {
            if (ocrLine == null || ocrLine.bounds().isEmpty()) continue;
            int matchingView = -1;
            for (int i = 0; i < lines.size(); i++) {
                OcrDocument.Line viewLine = lines.get(i);
                if (viewLine == null || viewLine.source() != OcrDocument.Source.VIEW
                        || viewLine.bounds().isEmpty()) continue;
                if (sameViewAndOcrText(viewLine, ocrLine)) {
                    matchingView = i;
                    break;
                }
            }

            if (matchingView >= 0) {
                OcrDocument.Line viewLine = lines.get(matchingView);
                if (viewLine.chars().isEmpty()) {
                    OcrDocument.Line hydrated = hydrateViewGeometry(viewLine, ocrLine);
                    if (hydrated != null) {
                        lines.set(matchingView, hydrated);
                    } else {
                        // We cannot safely map OCR glyphs to the View string. Keep OCR selectable
                        // instead of fabricating View character boxes; the View line remains as
                        // authoritative text metadata for later matching/refinement.
                        lines.add(ocrLine);
                    }
                }
            } else {
                lines.add(ocrLine);
            }
        }

        return documentFromLines(lines,
                "accessibility-view+" + ocr.engine() + "-geometry",
                Math.max(viewText.confidence(), ocr.confidence()),
                ocr.imageWidth(), ocr.imageHeight());
    }

    /**
     * Use OCR only as a ruler for a View string that lacks platform character locations. The text,
     * whitespace groups and source remain VIEW. Mapping is accepted only when the compact strings
     * and visible character counts agree exactly.
     */
    private static OcrDocument.Line hydrateViewGeometry(OcrDocument.Line viewLine,
                                                        OcrDocument.Line ocrLine) {
        if (viewLine == null || ocrLine == null || !viewLine.chars().isEmpty()
                || ocrLine.chars().isEmpty()) return null;
        String viewText = viewLine.text() == null ? "" : viewLine.text();
        String ocrText = ocrLine.text() == null ? "" : ocrLine.text();
        if (!compact(viewText).equals(compact(ocrText))) return null;

        ArrayList<OcrDocument.CharUnit> geometry = new ArrayList<>();
        for (OcrDocument.CharUnit unit : ocrLine.chars()) {
            if (unit == null || unit.text() == null || unit.text().isBlank() || unit.bounds().isEmpty()) continue;
            int[] cps = unit.text().codePoints().filter(cp -> !Character.isWhitespace(cp)).toArray();
            if (cps.length <= 1) {
                geometry.add(unit);
            } else {
                Rect box = unit.bounds();
                for (int i = 0; i < cps.length; i++) {
                    int left = box.left + box.width() * i / cps.length;
                    int right = box.left + box.width() * (i + 1) / cps.length;
                    geometry.add(new OcrDocument.CharUnit(new String(Character.toChars(cps[i])),
                            new Rect(left, box.top, Math.max(left + 1, right), box.bottom),
                            unit.confidence(), unit.line(), unit.group(), unit.order()));
                }
            }
        }
        if (geometry.size() != countVisible(viewText)) return null;

        ArrayList<OcrDocument.CharUnit> mapped = new ArrayList<>();
        int geo = 0;
        int group = 0;
        int order = 0;
        Rect union = null;
        for (int offset = 0; offset < viewText.length();) {
            int cp = viewText.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                group++;
                continue;
            }
            if (geo >= geometry.size()) return null;
            OcrDocument.CharUnit g = geometry.get(geo++);
            Rect bounds = g.bounds();
            mapped.add(new OcrDocument.CharUnit(new String(Character.toChars(cp)), bounds,
                    Math.max(0.72f, g.confidence()), 0, group, order++, OcrDocument.Source.VIEW));
            if (union == null) union = new Rect(bounds); else union.union(bounds);
        }
        if (mapped.isEmpty() || union == null || union.isEmpty()) return null;
        return new OcrDocument.Line(viewText, union, 0.95f, mapped, OcrDocument.Source.VIEW);
    }

'''
s = s[:merge_start] + new_merge + s[merge_end:]

replace_start = s.index('    private static OcrDocument replaceRegionPreservingView(')
replace_end = s.index('    private static OcrDocument documentFromLines(', replace_start)
new_replace = r'''    private static OcrDocument replaceRegionPreservingView(OcrDocument base,
                                                            OcrDocument patch,
                                                            Rect region) {
        if (patch == null || patch.lines().isEmpty()) return base == null ? patch : base;
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        if (base != null) {
            for (OcrDocument.Line line : base.lines()) {
                if (line == null || line.bounds().isEmpty()) continue;
                if (line.source() == OcrDocument.Source.VIEW || !Rect.intersects(line.bounds(), region)) {
                    lines.add(line);
                }
            }
        }

        for (OcrDocument.Line patchLine : patch.lines()) {
            if (patchLine == null || patchLine.bounds().isEmpty()) continue;
            int matchingView = -1;
            for (int i = 0; i < lines.size(); i++) {
                OcrDocument.Line line = lines.get(i);
                if (line != null && line.source() == OcrDocument.Source.VIEW
                        && sameViewAndOcrText(line, patchLine)) {
                    matchingView = i;
                    break;
                }
            }
            if (matchingView >= 0) {
                OcrDocument.Line viewLine = lines.get(matchingView);
                if (viewLine.chars().isEmpty()) {
                    OcrDocument.Line hydrated = hydrateViewGeometry(viewLine, patchLine);
                    if (hydrated != null) lines.set(matchingView, hydrated);
                    else lines.add(patchLine);
                }
            } else {
                lines.add(patchLine);
            }
        }

        return documentFromLines(lines, patch.engine() + "+view-preserved-index",
                patch.confidence(), patch.imageWidth(), patch.imageHeight());
    }

'''
s = s[:replace_start] + new_replace + s[replace_end:]

p.write_text(s, encoding='utf-8')
print('patched Circle Select View character geometry')
