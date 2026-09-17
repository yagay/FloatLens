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
    public void mappingAlwaysKeepsAtLeastOnePixelExtent() {
        CropMath.Bounds b = CropMath.viewRectRound(
                50, 50, 50, 50,
                1000, 1000, 1000, 1000);

        assertEquals(1, b.width());
        assertEquals(1, b.height());
    }

    @Test
    public void invalidDimensionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CropMath.viewRectRound(0, 0, 1, 1, 0, 100, 100, 100));
    }
}
