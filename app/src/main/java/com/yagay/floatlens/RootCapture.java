package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Root screencap helper. Never blocks the main thread and never waits forever for su. */
public final class RootCapture {
    private static final long ROOT_CAPTURE_TIMEOUT_SECONDS = 8L;
    private static final int MAX_CAPTURE_BYTES = 64 * 1024 * 1024;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-root-capture");
        t.setDaemon(true);
        return t;
    });

    static void captureAsync(Context c, Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            Process p = null;
            Thread reader = null;
            try {
                p = new ProcessBuilder("su", "-c", "screencap -p 2>/dev/null").start();
                Process process = p;
                ByteArrayOutputStream png = new ByteArrayOutputStream(2 * 1024 * 1024);
                AtomicReference<Throwable> readFailure = new AtomicReference<>();

                reader = new Thread(() -> {
                    try (InputStream in = process.getInputStream()) {
                        byte[] buf = new byte[256 * 1024];
                        int total = 0;
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            if (n == 0) continue;
                            total += n;
                            if (total > MAX_CAPTURE_BYTES) {
                                throw new IllegalStateException("Root 截图数据异常过大");
                            }
                            png.write(buf, 0, n);
                        }
                    } catch (Throwable t) {
                        readFailure.set(t);
                    }
                }, "FloatLens-root-capture-stream");
                reader.setDaemon(true);
                reader.start();

                if (!p.waitFor(ROOT_CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    p.destroy();
                    if (p.isAlive()) p.destroyForcibly();
                    throw new IllegalStateException("Root 截图超时");
                }

                reader.join(1_500L);
                if (reader.isAlive()) {
                    reader.interrupt();
                    throw new IllegalStateException("Root 截图读取超时");
                }

                Throwable streamError = readFailure.get();
                if (streamError != null) throw new IllegalStateException("Root 截图读取失败", streamError);

                int code = p.exitValue();
                if (code != 0) throw new IllegalStateException("su screencap exit=" + code);

                byte[] data = png.toByteArray();
                if (data.length < 1024) throw new IllegalStateException("Root 截图数据为空");
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) throw new IllegalStateException("无法解码 Root 截图");
                app.getMainExecutor().execute(() -> ok.accept(bitmap));
            } catch (Throwable t) {
                if (p != null && p.isAlive()) {
                    try { p.destroyForcibly(); } catch (Throwable ignored) { }
                }
                if (reader != null && reader.isAlive()) {
                    try { reader.interrupt(); } catch (Throwable ignored) { }
                }
                app.getMainExecutor().execute(() -> fail.accept(t));
            }
        });
    }

    private RootCapture() {}
}
