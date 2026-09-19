package com.yagay.floatlens.hook;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.DisplayMetrics;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/** Exact-version and structural-fallback selection adapters behind one stable runtime contract. */
final class GoogleSelectionAdapter {
    interface Binding {
        boolean available();
        Method method();
        Snapshot snapshot(Object metadata, Context context);
        String detail();
        String source();
        int confidence();
    }

    static final class Snapshot {
        private final String text;
        private final Rect bounds;
        private final String selectionClass;
        private final String detail;
        private final boolean directRegionCommit;

        Snapshot(String text, Rect bounds, String selectionClass,
                 String detail, boolean directRegionCommit) {
            this.text = text == null ? "" : text;
            this.bounds = bounds == null ? null : new Rect(bounds);
            this.selectionClass = selectionClass == null ? "" : selectionClass;
            this.detail = detail == null ? "" : detail;
            this.directRegionCommit = directRegionCommit;
        }

        String text() { return text; }
        Rect bounds() { return bounds == null ? null : new Rect(bounds); }
        String selectionClass() { return selectionClass; }
        String detail() { return detail; }
        boolean directRegionCommit() { return directRegionCommit; }
    }

    static Binding resolve(ClassLoader loader) {
        String profileError = GoogleLens1758Profile.selectionValidationError(loader);
        if (profileError.isBlank()) {
            try {
                Class<?> controller = Class.forName(
                        GoogleLens1758Profile.CONTROLLER, false, loader);
                for (Executable executable : HiddenApiBypass.getDeclaredMethods(controller)) {
                    if (executable instanceof Method method
                            && GoogleLens1758Profile.isSelectionMethod(method)) {
                        return new ExactBinding(method);
                    }
                }
                profileError = "validated profile selection method missing";
            } catch (Throwable t) {
                profileError = t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
            }
        }

        GoogleLensDynamicResolver.SelectionBinding dynamic =
                GoogleLensDynamicResolver.discoverSelection(loader);
        if (!dynamic.available()) {
            return new MissingBinding("profile=" + profileError
                    + "; dynamic=" + dynamic.detail());
        }
        return new DynamicBinding(dynamic, profileError);
    }

    private static final class ExactBinding implements Binding {
        private final Method method;

        ExactBinding(Method method) {
            this.method = method;
        }

        @Override public boolean available() { return method != null; }
        @Override public Method method() { return method; }
        @Override public String detail() { return "profile=" + GoogleLens1758Profile.NAME; }
        @Override public String source() { return "exact"; }
        @Override public int confidence() { return 100; }

        @Override public Snapshot snapshot(Object metadata, Context context) {
            GoogleLens1758Profile.SelectionSnapshot selected =
                    GoogleLens1758Profile.selection(metadata);
            Rect bounds = exactBounds(selected, context);
            return new Snapshot(selected.text(), bounds, selected.userSelectionClass(),
                    selected.detail() + " adapter=exact",
                    selected.isDirectRegionSelection() && bounds != null && !bounds.isEmpty());
        }
    }

    private static final class DynamicBinding implements Binding {
        private final GoogleLensDynamicResolver.SelectionBinding binding;
        private final String profileError;

        DynamicBinding(GoogleLensDynamicResolver.SelectionBinding binding, String profileError) {
            this.binding = binding;
            this.profileError = profileError == null ? "" : profileError;
        }

        @Override public boolean available() { return binding.available(); }
        @Override public Method method() { return binding.method(); }
        @Override public String source() { return "dynamic"; }
        @Override public int confidence() { return binding.confidence(); }
        @Override public String detail() {
            return binding.detail() + " profileError=" + profileError;
        }

        @Override public Snapshot snapshot(Object metadata, Context context) {
            GoogleLensDynamicResolver.DynamicSelectionSnapshot selected =
                    binding.snapshot(metadata);
            Rect bounds = dynamicBounds(selected.rawBounds(), context);
            String detail = selected.detail() + " resolver=" + binding.detail()
                    + " adapter=dynamic";
            // Structural discovery may select text immediately, but region auto-commit remains
            // exact-profile-only until the new Google version has been runtime validated.
            return new Snapshot(selected.text(), bounds, selected.selectionClass(),
                    detail, false);
        }
    }

    private static final class MissingBinding implements Binding {
        private final String detail;
        MissingBinding(String detail) { this.detail = detail == null ? "" : detail; }
        @Override public boolean available() { return false; }
        @Override public Method method() { return null; }
        @Override public Snapshot snapshot(Object metadata, Context context) {
            return new Snapshot("", null, "", detail, false);
        }
        @Override public String detail() { return detail; }
        @Override public String source() { return "none"; }
        @Override public int confidence() { return 0; }
    }

    private static Rect exactBounds(GoogleLens1758Profile.SelectionSnapshot selected,
                                    Context context) {
        if (selected == null) return null;
        Rect direct = selected.bounds();
        if (direct != null && !direct.isEmpty()) return direct;
        if (context == null) return null;
        try {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            return selected.boundsForFrame(metrics.widthPixels, metrics.heightPixels);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Rect dynamicBounds(RectF rawBounds, Context context) {
        if (rawBounds == null || rawBounds.width() <= 0f || rawBounds.height() <= 0f) return null;
        RectF working = new RectF(rawBounds);
        boolean normalized = working.left >= -0.05f && working.top >= -0.05f
                && working.right <= 1.05f && working.bottom <= 1.05f;
        if (normalized) {
            if (context == null) return null;
            try {
                DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                working.set(
                        working.left * metrics.widthPixels,
                        working.top * metrics.heightPixels,
                        working.right * metrics.widthPixels,
                        working.bottom * metrics.heightPixels);
            } catch (Throwable ignored) {
                return null;
            }
        }
        Rect out = new Rect();
        working.roundOut(out);
        return out.isEmpty() ? null : out;
    }

    private GoogleSelectionAdapter() {}
}
