package com.yagay.YFloat.hook;

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
        Binding profile1760 = resolve1760(loader);
        if (profile1760.available()) return profile1760;

        String profileError = GoogleLens1758Profile.selectionValidationError(loader);
        if (profileError.isBlank()) {
            try {
                Class<?> controller = Class.forName(
                        GoogleLens1758Profile.CONTROLLER, false, loader);
                for (Executable executable : HiddenApiBypass.getDeclaredMethods(controller)) {
                    if (executable instanceof Method method
                            && GoogleLens1758Profile.isSelectionMethod(method)) {
                        return new ExactBinding(method, "exact-1758", 100,
                                "profile=" + GoogleLens1758Profile.NAME);
                    }
                }
                profileError = "validated profile selection method missing";
            } catch (Throwable t) {
                profileError = t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
            }
        }

        Binding renamedProfile = resolveRenamedProfileSelection(loader, profileError);
        if (renamedProfile.available()) return renamedProfile;

        GoogleLensDynamicResolver.SelectionBinding dynamic =
                GoogleLensDynamicResolver.discoverSelection(loader);
        if (!dynamic.available()) {
            return new MissingBinding("profile1760=" + profile1760.detail()
                    + "; profile1758=" + profileError
                    + "; renamedProfile=" + renamedProfile.detail()
                    + "; dynamic=" + dynamic.detail());
        }
        return new DynamicBinding(dynamic,
                "1760=" + profile1760.detail() + "; 1758=" + profileError);
    }

    private static Binding resolve1760(ClassLoader loader) {
        String error = GoogleLens1760Profile.validationError(loader);
        if (!error.isBlank()) return new MissingBinding(error);
        try {
            Class<?> controller = Class.forName(GoogleLens1760Profile.CONTROLLER, false, loader);
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(controller)) {
                if (executable instanceof Method method
                        && GoogleLens1760Profile.isSelectionMethod(method)) {
                    return new Profile1760Binding(method,
                            "profile=" + GoogleLens1760Profile.NAME);
                }
            }
            return new MissingBinding("validated 17.60 selection method missing");
        } catch (Throwable t) {
            return new MissingBinding("17.60 lookup=" + t.getClass().getSimpleName()
                    + ":" + String.valueOf(t.getMessage()));
        }
    }

    private static Binding resolveRenamedProfileSelection(ClassLoader loader, String profileError) {
        if (loader == null) return new MissingBinding("classLoader=null");
        try {
            Class<?> controller = Class.forName(
                    GoogleLens1758Profile.CONTROLLER, false, loader);
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(controller)) {
                if (!(executable instanceof Method method)) continue;
                if (!isKnownProfileSelectionSignature(method)) continue;
                return new ExactBinding(method, "profile-signature-1758", 98,
                        "profile=" + GoogleLens1758Profile.NAME
                                + " renamedMethod=" + method.getName()
                                + " previousValidation=" + safe(profileError));
            }
            return new MissingBinding("known 17.58 controller has no SelectionMetadata,boolean->void method");
        } catch (Throwable t) {
            return new MissingBinding("known 17.58 profile signature lookup failed="
                    + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage()));
        }
    }

    static boolean isKnownProfileSelectionSignature(Method method) {
        if (method == null || method.getReturnType() != void.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 2
                && GoogleLens1758Profile.SELECTION_METADATA.equals(params[0].getName())
                && params[1] == boolean.class;
    }

    private static final class Profile1760Binding implements Binding {
        private final Method method;
        private final String detail;

        Profile1760Binding(Method method, String detail) {
            this.method = method;
            this.detail = detail == null ? "" : detail;
            try { if (method != null) method.setAccessible(true); } catch (Throwable ignored) { }
        }

        @Override public boolean available() { return method != null; }
        @Override public Method method() { return method; }
        @Override public String detail() { return detail; }
        @Override public String source() { return "exact-1760"; }
        @Override public int confidence() { return 100; }

        @Override public Snapshot snapshot(Object metadata, Context context) {
            GoogleLens1760Profile.SelectionSnapshot selected =
                    GoogleLens1760Profile.selection(metadata, context);
            Rect bounds = selected.bounds();
            return new Snapshot(selected.text(), bounds, selected.selectionClass(),
                    selected.detail() + " adapter=exact-1760", false);
        }
    }

    private static final class ExactBinding implements Binding {
        private final Method method;
        private final String source;
        private final int confidence;
        private final String detail;

        ExactBinding(Method method, String source, int confidence, String detail) {
            this.method = method;
            this.source = source == null ? "exact" : source;
            this.confidence = confidence;
            this.detail = detail == null ? "" : detail;
            try { if (this.method != null) this.method.setAccessible(true); } catch (Throwable ignored) {}
        }

        @Override public boolean available() { return method != null; }
        @Override public Method method() { return method; }
        @Override public String detail() { return detail; }
        @Override public String source() { return source; }
        @Override public int confidence() { return confidence; }

        @Override public Snapshot snapshot(Object metadata, Context context) {
            GoogleLens1758Profile.SelectionSnapshot selected =
                    GoogleLens1758Profile.selection(metadata);
            Rect bounds = exactBounds(selected, context);
            return new Snapshot(selected.text(), bounds, selected.userSelectionClass(),
                    selected.detail() + " adapter=" + source,
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

    private static String safe(String value) {
        if (value == null) return "";
        String out = value.replace('\n', ' ').replace('\r', ' ');
        return out.length() <= 300 ? out : out.substring(0, 300) + "…";
    }

    private GoogleSelectionAdapter() {}
}
