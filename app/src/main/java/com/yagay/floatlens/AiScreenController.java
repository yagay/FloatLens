package com.yagay.floatlens;

import android.content.Context;

/** Circle Select entry point kept separate from normal FV View-selection gestures. */
public final class AiScreenController {
    public static void show(Context c) {
        DiagnosticLog.i(c, "AI_SCREEN", "enter Circle Select workspace");
        CircleSelectController.show(c);
    }
    private AiScreenController() {}
}
