package com.yagay.floatlens;

import android.graphics.PointF;
import android.graphics.Rect;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GoogleCircleSelectionTest {
    @Test public void tapCreatesSafeRoi() {
        GoogleCircleSelection.Selection s = GoogleCircleSelection.fromStroke(
                List.of(new PointF(400, 900), new PointF(402, 901)),
                1080, 2400, 24f, 64f, 100f, 80f);
        assertEquals(GoogleCircleSelection.Kind.TAP, s.kind);
        Rect roi = GoogleCircleSelection.ensureMinAndClamp(s.bounds, 1080, 2400, 96);
        assertTrue(roi.width() >= 96);
        assertTrue(roi.height() >= 96);
    }

    @Test public void closedStrokeIsCircle() {
        ArrayList<PointF> points = new ArrayList<>();
        points.add(new PointF(300, 300));
        points.add(new PointF(450, 270));
        points.add(new PointF(600, 320));
        points.add(new PointF(650, 470));
        points.add(new PointF(600, 620));
        points.add(new PointF(450, 670));
        points.add(new PointF(300, 620));
        points.add(new PointF(250, 470));
        points.add(new PointF(300, 300));
        GoogleCircleSelection.Selection s = GoogleCircleSelection.fromStroke(
                points, 1080, 2400, 24f, 64f, 100f, 80f);
        assertEquals(GoogleCircleSelection.Kind.CIRCLE, s.kind);
    }

    @Test public void horizontalStrokeIsHighlight() {
        GoogleCircleSelection.Selection s = GoogleCircleSelection.fromStroke(
                List.of(new PointF(100, 500), new PointF(300, 505), new PointF(700, 510)),
                1080, 2400, 24f, 64f, 100f, 80f);
        assertEquals(GoogleCircleSelection.Kind.HIGHLIGHT, s.kind);
    }

    @Test public void bottomEdgeNeverCollapsesToOnePixel() {
        GoogleCircleSelection.Selection s = GoogleCircleSelection.fromStroke(
                List.of(new PointF(920, 2398), new PointF(980, 2399)),
                1080, 2400, 24f, 64f, 100f, 80f);
        Rect roi = GoogleCircleSelection.ensureMinAndClamp(s.bounds, 1080, 2400, 96);
        assertTrue(roi.top >= 0);
        assertTrue(roi.bottom <= 2400);
        assertTrue(roi.height() >= 96);
    }
}
