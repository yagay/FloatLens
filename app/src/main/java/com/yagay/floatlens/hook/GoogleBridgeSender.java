package com.yagay.floatlens.hook;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.yagay.floatlens.GoogleCtsContract;

import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import io.github.libxposed.api.XposedModule;

/** Owns all Google-process -> FloatLens bridge transport and frame backpressure. */
final class GoogleBridgeSender {
    private static final String TAG = "FloatLens-GoogleCTS";

    private final XposedModule module;
    private final Supplier<Context> contextSupplier;
    private final Supplier<String> tokenSupplier;
    private final BooleanSupplier active;
    private final BooleanSupplier diagnosticsEnabled;
    private final ThreadLocal<Boolean> traceDispatching = new ThreadLocal<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-GoogleBridge-Tx");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean frameQueued;

    GoogleBridgeSender(XposedModule module,
                       Supplier<Context> contextSupplier,
                       Supplier<String> tokenSupplier,
                       BooleanSupplier active,
                       BooleanSupplier diagnosticsEnabled) {
        this.module = module;
        this.contextSupplier = contextSupplier;
        this.tokenSupplier = tokenSupplier;
        this.active = active;
        this.diagnosticsEnabled = diagnosticsEnabled;
    }

    boolean frameQueued() { return frameQueued; }

    void reset() {
        frameQueued = false;
        traceDispatching.remove();
    }

    void sendTrace(String line) {
        String token = token();
        if (!diagnosticsEnabled.getAsBoolean()
                || line == null || line.isBlank() || token.isBlank()) return;
        if (Boolean.TRUE.equals(traceDispatching.get())) return;
        traceDispatching.set(Boolean.TRUE);
        try {
            Context context = contextSupplier.get();
            if (context == null) return;
            Intent intent = new Intent(GoogleCtsContract.ACTION_TRACE)
                    .setClassName("com.yagay.floatlens", GoogleCtsContract.TRACE_RECEIVER_CLASS)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(GoogleCtsContract.EXTRA_TRACE_SESSION, token)
                    .putExtra(GoogleCtsContract.EXTRA_TRACE_LINE,
                            line.length() > 8000 ? line.substring(0, 8000) : line);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "CTS trace broadcast failed", t);
        } finally {
            traceDispatching.remove();
        }
    }

    void sendEvent(String event, String text, String detail, Rect bounds) {
        String token = token();
        if (token.isBlank() || event == null || event.isBlank()) return;
        try {
            Context context = contextSupplier.get();
            if (context == null) return;
            Intent intent = new Intent(GoogleCtsContract.ACTION_BRIDGE)
                    .setClassName("com.yagay.floatlens", GoogleCtsContract.BRIDGE_RECEIVER_CLASS)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(GoogleCtsContract.EXTRA_BRIDGE_SESSION, token)
                    .putExtra(GoogleCtsContract.EXTRA_BRIDGE_EVENT, event);
            if (text != null && !text.isBlank()) {
                intent.putExtra(GoogleCtsContract.EXTRA_BRIDGE_TEXT,
                        text.length() > 20_000 ? text.substring(0, 20_000) : text);
            }
            if (detail != null && !detail.isBlank()) {
                intent.putExtra(GoogleCtsContract.EXTRA_BRIDGE_DETAIL,
                        detail.length() > 8_000 ? detail.substring(0, 8_000) : detail);
            }
            if (bounds != null && !bounds.isEmpty()) {
                intent.putExtra(GoogleCtsContract.EXTRA_LEFT, bounds.left);
                intent.putExtra(GoogleCtsContract.EXTRA_TOP, bounds.top);
                intent.putExtra(GoogleCtsContract.EXTRA_RIGHT, bounds.right);
                intent.putExtra(GoogleCtsContract.EXTRA_BOTTOM, bounds.bottom);
            }
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "CTS bridge event failed event=" + event, t);
        }
    }

    synchronized void sendFrame(Bitmap bitmap) {
        if (!active.getAsBoolean() || frameQueued || bitmap == null || bitmap.isRecycled()) return;
        String token = token();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        ByteBuffer pixels = snapshotPixels(bitmap);
        if (pixels == null) return;
        frameQueued = true;
        io.execute(() -> writeFrame(token, width, height, pixels));
    }

    private ByteBuffer snapshotPixels(Bitmap bitmap) {
        Bitmap normalized = null;
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            long byteCountLong = (long) width * (long) height * 4L;
            if (width <= 0 || height <= 0 || byteCountLong <= 0L
                    || byteCountLong > 64L * 1024L * 1024L) return null;
            int bytes = (int) byteCountLong;
            Bitmap source = bitmap;
            if (bitmap.getConfig() != Bitmap.Config.ARGB_8888
                    || bitmap.getRowBytes() != width * 4) {
                normalized = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (normalized == null) return null;
                source = normalized;
            }
            ByteBuffer pixels = ByteBuffer.allocateDirect(bytes);
            source.copyPixelsToBuffer(pixels);
            pixels.flip();
            return pixels.remaining() == bytes ? pixels : null;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google bridge pixel snapshot failed", t);
            return null;
        } finally {
            if (normalized != null && !normalized.isRecycled()) {
                try { normalized.recycle(); } catch (Throwable ignored) { }
            }
        }
    }

    private void writeFrame(String token, int width, int height, ByteBuffer pixels) {
        int bytes = pixels == null ? 0 : pixels.remaining();
        try {
            Context context = contextSupplier.get();
            if (context == null || bytes <= 0) {
                throw new IllegalStateException("context/pixels unavailable");
            }
            Uri uri = GoogleCtsContract.bridgeFrameUri(token, width, height, bytes);
            ParcelFileDescriptor descriptor =
                    context.getContentResolver().openFileDescriptor(uri, "w");
            if (descriptor == null) throw new IllegalStateException("bridge pipe unavailable");
            try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                FileChannel channel = out.getChannel();
                while (pixels.hasRemaining()) channel.write(pixels);
            }
            module.log(Log.INFO, TAG,
                    "Google bridge frame sent session=" + shortToken(token)
                            + " size=" + width + "x" + height + " bytes=" + bytes);
        } catch (Throwable t) {
            if (token != null && token.equals(token())) frameQueued = false;
            module.log(Log.WARN, TAG,
                    "Google bridge frame send failed session=" + shortToken(token), t);
        }
    }

    private String token() {
        String value = tokenSupplier.get();
        return value == null ? "" : value;
    }

    private static String shortToken(String token) {
        if (token == null || token.isBlank()) return "none";
        return token.substring(0, Math.min(8, token.length()));
    }
}
