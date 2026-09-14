package com.yagay.floatlens;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Single launch/update boundary for the one official ResultActivity host. */
final class ResultController {
    static final String EXTRA_TOKEN = "result_token";
    private static final long PENDING_EXPIRE_MS = 15_000L;
    private static final AtomicLong NEXT = new AtomicLong(1L);
    private static final Map<Long, ResultSession> PENDING = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static boolean show(Context c, ResultSession session) {
        if (c == null || session == null) return false;
        Context app = c.getApplicationContext();
        long token = NEXT.getAndIncrement();
        PENDING.put(token, session);
        MAIN.postDelayed(() -> expire(app, token, session), PENDING_EXPIRE_MS);
        Intent intent = new Intent(app, ResultActivity.class)
                .putExtra(EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            app.startActivity(intent);
            DiagnosticLog.i(app, "RESULT_CONTROLLER", "show token=" + token
                    + " mode=" + session.mode() + " origin=" + session.originMode());
            return true;
        } catch (Throwable t) {
            PENDING.remove(token, session);
            DiagnosticLog.i(app, "RESULT_CONTROLLER", "show failed token=" + token
                    + " error=" + ScreenCaptureBackend.safeMessage(t));
            return false;
        }
    }

    static boolean showCaptured(Context c, ResultSession session,
                                FlSystemPanelController.CaptureState shadeState, String reason) {
        if (c == null || session == null) return false;
        Context app = c.getApplicationContext();
        ResultReadyCoordinator.Ticket ticket = ResultReadyCoordinator.arm(app, shadeState, reason);
        boolean shown = show(app, session);
        DiagnosticLog.i(app, "RESULT_CONTROLLER", "captured mode=" + session.mode()
                + " shown=" + shown + " reason=" + reason);
        if (!shown) ResultReadyCoordinator.cancel(ticket, app, reason + "_start_failed");
        return shown;
    }

    static ResultSession take(long token) { return token == 0L ? null : PENDING.remove(token); }
    static void discard(long token) { if (token != 0L) PENDING.remove(token); }

    private static void expire(Context app, long token, ResultSession expected) {
        if (!PENDING.remove(token, expected)) return;
        Bitmap image = expected.image();
        if (image != null && !image.isRecycled()) {
            try { image.recycle(); } catch (Throwable ignored) {}
        }
        DiagnosticLog.i(app, "RESULT_CONTROLLER", "expired unconsumed token=" + token);
    }

    private ResultController() {}
}
