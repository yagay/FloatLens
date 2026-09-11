from pathlib import Path

base = Path('app/src/main/java/com/yagay/floatlens')

# FloatSettings: independent OCR engine mode (0 auto/Paddle first, 1 Paddle only, 2 ML Kit only).
p = base / 'FloatSettings.java'
s = p.read_text()
if 'K_OCR_ENGINE' not in s:
    s = s.replace('    public static final String K_OCR_TYPE = "ocr_type";\n',
                  '    public static final String K_OCR_TYPE = "ocr_type";\n'
                  '    public static final String K_OCR_ENGINE = "ocr_engine_mode";\n')
if 'public int ocrEngineMode()' not in s:
    s = s.replace('    public int ocrType() { return clamp(p.getInt(K_OCR_TYPE, 0), 0, 1); }\n',
                  '    public int ocrType() { return clamp(p.getInt(K_OCR_TYPE, 0), 0, 1); }\n'
                  '    public int ocrEngineMode() {\n'
                  '        Object raw = p.getAll().get(K_OCR_ENGINE);\n'
                  '        if (raw instanceof Number n) return clamp(n.intValue(), 0, 2);\n'
                  '        if (raw instanceof String v) { try { return clamp(Integer.parseInt(v.trim()), 0, 2); } catch (Throwable ignored) {} }\n'
                  '        return 0;\n'
                  '    }\n')
p.write_text(s)

# SettingsActivity: engine chooser + keep old ocr_type as ML Kit language preference.
p = base / 'SettingsActivity.java'
s = p.read_text()
if 'ocrEngineSpinner(root);' not in s:
    s = s.replace('        ocrTypeSpinner(root);\n', '        ocrEngineSpinner(root);\n        ocrTypeSpinner(root);\n', 1)
s = s.replace('TextView t = new TextView(this); t.setText("OCR 引擎 / ocr_type");',
              'TextView t = new TextView(this); t.setText("ML Kit 识别语言 / ocr_type");')
if 'private void ocrEngineSpinner(' not in s:
    marker = '    private void ocrTypeSpinner(LinearLayout r) {\n'
    method = '''    private void ocrEngineSpinner(LinearLayout r) {
        TextView t = new TextView(this); t.setText("OCR 引擎 / ocr_engine_mode"); r.addView(t);
        String[] labels = {
                "自动：PP-OCRv6 高精度优先，失败回退 ML Kit",
                "PP-OCRv6 高精度（纯本地）",
                "ML Kit 快速"
        };
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        s.setSelection(fs.ocrEngineMode());
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                fs.prefs().edit().putInt(FloatSettings.K_OCR_ENGINE, pos).apply();
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        r.addView(s);
    }

'''
    if marker not in s:
        raise SystemExit('SettingsActivity ocrTypeSpinner marker missing')
    s = s.replace(marker, method + marker, 1)
p.write_text(s)

# OcrEngine: PP-OCRv6 primary route, existing serial ML Kit pipeline retained as fallback/fast mode.
p = base / 'OcrEngine.java'
s = p.read_text()
old_start = '''        try {
            DiagnosticLog.i(app, "OCR_INIT", "read_type_begin");
            int type = readOcrTypeSafely(app);
            DiagnosticLog.i(app, "OCR_INIT", "read_type_ok type=" + type);

            ArrayList<PassSpec> plan = new ArrayList<>();
            if (type == 1) {
                plan.add(new PassSpec("latin-original", OcrImagePreprocessor.MODE_ORIGINAL, false));
                plan.add(new PassSpec("latin-enhanced", OcrImagePreprocessor.MODE_ENHANCED, false));
                plan.add(new PassSpec("latin-mono", OcrImagePreprocessor.MODE_MONO, false));
            } else {
                plan.add(new PassSpec("zh-original", OcrImagePreprocessor.MODE_ORIGINAL, true));
                plan.add(new PassSpec("latin-original", OcrImagePreprocessor.MODE_ORIGINAL, false));
                plan.add(new PassSpec("zh-enhanced", OcrImagePreprocessor.MODE_ENHANCED, true));
                plan.add(new PassSpec("latin-enhanced", OcrImagePreprocessor.MODE_ENHANCED, false));
                plan.add(new PassSpec("zh-mono", OcrImagePreprocessor.MODE_MONO, true));
            }

            DiagnosticLog.i(app, "OCR_PIPELINE", "start highAccuracy=serial-safe type=" + type
                    + " source=" + b.getWidth() + "x" + b.getHeight()
                    + " passes=" + plan.size());
            new RunState(app, service, b, resultAnchor, plan).next();
        } catch (Throwable t) {
            DiagnosticLog.i(app, "OCR_INIT_FAIL", t.getClass().getName() + ":" + safe(t));
            runFallback(app, service, b, resultAnchor, "init_failure");
        }
'''
new_start = '''        startSelectedEngine(app, service, b, resultAnchor);
'''
if old_start in s:
    s = s.replace(old_start, new_start, 1)
elif 'startSelectedEngine(app, service, b, resultAnchor);' not in s:
    raise SystemExit('OcrEngine recognize pipeline marker missing')

if 'private static void startSelectedEngine(' not in s:
    marker = '    private static int readOcrTypeSafely(Context app) {\n'
    helpers = '''    private static void startSelectedEngine(Context app, FloatService service,
                                            Bitmap source, Rect anchor) {
        int engineMode = readOcrEngineModeSafely(app);
        DiagnosticLog.i(app, "OCR_ENGINE", "mode=" + engineMode
                + " 0=auto 1=ppocrv6 2=mlkit loaded=" + PaddleOcrBridge.isLoaded());
        if (engineMode == 2) {
            startMlKitPipeline(app, service, source, anchor, "manual_mlkit");
            return;
        }

        final boolean allowFallback = engineMode == 0;
        try {
            DiagnosticLog.i(app, "PPOCRV6", "launch image=" + source.getWidth() + "x" + source.getHeight()
                    + " fallback=" + allowFallback);
            PaddleOcrBridge.recognize(app, source, new PaddleOcrBridge.Callback() {
                @Override public void onSuccess(String text, List<String> blocks, long totalMs,
                                                int lineCount, float averageConfidence) {
                    String full = text == null ? "" : text.trim();
                    List<String> safeBlocks = blocks == null ? List.of() : blocks;
                    DiagnosticLog.i(app, "PPOCRV6", "success chars=" + full.length()
                            + " lines=" + lineCount + " blocks=" + safeBlocks.size()
                            + " avgConf=" + String.format(java.util.Locale.US, "%.3f", averageConfidence)
                            + " totalMs=" + totalMs);
                    if (full.isBlank()) {
                        if (allowFallback) {
                            DiagnosticLog.i(app, "PPOCRV6", "empty -> ML Kit fallback");
                            startMlKitPipeline(app, service, source, anchor, "ppocrv6_empty");
                        } else {
                            if (service != null) service.onCircleFinished("ppocrv6_empty");
                            Toast.makeText(app, "PP-OCRv6 未识别到文字", Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }
                    if (service != null) service.onOcrResults(Math.max(1, safeBlocks.size()));
                    if (!ResultTextActivity.show(app, full, safeBlocks, source, anchor)) {
                        ResultOverlay.show(app, full, safeBlocks, source, anchor);
                    }
                }

                @Override public void onFailure(String message) {
                    DiagnosticLog.i(app, "PPOCRV6", "failure=" + message);
                    if (allowFallback) {
                        startMlKitPipeline(app, service, source, anchor, "ppocrv6_failure:" + message);
                    } else {
                        if (service != null) service.onCircleFinished("ppocrv6_failure");
                        Toast.makeText(app, "PP-OCRv6 失败: " + message, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (Throwable t) {
            DiagnosticLog.i(app, "PPOCRV6", "launchFailure=" + t.getClass().getSimpleName()
                    + ":" + safe(t));
            if (allowFallback) startMlKitPipeline(app, service, source, anchor, "ppocrv6_launch_failure");
            else {
                if (service != null) service.onCircleFinished("ppocrv6_launch_failure");
                Toast.makeText(app, "PP-OCRv6 启动失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static void startMlKitPipeline(Context app, FloatService service,
                                           Bitmap source, Rect anchor, String reason) {
        try {
            DiagnosticLog.i(app, "OCR_INIT", "MLKit begin reason=" + reason);
            int type = readOcrTypeSafely(app);
            DiagnosticLog.i(app, "OCR_INIT", "read_type_ok type=" + type);

            ArrayList<PassSpec> plan = new ArrayList<>();
            if (type == 1) {
                plan.add(new PassSpec("latin-original", OcrImagePreprocessor.MODE_ORIGINAL, false));
                plan.add(new PassSpec("latin-enhanced", OcrImagePreprocessor.MODE_ENHANCED, false));
                plan.add(new PassSpec("latin-mono", OcrImagePreprocessor.MODE_MONO, false));
            } else {
                plan.add(new PassSpec("zh-original", OcrImagePreprocessor.MODE_ORIGINAL, true));
                plan.add(new PassSpec("latin-original", OcrImagePreprocessor.MODE_ORIGINAL, false));
                plan.add(new PassSpec("zh-enhanced", OcrImagePreprocessor.MODE_ENHANCED, true));
                plan.add(new PassSpec("latin-enhanced", OcrImagePreprocessor.MODE_ENHANCED, false));
                plan.add(new PassSpec("zh-mono", OcrImagePreprocessor.MODE_MONO, true));
            }

            DiagnosticLog.i(app, "OCR_PIPELINE", "start MLKit serial-safe type=" + type
                    + " source=" + source.getWidth() + "x" + source.getHeight()
                    + " passes=" + plan.size() + " reason=" + reason);
            new RunState(app, service, source, anchor, plan).next();
        } catch (Throwable t) {
            DiagnosticLog.i(app, "OCR_INIT_FAIL", t.getClass().getName() + ":" + safe(t));
            runFallback(app, service, source, anchor, "mlkit_init_failure");
        }
    }

    private static int readOcrEngineModeSafely(Context app) {
        try {
            SharedPreferences p = app.getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE);
            Object raw = p.getAll().get(FloatSettings.K_OCR_ENGINE);
            if (raw instanceof Number) return Math.max(0, Math.min(2, ((Number) raw).intValue()));
            if (raw instanceof String) {
                try { return Math.max(0, Math.min(2, Integer.parseInt(((String) raw).trim()))); }
                catch (Throwable ignored) { return 0; }
            }
        } catch (Throwable t) {
            DiagnosticLog.i(app, "OCR_ENGINE", "read fallback=" + safe(t));
        }
        return 0;
    }

'''
    if marker not in s:
        raise SystemExit('OcrEngine readOcrType marker missing')
    s = s.replace(marker, helpers + marker, 1)
p.write_text(s)
