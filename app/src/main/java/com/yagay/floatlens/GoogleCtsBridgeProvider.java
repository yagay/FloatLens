package com.yagay.floatlens;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One-shot authenticated pixel pipe from the hooked Google process into FloatLens.
 *
 * <p>The random FloatLens CTS session token is the capability. No bitmap is placed in an Intent,
 * so full-resolution screenshots do not hit Binder's transaction-size limit.</p>
 */
public final class GoogleCtsBridgeProvider extends ContentProvider {
    private static final long MAX_FRAME_BYTES = 64L * 1024L * 1024L;
    private static final ExecutorService IO = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "FloatLens-GoogleBridge-Rx");
        t.setDaemon(true);
        return t;
    });

    @Override public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context context = getContext();
        if (context == null || uri == null || mode == null || !mode.contains("w")) {
            throw new FileNotFoundException("invalid bridge request");
        }

        String caller = getCallingPackage();
        if (caller != null && !caller.isBlank()
                && !GoogleCtsContract.GOOGLE_PACKAGE.equals(caller)) {
            throw new FileNotFoundException("caller not allowed");
        }

        List<String> path = uri.getPathSegments();
        if (path.size() != 2 || !"frame".equals(path.get(0))) {
            throw new FileNotFoundException("unknown bridge path");
        }
        String token = path.get(1);
        if (!authorized(context, token)) {
            throw new FileNotFoundException("expired bridge session");
        }

        int width = parsePositive(uri.getQueryParameter(GoogleCtsContract.Q_WIDTH));
        int height = parsePositive(uri.getQueryParameter(GoogleCtsContract.Q_HEIGHT));
        int bytes = parsePositive(uri.getQueryParameter(GoogleCtsContract.Q_BYTES));
        long expected = (long) width * (long) height * 4L;
        if (width <= 0 || height <= 0 || expected <= 0L || expected > MAX_FRAME_BYTES
                || bytes != expected) {
            throw new FileNotFoundException("invalid frame geometry");
        }

        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (Throwable t) {
            throw new FileNotFoundException("cannot create frame pipe: " + t.getClass().getSimpleName());
        }

        Context app = context.getApplicationContext();
        IO.execute(() -> readFrame(app, token, width, height, bytes, pipe[0]));
        return pipe[1];
    }

    private static void readFrame(Context context, String token, int width, int height,
                                  int bytes, ParcelFileDescriptor readSide) {
        Bitmap bitmap = null;
        try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(readSide)) {
            ByteBuffer pixels = ByteBuffer.allocateDirect(bytes);
            FileChannel channel = in.getChannel();
            while (pixels.hasRemaining()) {
                int n = channel.read(pixels);
                if (n < 0) break;
            }
            if (pixels.hasRemaining()) {
                DiagnosticLog.i(context, "GOOGLE_BRIDGE",
                        "frame truncated session=" + shortToken(token)
                                + " remaining=" + pixels.remaining());
                return;
            }
            pixels.flip();
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(pixels);
            GoogleCtsBridgeController.onFrame(context, token, bitmap);
            bitmap = null; // ownership transferred
            DiagnosticLog.i(context, "GOOGLE_BRIDGE",
                    "frame received session=" + shortToken(token)
                            + " size=" + width + "x" + height);
        } catch (Throwable t) {
            DiagnosticLog.i(context, "GOOGLE_BRIDGE",
                    "frame receive failed session=" + shortToken(token)
                            + " error=" + t.getClass().getSimpleName()
                            + ":" + String.valueOf(t.getMessage()));
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                try { bitmap.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean authorized(Context context, String suppliedToken) {
        SharedPreferences prefs = context.getSharedPreferences(
                FloatSettings.PREF, Context.MODE_PRIVATE);
        String expected = prefs.getString(FloatSettings.K_GOOGLE_CTS_ACTIVE_SESSION, "");
        long validUntil = prefs.getLong(FloatSettings.K_GOOGLE_CTS_ACTIVE_UNTIL, 0L);
        return GoogleCtsContract.isAuthorizedTrace(
                expected, validUntil, suppliedToken, SystemClock.elapsedRealtime());
    }

    private static int parsePositive(String raw) {
        if (raw == null) return -1;
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String shortToken(String token) {
        if (token == null || token.isBlank()) return "none";
        return token.substring(0, Math.min(8, token.length()));
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        return null;
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("bridge is write-pipe only");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
