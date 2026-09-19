package com.yagay.floatlens;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GoogleRegionConfirmPositionerTest {
    @Test public void prefersBelowSelectionWhenThereIsRoom() {
        var p = GoogleRegionConfirmPositioner.place(
                100, 100, 500, 500, 0, 0, 1080, 2400, 160, 80, 20);
        assertEquals(340, p.left);
        assertEquals(520, p.top);
    }

    @Test public void movesAboveSelectionNearBottomEdge() {
        var p = GoogleRegionConfirmPositioner.place(
                100, 2100, 1000, 2350, 0, 0, 1080, 2400, 160, 80, 20);
        assertEquals(840, p.left);
        assertEquals(2000, p.top);
    }

    @Test public void clampsToUsableBounds() {
        var p = GoogleRegionConfirmPositioner.place(
                -100, 10, 70, 90, 30, 50, 1050, 2200, 160, 80, 20);
        assertEquals(30, p.left);
        assertEquals(110, p.top);
    }
}
