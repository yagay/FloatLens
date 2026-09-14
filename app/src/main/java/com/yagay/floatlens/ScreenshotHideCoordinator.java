package com.yagay.floatlens;

import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reference-counted owner for temporary floating-icon hiding during asynchronous captures.
 *
 * Several screenshot flows may overlap. A plain boolean lets the first completed request reveal the
 * icon while another request is still capturing. Leases keep the icon hidden until the last owner
 * releases it.
 */
final class ScreenshotHideCoordinator {
    private static int activeLeases;
    private static long nextId;

    static Lease acquire(Context context, String reason) {
        FloatService service = FloatService.get();
        if (service == null) return new Lease(0L, null, false, reason);
        final long id;
        final int count;
        synchronized (ScreenshotHideCoordinator.class) {
            id = ++nextId;
            count = ++activeLeases;
        }
        if (count == 1) service.setScreenshotHidden(true);
        DiagnosticLog.i(context == null ? service : context.getApplicationContext(),
                "SCREENSHOT_HIDE", "acquire id=" + id + " count=" + count + " reason=" + safe(reason));
        return new Lease(id, service, true, reason);
    }

    private static void release(Context context, Lease lease) {
        if (lease == null || !lease.counted || !lease.released.compareAndSet(false, true)) return;
        final int count;
        synchronized (ScreenshotHideCoordinator.class) {
            activeLeases = Math.max(0, activeLeases - 1);
            count = activeLeases;
        }
        FloatService service = lease.service;
        if (count == 0 && service != null) service.setScreenshotHidden(false);
        Context logContext = context == null ? service : context.getApplicationContext();
        if (logContext != null) {
            DiagnosticLog.i(logContext, "SCREENSHOT_HIDE",
                    "release id=" + lease.id + " count=" + count + " reason=" + safe(lease.reason));
        }
    }

    static final class Lease {
        private final long id;
        private final FloatService service;
        private final boolean counted;
        private final String reason;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Lease(long id, FloatService service, boolean counted, String reason) {
            this.id = id;
            this.service = service;
            this.counted = counted;
            this.reason = reason;
        }

        void release(Context context) {
            ScreenshotHideCoordinator.release(context, this);
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "capture" : value;
    }

    private ScreenshotHideCoordinator() {}
}
