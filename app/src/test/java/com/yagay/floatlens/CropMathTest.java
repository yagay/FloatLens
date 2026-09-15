package com.yagay.floatlens;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CropMathTest {
    @Test
    public void floorCeilMappingPreservesFractionalCoverage() {
        CropMath.Bounds b = CropMath.viewRectFloorCeil(
                10.25f, 20.25f, 30.10f, 40.10f,
                200, 400, 100, 200);

        assertEquals(20, b.left);
        assertEquals(40, b.top);
        assertEquals(61, b.right);
        assertEquals(81, b.bottom);
    }

    @Test
    public void roundMappingClampsOutsideView() {
        CropMath.Bounds b = CropMath.viewRectRound(
                -20f, -10f, 120f, 110f,
                300, 200, 100, 100);

        assertEquals(0, b.left);
        assertEquals(0, b.top);
        assertEquals(300, b.right);
        assertEquals(200, b.bottom);
    }

    @Test
    public void screenMappingAccountsForDisplayOriginAndScale() {
        CropMath.Bounds b = CropMath.screenRectRound(
                110, 220, 310, 420,
                10, 20, 400, 800,
                800, 1600);

        assertEquals(200, b.left);
        assertEquals(400, b.top);
        assertEquals(600, b.right);
        assertEquals(800, b.bottom);
    }

    @Test
    public void bitmapMappingRestoresAbsoluteScreenOriginAndScale() {
        CropMath.Bounds b = CropMath.bitmapRectRound(
                200, 400, 600, 800,
                800, 1600,
                10, 20, 400, 800);

        assertEquals(110, b.left);
        assertEquals(220, b.top);
        assertEquals(310, b.right);
        assertEquals(420, b.bottom);
    }

    @Test
    public void screenBitmapRoundTripKeepsRectangle() {
        CropMath.Bounds bitmap = CropMath.screenRectRound(
                37, 91, 801, 1703,
                0, 40, 1080, 2300,
                1440, 3067);
        CropMath.Bounds screen = CropMath.bitmapRectRound(
                bitmap.left, bitmap.top, bitmap.right, bitmap.bottom,
                1440, 3067,
                0, 40, 1080, 2300);

        assertEquals(37, screen.left, 1);
        assertEquals(91, screen.top, 1);
        assertEquals(801, screen.right, 1);
        assertEquals(1703, screen.bottom, 1);
    }

    @Test
    public void viewPointMappingUsesAbsoluteScreenOrigin() {
        assertEquals(560, CropMath.viewPointToScreen(500f, 1000, 60, 1000));
    }

    @Test
    public void mappingAlwaysKeepsAtLeastOnePixelExtent() {
        CropMath.Bounds b = CropMath.screenRectRound(
                100, 100, 100, 100,
                0, 0, 1000, 1000,
                1000, 1000);

        assertEquals(1, b.width());
        assertEquals(1, b.height());
    }

    @Test
    public void invalidDimensionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CropMath.viewRectRound(0, 0, 1, 1, 0, 100, 100, 100));
    }
}
