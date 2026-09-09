package com.yagay.floatlens;

import android.content.Context;

/** Clean-room smart-screen entry point kept separate from normal gesture actions. */
public final class AiScreenController {
    public static void show(Context c) {
        DiagnosticLog.i(c,"AI_SCREEN","enter clean-room smart-screen selection");
        FloatService f=FloatService.get();
        if(f!=null) f.onCircleCaptureStarted();
        ScreenshotController.captureForOcr(c);
    }
    private AiScreenController(){}
}
