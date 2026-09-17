package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts any OCR engine output into the exact document semantics used by MlKitTextCore.
 *
 * <p>This does not invoke ML Kit. Recognition remains owned by the selected engine. Everything
 * after recognition follows the ML pipeline contract: visual line ordering, element/group
 * boundaries, symbol/character ordering, element fallback splitting and stable reading order.</p>
 */
final class OcrCanonicalGeometry {
    static OcrDocument normalize(Context context, OcrDocument source) {
        if (source == null || source.lines().isEmpty()) return source;

        ArrayList<OcrDocument.Line> rawLines = new ArrayList<>();
        for (OcrDocument.Line line : source.lines()) {
            if (line == null || line.text() == null || line.text().trim().isEmpty()
                    || line.bounds().isEmpty()) continue;
            rawLines.add(line);
        }
        if (rawLines.isEmpty()) return source;

        // Same visual reading-order policy as MlKitTextCore.toDocument().
        rawLines.sort(Comparator.comparingInt((OcrDocument.Line l) -> l.bounds().centerY())
                .thenComparingInt(l -> l.bounds().left));

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        ArrayList<String> blocks = new ArrayList<>();
        int lineId = 0;
        int nextGroup = 0;
        int order = 0;

        for (OcrDocument.Line rawLine : rawLines) {
            ArrayList<OcrDocument.CharUnit> sourceChars = new ArrayList<>();
            for (OcrDocument.CharUnit c : rawLine.chars()) {
                if (c == null || c.text() == null || c.text().isBlank() || c.bounds().isEmpty()) continue;
                sourceChars.add(c);
            }
            sourceChars.sort(Comparator.comparingInt((OcrDocument.CharUnit c) -> c.bounds().left)
                    .thenComparingInt(c -> c.bounds().top));

            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            if (!sourceChars.isEmpty()) {
                // Existing source groups are treated exactly like ML Text.Element boundaries.
                // Paddle creates these groups from recognizer word/space segmentation. Every
                // character inside one group is therefore equivalent to an ML Text.Symbol.
                Map<Integer, ArrayList<OcrDocument.CharUnit>> elements = new LinkedHashMap<>();
                for (OcrDocument.CharUnit c : sourceChars) {
                    elements.computeIfAbsent(c.group(), ignored -> new ArrayList<>()).add(c);
                }
                for (ArrayList<OcrDocument.CharUnit> element : elements.values()) {
                    element.sort(Comparator.comparingInt((OcrDocument.CharUnit c) -> c.bounds().left)
                            .thenComparingInt(c -> c.bounds().top));
                    int elementGroup = nextGroup++;
                    for (OcrDocument.CharUnit symbol : element) {
                        chars.add(new OcrDocument.CharUnit(symbol.text(), symbol.bounds(),
                                symbol.confidence(), lineId, elementGroup, order++));
                    }
                }
            }

            // Same fallback as ML: if no symbol geometry exists, split the line box uniformly over
            // visible code points. This keeps non-ML engines compatible with ML selection semantics.
            if (chars.isEmpty()) {
                int lineGroup = nextGroup++;
                order += appendSplit(chars, rawLine.text(), rawLine.bounds(), rawLine.confidence(),
                        lineId, lineGroup, order);
            }
            if (chars.isEmpty()) continue;

            chars.sort(Comparator.comparingInt((OcrDocument.CharUnit c) -> c.bounds().left)
                    .thenComparingInt(c -> c.bounds().top));
            String text = rawLine.text().trim();
            lines.add(new OcrDocument.Line(text, rawLine.bounds(), rawLine.confidence(), chars));
            blocks.add(text);
            lineId++;
        }

        if (lines.isEmpty()) return source;
        lines.sort(Comparator.comparingInt((OcrDocument.Line l) -> l.bounds().centerY())
                .thenComparingInt(l -> l.bounds().left));

        StringBuilder full = new StringBuilder();
        ArrayList<String> orderedBlocks = new ArrayList<>();
        for (OcrDocument.Line line : lines) {
            String value = line.text().trim();
            if (value.isEmpty()) continue;
            if (full.length() > 0) full.append('\n');
            full.append(value);
            orderedBlocks.add(value);
        }
        if (orderedBlocks.isEmpty()) orderedBlocks.addAll(blocks);

        double score = lines.size() * 5d;
        for (OcrDocument.Line line : lines) score += line.chars().size();
        OcrDocument result = new OcrDocument(full.toString(), orderedBlocks, lines,
                source.engine() + "+ml-semantics", source.confidence(), score,
                source.imageWidth(), source.imageHeight(), source.coordinateSpace());
        DiagnosticLog.i(context, "OCR_ML_SEMANTICS",
                "recognizer=" + source.engine()
                        + " lines=" + source.lines().size() + "->" + result.lines().size()
                        + " chars=" + source.chars().size() + "->" + result.chars().size()
                        + " policy=ml_line_element_symbol_order_no_ml_runtime");
        return result;
    }

    private static int appendSplit(List<OcrDocument.CharUnit> out, String value, Rect box,
                                   float confidence, int line, int group, int startOrder) {
        if (value == null || value.isEmpty() || box == null || box.isEmpty()) return 0;
        int visible = MlKitTextCore.countVisible(value);
        if (visible <= 0) return 0;
        int index = 0;
        for (int cp : value.codePoints().toArray()) {
            if (Character.isWhitespace(cp)) continue;
            int left = box.left + box.width() * index / visible;
            int right = box.left + box.width() * (index + 1) / visible;
            out.add(new OcrDocument.CharUnit(new String(Character.toChars(cp)),
                    new Rect(left, box.top, Math.max(left + 1, right), box.bottom),
                    confidence, line, group, startOrder + index));
            index++;
        }
        return index;
    }

    private OcrCanonicalGeometry() {}
}
