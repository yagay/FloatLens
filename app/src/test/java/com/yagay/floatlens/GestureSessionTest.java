package com.yagay.floatlens;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GestureSessionTest {
    @Test
    public void beginInitializesSingleDownPoint() {
        GestureSession session = new GestureSession();
        session.begin(12f, 34f, 100L);

        assertEquals(GestureSession.Phase.DOWN, session.phase);
        assertEquals(1, session.points.size());
        assertEquals(12f, session.downX, 0.001f);
        assertEquals(34f, session.downY, 0.001f);
        assertEquals(0f, session.dx(), 0.001f);
        assertEquals(0f, session.dy(), 0.001f);
        assertFalse(session.moved);
        assertFalse(session.longPressReady);
        assertFalse(session.multiTouch);
    }

    @Test
    public void pointStreamTracksDisplacementDistanceAndDuration() {
        GestureSession session = new GestureSession();
        session.begin(10f, 20f, 100L);
        session.add(13f, 24f, 120L);
        session.add(16f, 28f, 150L);

        assertEquals(6f, session.dx(), 0.001f);
        assertEquals(8f, session.dy(), 0.001f);
        assertEquals(10f, session.distance(), 0.001f);
        assertEquals(50L, session.duration(150L));
        assertEquals(0L, session.duration(50L));
        assertEquals(3, session.points.size());
    }

    @Test
    public void snapshotIsStableAndUnmodifiable() {
        GestureSession session = new GestureSession();
        session.begin(1f, 2f, 10L);
        List<GesturePointSample> snapshot = session.snapshot();
        session.add(3f, 4f, 20L);

        assertEquals(1, snapshot.size());
        assertEquals(2, session.points.size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new GesturePointSample(5f, 6f, 30L)));
    }

    @Test
    public void resetClearsTransientGestureState() {
        GestureSession session = new GestureSession();
        session.begin(1f, 2f, 10L);
        session.phase = GestureSession.Phase.CIRCLE;
        session.longPressReady = true;
        session.moved = true;
        session.multiTouch = true;

        session.reset();

        assertEquals(GestureSession.Phase.IDLE, session.phase);
        assertTrue(session.points.isEmpty());
        assertFalse(session.longPressReady);
        assertFalse(session.moved);
        assertFalse(session.multiTouch);
    }
}
