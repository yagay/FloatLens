package com.yagay.floatlens;

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
