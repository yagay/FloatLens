package com.yagay.floatlens;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reference-counted ownership for temporarily hiding FloatLens from screenshots.
 *
 * <p>Several capture flows can overlap: a View crop can start while Circle Select is still
 * restoring, or a stale async callback can arrive after a new capture started. A plain boolean lets
 * the first finisher reveal the icon while another capture still needs it hidden. This lease keeps
 * the icon hidden until the last live capture releases its ownership.</p>
 */
final class ScreenshotVisibilityLease {
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);
    private static final long RESTORE_DELAY_MS = 80L;

    private static int leases;
    private static FloatService ownerService;

    static Lease acquire(Context context, String owner) {
        FloatService service = FloatService.get();
        if (service == null) return null;
        Lease lease = new Lease(NEXT_ID.getAndIncrement(), service,
                owner == null || owner.isBlank() ? "capture" : owner);
        synchronized (LOCK) {
            if (ownerService != service) {
                leases = 0;
                ownerService = service;
            }
            leases++;
            if (leases == 1) service.setScreenshotHidden(true);
            DiagnosticLog.i(context == null ? service : context.getApplicationContext(),
                    "SCREENSHOT_VISIBILITY", "acquire id=" + lease.id + " owner=" + lease.owner
                            + " leases=" + leases);
        }
        return lease;
    }

    static void release(Context context, Lease lease, String reason) {
        if (lease == null || !lease.released.compareAndSet(false, true)) return;
        Context app = context == null ? lease.service.getApplicationContext()
                : context.getApplicationContext();
        MAIN.postDelayed(() -> {
            boolean reveal = false;
            int remaining;
            synchronized (LOCK) {
                if (ownerService != lease.service) return;
                if (leases > 0) leases--;
                remaining = leases;
                if (leases == 0) {
                    reveal = FloatService.get() == lease.service;
                    ownerService = null;
                }
            }
            if (reveal) lease.service.setScreenshotHidden(false);
            DiagnosticLog.i(app, "SCREENSHOT_VISIBILITY",
                    "release id=" + lease.id + " owner=" + lease.owner
                            + " reason=" + (reason == null ? "done" : reason)
                            + " remaining=" + remaining + " reveal=" + reveal);
        }, RESTORE_DELAY_MS);
    }

    static final class Lease {
        private final long id;
        private final FloatService service;
        private final String owner;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Lease(long id, FloatService service, String owner) {
            this.id = id;
            this.service = service;
            this.owner = owner;
        }
    }

    private ScreenshotVisibilityLease() {}
}
