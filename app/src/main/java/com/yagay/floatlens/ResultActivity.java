package com.yagay.floatlens;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import java.util.List;

/**
 * Transparent native-selection host for the one UnifiedResultDialogFragment.
 *
 * This Activity no longer owns result UI, OCR state, popup geometry or result-ready timing. Every
 * screenshot / View / OCR result is rendered by one DialogFragment + one UnifiedResultPanel.
 */
public final class ResultActivity extends AppCompatActivity {
    private static final String DIALOG_TAG = "floatlens_result_dialog";

    public interface InlineResultSink {
        void onResult(String text, List<String> blocks);
    }

    /** Compatibility entry points; all routes converge on ResultController. */
    public static boolean showScreenshot(Context c, Bitmap image, Rect anchor) {
        if (c == null || image == null || image.isRecycled()) return false;
        return ResultController.show(c, ResultSession.screenshot(image, anchor));
    }

    public static boolean showViewText(Context c, String text, Bitmap image, Rect anchor) {
        if (c == null || text == null || text.isBlank()) return false;
        return ResultController.show(c, ResultSession.viewText(text, image, anchor));
    }

    public static boolean showViewImage(Context c, Bitmap image, ViewNodeCandidate view, Rect anchor) {
        if (c == null || image == null || image.isRecycled()) return false;
        return ResultController.show(c, ResultSession.viewImage(image, view, anchor));
    }

    public static boolean showOcr(Context c, String text, List<String> blocks,
                                  Bitmap image, Rect anchor) {
        if (c == null) return false;
        return ResultController.show(c, ResultSession.ocr(text, blocks, image, anchor));
    }

    public static void captureNextForImage(Bitmap image, InlineResultSink sink) {
        OcrResultDispatcher.register(image, sink == null ? null : sink::onResult);
    }

    public static void clearInlineForImage(Bitmap image) {
        OcrResultDispatcher.cancel(image);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!acceptIntent(getIntent(), false)) {
            finishNoAnim();
            return;
        }
        overridePendingTransition(0, 0);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!acceptIntent(intent, true)) finishNoAnim();
    }

    private boolean acceptIntent(Intent intent, boolean reuse) {
        long token = intent == null ? 0L : intent.getLongExtra(ResultController.EXTRA_TOKEN, 0L);
        ResultSession next = ResultController.take(token);
        if (next == null) {
            DiagnosticLog.i(this, "RESULT_ACTIVITY", "missing session token=" + token
                    + " reuse=" + reuse);
            return false;
        }

        Fragment existing = getSupportFragmentManager().findFragmentByTag(DIALOG_TAG);
        UnifiedResultDialogFragment dialog = existing instanceof UnifiedResultDialogFragment
                ? (UnifiedResultDialogFragment) existing : null;

        if (dialog == null) {
            dialog = new UnifiedResultDialogFragment();
            dialog.setInitialSession(next);
            try {
                dialog.showNow(getSupportFragmentManager(), DIALOG_TAG);
            } catch (Throwable t) {
                DiagnosticLog.i(this, "RESULT_ACTIVITY", "dialog show failed token=" + token
                        + " error=" + ScreenCaptureBackend.safeMessage(t));
                return false;
            }
        } else {
            dialog.showSession(next);
        }

        DiagnosticLog.i(this, "RESULT_ACTIVITY", (reuse ? "REUSE" : "CREATED")
                + " token=" + token + " mode=" + next.mode()
                + " origin=" + next.originMode() + " dialogHost=true");
        return true;
    }

    void finishFromDialog() {
        finishNoAnim();
    }

    private void finishNoAnim() {
        finish();
        overridePendingTransition(0, 0);
    }
}
