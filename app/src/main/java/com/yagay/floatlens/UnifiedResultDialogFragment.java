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
 * ResultActivity is only a transparent native-selection host. This DialogFragment owns the result
 * session, the one UnifiedResultPanel instance and the inline OCR lifecycle. The dialog window uses
 * WRAP_CONTENT height; only the content slots have maximum heights, so the fixed action row can
 * never be clipped by hand-computed window geometry.
 */
public final class UnifiedResultDialogFragment extends DialogFragment {
    private static final long OCR_TIMEOUT_MS = 12_000L;

    private ResultSession session;
    private UnifiedResultPanel panel;
    private boolean ocrRunning;
    private long ocrGeneration;
    private boolean closing;

    public UnifiedResultDialogFragment() {}

    void setInitialSession(ResultSession value) {
        session = value;
    }

    void showSession(ResultSession value) {
        if (value == null) return;
        cancelCurrentOcr("new_session");
        session = value;
        if (panel != null) {
            panel.render(session);
            panel.setOcrRunning(false);
            resizeDialog();
            panel.root().post(() -> {
                if (getActivity() instanceof ResultActivity host) {
                    ResultReadyCoordinator.onResultActivityResumed(host);
                }
            });
        }
        DiagnosticLog.i(requireContext(), "RESULT_DIALOG", "session mode=" + session.mode()
                + " origin=" + session.originMode() + " sameDialog=true");
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
        DiagnosticLog.i(requireContext(), "RESULT_DIALOG", "started mode="
                + (session == null ? "none" : session.mode()) + " wrapContent=true fixedActions=true");
    }

    private void configureDialogWindow() {
        Dialog d = getDialog();
        Window w = d == null ? null : d.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
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
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();

        long gen = ++ocrGeneration;
        ocrRunning = true;
        panel.setOcrRunning(true);
        Bitmap image = session.image();
        Rect anchor = session.anchor();
        OcrResultDispatcher.register(image, (text, blocks) -> {
            if (panel == null) return;
            panel.root().post(() -> applyInlineOcr(gen, text, blocks));
        });

        panel.root().postDelayed(() -> {
            if (!isAdded() || !ocrRunning || ocrGeneration != gen) return;
            OcrResultDispatcher.cancel(image);
            ocrRunning = false;
            if (panel != null) panel.setOcrRunning(false);
            DiagnosticLog.i(requireContext(), "RESULT_DIALOG", "OCR_INLINE_TIMEOUT gen=" + gen);
        }, OCR_TIMEOUT_MS);

        DiagnosticLog.i(requireContext(), "RESULT_DIALOG", "OCR_INLINE_BEGIN gen=" + gen
                + " sameSession=true mode=" + session.mode());
        OcrEngine.recognize(requireContext().getApplicationContext(), image, anchor);
    }

    private void applyInlineOcr(long gen, String text, List<String> blocks) {
        if (!isAdded() || session == null || panel == null || gen != ocrGeneration) return;
        ocrRunning = false;
        session.applyOcr(text, blocks);
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

    private void closeResult() {
        if (closing) return;
        closing = true;
        if (session != null && session.notifyCircleOnClose()) {
            FloatService f = FloatService.get();
            if (f != null) f.onCircleFinished("result_closed");
        }
        dismissAllowingStateLoss();
        finishHost();
    }

    private void finishHost() {
        if (getActivity() instanceof ResultActivity host && !host.isFinishing()) {
            host.finishFromDialog();
        }
    }

    @Override public void onCancel(@NonNull android.content.DialogInterface dialog) {
        super.onCancel(dialog);
        closing = true;
        finishHost();
    }

    @Override public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        if (!closing) finishHost();
    }

    @Override public void onDestroyView() {
        cancelCurrentOcr("destroy_view");
        if (panel != null) panel.clearSelection();
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
        panel = null;
        super.onDestroyView();
    }
}
