package com.yagay.floatlens;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CircleStateMachineTest {
    @Test
    public void enterStartsNewGenerationAndBecomesActive() {
        CircleStateMachine machine = new CircleStateMachine();

        long generation = machine.enter();

        assertEquals(1L, generation);
        assertEquals(CircleStateMachine.State.ACTIVE, machine.state());
        assertTrue(machine.active());
    }

    @Test
    public void captureFromIdleImplicitlyStartsGeneration() {
        CircleStateMachine machine = new CircleStateMachine();

        machine.captureStarted();

        assertEquals(1L, machine.generation());
        assertEquals(CircleStateMachine.State.CAPTURE, machine.state());
    }

    @Test
    public void recognizeAndResultsFollowExpectedStates() {
        CircleStateMachine machine = new CircleStateMachine();
        machine.enter();

        machine.recognizeStarted();
        assertEquals(CircleStateMachine.State.OCR, machine.state());

        machine.resultsReady(3);
        assertEquals(CircleStateMachine.State.RESULTS, machine.state());
    }

    @Test
    public void finishReturnsToIdleWithoutResettingGeneration() {
        CircleStateMachine machine = new CircleStateMachine();
        machine.enter();
        machine.finish("done");

        assertEquals(CircleStateMachine.State.IDLE, machine.state());
        assertFalse(machine.active());
        assertEquals(1L, machine.generation());

        machine.enter();
        assertEquals(2L, machine.generation());
    }
}
