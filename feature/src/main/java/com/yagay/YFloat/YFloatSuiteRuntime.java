package com.yagay.YFloat;

import android.app.Application;
import android.content.Context;

/** One app-side initializer shared by standalone YFloat and YSuite. */
public final class YFloatSuiteRuntime {
    private static boolean initialized;
    private static YFloatApp callbacks;
    private static Context appContext;
    private static final LsposedStatusManager.Listener HOOK_LISTENER =
            snapshot -> {
                Context app = appContext;
                if (app != null) HookReloadManager.autoReloadChangedTargets(app, snapshot);
            };

    private YFloatSuiteRuntime() { }

    public static synchronized Object get(Context context) {
        if (initialized) return LsposedStatusManager.hostListener();
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null) app = context;
        if (app == null) return null;
        appContext = app;

        try { SettingsMigrator.run(app); }
        catch (Throwable t) { DiagnosticLog.i(app, "APP_MIGRATION", "settings migration failed=" + t); }
        ThemeSettings.applySavedMode(app);
        HookReloadManager.initialize(app);
        try { RemovedFeatureMigration.run(app); }
        catch (Throwable t) { DiagnosticLog.i(app, "APP_MIGRATION", "AI/dictionary cleanup failed=" + t); }
        // In the combined APK, publish YSuite as the physical IPC/provider host before hooked
        // processes start sending YFloat events. Standalone builds simply skip this extra listener.
        YFloatHostIdentity.initialize(app);
        LsposedStatusManager.initialize(app);
        LsposedStatusManager.addListener(HOOK_LISTENER, true);

        if (app instanceof Application) {
            callbacks = app instanceof YFloatApp ? (YFloatApp) app : new YFloatApp();
            ((Application) app).registerActivityLifecycleCallbacks(callbacks);
        }
        initialized = true;
        return LsposedStatusManager.hostListener();
    }
}
