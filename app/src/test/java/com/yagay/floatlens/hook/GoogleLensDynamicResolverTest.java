package com.yagay.floatlens.hook;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.PointF;
import android.graphics.RectF;

import org.junit.Test;

import java.lang.reflect.Method;

public class GoogleLensDynamicResolverTest {

    private static final class FakeUserSelection {
        String selectedText() { return "hello"; }
        RectF bounds() { return new RectF(); }
        PointF point() { return new PointF(); }
    }

    private static final class FakeMetadata {
        final FakeUserSelection selection = new FakeUserSelection();
    }

    private static final class FakeController {
        void onSelection(FakeMetadata metadata, boolean primary) {}
    }

    private static final class WrongMetadata {
        final Object value = new Object();
    }

    private static final class WrongController {
        int notSelection(WrongMetadata metadata, boolean primary) { return 0; }
    }

    @Test public void structuralSelectionCandidateRequiresSemanticShape() throws Exception {
        Method method = FakeController.class.getDeclaredMethod(
                "onSelection", FakeMetadata.class, boolean.class);
        GoogleLensDynamicResolver.Candidate candidate =
                GoogleLensDynamicResolver.scoreSelectionMethod(method);
        assertNotNull(candidate);
        assertTrue(candidate.score >= GoogleLensDynamicResolver.MIN_SELECTION_CONFIDENCE);
    }

    @Test public void nonVoidLookalikeIsRejected() throws Exception {
        Method method = WrongController.class.getDeclaredMethod(
                "notSelection", WrongMetadata.class, boolean.class);
        assertTrue(GoogleLensDynamicResolver.scoreSelectionMethod(method) == null);
    }
}
