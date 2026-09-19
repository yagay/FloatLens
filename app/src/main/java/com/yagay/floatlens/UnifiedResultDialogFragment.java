package com.yagay.floatlens;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import java.util.List;

/**
 * The single visible result container for screenshot, View and OCR content.
 *
 * ResultActivity is only a transparent host. This fragment owns the current ResultSession and one
 * teardown path so close button, outside-tap, Back/dismiss and destruction cannot leave recognition
 * state behind.
 */
public final class UnifiedResultDialogFragment extends DialogFragment {
    private static final long OCR_TIMEOUT_MS = 12_000L;

    private ResultSession session;
    private ResultReadyCoordinator.Ticket readyTicket;
    private UnifiedResultPanel panel;
    private boolean ocrRunning;
    private long ocrGeneration;
    private boolean closing;
    private boolean workflowFinished;

    public UnifiedResultDialogFragment() {}

    void setInitialSession(ResultSession value, ResultReadyCoordinator.Ticket ticket) {
        session = value;
        readyTicket = ticket;
        workflowFinished = false;
    }

    void runAfterFirstVisibleFrame(Runnable callback) {
        if (callback == null) return;
        if (panel == null) {
            // showNow() normally creates the panel synchronously; this fallback handles lifecycle
            // edge cases without releasing the Google session before a visible frame exists.
            getViewLifecycleOwnerLiveData().observe(this, owner -> {
                if (owner != null && panel != null) {
                    ResultReadyCoordinator.afterFirstVisibleFrame(
                            panel.root(), stage -> runFirstFrameCallback(callback, stage));
                }
            });
            return;
        }
        ResultReadyCoordinator.afterFirstVisibleFrame(
                panel.root(), stage -> runFirstFrameCallback(callback, stage));
    }

    private void runFirstFrameCallback(Runnable callback, String stage) {
        try { callback.run(); }
        catch (Throwable t) {
            DiagnosticLog.i(requireContext(), "RESULT_HANDOFF",
                    "callback failed stage=" + stage + " error="
                            + ScreenCaptureBackend.safeMessage(t));
            return;
        }
        DiagnosticLog.i(requireContext(), "RESULT_HANDOFF", "callback stage=" + stage);
    }

    void showSession(ResultSession value, ResultReadyCoordinator.Ticket ticket) {
        if (value == null) return;
        ResultSession previous = session;
        cancelCurrentOcr("new_session");
        session = value;
        readyTicket = ticket;
        closing = false;
        workflowFinished = false;
        if (panel != null) {
            panel.render(session);
            panel.setOcrRunning(false);
            resizeDialog();
            notifyVisibleResultReady(ticket);
        }
        if (previous != null && previous != value) {
            try { previous.close(); } catch (Throwable ignored) {}
        }
        DiagnosticLog.i(requireContext(), "RESULT_DIALOG", "session mode=" + session.mode()
                + " origin=" + session.originMode()
                + " ready=" + (ticket == null ? "none" : ticket.id)
                + " sameDialog=true");
    }

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setStyle(STYLE_NO_TITLE, R.style.Theme_FloatLens_ResultDialog);
    }

    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle state) {
        Dialog dialog = super.onCreateDialog(state);
        dialog.setCanceledOnTouchOutside(true);
        return dialog;
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
                                                  @Nullable ViewGroup container,
                                                  @Nullable Bundle state) {
        if (session == null) {
            DiagnosticLog.i(requireContext(), "RESULT_DIALOG", "missing initial session");
            return new View(requireContext());
        }
        panel = new UnifiedResultPanel(requireContext(), session);
        panel.bindActions(this::beginInlineOcr,
                () -> {
                    if (session != null && session.canSave()) {
                        ScreenshotController.save(requireContext(), session.image());
                    }
                }, this::closeResult);
        panel.setOcrRunning(false);
        return panel.root();
    }

    @Override public void onStart() {
        super.onStart();
        configureDialogWindow();
        resizeDialog();
        notifyVisibleResultReady(readyTicket);
        DiagnosticLog.i(requireContext(), "RESULT_DIALOG", "started mode="
                + (session == null ? "none" : session.mode()) + " wrapContent=true fixedActions=true");
    }

    private void notifyVisibleResultReady(ResultReadyCoordinator.Ticket ticket) {
        if (panel == null || ticket == null) return;
        View root = panel.root();
        root.post(() -> {
            if (getActivity() instanceof ResultActivity host && panel != null) {
                ResultReadyCoordinator.onResultDialogReady(host, root, ticket);
            }
        });
    }

    private void configureDialogWindow() {
        Dialog d = getDialog();
        Window w = d == null ? null : d.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setElevation(0f);
        View decor = w.getDecorView();
        decor.setBackgroundColor(Color.TRANSPARENT);
        decor.setElevation(0f);
        decor.setTranslationZ(0f);
        decor.setStateListAnimator(null);
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = Gravity.CENTER;
        lp.dimAmount = 0f;
        w.setAttributes(lp);
    }

    private void resizeDialog() {
        Dialog d = getDialog();
        Window w = d == null ? null : d.getWindow();
        if (w == null || panel == null) return;
        w.setLayout(panel.width(), WindowManager.LayoutParams.WRAP_CONTENT);
        panel.root().requestLayout();
        panel.root().post(() -> DiagnosticLog.i(requireContext(), "RESULT_DIALOG",
                "measured=" + panel.root().getWidth() + "x" + panel.root().getHeight()
                        + " width=" + panel.width() + " mode="
                        + (session == null ? "none" : session.mode())));
    }

    private void beginInlineOcr() {
        if (ocrRunning || session == null || !session.canOcr() || panel == null) return;
        panel.clearSelection();

        long gen = ++ocrGeneration;
        ocrRunning = true;
        panel.setOcrRunning(true);
        Bitmap image = session.image();
        Rect sourceBounds = session.sourceBounds();
        OcrResultDispatcher.register(image, (text, blocks) -> {
            if (panel == null) return;
            panel.root().post(() -> applyInlineOcr(gen, text, blocks));
        });

        panel.root().postDelayed(() -> {
            if (!isAdded() || !ocrRunning || ocrGeneration != gen) return;
            OcrResultDispatcher.cancel(image);
            OcrEngine.invalidatePending(requireContext().getApplicationContext(),
                    "result_dialog_timeout");
            ocrRunning = false;
            if (panel != null) panel.setOcrRunning(false);
            DiagnosticLog.i(requireContext(), "RESULT_DIALOG", "OCR_INLINE_TIMEOUT gen=" + gen);
        }, OCR_TIMEOUT_MS);

        DiagnosticLog.i(requireContext(), "RESULT_DIALOG", "OCR_INLINE_BEGIN gen=" + gen
                + " sameSession=true mode=" + session.mode()
                + " sourceBounds=" + (sourceBounds == null ? "none" : sourceBounds.toShortString()));
        OcrEngine.recognize(requireContext().getApplicationContext(), image, sourceBounds);
    }

    private void applyInlineOcr(long gen, String text, List<String> blocks) {
        if (!isAdded() || session == null || panel == null || gen != ocrGeneration) return;
        ocrRunning = false;
        session.applyOcr(text, blocks);
        workflowFinished = false;
        panel.render(session);
        panel.setOcrRunning(false);
        resizeDialog();
        DiagnosticLog.i(requireContext(), "RESULT_DIALOG", "OCR_INLINE chars="
                + session.text().length() + " blocks=" + session.blocks().size()
                + " sameDialog=true nativeSelection=true magnifier=true");
    }

    private void cancelCurrentOcr(String reason) {
        if (session != null && ocrRunning && session.hasImage()) {
            OcrResultDispatcher.cancel(session.image());
            if (isAdded()) {
                OcrEngine.invalidatePending(requireContext().getApplicationContext(),
                        "result_dialog_" + reason);
            }
        }
        ocrRunning = false;
        ocrGeneration++;
        if (panel != null) panel.setOcrRunning(false);
    }

    private void finishWorkflowState(String reason) {
        if (workflowFinished) return;
        workflowFinished = true;
        if (session != null && session.notifyCircleOnClose()) {
            FloatService service = FloatService.get();
            if (service != null) service.onCircleFinished(reason);
        }
        DiagnosticLog.i(requireContext(), "RESULT_DIALOG", "workflow teardown reason=" + reason
                + " mode=" + (session == null ? "none" : session.mode()));
    }

    private void closeResult() {
        if (closing) return;
        closing = true;
        finishWorkflowState("result_closed");
        dismissAllowingStateLoss();
        finishHost();
    }

    private void finishHost() {
        if (getActivity() instanceof ResultActivity host && !host.isFinishing()) {
            host.finishFromDialog();
        }
    }

    @Override public void onCancel(@NonNull android.content.DialogInterface dialog) {
        finishWorkflowState("result_cancelled");
        closing = true;
        super.onCancel(dialog);
        finishHost();
    }

    @Override public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        if (!closing) finishWorkflowState("result_dismissed");
        super.onDismiss(dialog);
        if (!closing) finishHost();
    }

    @Override public void onDestroyView() {
        cancelCurrentOcr("destroy_view");
        if (panel != null) panel.clearSelection();
        panel = null;
        super.onDestroyView();
    }

    @Override public void onDestroy() {
        if (session != null && session.notifyCircleOnClose()) {
            try { finishWorkflowState("result_destroyed"); } catch (Throwable ignored) { }
        }
        ResultSession owned = session;
        session = null;
        if (owned != null) {
            try { owned.close(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
