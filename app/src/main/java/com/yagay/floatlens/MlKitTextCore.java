package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;

import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Shared ML Kit foundation.
 *
 * Callers still decide when/how many passes to run. Recognizer language choice and Text ->
 * OcrDocument parsing live here so normal OCR and Circle do not maintain different geometry rules.
 */
final class MlKitTextCore {
    interface RectMapper { Rect map(Rect source); }

    static TextRecognizer createPreferredRecognizer(Context context) {
        boolean chinese = preferChinese(context);
        return chinese
                ? TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    static String preferredEngine(String prefix, Context context) {
        return (prefix == null ? "mlkit" : prefix) + (preferChinese(context) ? "-zh" : "-latin");
    }

    private static boolean preferChinese(Context context) {
        if (context == null) return true;
        Set<String> languages = OcrLanguages.get(context.getApplicationContext());
        boolean chinese = OcrLanguages.chineseEnabled(languages);
        boolean english = OcrLanguages.englishEnabled(languages);
        if (!chinese && !english) return true;
        return chinese;
    }

    static OcrDocument toDocument(Text text, String engine,
                                  int imageWidth, int imageHeight,
                                  float confidence, RectMapper mapper) {
        RectMapper safeMapper = mapper == null ? MlKitTextCore::copy : mapper;
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        ArrayList<String> blocks = new ArrayList<>();
        int lineId = 0;
        int group = 0;
        int order = 0;

        if (text != null) {
            for (Text.TextBlock block : text.getTextBlocks()) {
                if (block != null && block.getText() != null && !block.getText().isBlank()) {
                    blocks.add(block.getText());
                }
                if (block == null) continue;
                for (Text.Line line : block.getLines()) {
                    if (line == null) continue;
                    String lineText = line.getText() == null ? "" : line.getText().trim();
                    Rect lineBox = safeMap(safeMapper, line.getBoundingBox());
                    if (lineText.isEmpty() || lineBox.isEmpty()) continue;

                    ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
                    for (Text.Element element : line.getElements()) {
                        if (element == null) continue;
                        String value = element.getText() == null ? "" : element.getText();
                        Rect elementBox = safeMap(safeMapper, element.getBoundingBox());
                        if (value.isBlank() || elementBox.isEmpty()) continue;

                        int elementGroup = group++;
                        ArrayList<OcrDocument.CharUnit> symbolsOut = new ArrayList<>();
                        StringBuilder symbolsText = new StringBuilder();
                        List<Text.Symbol> symbols;
                        try { symbols = element.getSymbols(); }
                        catch (Throwable ignored) { symbols = List.of(); }
                        if (symbols != null) {
                            for (Text.Symbol symbol : symbols) {
                                if (symbol == null || symbol.getText() == null || symbol.getText().isBlank()) continue;
                                Rect symbolBox = safeMap(safeMapper, symbol.getBoundingBox());
                                if (symbolBox.isEmpty()) continue;
                                symbolsText.append(symbol.getText());
                                symbolsOut.add(new OcrDocument.CharUnit(symbol.getText(), symbolBox,
                                        confidence, lineId, elementGroup, order++));
                            }
                        }

                        if (!symbolsOut.isEmpty()
                                && compact(symbolsText.toString()).equals(compact(value))) {
                            chars.addAll(symbolsOut);
                        } else {
                            order -= symbolsOut.size();
                            appendSplit(chars, value, elementBox, confidence,
                                    lineId, elementGroup, order);
                            order += countVisible(value);
                        }
                    }

                    if (chars.isEmpty()) {
                        int lineGroup = group++;
                        appendSplit(chars, lineText, lineBox, confidence,
                                lineId, lineGroup, order);
                        order += countVisible(lineText);
                    }
                    if (!chars.isEmpty()) {
                        chars.sort(Comparator.comparingInt((OcrDocument.CharUnit c) -> c.bounds().left)
                                .thenComparingInt(c -> c.bounds().top));
                        lines.add(new OcrDocument.Line(lineText, lineBox, confidence, chars));
                        lineId++;
                    }
                }
            }
        }

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
        if (orderedBlocks.isEmpty() && !blocks.isEmpty()) orderedBlocks.addAll(blocks);
        double score = lines.size() * 5d;
        for (OcrDocument.Line line : lines) score += line.chars().size();
        return new OcrDocument(full.toString(), orderedBlocks, lines, engine,
                confidence, score, Math.max(1, imageWidth), Math.max(1, imageHeight));
    }

    private static Rect safeMap(RectMapper mapper, Rect source) {
        if (source == null || source.isEmpty()) return new Rect();
        try {
            Rect mapped = mapper.map(new Rect(source));
            return mapped == null ? new Rect() : new Rect(mapped);
        } catch (Throwable ignored) {
            return new Rect();
        }
    }

    private static Rect copy(Rect source) { return source == null ? new Rect() : new Rect(source); }

    private static void appendSplit(List<OcrDocument.CharUnit> out, String value, Rect box,
                                    float confidence, int line, int group, int startOrder) {
        if (value == null || value.isEmpty() || box == null || box.isEmpty()) return;
        int visible = countVisible(value);
        if (visible <= 0) return;
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
    }

    static int countVisible(String value) {
        if (value == null || value.isEmpty()) return 0;
        int count = 0;
        for (int cp : value.codePoints().toArray()) if (!Character.isWhitespace(cp)) count++;
        return count;
    }

    static String compact(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp)) continue;
            out.appendCodePoint(Character.toLowerCase(cp));
        }
        return out.toString();
    }

    private MlKitTextCore() {}
}
