package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;

/** Current external-overlay implementation of the text action menu. */
final class OverlayTextActionMenuPresenter implements TextActionMenuPresenter {
    private final Context context;

    OverlayTextActionMenuPresenter(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }

    @Override public void show(SelectionSnapshot snapshot, Runnable selectAll) {
        if (context == null || snapshot == null || snapshot.text().isBlank()) return;
        Rect anchor = snapshot.screenBounds();
        FloatActionMenu.showTextAt(context, snapshot.text(), selectAll, anchor);
    }

    @Override public void dismiss() {
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
    }
}
