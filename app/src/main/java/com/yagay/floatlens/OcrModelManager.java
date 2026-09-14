package com.yagay.floatlens;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloaded PP-OCR model files plus runtime invalidation when those files change. */
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
    private static final String MANIFEST_FILE = "integrity.properties";
    private static final String MANIFEST_VERSION = "1";

    public interface Callback {
        void onProgress(String stage, int percent);
        void onSuccess();
        void onFailure(String message);
    }

    /** Plain class instead of a Java record to avoid record-runtime/desugaring edge cases on Android 12/13. */
    private static final class Spec {
        final String name;
        final String detUrl;
        final String recUrl;
        final String ymlUrl;
        final long detMin;
        final long recMin;
        final long estimatedTotal;

        Spec(String name, String detUrl, String recUrl, String ymlUrl,
             long detMin, long recMin, long estimatedTotal) {
            this.name = name;
            this.detUrl = detUrl;
            this.recUrl = recUrl;
            this.ymlUrl = ymlUrl;
            this.detMin = detMin;
            this.recMin = recMin;
            this.estimatedTotal = estimatedTotal;
        }
    }

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
    private static File manifestFile(Context c, int model) { return new File(dir(c, model), MANIFEST_FILE); }

    /** Fast readiness check used by UI/strategy selection. It intentionally does not hash large files. */
    public static boolean isReady(Context c, int model) {
        try {
            Spec s = spec(model);
            File d = detFile(c, model), r = recFile(c, model), y = ymlFile(c, model);
            return d.isFile() && d.length() >= s.detMin
                    && r.isFile() && r.length() >= s.recMin
                    && y.isFile() && y.length() >= 4_000L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Expensive integrity verification. Call only immediately before cold-loading a model, never from
     * settings rendering. A successful download writes SHA-256 hashes atomically. Existing installs
     * from older FloatLens versions get a one-time local baseline manifest on first verified load.
     */
    public static boolean verifyIntegrity(Context c, int model) {
        Context app = c.getApplicationContext();
        if (!isReady(app, model) || isDownloading(model)) return false;
        try {
            File manifest = manifestFile(app, model);
            if (!manifest.isFile()) {
                writeIntegrityManifest(app, model);
                DiagnosticLog.i(app, "OCR_MODEL", "integrity baseline created model=" + model
                        + " legacy=true");
            }

            Properties p = loadProperties(manifest);
            if (!MANIFEST_VERSION.equals(p.getProperty("version"))) return false;
            boolean ok = verifyFile(detFile(app, model), p, "det")
                    && verifyFile(recFile(app, model), p, "rec")
                    && verifyFile(ymlFile(app, model), p, "yml");
            DiagnosticLog.i(app, "OCR_MODEL", "integrity model=" + model + " ok=" + ok);
            return ok;
        } catch (Throwable t) {
            DiagnosticLog.i(app, "OCR_MODEL", "integrity failure model=" + model + " " + safe(t));
            return false;
        }
    }

    public static boolean isDownloading(int model) {
        synchronized (DOWNLOADING) { return DOWNLOADING.contains(model); }
    }

    public static long installedBytes(Context c, int model) {
        try { return size(dir(c, model)); } catch (Throwable ignored) { return 0L; }
    }
    public static long estimatedBytes(int model) { return spec(model).estimatedTotal; }
    public static String displayName(int model) { return spec(model).name; }

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
                if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("无法创建模型目录");
                File oldManifest = manifestFile(app, model);
                if (oldManifest.exists() && !oldManifest.delete()) {
                    throw new IllegalStateException("无法更新模型完整性清单");
                }

                long total = sp.estimatedTotal;
                long[] doneBase = {0L};
                downloadOne(sp.detUrl, detFile(app, model), sp.detMin, doneBase, total, "检测模型", cb);
                doneBase[0] += detFile(app, model).length();
                downloadOne(sp.recUrl, recFile(app, model), sp.recMin, doneBase, total, "识别模型", cb);
                doneBase[0] += recFile(app, model).length();
                downloadOne(sp.ymlUrl, ymlFile(app, model), 4_000L, doneBase, total, "字符配置", cb);
                if (!isReady(app, model)) throw new IllegalStateException("下载完成但模型大小校验失败");
                writeIntegrityManifest(app, model);
                if (!verifyIntegrity(app, model)) throw new IllegalStateException("下载完成但 SHA-256 校验失败");
                // A previous engine may still map the old model files. Invalidate it after all new
                // files have been atomically moved into place so the next OCR run reloads them.
                PaddleOcrBridge.releaseModel(model);
                DiagnosticLog.i(app, "OCR_MODEL", "download success model=" + model
                        + " bytes=" + installedBytes(app, model) + " integrity=sha256 runtimeReload=true");
                if (cb != null) MAIN.post(cb::onSuccess);
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
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("无法创建 " + stage + " 目录");
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
                    if (cb != null) MAIN.post(() -> cb.onProgress(stage, p));
                }
            }
            fos.getFD().sync();
        } finally { conn.disconnect(); }
        if (part.length() < minBytes) throw new IllegalStateException(stage + " 文件过小: " + part.length());
        if (out.exists() && !out.delete()) throw new IllegalStateException("无法替换旧模型");
        if (!part.renameTo(out)) throw new IllegalStateException("无法保存 " + stage);
    }

    private static void writeIntegrityManifest(Context c, int model) throws Exception {
        File root = dir(c, model);
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("无法创建模型目录");
        Properties p = new Properties();
        p.setProperty("version", MANIFEST_VERSION);
        putFileProperties(p, "det", detFile(c, model));
        putFileProperties(p, "rec", recFile(c, model));
        putFileProperties(p, "yml", ymlFile(c, model));

        File out = manifestFile(c, model);
        File part = new File(out.getAbsolutePath() + ".part");
        try (FileOutputStream fos = new FileOutputStream(part, false)) {
            p.store(fos, "FloatLens OCR model integrity");
            fos.getFD().sync();
        }
        if (out.exists() && !out.delete()) throw new IllegalStateException("无法替换完整性清单");
        if (!part.renameTo(out)) throw new IllegalStateException("无法保存完整性清单");
    }

    private static void putFileProperties(Properties p, String key, File file) throws Exception {
        if (file == null || !file.isFile()) throw new IllegalStateException(key + " 模型文件不存在");
        p.setProperty(key + ".length", Long.toString(file.length()));
        p.setProperty(key + ".sha256", sha256(file));
    }

    private static boolean verifyFile(File file, Properties p, String key) throws Exception {
        if (file == null || !file.isFile()) return false;
        long expectedLength = Long.parseLong(p.getProperty(key + ".length", "-1"));
        String expectedHash = p.getProperty(key + ".sha256", "");
        if (file.length() != expectedLength || expectedHash.length() != 64) return false;
        return expectedHash.equalsIgnoreCase(sha256(file));
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("model hash interrupted");
                if (n > 0) md.update(buf, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : md.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }

    private static Properties loadProperties(File file) throws Exception {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(file)) { p.load(in); }
        return p;
    }

    public static void delete(Context c, int model) {
        Context app = c.getApplicationContext();
        try {
            // Release/make-stale the runtime as well as deleting its backing files. releaseModel()
            // is serialized with inference, so native ORT sessions are never closed mid-run.
            PaddleOcrBridge.releaseModel(model);
            deleteRecursively(dir(app, model));
            DiagnosticLog.i(app, "OCR_MODEL", "deleted model=" + model + " runtimeRelease=true");
        } catch (Throwable t) {
            DiagnosticLog.i(app, "OCR_MODEL", "delete failure model=" + model + " " + safe(t));
        }
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
    private static void fail(Callback cb, String msg) {
        if (cb != null) MAIN.post(() -> cb.onFailure(msg));
    }
    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
    private OcrModelManager() {}
}
