package com.yagay.floatlens;

import android.content.Context;

/**
 * One state owner for screenshot/OCR/Circle recognition work.
 *
 * UI surfaces may differ, but lifecycle semantics do not: a workflow is idle, capturing/selecting,
 * recognizing, showing results, or finished. A new generation starts only when work begins from
 * IDLE; phase changes within the same workflow keep that generation.
 */
final class RecognitionWorkflowState {
    enum State { IDLE, CAPTURING, RECOGNIZING, RESULTS }

    private final Context context;
    private State state = State.IDLE;
    private long generation;

    RecognitionWorkflowState(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }

    RecognitionWorkflowState() { this(null); }

    synchronized State state() { return state; }
    synchronized long generation() { return generation; }
    synchronized boolean active() { return state != State.IDLE; }

    synchronized long captureStarted(String reason) {
        beginIfIdle(reason == null ? "capture" : reason);
        transition(State.CAPTURING, reason == null ? "capture" : reason);
        return generation;
    }

    synchronized long recognitionStarted(String reason) {
        beginIfIdle(reason == null ? "recognition" : reason);
        transition(State.RECOGNIZING, reason == null ? "recognition" : reason);
        return generation;
    }

    synchronized long resultsReady(int candidates, String reason) {
        beginIfIdle(reason == null ? "results" : reason);
        transition(State.RESULTS, (reason == null ? "results" : reason)
                + " candidates=" + Math.max(0, candidates));
        return generation;
    }

    synchronized void finish(String reason) {
        transition(State.IDLE, reason == null ? "finish" : reason);
    }

    private void beginIfIdle(String reason) {
        if (state != State.IDLE) return;
        generation++;
        log("gen=" + generation + " begin reason=" + reason);
    }

    private void transition(State next, String reason) {
        State old = state;
        state = next == null ? State.IDLE : next;
        if (old != state || state == State.IDLE) {
            log("gen=" + generation + " " + old + "->" + state + " reason=" + reason);
        }
    }

    private void log(String message) {
        if (context != null) DiagnosticLog.i(context, "WORKFLOW_STATE", message);
    }
}
