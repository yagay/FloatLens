package com.yagay.floatlens;

import android.content.Context;

/** Compatibility facade over the process-wide WorkflowSessionManager. */
final class RecognitionWorkflowState {
    enum State { IDLE, CAPTURING, RECOGNIZING, RESULTS }

    private final Context context;

    RecognitionWorkflowState(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }

    RecognitionWorkflowState() { this(null); }

    synchronized State state() {
        WorkflowSessionManager.Session session = WorkflowSessionManager.current();
        if (session == null) return State.IDLE;
        return switch (session.phase()) {
            case CAPTURING, CREATED, SELECTING -> State.CAPTURING;
            case RECOGNIZING -> State.RECOGNIZING;
            case RESULT_PENDING, RESULT_VISIBLE -> State.RESULTS;
            case FINISHED, CANCELLED, FAILED -> State.IDLE;
        };
    }

    synchronized long generation() {
        return WorkflowSessionManager.currentId();
    }

    synchronized boolean active() {
        return WorkflowSessionManager.current() != null;
    }

    synchronized long captureStarted(String reason) {
        WorkflowSessionManager.Session session = WorkflowSessionManager.ensureCurrent(
                context, WorkflowSessionManager.Type.RECOGNITION,
                reason == null ? "capture" : reason);
        WorkflowSessionManager.transition(context, session,
                WorkflowSessionManager.Phase.CAPTURING,
                reason == null ? "capture" : reason);
        return session.id();
    }

    synchronized long recognitionStarted(String reason) {
        WorkflowSessionManager.Session session = WorkflowSessionManager.ensureCurrent(
                context, WorkflowSessionManager.Type.OCR,
                reason == null ? "recognition" : reason);
        WorkflowSessionManager.transition(context, session,
                WorkflowSessionManager.Phase.RECOGNIZING,
                reason == null ? "recognition" : reason);
        return session.id();
    }

    synchronized long resultsReady(int candidates, String reason) {
        WorkflowSessionManager.Session session = WorkflowSessionManager.ensureCurrent(
                context, WorkflowSessionManager.Type.RECOGNITION,
                reason == null ? "results" : reason);
        WorkflowSessionManager.transition(context, session,
                WorkflowSessionManager.Phase.RESULT_VISIBLE,
                (reason == null ? "results" : reason)
                        + " candidates=" + Math.max(0, candidates));
        return session.id();
    }

    synchronized void finish(String reason) {
        WorkflowSessionManager.finishCurrent(context, reason == null ? "finish" : reason);
    }
}
