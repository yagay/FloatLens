package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * App-side owner of a FloatLens-triggered Google selection session.
 *
 * <p>Google supplies the pixels and selection metadata; FloatLens owns the resulting UI. The
 * controller deliberately waits briefly for the full frame before opening ResultActivity so the
 * text event cannot outrun the pixel pipe.</p>
 */
final class GoogleCtsBridgeController {
    private static final long FRAME_WAIT_MS = 900L;
    private static final long STATE_TTL_MS = 150_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private static final class State {
        Bitmap frame;
        String text = "";
        Rect bounds;
        String detail = "";
        boolean committed;
        boolean delivered;
    }

    static void onFrame(Context context, String token, Bitmap frame) {
        if (context == null || token == null || token.isBlank() || frame == null) {
            recycle(frame);
            return;
        }
        State state = state(token);
        synchronized (state) {
            if (state.delivered) {
                recycle(frame);
                return;
            }
            recycle(state.frame);
            state.frame = frame;
        }
        DiagnosticLog.i(context, "GOOGLE_BRIDGE",
                "frame ready session=" + shortToken(token)
                        + " size=" + frame.getWidth() + "x" + frame.getHeight());
        scheduleCleanup(context.getApplicationContext(), token, state);
        MAIN.post(() -> tryDeliver(context.getApplicationContext(), token, state, false));
    }

    static void onSelection(Context context, String token, String text, Rect bounds, String detail) {
        if (context == null || token == null || token.isBlank()) return;
        State state = state(token);
        synchronized (state) {
            if (state.delivered) return;
            state.text = text == null ? "" : text.trim();
            state.bounds = bounds == null ? null : new Rect(bounds);
            state.detail = detail == null ? "" : detail;
        }
        DiagnosticLog.i(context, "GOOGLE_BRIDGE",
                "selection session=" + shortToken(token)
                        + " textLen=" + (text == null ? 0 : text.length())
                        + " bounds=" + String.valueOf(bounds));
        scheduleCleanup(context.getApplicationContext(), token, state);
    }

    static void onCommit(Context context, String token, String detail) {
        if (context == null || token == null || token.isBlank()) return;
        Context app = context.getApplicationContext();
        State state = state(token);
        synchronized (state) {
            if (state.delivered) return;
            state.committed = true;
            if (detail != null && !detail.isBlank()) state.detail = detail;
        }
        DiagnosticLog.i(app, "GOOGLE_BRIDGE",
                "commit session=" + shortToken(token));
        scheduleCleanup(app, token, state);
        MAIN.post(() -> tryDeliver(app, token, state, false));
        MAIN.postDelayed(() -> tryDeliver(app, token, state, true), FRAME_WAIT_MS);
    }

    static void onQueryResult(Context context, String token, String text, String detail) {
        if (context == null || token == null || token.isBlank()) return;
        State state = state(token);
        synchronized (state) {
            if (state.delivered) return;
            if ((state.text == null || state.text.isBlank()) && text != null && !text.isBlank()) {
                state.text = text.trim();
            }
            if (detail != null && !detail.isBlank()) state.detail = detail;
            state.committed = true;
        }
        Context app = context.getApplicationContext();
        MAIN.post(() -> tryDeliver(app, token, state, false));
        MAIN.postDelayed(() -> tryDeliver(app, token, state, true), FRAME_WAIT_MS);
    }

    static void onEnd(Context context, String token, String detail) {
        if (context == null || token == null || token.isBlank()) return;
        Context app = context.getApplicationContext();
        State state = STATES.remove(token);
        if (state != null) {
            synchronized (state) {
                if (detail != null && !detail.isBlank()) state.detail = detail;
                recycle(state.frame);
                state.frame = null;
                state.delivered = true;
            }
        }
        new FloatSettings(app).clearGoogleCtsSession();
        FloatService service = FloatService.get();
        if (service != null) service.onCircleFinished("google_bridge_end");
        DiagnosticLog.i(app, "GOOGLE_BRIDGE",
                "ended session=" + shortToken(token)
                        + " detail=" + trim(detail, 600));
    }

    private static void tryDeliver(Context app, String token, State state, boolean force) {
        Bitmap frame;
        String text;
        Rect bounds;
        String detail;
        synchronized (state) {
            if (state.delivered || !state.committed) return;
            if (!force && state.frame == null) return;
            if (force && state.frame == null && (state.text == null || state.text.isBlank())) {
                DiagnosticLog.i(app, "GOOGLE_BRIDGE",
                        "commit has no displayable payload session=" + shortToken(token)
                                + " detail=" + trim(state.detail, 600));
                return;
            }
            state.delivered = true;
            frame = state.frame;
            state.frame = null;
            text = state.text == null ? "" : state.text.trim();
            bounds = state.bounds == null ? null : new Rect(state.bounds);
            detail = state.detail == null ? "" : state.detail;
        }
        STATES.remove(token, state);

        Bitmap display = frame;
        Rect normalized = normalize(bounds, frame);
        if (frame != null && normalized != null
                && (normalized.width() < frame.getWidth() || normalized.height() < frame.getHeight())) {
            Bitmap cropped = crop(frame, normalized);
            if (cropped != null) {
                recycle(frame);
                display = cropped;
            }
        }

        ResultSession session;
        if (!text.isBlank()) {
            session = ResultSession.viewText(text, display, normalized);
        } else if (display != null) {
            session = ResultSession.screenshot(display, normalized);
        } else {
            DiagnosticLog.i(app, "GOOGLE_BRIDGE",
                    "nothing to show session=" + shortToken(token)
                            + " detail=" + trim(detail, 600));
            new FloatSettings(app).clearGoogleCtsSession();
            return;
        }

        boolean shown = ResultController.show(app, session);
        if (!shown) {
            try { session.close(); } catch (Throwable ignored) {}
        }
        FloatService service = FloatService.get();
        if (service != null) service.onCircleFinished("google_bridge_delivered");
        new FloatSettings(app).clearGoogleCtsSession();
        DiagnosticLog.i(app, "GOOGLE_BRIDGE",
                "delivered session=" + shortToken(token)
                        + " shown=" + shown
                        + " textLen=" + text.length()
                        + " bounds=" + String.valueOf(normalized));
    }

    private static State state(String token) {
        return STATES.computeIfAbsent(token, ignored -> new State());
    }

    private static void scheduleCleanup(Context app, String token, State state) {
        MAIN.postDelayed(() -> {
            if (!STATES.remove(token, state)) return;
            synchronized (state) {
                recycle(state.frame);
                state.frame = null;
                state.delivered = true;
            }
            DiagnosticLog.i(app, "GOOGLE_BRIDGE",
                    "state expired session=" + shortToken(token));
        }, STATE_TTL_MS);
    }

    private static Rect normalize(Rect candidate, Bitmap frame) {
        if (candidate == null || frame == null) return candidate == null ? null : new Rect(candidate);
        Rect out = new Rect(candidate);
        if (!out.intersect(0, 0, frame.getWidth(), frame.getHeight())) return null;
        return out.width() > 0 && out.height() > 0 ? out : null;
    }

    private static Bitmap crop(Bitmap source, Rect src) {
        if (source == null || src == null || src.width() <= 0 || src.height() <= 0) return null;
        try {
            Bitmap out = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawBitmap(source, src, new Rect(0, 0, out.getWidth(), out.getHeight()), null);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        try { bitmap.recycle(); } catch (Throwable ignored) {}
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String shortToken(String token) {
        if (token == null || token.isBlank()) return "none";
        return token.substring(0, Math.min(8, token.length()));
    }

    private GoogleCtsBridgeController() {}
}
