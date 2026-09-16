package com.yagay.floatlens;

import android.content.Context;

/**
 * Thin service-state compatibility facade.
 *
 * <p>This is not a recognition pipeline. Circle recognition is owned by the Google Circle TextMap
 * flow; this class only maps older FloatService lifecycle hooks onto the generic
 * {@link RecognitionWorkflowState} until those service APIs are renamed.</p>
 */
@Deprecated
public final class CircleStateMachine {
    public enum State { IDLE, ENTERING, ACTIVE, CAPTURE, OCR, RESULTS }

    private final RecognitionWorkflowState workflow;

    public CircleStateMachine(Context context) {
        workflow = new RecognitionWorkflowState(context);
    }

    CircleStateMachine() {
        workflow = new RecognitionWorkflowState();
    }

    public synchronized State state() {
        return switch (workflow.state()) {
            case IDLE -> State.IDLE;
            case CAPTURING -> State.CAPTURE;
            case RECOGNIZING -> State.OCR;
            case RESULTS -> State.RESULTS;
        };
    }

    public synchronized long generation() { return workflow.generation(); }
    public synchronized boolean active() { return workflow.active(); }

    public synchronized long enter() {
        return workflow.captureStarted("circle_enter");
    }

    public synchronized void captureStarted() {
        workflow.captureStarted("capture_started");
    }

    public synchronized void recognizeStarted() {
        workflow.recognitionStarted("recognition_started");
    }

    public synchronized void resultsReady(int candidates) {
        workflow.resultsReady(candidates, "results_ready");
    }

    public synchronized void finish(String reason) {
        workflow.finish(reason);
    }
}
