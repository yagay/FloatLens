package com.yagay.floatlens;

import android.content.Context;
import android.widget.Toast;

/** Entry point for the FloatLens exact content-selection workflow. */
final class FLCircleController {
    private static CaptureTransaction pendingTransaction;
    private static WorkflowSessionManager.Session pendingSession;

    static synchronized void show(Context c) {
        Context app = c.getApplicationContext();
        WorkflowSessionManager.Session session = WorkflowSessionManager.begin(
                app, WorkflowSessionManager.Type.CIRCLE, "fl_circle");
        WorkflowSessionManager.transition(app, session,
                WorkflowSessionManager.Phase.CAPTURING, "fl_circle_capture");

        FLCircleInlineOverlay.dismissActive("restart");
        CircleActiveBorderOverlay.hide(app, "restart");
        cancelPendingLocked(app);

        CaptureTransaction transaction = CaptureTransaction.begin(app, "fl_circle")
                .hideFloatingIcon("fl_circle_" + gen);
        pendingTransaction = transaction;
        pendingSession = session;
        FloatSettings fs = new FloatSettings(app);
        DiagnosticLog.i(app, "FL_CIRCLE", "start gen=" + gen
                + " phase=capture_then_fullscreen_ocr"
                + " fullEngine=" + CircleStableOcr.fullModeLabel(app)
                + " fullMode=" + fs.circleFullOcrEngine()
                + " correctionEngine=" + CircleStableOcr.correctionModeLabel(app)
                + " correctionMode=" + fs.circleCorrectionEngine()
                + " textRecognition=full_once_plus_optional_per_gesture_correction"
                + " layoutDetection=disabled paragraphStitching=disabled"
                + " backgroundOcr=false tileOcr=false fullFrameOcr=true"
                + " viewText=false semanticLabels=false contentHints=false"
                + " gestureGeometry=exact_path"
                + " tapSelection=precise_char_or_latin_word"
                + " circleMode=editable_screenshot autoExpand=false");

        FLCircleCapture.capture(app, frame -> {
            if (!session.current()) {
                frame.recycle();
                transaction.close();
                return;
            }

            // Initialize per-frame OCR state. Full-screen recognition starts on the first text
            // gesture and is cached. Optional PP correction is then evaluated on every text gesture.
            FLCircleTextResolver.preload(app, frame);

            boolean shown = FLCircleInlineOverlay.show(app, frame, () -> {
                FLCircleTextResolver.release(app, frame, "workspace_closed");
                CircleActiveBorderOverlay.hide(app, "workspace_closed");
                restore(app, transaction, session, "closed");
            });
            if (!shown) {
                FLCircleTextResolver.release(app, frame, "overlay_failed");
                frame.recycle();
                CircleActiveBorderOverlay.hide(app, "overlay_failed");
                restore(app, transaction, session, "overlay_failed");
                Toast.makeText(app, "圈画识别启动失败", Toast.LENGTH_SHORT).show();
                return;
            }

            CircleActiveBorderOverlay.show(app);
            WorkflowSessionManager.transition(app, session,
                    WorkflowSessionManager.Phase.SELECTING, "circle_overlay_visible");

            transaction.overlayReady("fl_circle",
                    collapsed -> {
                        if (!session.current()) return;
                        DiagnosticLog.i(app, "FL_CIRCLE", "shade cleanup collapsed="
                                + collapsed + " workflow=" + session.id());
                        FLCircleInlineOverlay.promoteActiveFocus(
                                collapsed ? "shade_collapsed" : "shade_cleanup_finished");
                    });
        }, error -> {
            if (!session.current()) {
                transaction.close();
                return;
            }
            CircleActiveBorderOverlay.hide(app, "capture_failed");
            WorkflowSessionManager.fail(app, session, "capture_failed");
            restore(app, transaction, session, "capture_failed");
            DiagnosticLog.i(app, "FL_CIRCLE", "capture failed="
                    + ScreenCaptureBackend.safeMessage(error));
            Toast.makeText(app, "圈画识别截图失败: "
                    + ScreenCaptureBackend.safeMessage(error), Toast.LENGTH_LONG).show();
        });
    }

    private static void restore(Context app, CaptureTransaction transaction,
                                WorkflowSessionManager.Session session, String reason) {
        transaction.close();
        synchronized (FLCircleController.class) {
            if (pendingTransaction == transaction) pendingTransaction = null;
            if (pendingSession == session) pendingSession = null;
        }
        if (session.current()) WorkflowSessionManager.finish(app, session, reason);
        DiagnosticLog.i(app, "FL_CIRCLE", "finish workflow=" + session.id()
                + " reason=" + reason);
    }

    private static void cancelPendingLocked(Context app) {
        CaptureTransaction transaction = pendingTransaction;
        WorkflowSessionManager.Session session = pendingSession;
        pendingTransaction = null;
        pendingSession = null;
        if (transaction != null) transaction.close();
        if (session != null && session.current()) {
            WorkflowSessionManager.cancel(app, session, "circle_replaced");
        }
    }

    private FLCircleController() {}
}
