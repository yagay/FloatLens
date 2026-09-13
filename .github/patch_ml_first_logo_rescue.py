from pathlib import Path

p = Path('app/src/main/java/com/yagay/floatlens/CircleRecognitionSession.java')
s = p.read_text()

s = s.replace('enum Stage { ACCESSIBILITY, FAST_MLKIT, FULL_DETECTOR, ROI_PRECISE }',
              'enum Stage { ACCESSIBILITY, FAST_MLKIT, ML_RESCUE, FULL_DETECTOR, ROI_PRECISE }')

# Replace ROI refinement with ML-first sequential rescue and configured OCR only as final fallback.
start = s.index('    /** Refine only a missed tap/line ROI with the configured high-accuracy OCR engine. */')
end = s.index('\n    void cancel()', start)
refine_block = r'''    /**
     * Refine a missed tap/line ROI with ML Kit first. Original geometry wins; enhanced/inverted/
     * binary passes are attempted only when earlier ML passes return no text. The configured OCR
     * engine (which may be Paddle) is a final fallback, not the primary geometry source.
     */
    void refine(Rect imageRegion) {
        if (closed || imageRegion == null || imageRegion.isEmpty()
                || screenshot == null || screenshot.isRecycled()) return;
        Rect region = new Rect(imageRegion);
        if (!region.intersect(0, 0, screenshot.getWidth(), screenshot.getHeight()) || region.isEmpty()) return;

        Bitmap crop;
        try {
            crop = Bitmap.createBitmap(screenshot, region.left, region.top, region.width(), region.height());
        } catch (Throwable t) {
            emitFailure(Stage.ROI_PRECISE, t, true);
            return;
        }

        final long run = generation;
        DiagnosticLog.i(app, "CIRCLE_INDEX", "roi ml-first start=" + region.toShortString()
                + " crop=" + crop.getWidth() + "x" + crop.getHeight());
        runRegionMlPass(run, crop, region,
                new int[] { OcrImagePreprocessor.MODE_ORIGINAL,
                        OcrImagePreprocessor.MODE_ENHANCED,
                        OcrImagePreprocessor.MODE_INVERTED,
                        OcrImagePreprocessor.MODE_BINARY }, 0);
    }

    private void runRegionMlPass(long run, Bitmap crop, Rect region, int[] modes, int index) {
        if (!isCurrent(run)) {
            if (crop != null && !crop.isRecycled()) crop.recycle();
            return;
        }
        if (index >= modes.length) {
            runRegionConfiguredFallback(run, crop, region);
            return;
        }

        final int mode = modes[index];
        final boolean chinese = useChineseRecognizer();
        Thread prep = new Thread(() -> {
            OcrImagePreprocessor.Prepared prepared = OcrImagePreprocessor.prepare(crop, mode);
            if (prepared == null || prepared.bitmap == null || prepared.bitmap.isRecycled()) {
                runRegionMlPass(run, crop, region, modes, index + 1);
                return;
            }
            TextRecognizer recognizer = null;
            try {
                recognizer = chinese
                        ? TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                        : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                final TextRecognizer client = recognizer;
                String engine = "roi-ml-" + (chinese ? "zh-" : "latin-")
                        + OcrImagePreprocessor.modeName(mode).toLowerCase(Locale.ROOT);
                client.process(InputImage.fromBitmap(prepared.bitmap, 0))
                        .addOnSuccessListener(text -> {
                            try {
                                if (!isCurrent(run)) return;
                                OcrDocument doc = mlKitPreparedDocument(text, engine, prepared,
                                        crop.getWidth(), crop.getHeight());
                                DiagnosticLog.i(app, "CIRCLE_ML_PASS", "roi pass=" + engine
                                        + " chars=" + doc.chars().size()
                                        + " lines=" + doc.lines().size()
                                        + " prepared=" + prepared.bitmap.getWidth() + "x"
                                        + prepared.bitmap.getHeight());
                                if (!doc.chars().isEmpty()) {
                                    OcrDocument translated = doc.translated(region.left, region.top,
                                            screenshot.getWidth(), screenshot.getHeight());
                                    current = replaceRegionPreservingView(current, translated, region);
                                    DiagnosticLog.i(app, "CIRCLE_INDEX", "roi ml-first ready engine="
                                            + engine + " chars=" + doc.chars().size()
                                            + " region=" + region.toShortString());
                                    emit(current, Stage.ROI_PRECISE, true);
                                    if (!crop.isRecycled()) crop.recycle();
                                } else {
                                    runRegionMlPass(run, crop, region, modes, index + 1);
                                }
                            } finally {
                                try { client.close(); } catch (Throwable ignored) {}
                                OcrImagePreprocessor.recycle(prepared);
                            }
                        })
                        .addOnFailureListener(error -> {
                            try {
                                DiagnosticLog.i(app, "CIRCLE_ML_PASS", "roi pass=" + engine
                                        + " failed=" + safe(error));
                            } finally {
                                try { client.close(); } catch (Throwable ignored) {}
                                OcrImagePreprocessor.recycle(prepared);
                            }
                            runRegionMlPass(run, crop, region, modes, index + 1);
                        });
            } catch (Throwable t) {
                if (recognizer != null) try { recognizer.close(); } catch (Throwable ignored) {}
                OcrImagePreprocessor.recycle(prepared);
                runRegionMlPass(run, crop, region, modes, index + 1);
            }
        }, "FloatLens-Circle-ROI-ML");
        prep.setPriority(Thread.NORM_PRIORITY - 1);
        prep.start();
    }

    private void runRegionConfiguredFallback(long run, Bitmap crop, Rect region) {
        DiagnosticLog.i(app, "CIRCLE_INDEX", "roi ML empty -> configured OCR fallback region="
                + region.toShortString());
        OcrEngine.recognizeDocument(app, crop, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                try {
                    if (!isCurrent(run)) return;
                    OcrDocument translated = document.translated(region.left, region.top,
                            screenshot.getWidth(), screenshot.getHeight());
                    current = replaceRegionPreservingView(current, translated, region);
                    DiagnosticLog.i(app, "CIRCLE_INDEX", "roi fallback ready engine="
                            + document.engine() + " chars=" + document.chars().size()
                            + " region=" + region.toShortString());
                    emit(current, Stage.ROI_PRECISE, true);
                } finally {
                    if (!crop.isRecycled()) crop.recycle();
                }
            }

            @Override public void onFailure(Throwable error) {
                if (!crop.isRecycled()) crop.recycle();
                if (!isCurrent(run)) return;
                DiagnosticLog.i(app, "CIRCLE_INDEX", "roi fallback failed=" + safe(error));
                emitFailure(Stage.ROI_PRECISE, error, true);
            }
        });
    }
'''
s = s[:start] + refine_block + s[end:]

# Original quick pass remains first, but all follow-up work is ML rescue before Paddle.
method_start = s.index('    private void startFastMlKit(long run)')
method_end = s.index('\n    /**\n     * One deep full-screen detector pass', method_start)
fast = s[method_start:method_end].replace('startFullScreenDetector(run);', 'startMlRescue(run);')
s = s[:method_start] + fast + s[method_end:]

marker = '    /**\n     * One deep full-screen detector pass supplements the fast ML Kit cache.'
insert = r'''    private boolean useChineseRecognizer() {
        Set<String> languages = OcrLanguages.get(app);
        boolean chinese = OcrLanguages.chineseEnabled(languages);
        boolean english = OcrLanguages.englishEnabled(languages);
        if (!chinese && !english) chinese = true;
        return chinese;
    }

    /**
     * Build the persistent image-text cache entirely with ML Kit first. Later passes only add text
     * missed by earlier passes, so original ML geometry remains authoritative when results overlap.
     */
    private void startMlRescue(long run) {
        if (!isCurrent(run) || screenshot == null || screenshot.isRecycled()) return;
        int[] modes = new int[] {
                OcrImagePreprocessor.MODE_ENHANCED,
                OcrImagePreprocessor.MODE_INVERTED,
                OcrImagePreprocessor.MODE_BINARY
        };
        runFullScreenMlPass(run, modes, 0, useChineseRecognizer());
    }

    private void runFullScreenMlPass(long run, int[] modes, int index, boolean chinese) {
        if (!isCurrent(run)) return;
        if (index >= modes.length) {
            int mlChars = countOcrChars(current);
            if (mlChars == 0) {
                DiagnosticLog.i(app, "CIRCLE_INDEX", "all ML image passes empty -> Paddle fallback");
                startFullScreenDetector(run);
            } else {
                DiagnosticLog.i(app, "CIRCLE_INDEX", "ML-first cache complete ocrChars=" + mlChars
                        + " Paddle=skipped");
            }
            return;
        }

        final int mode = modes[index];
        Thread prep = new Thread(() -> {
            OcrImagePreprocessor.Prepared prepared = OcrImagePreprocessor.prepare(screenshot, mode);
            if (prepared == null || prepared.bitmap == null || prepared.bitmap.isRecycled()) {
                DiagnosticLog.i(app, "CIRCLE_ML_PASS", "full pass="
                        + OcrImagePreprocessor.modeName(mode) + " skipped=prepare_failed");
                runFullScreenMlPass(run, modes, index + 1, chinese);
                return;
            }
            TextRecognizer recognizer = null;
            try {
                recognizer = chinese
                        ? TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                        : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                final TextRecognizer client = recognizer;
                String engine = "ml-rescue-" + (chinese ? "zh-" : "latin-")
                        + OcrImagePreprocessor.modeName(mode).toLowerCase(Locale.ROOT);
                long started = android.os.SystemClock.uptimeMillis();
                client.process(InputImage.fromBitmap(prepared.bitmap, 0))
                        .addOnSuccessListener(text -> {
                            try {
                                if (!isCurrent(run)) return;
                                OcrDocument doc = mlKitPreparedDocument(text, engine, prepared,
                                        screenshot.getWidth(), screenshot.getHeight());
                                int before = current == null ? 0 : current.chars().size();
                                current = mergeSupplementalOcr(current, doc);
                                int after = current == null ? 0 : current.chars().size();
                                DiagnosticLog.i(app, "CIRCLE_ML_PASS", "full pass=" + engine
                                        + " chars=" + doc.chars().size()
                                        + " lines=" + doc.lines().size()
                                        + " added=" + Math.max(0, after - before)
                                        + " prepared=" + prepared.bitmap.getWidth() + "x"
                                        + prepared.bitmap.getHeight()
                                        + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started));
                                emit(current, Stage.ML_RESCUE, true);
                            } finally {
                                try { client.close(); } catch (Throwable ignored) {}
                                OcrImagePreprocessor.recycle(prepared);
                            }
                            runFullScreenMlPass(run, modes, index + 1, chinese);
                        })
                        .addOnFailureListener(error -> {
                            try {
                                DiagnosticLog.i(app, "CIRCLE_ML_PASS", "full pass=" + engine
                                        + " failed=" + safe(error));
                            } finally {
                                try { client.close(); } catch (Throwable ignored) {}
                                OcrImagePreprocessor.recycle(prepared);
                            }
                            runFullScreenMlPass(run, modes, index + 1, chinese);
                        });
            } catch (Throwable t) {
                if (recognizer != null) try { recognizer.close(); } catch (Throwable ignored) {}
                OcrImagePreprocessor.recycle(prepared);
                DiagnosticLog.i(app, "CIRCLE_ML_PASS", "full pass="
                        + OcrImagePreprocessor.modeName(mode) + " init_failed=" + safe(t));
                runFullScreenMlPass(run, modes, index + 1, chinese);
            }
        }, "FloatLens-Circle-Full-ML");
        prep.setPriority(Thread.NORM_PRIORITY - 1);
        prep.start();
    }

    private static int countOcrChars(OcrDocument document) {
        if (document == null || document.lines().isEmpty()) return 0;
        int count = 0;
        for (OcrDocument.Line line : document.lines()) {
            if (line != null && line.source() == OcrDocument.Source.OCR) count += line.chars().size();
        }
        return count;
    }

'''
pos = s.index(marker)
s = s[:pos] + insert + s[pos:]

# Add ML document conversion that maps every ML Kit box back through preprocessing geometry.
marker2 = '    /**\n     * Accessibility View text wins only for OCR lines that represent the same visible text.'
prepared_method = r'''    /** Build ML Kit symbol geometry and map every box back to the unprocessed source bitmap. */
    private static OcrDocument mlKitPreparedDocument(Text text, String engine,
                                                      OcrImagePreprocessor.Prepared prepared,
                                                      int width, int height) {
        if (prepared == null) return emptyDocument(engine);
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        int lineId = 0;
        int group = 0;
        int order = 0;
        if (text != null) {
            for (Text.TextBlock block : text.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    String lineText = line.getText() == null ? "" : line.getText().trim();
                    Rect lineBox = prepared.toSourceRect(line.getBoundingBox());
                    if (lineText.isEmpty() || lineBox.isEmpty()) continue;

                    ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
                    for (Text.Element element : line.getElements()) {
                        String value = element.getText() == null ? "" : element.getText();
                        Rect elementBox = prepared.toSourceRect(element.getBoundingBox());
                        if (value.isBlank() || elementBox.isEmpty()) continue;

                        int elementGroup = group++;
                        ArrayList<OcrDocument.CharUnit> symbolsOut = new ArrayList<>();
                        StringBuilder symbolsText = new StringBuilder();
                        try {
                            List<Text.Symbol> symbols = element.getSymbols();
                            if (symbols != null) {
                                for (Text.Symbol symbol : symbols) {
                                    if (symbol == null || symbol.getText() == null || symbol.getText().isBlank()) continue;
                                    Rect symbolBox = prepared.toSourceRect(symbol.getBoundingBox());
                                    if (symbolBox.isEmpty()) continue;
                                    symbolsText.append(symbol.getText());
                                    symbolsOut.add(new OcrDocument.CharUnit(symbol.getText(), symbolBox,
                                            0.78f, lineId, elementGroup, order++));
                                }
                            }
                        } catch (Throwable ignored) {}

                        if (!symbolsOut.isEmpty()
                                && compact(symbolsText.toString()).equals(compact(value))) {
                            chars.addAll(symbolsOut);
                        } else {
                            order -= symbolsOut.size();
                            appendSplit(chars, value, elementBox, lineId, elementGroup, order);
                            order += countVisible(value);
                        }
                    }

                    if (chars.isEmpty()) {
                        int lineGroup = group++;
                        appendSplit(chars, lineText, lineBox, lineId, lineGroup, order);
                        order += countVisible(lineText);
                    }
                    if (!chars.isEmpty()) {
                        chars.sort(Comparator.comparingInt((OcrDocument.CharUnit c) -> c.bounds().left)
                                .thenComparingInt(c -> c.bounds().top));
                        lines.add(new OcrDocument.Line(lineText, lineBox, 0.78f, chars));
                        lineId++;
                    }
                }
            }
        }
        return documentFromLines(lines, engine, 0.78f, width, height);
    }

'''
pos2 = s.index(marker2)
s = s[:pos2] + prepared_method + s[pos2:]

p.write_text(s)
print('patched CircleRecognitionSession.java')
