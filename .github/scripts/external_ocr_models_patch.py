from pathlib import Path

root = Path('.')
base = root / 'app/src/main/java/com/yagay/floatlens'
sdk = root / 'ppocr-sdk/src/main/java/com/paddle/ocr'

# Root Gradle: enable Kotlin for PaddleOCR SDK + bridge.
p = root / 'build.gradle'
s = p.read_text()
if "org.jetbrains.kotlin.android" not in s:
    s = s.replace("    id 'com.android.library' version '9.2.0' apply false\n", "    id 'com.android.library' version '9.2.0' apply false\n    id 'org.jetbrains.kotlin.android' version '2.1.0' apply false\n")
p.write_text(s)

# App Gradle: compile Kotlin bridge. Models are NOT bundled in APK.
p = root / 'app/build.gradle'
s = p.read_text()
if "id 'org.jetbrains.kotlin.android'" not in s:
    s = s.replace("plugins {\n    id 'com.android.application'\n}", "plugins {\n    id 'com.android.application'\n    id 'org.jetbrains.kotlin.android'\n}")
p.write_text(s)

# SDK Gradle: Kotlin support, no build-time model download / assets.
p = root / 'ppocr-sdk/build.gradle'
s = p.read_text()
if "id 'org.jetbrains.kotlin.android'" not in s:
    s = s.replace("    id 'com.android.library'\n", "    id 'com.android.library'\n    id 'org.jetbrains.kotlin.android'\n")
marker = "\ndef modelFiles = ["
if marker in s:
    s = s[:s.index(marker)].rstrip() + "\n"
p.write_text(s)

# SDK: allow absolute files as well as packaged assets.
p = sdk / 'engine/ORTSessionManager.kt'
s = p.read_text()
if 'import java.io.File' not in s:
    s = s.replace('import java.nio.FloatBuffer\n', 'import java.io.File\nimport java.nio.FloatBuffer\n')
old = '''    private fun readModelAsset(assetPath: String): ByteArray {
        return try {
            context.assets.open(assetPath).use { it.readBytes() }
        } catch (t: Throwable) {
            throw OCRError.ModelNotFound(assetPath, t)
        }
    }
'''
new = '''    private fun readModelAsset(assetPath: String): ByteArray {
        return try {
            val file = File(assetPath)
            if (file.isAbsolute && file.isFile) file.readBytes()
            else context.assets.open(assetPath).use { it.readBytes() }
        } catch (t: Throwable) {
            throw OCRError.ModelNotFound(assetPath, t)
        }
    }
'''
if old not in s:
    raise SystemExit('ORTSessionManager marker missing')
s = s.replace(old, new, 1)
p.write_text(s)

p = sdk / 'model/ModelConfig.kt'
s = p.read_text()
if 'import java.io.File' not in s:
    s = s.replace('import com.paddle.ocr.util.YamlUtils\n', 'import com.paddle.ocr.util.YamlUtils\nimport java.io.File\n')
old = '''            val content = try {
                context.assets.open(assetPath).bufferedReader().use { it.readText() }
            } catch (t: Throwable) {
                throw OCRError.ConfigParseFailed(assetPath, t)
            }
'''
new = '''            val content = try {
                val file = File(assetPath)
                if (file.isAbsolute && file.isFile) file.readText()
                else context.assets.open(assetPath).bufferedReader().use { it.readText() }
            } catch (t: Throwable) {
                throw OCRError.ConfigParseFailed(assetPath, t)
            }
'''
if old not in s:
    raise SystemExit('ModelConfig marker missing')
s = s.replace(old, new, 1)
p.write_text(s)

# Runtime model manager. Model files live under app-private storage and can be independently downloaded/deleted.
(base / 'OcrModelManager.java').write_text(r'''package com.yagay.floatlens;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OcrModelManager {
    public static final int SMALL = 1;
    public static final int MEDIUM = 2;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-OCR-Models");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Set<Integer> DOWNLOADING = new HashSet<>();

    public interface Callback {
        void onProgress(String stage, int percent);
        void onSuccess();
        void onFailure(String message);
    }

    private record Spec(String name, String detUrl, String recUrl, String ymlUrl,
                        long detMin, long recMin, long estimatedTotal) {}

    private static Spec spec(int model) {
        if (model == MEDIUM) return new Spec(
                "PP-OCRv6 Medium",
                "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_det_onnx/resolve/main/inference.onnx?download=true",
                "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/main/inference.onnx?download=true",
                "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/main/inference.yml?download=true",
                60_000_000L, 74_000_000L, 139_000_000L);
        return new Spec(
                "PP-OCRv6 Small",
                "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx?download=true",
                "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx?download=true",
                "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.yml?download=true",
                9_000_000L, 20_000_000L, 32_000_000L);
    }

    public static File dir(Context c, int model) {
        return new File(c.getApplicationContext().getFilesDir(),
                "ocr_models/" + (model == MEDIUM ? "ppocrv6_medium" : "ppocrv6_small"));
    }
    public static File detFile(Context c, int model) { return new File(dir(c, model), "det/inference.onnx"); }
    public static File recFile(Context c, int model) { return new File(dir(c, model), "rec/inference.onnx"); }
    public static File ymlFile(Context c, int model) { return new File(dir(c, model), "rec/inference.yml"); }

    public static boolean isReady(Context c, int model) {
        Spec s = spec(model);
        File d = detFile(c, model), r = recFile(c, model), y = ymlFile(c, model);
        return d.isFile() && d.length() >= s.detMin()
                && r.isFile() && r.length() >= s.recMin()
                && y.isFile() && y.length() >= 4_000L;
    }

    public static boolean isDownloading(int model) {
        synchronized (DOWNLOADING) { return DOWNLOADING.contains(model); }
    }

    public static long installedBytes(Context c, int model) { return size(dir(c, model)); }
    public static long estimatedBytes(int model) { return spec(model).estimatedTotal(); }
    public static String displayName(int model) { return spec(model).name(); }

    public static void download(Context c, int model, Callback cb) {
        Context app = c.getApplicationContext();
        synchronized (DOWNLOADING) {
            if (DOWNLOADING.contains(model)) {
                fail(cb, "模型正在下载");
                return;
            }
            DOWNLOADING.add(model);
        }
        IO.execute(() -> {
            Spec sp = spec(model);
            try {
                File root = dir(app, model);
                root.mkdirs();
                long total = sp.estimatedTotal();
                long[] doneBase = {0L};
                downloadOne(sp.detUrl(), detFile(app, model), sp.detMin(), doneBase, total, "检测模型", cb);
                doneBase[0] += detFile(app, model).length();
                downloadOne(sp.recUrl(), recFile(app, model), sp.recMin(), doneBase, total, "识别模型", cb);
                doneBase[0] += recFile(app, model).length();
                downloadOne(sp.ymlUrl(), ymlFile(app, model), 4_000L, doneBase, total, "字符配置", cb);
                if (!isReady(app, model)) throw new IllegalStateException("下载完成但模型校验失败");
                DiagnosticLog.i(app, "OCR_MODEL", "download success model=" + model
                        + " bytes=" + installedBytes(app, model));
                MAIN.post(cb::onSuccess);
            } catch (Throwable t) {
                DiagnosticLog.i(app, "OCR_MODEL", "download failure model=" + model + " " + safe(t));
                fail(cb, safe(t));
            } finally {
                synchronized (DOWNLOADING) { DOWNLOADING.remove(model); }
            }
        });
    }

    private static void downloadOne(String url, File out, long minBytes, long[] base,
                                    long total, String stage, Callback cb) throws Exception {
        out.getParentFile().mkdirs();
        File part = new File(out.getAbsolutePath() + ".part");
        long existing = part.isFile() ? part.length() : 0L;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(180_000);
        conn.setRequestProperty("User-Agent", "FloatLens/PP-OCRv6");
        if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");
        int code = conn.getResponseCode();
        boolean append = existing > 0 && code == HttpURLConnection.HTTP_PARTIAL;
        if (code < 200 || code >= 300) throw new IllegalStateException(stage + " HTTP " + code);
        if (!append) existing = 0L;
        try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(part, append)) {
            byte[] buf = new byte[256 * 1024];
            long current = existing;
            int lastPercent = -1;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                fos.write(buf, 0, n);
                current += n;
                int percent = (int)Math.min(99, ((base[0] + current) * 100L) / Math.max(1L, total));
                if (percent != lastPercent) {
                    lastPercent = percent;
                    int p = percent;
                    MAIN.post(() -> cb.onProgress(stage, p));
                }
            }
            fos.getFD().sync();
        } finally { conn.disconnect(); }
        if (part.length() < minBytes) throw new IllegalStateException(stage + " 文件过小: " + part.length());
        if (out.exists() && !out.delete()) throw new IllegalStateException("无法替换旧模型");
        if (!part.renameTo(out)) throw new IllegalStateException("无法保存 " + stage);
    }

    public static void delete(Context c, int model) {
        PaddleOcrBridge.releaseModel(model);
        deleteRecursively(dir(c, model));
        DiagnosticLog.i(c, "OCR_MODEL", "deleted model=" + model);
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        try { f.delete(); } catch (Throwable ignored) {}
    }
    private static long size(File f) {
        if (f == null || !f.exists()) return 0L;
        if (f.isFile()) return f.length();
        long n = 0; File[] children = f.listFiles();
        if (children != null) for (File child : children) n += size(child);
        return n;
    }
    private static void fail(Callback cb, String msg) { MAIN.post(() -> cb.onFailure(msg)); }
    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
    private OcrModelManager() {}
}
''')

# Bridge: one cached engine per downloaded model, using absolute file paths.
(base / 'PaddleOcrBridge.kt').write_text(r'''package com.yagay.floatlens

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object PaddleOcrBridge {
    interface Callback {
        fun onSuccess(text: String, blocks: List<String>, totalMs: Long, lineCount: Int, averageConfidence: Float)
        fun onFailure(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initMutex = Mutex()
    private val runMutex = Mutex()
    private val engines = mutableMapOf<Int, PaddleOCR>()

    @JvmStatic
    fun recognize(context: Context, bitmap: Bitmap, model: Int, callback: Callback) {
        val app = context.applicationContext
        scope.launch {
            try {
                if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) throw IllegalArgumentException("invalid bitmap")
                if (!OcrModelManager.isReady(app, model)) throw IllegalStateException("model_not_downloaded")
                val ocr = getOrCreate(app, model)
                val result = runMutex.withLock { ocr.recognize(bitmap) }
                val blocks = result.results.mapNotNull { item -> item.text.trim().takeIf { it.isNotEmpty() } }
                val text = blocks.joinToString("\n").trim()
                val avg = if (result.results.isEmpty()) 0f else result.results.map { it.confidence }.average().toFloat()
                withContext(Dispatchers.Main) { callback.onSuccess(text, blocks, result.totalTimeMs, result.lineCount, avg) }
            } catch (t: Throwable) {
                val msg = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                withContext(Dispatchers.Main) { callback.onFailure(msg) }
            }
        }
    }

    @JvmStatic fun isLoaded(model: Int): Boolean = synchronized(engines) { engines.containsKey(model) }

    @JvmStatic
    fun releaseModel(model: Int) {
        scope.launch {
            initMutex.withLock {
                val old = synchronized(engines) { engines.remove(model) }
                try { old?.release() } catch (_: Throwable) { }
            }
        }
    }

    private suspend fun getOrCreate(context: Context, model: Int): PaddleOCR {
        synchronized(engines) { engines[model] }?.let { return it }
        return initMutex.withLock {
            synchronized(engines) { engines[model] }?.let { return@withLock it }
            if (!OpenCVUtils.init(context)) throw IllegalStateException("OpenCV 初始化失败")
            if (!OcrModelManager.isReady(context, model)) throw IllegalStateException("model_not_downloaded")
            val config = PaddleOCRConfig(
                detThresh = 0.20f,
                detBoxThresh = 0.45f,
                detUnclipRatio = 1.4f,
                recScoreThresh = 0.0f,
                recBatchSize = if (model == OcrModelManager.MEDIUM) 2 else 4,
            )
            val created = PaddleOCR.create(
                context = context,
                config = config,
                engineConfig = EngineConfig(numThreads = 4),
                detModelAssetPath = OcrModelManager.detFile(context, model).absolutePath,
                recModelAssetPath = OcrModelManager.recFile(context, model).absolutePath,
                recConfigAssetPath = OcrModelManager.ymlFile(context, model).absolutePath,
            )
            synchronized(engines) { engines[model] = created }
            created
        }
    }
}
''')

# Preference mapping: v2 key avoids ambiguous old value 2 (old ML Kit).
p = base / 'FloatSettings.java'
s = p.read_text()
s = s.replace('public static final String K_OCR_ENGINE = "ocr_engine_mode";', 'public static final String K_OCR_ENGINE = "ocr_engine_mode_v2";')
s = s.replace('return clamp(n.intValue(), 0, 2);', 'return clamp(n.intValue(), 0, 3);')
s = s.replace('return clamp(Integer.parseInt(v.trim()), 0, 2);', 'return clamp(Integer.parseInt(v.trim()), 0, 3);')
p.write_text(s)

# Settings UI: four modes + independent model manager controls.
p = base / 'SettingsActivity.java'
s = p.read_text()
if 'ocrModelControls(root);' not in s:
    s = s.replace('        ocrEngineSpinner(root);\n        ocrTypeSpinner(root);', '        ocrEngineSpinner(root);\n        ocrModelControls(root);\n        ocrTypeSpinner(root);')
start = s.index('    private void ocrEngineSpinner(LinearLayout r) {')
end = s.index('    private void ocrTypeSpinner(LinearLayout r) {', start)
replacement = r'''    private void ocrEngineSpinner(LinearLayout r) {
        TextView t = new TextView(this); t.setText("OCR 引擎 / ocr_engine_mode_v2"); r.addView(t);
        String[] labels = {
                "自动：Small 优先，低置信度升级 Medium，失败回退 ML Kit",
                "PP-OCRv6 Medium 高精度",
                "PP-OCRv6 Small 平衡",
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

    private void ocrModelControls(LinearLayout root) {
        TextView note = new TextView(this);
        note.setText("本地模型与 APK 分离，下载一次后可离线使用。Small 约 32 MB；Medium 约 139 MB。");
        note.setPadding(0, dp(8), 0, dp(6)); root.addView(note);
        addOcrModelRow(root, OcrModelManager.SMALL);
        addOcrModelRow(root, OcrModelManager.MEDIUM);
    }

    private void addOcrModelRow(LinearLayout root, int model) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
        TextView status = new TextView(this); row.addView(status);
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button download = new Button(this); download.setText("下载/更新");
        Button remove = new Button(this); remove.setText("删除");
        buttons.addView(download, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(remove, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(buttons); root.addView(row);
        Runnable refresh = () -> {
            boolean ready = OcrModelManager.isReady(this, model);
            long mb = OcrModelManager.installedBytes(this, model) / (1024 * 1024);
            status.setText(OcrModelManager.displayName(model) + ": " + (ready ? "已下载 " + mb + " MB" : "未下载"));
            remove.setEnabled(ready && !OcrModelManager.isDownloading(model));
            download.setEnabled(!OcrModelManager.isDownloading(model));
        };
        refresh.run();
        download.setOnClickListener(v -> {
            download.setEnabled(false); remove.setEnabled(false);
            OcrModelManager.download(this, model, new OcrModelManager.Callback() {
                public void onProgress(String stage, int percent) { status.setText(OcrModelManager.displayName(model) + ": " + stage + " " + percent + "%"); }
                public void onSuccess() { Toast.makeText(SettingsActivity.this, "模型下载完成", Toast.LENGTH_SHORT).show(); refresh.run(); }
                public void onFailure(String message) { Toast.makeText(SettingsActivity.this, "下载失败: " + message, Toast.LENGTH_LONG).show(); refresh.run(); }
            });
        });
        remove.setOnClickListener(v -> { OcrModelManager.delete(this, model); refresh.run(); });
    }

'''
s = s[:start] + replacement + s[end:]
p.write_text(s)

# OcrEngine selection. Keep existing ML Kit pipeline, replace only local-engine front-end.
p = base / 'OcrEngine.java'
s = p.read_text()
start = s.index('    private static void startSelectedEngine(Context app, FloatService service,')
end = s.index('    private static void startMlKitPipeline(Context app, FloatService service,', start)
replacement = r'''    private static void startSelectedEngine(Context app, FloatService service,
                                            Bitmap source, Rect anchor) {
        int mode = readOcrEngineModeSafely(app);
        boolean smallReady = OcrModelManager.isReady(app, OcrModelManager.SMALL);
        boolean mediumReady = OcrModelManager.isReady(app, OcrModelManager.MEDIUM);
        DiagnosticLog.i(app, "OCR_ENGINE", "mode=" + mode + " small=" + smallReady + " medium=" + mediumReady);
        if (mode == 3) { startMlKitPipeline(app, service, source, anchor, "manual_mlkit"); return; }
        if (mode == 1) { runPaddle(app, service, source, anchor, OcrModelManager.MEDIUM, false, null); return; }
        if (mode == 2) { runPaddle(app, service, source, anchor, OcrModelManager.SMALL, false, null); return; }

        if (smallReady) {
            runPaddle(app, service, source, anchor, OcrModelManager.SMALL, true, null);
        } else if (mediumReady) {
            runPaddle(app, service, source, anchor, OcrModelManager.MEDIUM, true, null);
        } else {
            DiagnosticLog.i(app, "OCR_ENGINE", "auto no local model -> ML Kit");
            Toast.makeText(app, "未下载 PP-OCRv6 模型，暂用 ML Kit；可在设置中下载", Toast.LENGTH_SHORT).show();
            startMlKitPipeline(app, service, source, anchor, "no_local_model");
        }
    }

    private static final class PaddleResult {
        final String text; final List<String> blocks; final float confidence;
        PaddleResult(String text, List<String> blocks, float confidence) {
            this.text = text == null ? "" : text.trim(); this.blocks = blocks == null ? List.of() : blocks; this.confidence = confidence;
        }
    }

    private static void runPaddle(Context app, FloatService service, Bitmap source, Rect anchor,
                                  int model, boolean auto, PaddleResult previous) {
        if (!OcrModelManager.isReady(app, model)) {
            if (auto) { startMlKitPipeline(app, service, source, anchor, "local_model_missing"); return; }
            if (service != null) service.onCircleFinished("ppocr_model_missing");
            Toast.makeText(app, "请先在设置中下载 " + OcrModelManager.displayName(model), Toast.LENGTH_LONG).show();
            return;
        }
        DiagnosticLog.i(app, "PPOCRV6", "launch model=" + model + " image=" + source.getWidth() + "x" + source.getHeight());
        PaddleOcrBridge.recognize(app, source, model, new PaddleOcrBridge.Callback() {
            @Override public void onSuccess(String text, List<String> blocks, long totalMs, int lineCount, float averageConfidence) {
                PaddleResult now = new PaddleResult(text, blocks, averageConfidence);
                DiagnosticLog.i(app, "PPOCRV6", "success model=" + model + " chars=" + now.text.length()
                        + " lines=" + lineCount + " avgConf=" + String.format(java.util.Locale.US, "%.3f", averageConfidence)
                        + " totalMs=" + totalMs);
                if (now.text.isBlank()) {
                    if (previous != null && !previous.text.isBlank()) { showPaddleResult(app, service, source, anchor, previous); return; }
                    if (auto) { startMlKitPipeline(app, service, source, anchor, "ppocr_empty"); return; }
                    if (service != null) service.onCircleFinished("ppocr_empty");
                    Toast.makeText(app, "未识别到文字", Toast.LENGTH_SHORT).show(); return;
                }
                if (auto && model == OcrModelManager.SMALL
                        && OcrModelManager.isReady(app, OcrModelManager.MEDIUM)
                        && shouldEscalate(now)) {
                    DiagnosticLog.i(app, "OCR_ENGINE", "Small low confidence -> Medium");
                    runPaddle(app, service, source, anchor, OcrModelManager.MEDIUM, true, now);
                    return;
                }
                showPaddleResult(app, service, source, anchor, chooseBetter(previous, now));
            }
            @Override public void onFailure(String message) {
                DiagnosticLog.i(app, "PPOCRV6", "failure model=" + model + " " + message);
                if (previous != null && !previous.text.isBlank()) { showPaddleResult(app, service, source, anchor, previous); return; }
                if (auto) startMlKitPipeline(app, service, source, anchor, "ppocr_failure:" + message);
                else {
                    if (service != null) service.onCircleFinished("ppocr_failure");
                    Toast.makeText(app, "PP-OCRv6 失败: " + message, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private static boolean shouldEscalate(PaddleResult r) {
        if (r == null || r.text.isBlank()) return true;
        if (r.confidence < 0.82f) return true;
        int meaningful = 0;
        for (int i=0;i<r.text.length();i++) if (Character.isLetterOrDigit(r.text.charAt(i)) || isCjk(r.text.charAt(i))) meaningful++;
        return meaningful < 6 || meaningful * 2 < r.text.length();
    }

    private static PaddleResult chooseBetter(PaddleResult a, PaddleResult b) {
        if (a == null || a.text.isBlank()) return b;
        if (b == null || b.text.isBlank()) return a;
        double sa = a.confidence * 1000.0 + Math.min(300, a.text.length());
        double sb = b.confidence * 1000.0 + Math.min(300, b.text.length());
        return sb >= sa ? b : a;
    }

    private static void showPaddleResult(Context app, FloatService service, Bitmap source, Rect anchor, PaddleResult r) {
        if (r == null || r.text.isBlank()) { if (service != null) service.onCircleFinished("ppocr_empty"); return; }
        if (service != null) service.onOcrResults(Math.max(1, r.blocks.size()));
        if (!ResultTextActivity.show(app, r.text, r.blocks, source, anchor)) ResultOverlay.show(app, r.text, r.blocks, source, anchor);
    }

'''
s = s[:start] + replacement + s[end:]
s = s.replace('return Math.max(0, Math.min(2, ((Number) raw).intValue()));', 'return Math.max(0, Math.min(3, ((Number) raw).intValue()));')
s = s.replace('return Math.max(0, Math.min(2, Integer.parseInt(((String) raw).trim())));', 'return Math.max(0, Math.min(3, Integer.parseInt(((String) raw).trim())));')
p.write_text(s)

print('external OCR models patch applied')
