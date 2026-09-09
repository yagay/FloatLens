package com.yagay.floatlens;

import android.content.Context;

/**
 * Explicit clean-room state machine for FloatLens long-hold -> circle -> OCR flow.
 * Keeps UI mode transitions independent from gesture recognition and action binding.
 */
public final class CircleStateMachine {
    public enum State { IDLE, ENTERING, ACTIVE, CAPTURE, OCR, RESULTS }

    private final Context context;
    private State state = State.IDLE;
    private long generation;

    public CircleStateMachine(Context c) { context = c.getApplicationContext(); }

    public synchronized State state() { return state; }
    public synchronized long generation() { return generation; }
    public synchronized boolean active() { return state != State.IDLE; }

    public synchronized long enter() {
        generation++;
        transition(State.ENTERING, "gesture=" + GestureCode.ENTER_CIRCLE);
        transition(State.ACTIVE, "circle_ready");
        return generation;
    }

    public synchronized void captureStarted() {
        if (state == State.IDLE) enter();
        transition(State.CAPTURE, "selection_started");
    }

    public synchronized void recognizeStarted() {
        if (state == State.IDLE) enter();
        transition(State.OCR, "gesture=" + GestureCode.RECOGNIZE);
    }

    public synchronized void resultsReady(int candidates) {
        transition(State.RESULTS, "candidates=" + Math.max(0, candidates));
    }

    public synchronized void finish(String reason) {
        transition(State.IDLE, reason == null ? "finish" : reason);
    }

    private void transition(State next, String reason) {
        State old = state;
        state = next;
        DiagnosticLog.i(context, "CIRCLE_STATE", "gen=" + generation + " " + old + "->" + next + " reason=" + reason);
    }
}
