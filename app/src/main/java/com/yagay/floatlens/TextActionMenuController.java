package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;

/** Single entry point for every FloatLens text action menu. */
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
    }
}
