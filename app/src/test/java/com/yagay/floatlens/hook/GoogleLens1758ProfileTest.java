package com.yagay.floatlens.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GoogleLens1758ProfileTest {

    private static class OptionalBase {}
    private static final class OptionalImpl extends OptionalBase {}

    @Test public void optionalRuntimeSubclassMatchesDeclaredBaseType() {
        assertTrue(GoogleLens1758Profile.hasTypeInHierarchy(
                OptionalImpl.class, OptionalBase.class.getName()));
        assertTrue(GoogleLens1758Profile.hasTypeInHierarchy(
                OptionalImpl.class, OptionalImpl.class.getName()));
        assertFalse(GoogleLens1758Profile.hasTypeInHierarchy(
                OptionalImpl.class, "missing.OptionalType"));
    }

    @Test public void selectionTextFallbackParsesObservedGoogle1758WordSelection() {
        String kernel = "WordSelection(textSelection=TextSelection(selectedText=KernelSU, "
                + "wordBoxes=[x], selectionRange=y, salientText=[]))";
        String rednote = "SelectionWithMetadata(userSelection=WordSelection("
                + "textSelection=TextSelection(selectedText=rednote, wordBoxes=[x], "
                + "selectionRange=y)))";
        assertEquals("KernelSU", GoogleLens1758Profile.selectedTextFromString(kernel));
        assertEquals("rednote", GoogleLens1758Profile.selectedTextFromString(rednote));
        assertEquals("", GoogleLens1758Profile.selectedTextFromString("RegionSearchSelection"));
    }

    @Test public void presentationBoundaryRequiresCompleteInteractionPresentation() {
        assertFalse(GoogleLens1758Profile.isPresentationBoundary(false, true, true));
        assertFalse(GoogleLens1758Profile.isPresentationBoundary(true, false, true));
        assertFalse(GoogleLens1758Profile.isPresentationBoundary(true, true, false));
        assertTrue(GoogleLens1758Profile.isPresentationBoundary(true, true, true));
    }

    @Test public void resultCompletionRequiresImageAndAnyPresentInteraction() {
        assertFalse(GoogleLens1758Profile.isCompleteState(false, false, false));
        assertTrue(GoogleLens1758Profile.isCompleteState(true, false, false));
        assertFalse(GoogleLens1758Profile.isCompleteState(true, true, false));
        assertTrue(GoogleLens1758Profile.isCompleteState(true, true, true));
    }

    @Test public void postSelectionResultSuppressionRequiresRealTextSelection() {
        assertFalse(GoogleLens1758Profile.shouldSuppressPostSelectionResult(false, "KernelSU"));
        assertFalse(GoogleLens1758Profile.shouldSuppressPostSelectionResult(true, ""));
        assertFalse(GoogleLens1758Profile.shouldSuppressPostSelectionResult(true, "   "));
        assertTrue(GoogleLens1758Profile.shouldSuppressPostSelectionResult(true, "KernelSU"));
    }

    @Test public void anyFloatLensSelectionSuppressesGooglePostSelectionUi() {
        assertFalse(GoogleLens1758Profile.shouldSuppressAnyPostSelectionResult(false));
        assertTrue(GoogleLens1758Profile.shouldSuppressAnyPostSelectionResult(true));
    }

    @Test public void textViewportFocusSuppressionIsNarrowlyScoped() {
        assertFalse(GoogleLens1758Profile.shouldSuppressTextViewportFocus(
                false, "KernelSU", "dudp", true));
        assertFalse(GoogleLens1758Profile.shouldSuppressTextViewportFocus(
                true, "", "dudp", true));
        assertFalse(GoogleLens1758Profile.shouldSuppressTextViewportFocus(
                true, "KernelSU", "other", true));
        assertFalse(GoogleLens1758Profile.shouldSuppressTextViewportFocus(
                true, "KernelSU", "dudp", false));
        assertTrue(GoogleLens1758Profile.shouldSuppressTextViewportFocus(
                true, "KernelSU", "dudp", true));
    }

    @Test public void regionSelectionClassUsesImmediateCommitPath() {
        assertTrue(GoogleLens1758Profile.isDirectRegionSelectionClass("dtln"));
        assertFalse(GoogleLens1758Profile.isDirectRegionSelectionClass("dtlr"));
        assertFalse(GoogleLens1758Profile.isDirectRegionSelectionClass(""));
        assertFalse(GoogleLens1758Profile.isDirectRegionSelectionClass(null));
    }

    @Test public void nonTextSelectionCommitsOnCompleteInteractionWithoutPresentation() {
        assertFalse(GoogleLens1758Profile.shouldCommitNonTextSelection(
                false, "", true, true));
        assertFalse(GoogleLens1758Profile.shouldCommitNonTextSelection(
                true, "KernelSU", true, true));
        assertFalse(GoogleLens1758Profile.shouldCommitNonTextSelection(
                true, "", false, true));
        assertFalse(GoogleLens1758Profile.shouldCommitNonTextSelection(
                true, "", true, false));
        assertTrue(GoogleLens1758Profile.shouldCommitNonTextSelection(
                true, "", true, true));
    }
}
