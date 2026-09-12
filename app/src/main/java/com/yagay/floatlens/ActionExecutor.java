package com.yagay.floatlens;

import android.content.Context;

/** Compatibility facade; ActionRegistry is the single owner of action behavior. */
public final class ActionExecutor {
    public static void execute(Context c, String action) {
        ActionRegistry.execute(c, action);
    }

    private ActionExecutor() {}
}
