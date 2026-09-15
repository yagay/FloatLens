package com.yagay.floatlens;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CircleStateMachineTest {
    @Test public void captureStartsOneWorkflowGeneration() {
        RecognitionWorkflowState workflow = new RecognitionWorkflowState();
        long generation = workflow.captureStarted("capture");

        assertEquals(1L, generation);
        assertEquals(RecognitionWorkflowState.State.CAPTURING, workflow.state());
        assertTrue(workflow.active());
    }

    @Test public void phasesWithinOneWorkflowKeepGeneration() {
        RecognitionWorkflowState workflow = new RecognitionWorkflowState();
        workflow.captureStarted("capture");
        workflow.recognitionStarted("ocr");
        workflow.resultsReady(3, "ready");

        assertEquals(1L, workflow.generation());
        assertEquals(RecognitionWorkflowState.State.RESULTS, workflow.state());
    }

    @Test public void startingRecognitionFromIdleCreatesGeneration() {
        RecognitionWorkflowState workflow = new RecognitionWorkflowState();
        workflow.recognitionStarted("direct_ocr");

        assertEquals(1L, workflow.generation());
        assertEquals(RecognitionWorkflowState.State.RECOGNIZING, workflow.state());
    }

    @Test public void finishReturnsIdleAndNextWorkGetsNewGeneration() {
        RecognitionWorkflowState workflow = new RecognitionWorkflowState();
        workflow.captureStarted("capture");
        workflow.finish("done");

        assertEquals(RecognitionWorkflowState.State.IDLE, workflow.state());
        assertFalse(workflow.active());
        assertEquals(1L, workflow.generation());

        workflow.recognitionStarted("next");
        assertEquals(2L, workflow.generation());
    }

    @Test public void legacyFacadeUsesTheSameCoreLifecycle() {
        CircleStateMachine legacy = new CircleStateMachine();
        legacy.captureStarted();
        assertEquals(CircleStateMachine.State.CAPTURE, legacy.state());
        legacy.recognizeStarted();
        assertEquals(CircleStateMachine.State.OCR, legacy.state());
        legacy.resultsReady(1);
        assertEquals(CircleStateMachine.State.RESULTS, legacy.state());
        legacy.finish("done");
        assertEquals(CircleStateMachine.State.IDLE, legacy.state());
    }
}
