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
    private static final long MENU_UPDATE_DELAY_MS = 90L;
    /** Region confirm appears only after Google's rectangle stops changing for this long. */
    private static final long REGION_CONFIRM_STABLE_MS = 360L;
    /** Give the Google hook a brief chance to consume the remote confirm before local fallback. */
    private static final long REGION_REMOTE_GRACE_MS = 220L;
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
        boolean textMenuShown;
        boolean regionPending;
        boolean regionConfirmRequested;
        int selectionRevision;
        Runnable regionConfirmShowTask;
        Runnable regionFallbackTask;
        Runnable cleanupTask;
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
        Context app = context.getApplicationContext();
        State state = state(token);
        final int revision;
        final String selectedText;
        final Rect selectedBounds;
        synchronized (state) {
            if (state.delivered) return;
            state.regionPending = false;
            state.regionConfirmRequested = false;
            cancelRegionConfirmShowLocked(state);
            cancelRegionFallbackLocked(state);
            state.text = text == null ? "" : text.trim();
            if (bounds != null && !bounds.isEmpty()) {
                state.bounds = new Rect(bounds);
            }
            state.detail = detail == null ? "" : detail;
            revision = ++state.selectionRevision;
            selectedText = state.text;
            selectedBounds = state.bounds == null ? null : new Rect(state.bounds);
        }
        GoogleRegionConfirmOverlay.dismiss(token, "text_selection");
        WorkflowSessionManager.Session workflow = WorkflowSessionManager.current();
        if (workflow != null && token.equals(workflow.externalKey())) {
            WorkflowSessionManager.transition(app, workflow,
                    WorkflowSessionManager.Phase.SELECTING, "google_selection");
        }
        DiagnosticLog.i(app, "GOOGLE_BRIDGE",
                "selection session=" + shortToken(token)
                        + " textLen=" + selectedText.length()
                        + " bounds=" + String.valueOf(selectedBounds));
        // v177 deliberately does not renew any system_server component-block lease.
        // Google UI suppression is scoped inside the marked Google App process so native CTS
        // remains independently usable immediately after a FloatLens session.
        scheduleCleanup(app, token, state);

        // Google Circle has already done OCR/selection geometry at this point. Reuse the same
        // FloatLens text menu used by result-dialog selections instead of waiting for Google's
        // Lens Result Panel. While the user drags Google's native selection handles, y(dscl)
        // can fire rapidly; debounce menu reconstruction so the gesture remains smooth.
        if (!selectedText.isBlank()) {
            MAIN.postDelayed(() -> {
                synchronized (state) {
                    if (state.delivered || state.selectionRevision != revision) return;
                    state.textMenuShown = true;
                }
                FloatActionMenu.showTextAt(app, selectedText, null, selectedBounds);
                DiagnosticLog.i(app, "GOOGLE_TEXT_MENU",
                        "show session=" + shortToken(token)
                                + " revision=" + revision
                                + " textLen=" + selectedText.length()
                                + " bounds=" + String.valueOf(selectedBounds)
                                + " debouncedMs=" + MENU_UPDATE_DELAY_MS);
            }, MENU_UPDATE_DELAY_MS);
        } else {
            MAIN.post(() -> {
                synchronized (state) {
                    if (state.delivered || state.selectionRevision != revision) return;
                    state.textMenuShown = false;
                }
                FloatActionMenu.dismiss();
                DiagnosticLog.i(app, "GOOGLE_TEXT_MENU",
                        "dismiss empty selection session=" + shortToken(token));
            });
        }
    }

    static void onRegionSelection(
            Context context, String token, Rect bounds, String detail) {
        if (context == null || token == null || token.isBlank()
                || bounds == null || bounds.isEmpty()) return;

        Context app = context.getApplicationContext();
        State state = state(token);
        final int revision;
        final Rect selectedBounds;
        synchronized (state) {
            if (state.delivered) return;
            state.text = "";
            state.bounds = new Rect(bounds);
            state.detail = detail == null ? "" : detail;
            state.regionPending = true;
            state.regionConfirmRequested = false;
            state.textMenuShown = false;
            revision = ++state.selectionRevision;
            selectedBounds = new Rect(state.bounds);
            cancelRegionConfirmShowLocked(state);
            cancelRegionFallbackLocked(state);
        }

        // Any new region geometry means the user is still drawing/refining. Hide immediately;
        // only re-show after the rectangle has stayed unchanged for REGION_CONFIRM_STABLE_MS.
        GoogleRegionConfirmOverlay.dismiss(token, "region_adjusting");
        FloatActionMenu.dismiss();

        WorkflowSessionManager.Session workflow = WorkflowSessionManager.current();
        if (workflow != null && token.equals(workflow.externalKey())) {
            WorkflowSessionManager.transition(app, workflow,
                    WorkflowSessionManager.Phase.SELECTING, "google_region_selection");
        }
        scheduleCleanup(app, token, state);
        scheduleStableRegionConfirm(app, token, state, revision, selectedBounds);

        DiagnosticLog.i(app, "GOOGLE_REGION",
                "selection session=" + shortToken(token)
                        + " revision=" + revision
                        + " bounds=" + selectedBounds
                        + " confirmVisible=false state=adjusting");
    }

    private static void scheduleStableRegionConfirm(
            Context app, String token, State state, int revision, Rect selectedBounds) {
        final Runnable[] holder = new Runnable[1];
        holder[0] = () -> {
            synchronized (state) {
                if (state.regionConfirmShowTask != holder[0]) return;
                state.regionConfirmShowTask = null;
                if (state.delivered || !state.regionPending
                        || state.regionConfirmRequested
                        || state.selectionRevision != revision) {
                    return;
                }
            }

            GoogleRegionConfirmOverlay.show(
                    app, token, selectedBounds,
                    () -> confirmRegion(app, token));
            DiagnosticLog.i(app, "GOOGLE_REGION",
                    "confirm stable-show session=" + shortToken(token)
                            + " revision=" + revision
                            + " stableMs=" + REGION_CONFIRM_STABLE_MS
                            + " bounds=" + selectedBounds);
        };
        synchronized (state) {
            cancelRegionConfirmShowLocked(state);
            state.regionConfirmShowTask = holder[0];
        }
        MAIN.postDelayed(holder[0], REGION_CONFIRM_STABLE_MS);
    }

    private static void confirmRegion(Context app, String token) {
        State state = STATES.get(token);
        if (app == null || state == null) return;

        Rect bounds;
        synchronized (state) {
            if (state.delivered || !state.regionPending || state.regionConfirmRequested) return;
            state.regionConfirmRequested = true;
            cancelRegionConfirmShowLocked(state);
            cancelRegionFallbackLocked(state);
            bounds = state.bounds == null ? null : new Rect(state.bounds);
        }

        GoogleRegionConfirmOverlay.dismiss(token, "confirm_requested");
        DiagnosticLog.i(app, "GOOGLE_REGION",
                "confirm requested session=" + shortToken(token)
                        + " bounds=" + String.valueOf(bounds));

        // Preferred path: tell the Google hook that the user explicitly confirmed this region.
        // If that cross-process signal is not observed quickly, FloatLens already owns the frozen
        // frame and region geometry, so fall back to the same result pipeline locally.
        LsposedStatusManager.confirmGoogleCtsRegionRemoteAsync(token, success -> {
            State current = STATES.get(token);
            if (current != state) return;
            if (success) {
                DiagnosticLog.i(app, "GOOGLE_REGION",
                        "confirm remote-write-ok session=" + shortToken(token)
                                + " graceMs=" + REGION_REMOTE_GRACE_MS);
                scheduleRegionFallback(app, token, state,
                        "remote_no_query_result", REGION_REMOTE_GRACE_MS);
            } else {
                DiagnosticLog.i(app, "GOOGLE_REGION",
                        "confirm remote-write-failed session=" + shortToken(token)
                                + " fallback=local");
                scheduleRegionFallback(app, token, state,
                        "remote_write_failed", 0L);
            }
        });
    }

    private static void scheduleRegionFallback(
            Context app, String token, State state, String reason, long delayMs) {
        final Runnable[] holder = new Runnable[1];
        holder[0] = () -> {
            synchronized (state) {
                if (state.regionFallbackTask != holder[0]) return;
                state.regionFallbackTask = null;
                if (state.delivered || !state.regionPending) return;
                state.regionPending = false;
                state.committed = true;
            }
            DiagnosticLog.i(app, "GOOGLE_REGION",
                    "confirm local-fallback session=" + shortToken(token)
                            + " reason=" + reason);
            scheduleCleanup(app, token, state);
            tryDeliver(app, token, state, false);
            MAIN.postDelayed(() -> tryDeliver(app, token, state, true), FRAME_WAIT_MS);
        };

        synchronized (state) {
            cancelRegionFallbackLocked(state);
            state.regionFallbackTask = holder[0];
        }
        if (delayMs <= 0L) MAIN.post(holder[0]);
        else MAIN.postDelayed(holder[0], delayMs);
    }

    static void onCommit(Context context, String token, String detail) {
        if (context == null || token == null || token.isBlank()) return;
        Context app = context.getApplicationContext();
        State state = STATES.get(token);
        if (state == null) {
            DiagnosticLog.i(app, "GOOGLE_BRIDGE",
                    "late commit ignored session=" + shortToken(token));
            return;
        }
        GoogleRegionConfirmOverlay.dismiss(token, "commit");
        synchronized (state) {
            if (state.delivered) return;
            state.regionPending = false;
            cancelRegionConfirmShowLocked(state);
            cancelRegionFallbackLocked(state);
            state.committed = true;
            if (detail != null && !detail.isBlank()) state.detail = detail;
        }
        DiagnosticLog.i(app, "GOOGLE_BRIDGE",
                "commit session=" + shortToken(token));
        scheduleCleanup(app, token, state);
        MAIN.post(() -> tryDeliver(app, token, state, false));
        MAIN.postDelayed(() -> tryDeliver(app, token, state, true), FRAME_WAIT_MS);
    }

    static void onTextMenuCommit(Context context, String token, String text,
                                 Rect bounds, String detail) {
        if (context == null || token == null || token.isBlank()) return;
        Context app = context.getApplicationContext();
        State state = state(token);
        Bitmap frame = null;
        String finalText;
        Rect finalBounds;
        boolean showMenuNow;
        synchronized (state) {
            if (state.delivered) return;
            if (text != null && !text.isBlank()) state.text = text.trim();
            if (bounds != null && !bounds.isEmpty()) state.bounds = new Rect(bounds);
            if (detail != null && !detail.isBlank()) state.detail = detail;
            finalText = state.text == null ? "" : state.text.trim();
            finalBounds = state.bounds == null ? null : new Rect(state.bounds);
            showMenuNow = !state.textMenuShown && !finalText.isBlank();
            state.committed = true;
            state.delivered = true;
            state.textMenuShown = state.textMenuShown || showMenuNow;
            frame = state.frame;
            state.frame = null;
        }
        STATES.remove(token, state);
        cancelCleanup(state);
        GoogleRegionConfirmOverlay.dismiss(token, "text_menu_commit");
        recycle(frame);
        if (showMenuNow) {
            FloatActionMenu.showTextAt(app, finalText, null, finalBounds);
            DiagnosticLog.i(app, "GOOGLE_TEXT_MENU",
                    "late show session=" + shortToken(token)
                            + " textLen=" + finalText.length()
                            + " bounds=" + String.valueOf(finalBounds));
        }
        clearSessionState(app, token, "text_menu_commit");
        FloatService service = FloatService.get();
        if (service != null) service.onCircleFinished("google_text_menu_committed");
        DiagnosticLog.i(app, "GOOGLE_TEXT_MENU",
                "committed session=" + shortToken(token)
                        + " textLen=" + (text == null ? 0 : text.length())
                        + " bounds=" + String.valueOf(bounds)
                        + " resultDialog=false");
    }

    private static void clearSessionState(Context app, String token, String reason) {
        if (app == null) return;
        GoogleRegionConfirmOverlay.dismiss(token, reason);
        new FloatSettings(app).clearGoogleCtsSession();
        LsposedStatusManager.clearGoogleCtsSessionRemote(token);
        WorkflowSessionManager.finishExternal(app, token, reason);
        DiagnosticLog.i(app, "GOOGLE_CTS_LEASE",
                "cleared session=" + shortToken(token) + " reason=" + reason);
    }

    static void onQueryResult(Context context, String token, String text,
                              Rect bounds, String detail) {
        if (context == null || token == null || token.isBlank()) return;
        State state = STATES.get(token);
        if (state == null) {
            DiagnosticLog.i(context, "GOOGLE_BRIDGE",
                    "late query result ignored session=" + shortToken(token));
            return;
        }
        GoogleRegionConfirmOverlay.dismiss(token, "query_result");
        synchronized (state) {
            if (state.delivered) return;
            state.regionPending = false;
            cancelRegionConfirmShowLocked(state);
            cancelRegionFallbackLocked(state);
            if ((state.text == null || state.text.isBlank()) && text != null && !text.isBlank()) {
                state.text = text.trim();
            }
            if (state.bounds == null && bounds != null && !bounds.isEmpty()) {
                state.bounds = new Rect(bounds);
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
            cancelRegionConfirmShow(state);
            cancelRegionFallback(state);
            cancelCleanup(state);
        }
        GoogleRegionConfirmOverlay.dismiss(token, "bridge_end");
        boolean dismissTextMenu = false;
        if (state != null) {
            synchronized (state) {
                if (detail != null && !detail.isBlank()) state.detail = detail;
                recycle(state.frame);
                state.frame = null;
                dismissTextMenu = state.textMenuShown && !state.committed;
                state.delivered = true;
            }
        }
        if (dismissTextMenu) FloatActionMenu.dismiss();
        clearSessionState(app, token, "bridge_end");
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
        cancelRegionConfirmShow(state);
        cancelRegionFallback(state);
        cancelCleanup(state);
        GoogleRegionConfirmOverlay.dismiss(token, "deliver");

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

        WorkflowSessionManager.Session workflow = WorkflowSessionManager.current();
        if (workflow != null && token.equals(workflow.externalKey())) {
            WorkflowSessionManager.transition(app, workflow,
                    WorkflowSessionManager.Phase.RESULT_PENDING, "google_result_pending");
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
            clearSessionState(app, token, "nothing_to_show");
            return;
        }

        Runnable firstFrameHandoff = () -> {
            FloatService service = FloatService.get();
            if (service != null) service.onCircleFinished("google_bridge_result_visible");
            clearSessionState(app, token, "result_first_frame");
            DiagnosticLog.i(app, "GOOGLE_BRIDGE",
                    "result visible session=" + shortToken(token)
                            + " release=after_first_frame");
        };

        boolean shown = ResultController.showAfterFirstFrame(app, session, firstFrameHandoff);
        if (!shown) {
            try { session.close(); } catch (Throwable ignored) {}
            FloatService service = FloatService.get();
            if (service != null) service.onCircleFinished("google_bridge_delivery_failed");
            clearSessionState(app, token, "result_start_failed");
        }
        DiagnosticLog.i(app, "GOOGLE_BRIDGE",
                "delivered session=" + shortToken(token)
                        + " shown=" + shown
                        + " release=" + (shown ? "deferred_first_frame" : "immediate_failed")
                        + " textLen=" + text.length()
                        + " bounds=" + String.valueOf(normalized));
    }

    private static State state(String token) {
        return STATES.computeIfAbsent(token, ignored -> new State());
    }

    private static void scheduleCleanup(Context app, String token, State state) {
        final Runnable[] holder = new Runnable[1];
        holder[0] = () -> {
            synchronized (state) {
                if (state.cleanupTask != holder[0]) return;
                state.cleanupTask = null;
            }
            if (!STATES.remove(token, state)) return;
            boolean dismissTextMenu;
            synchronized (state) {
                cancelRegionConfirmShowLocked(state);
                cancelRegionFallbackLocked(state);
                recycle(state.frame);
                state.frame = null;
                dismissTextMenu = state.textMenuShown && !state.committed;
                state.delivered = true;
            }
            if (dismissTextMenu) FloatActionMenu.dismiss();
            GoogleRegionConfirmOverlay.dismiss(token, "state_expired");
            clearSessionState(app, token, "state_expired");
            DiagnosticLog.i(app, "GOOGLE_BRIDGE",
                    "state expired session=" + shortToken(token)
                            + " menuDismissed=" + dismissTextMenu);
        };

        Runnable previous;
        synchronized (state) {
            previous = state.cleanupTask;
            state.cleanupTask = holder[0];
        }
        if (previous != null) MAIN.removeCallbacks(previous);
        MAIN.postDelayed(holder[0], STATE_TTL_MS);
    }

    private static void cancelRegionConfirmShow(State state) {
        if (state == null) return;
        Runnable pending;
        synchronized (state) {
            pending = state.regionConfirmShowTask;
            state.regionConfirmShowTask = null;
        }
        if (pending != null) MAIN.removeCallbacks(pending);
    }

    private static void cancelRegionConfirmShowLocked(State state) {
        Runnable pending = state.regionConfirmShowTask;
        state.regionConfirmShowTask = null;
        if (pending != null) MAIN.removeCallbacks(pending);
    }

    private static void cancelRegionFallback(State state) {
        if (state == null) return;
        Runnable pending;
        synchronized (state) {
            pending = state.regionFallbackTask;
            state.regionFallbackTask = null;
        }
        if (pending != null) MAIN.removeCallbacks(pending);
    }

    private static void cancelRegionFallbackLocked(State state) {
        Runnable pending = state.regionFallbackTask;
        state.regionFallbackTask = null;
        if (pending != null) MAIN.removeCallbacks(pending);
    }

    private static void cancelCleanup(State state) {
        if (state == null) return;
        Runnable pending;
        synchronized (state) {
            pending = state.cleanupTask;
            state.cleanupTask = null;
        }
        if (pending != null) MAIN.removeCallbacks(pending);
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
