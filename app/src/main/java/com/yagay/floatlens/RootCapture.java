package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
            try {
                RootCommandExecutor.Result command = RootCommandExecutor.runBinary(
                        "screencap -p 2>/dev/null", ROOT_CAPTURE_TIMEOUT_SECONDS, MAX_CAPTURE_BYTES);
                if (!command.success()) {
                    throw new IllegalStateException(
                            command.failureMessage("Root 截图超时"), command.error);
                }
                byte[] data = command.stdout;
                if (data.length < 1024) throw new IllegalStateException("Root 截图数据为空");
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) throw new IllegalStateException("无法解码 Root 截图");
                app.getMainExecutor().execute(() -> ok.accept(bitmap));
            } catch (Throwable t) {
                app.getMainExecutor().execute(() -> fail.accept(t));
            }
        });
    }

    private RootCapture() {}
}
