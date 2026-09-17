package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;

/**
 * Single entry point for FloatLens text action menus.
 *
 * <p>Callers provide immutable SelectionSnapshot state. Menu content/behavior remains owned by
 * FloatActionMenu until that legacy class is split internally; callers never choose another menu
 * implementation.</p>
 */
final class TextActionMenuController {
    private final Context context;

    TextActionMenuController(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }

    void show(SelectionSnapshot snapshot, Runnable selectAll) {
        if (context == null || snapshot == null || snapshot.text().isBlank()) return;
        Rect anchor = snapshot.screenBounds();
        FloatActionMenu.showTextAt(context, snapshot.text(), selectAll, anchor);
    }

    void dismiss() {
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
    }
}
