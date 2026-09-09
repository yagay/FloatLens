package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Root screencap helper. Never blocks the main thread. */
public final class RootCapture {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-root-capture");
        t.setDaemon(true);
        return t;
    });

    static void captureAsync(Context c, Consumer<Bitmap> ok, Consumer<Throwable> fail) {
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            File f = new File(app.getCacheDir(), "root_capture_" + System.nanoTime() + ".png");
            try {
                String path = shellQuote(f.getAbsolutePath());
                Process p = new ProcessBuilder("su", "-c", "screencap -p " + path).redirectErrorStream(true).start();
                int code = p.waitFor();
                if (code != 0) throw new IllegalStateException("su screencap exit=" + code);
                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (b == null) throw new IllegalStateException("无法读取 Root 截图");
                app.getMainExecutor().execute(() -> ok.accept(b));
            } catch (Throwable t) {
                app.getMainExecutor().execute(() -> fail.accept(t));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        });
    }

    private static String shellQuote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private RootCapture() {}
}
