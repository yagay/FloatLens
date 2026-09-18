package com.yagay.floatlens.hook;

import android.graphics.PointF;
import android.graphics.RectF;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Array;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import dalvik.system.DexFile;

/**
 * Fail-soft structural resolver for Google Lens selection internals.
 *
 * <p>It runs only when the exact built-in profile no longer validates. No candidate is accepted
 * from a class/method name. A high-confidence candidate must expose the semantic shape currently
 * needed by FloatLens: controller (SelectionMetadata, boolean)->void, metadata -> UserSelection,
 * and UserSelection no-arg String + RectF accessors. This makes ordinary R8 renaming survivable
 * without guessing unsafe hooks.</p>
 */
final class GoogleLensDynamicResolver {
    static final int MIN_SELECTION_CONFIDENCE = 95;
    private static final int MAX_SCANNED_CLASSES = 14_000;

    static SelectionBinding discoverSelection(ClassLoader loader) {
        if (loader == null) return SelectionBinding.none("classLoader=null");

        SelectionBinding best = SelectionBinding.none("no structural candidate");
        int scanned = 0;
        Set<String> seen = new HashSet<>();

        try {
            for (String name : dexClassNames(loader)) {
                if (scanned >= MAX_SCANNED_CLASSES) break;
                if (!eligibleClassName(name) || !seen.add(name)) continue;
                scanned++;

                Class<?> controller;
                try {
                    controller = Class.forName(name, false, loader);
                } catch (Throwable ignored) {
                    continue;
                }
                if (controller.isInterface() || controller.isAnnotation()
                        || controller.isEnum() || controller.isArray()) {
                    continue;
                }

                for (Method method : declaredMethods(controller)) {
                    Candidate candidate = scoreSelectionMethod(method);
                    if (candidate == null || candidate.score <= best.confidence()) continue;
                    best = new SelectionBinding(
                            method,
                            candidate.userSelectionField,
                            candidate.textMethod,
                            candidate.boundsMethod,
                            candidate.pointMethod,
                            candidate.score,
                            "dynamic-structural scanned=" + scanned
                                    + " controller=" + controller.getName()
                                    + " method=" + method.getName());
                    if (best.confidence() >= 110) {
                        return best;
                    }
                }
            }
        } catch (Throwable t) {
            return SelectionBinding.none("resolver failed="
                    + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage()));
        }

        if (best.available()) {
            return new SelectionBinding(best.method(), best.userSelectionField(),
                    best.textMethod(), best.boundsMethod(), best.pointMethod(),
                    best.confidence(), best.detail() + " scannedFinal=" + scanned);
        }
        return SelectionBinding.none("no structural candidate scanned=" + scanned);
    }

    static Candidate scoreSelectionMethod(Method method) {
        if (method == null || method.getReturnType() != void.class) return null;
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 2 || params[1] != boolean.class) return null;
        if (isPlatformType(params[0])) return null;

        int base = 20;
        Candidate best = null;
        for (Field field : instanceFields(params[0])) {
            Class<?> userSelection = field.getType();
            if (isPlatformType(userSelection) || userSelection.isPrimitive()) continue;

            Method text = null;
            Method bounds = null;
            Method point = null;
            for (Method semantic : declaredMethods(userSelection)) {
                if (semantic.getParameterCount() != 0) continue;
                if (semantic.getReturnType() == String.class && text == null) {
                    text = semantic;
                } else if (semantic.getReturnType() == RectF.class && bounds == null) {
                    bounds = semantic;
                } else if (semantic.getReturnType() == PointF.class && point == null) {
                    point = semantic;
                }
            }

            int score = base;
            if (text != null) score += 40;
            if (bounds != null) score += 35;
            if (point != null) score += 10;
            if (Modifier.isFinal(field.getModifiers())) score += 2;

            if (text != null && bounds != null
                    && (best == null || score > best.score)) {
                best = new Candidate(field, text, bounds, point, score);
            }
        }
        return best;
    }

    private static Set<String> dexClassNames(ClassLoader loader) {
        Set<String> out = new HashSet<>();
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            Object pathList = readNamedField(current, "pathList");
            Object elements = readNamedField(pathList, "dexElements");
            if (elements == null || !elements.getClass().isArray()) continue;
            int length = Array.getLength(elements);
            for (int i = 0; i < length; i++) {
                Object element = Array.get(elements, i);
                Object dex = readNamedField(element, "dexFile");
                if (!(dex instanceof DexFile dexFile)) continue;
                try {
                    Enumeration<String> entries = dexFile.entries();
                    while (entries.hasMoreElements()) out.add(entries.nextElement());
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static boolean eligibleClassName(String name) {
        if (name == null || name.isBlank() || name.contains("$")) return false;
        int dot = name.lastIndexOf('.');
        String simple = dot < 0 ? name : name.substring(dot + 1);
        if (simple.length() < 2 || simple.length() > 8) return false;
        char first = simple.charAt(0);
        if (first < 'a' || first > 'z') return false;
        if (name.startsWith("android.") || name.startsWith("java.")
                || name.startsWith("kotlin.") || name.startsWith("androidx.")) {
            return false;
        }
        return true;
    }

    private static boolean isPlatformType(Class<?> type) {
        if (type == null) return true;
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("android.")
                || name.startsWith("androidx.") || name.startsWith("kotlin.");
    }

    private static Method[] declaredMethods(Class<?> cls) {
        try {
            return cls.getDeclaredMethods();
        } catch (Throwable ignored) {
            try {
                java.util.ArrayList<Method> out = new java.util.ArrayList<>();
                for (Executable executable : HiddenApiBypass.getDeclaredMethods(cls)) {
                    if (executable instanceof Method method) out.add(method);
                }
                return out.toArray(new Method[0]);
            } catch (Throwable ignoredAgain) {
                return new Method[0];
            }
        }
    }

    private static Field[] instanceFields(Class<?> cls) {
        try {
            return java.util.Arrays.stream(cls.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .toArray(Field[]::new);
        } catch (Throwable ignored) {
            try {
                return HiddenApiBypass.getInstanceFields(cls).toArray(new Field[0]);
            } catch (Throwable ignoredAgain) {
                return new Field[0];
            }
        }
    }

    private static Object readNamedField(Object target, String name) {
        if (target == null || name == null) return null;
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
            }
        }
        try {
            for (Field field : HiddenApiBypass.getInstanceFields(target.getClass())) {
                if (!name.equals(field.getName())) continue;
                field.setAccessible(true);
                return field.get(target);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static final class SelectionBinding {
        private final Method method;
        private final Field userSelectionField;
        private final Method textMethod;
        private final Method boundsMethod;
        private final Method pointMethod;
        private final int confidence;
        private final String detail;

        SelectionBinding(Method method, Field userSelectionField, Method textMethod,
                         Method boundsMethod, Method pointMethod, int confidence, String detail) {
            this.method = method;
            this.userSelectionField = userSelectionField;
            this.textMethod = textMethod;
            this.boundsMethod = boundsMethod;
            this.pointMethod = pointMethod;
            this.confidence = confidence;
            this.detail = detail == null ? "" : detail;
            try { if (this.method != null) this.method.setAccessible(true); } catch (Throwable ignored) {}
            try { if (this.userSelectionField != null) this.userSelectionField.setAccessible(true); } catch (Throwable ignored) {}
            try { if (this.textMethod != null) this.textMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { if (this.boundsMethod != null) this.boundsMethod.setAccessible(true); } catch (Throwable ignored) {}
            try { if (this.pointMethod != null) this.pointMethod.setAccessible(true); } catch (Throwable ignored) {}
        }

        static SelectionBinding none(String detail) {
            return new SelectionBinding(null, null, null, null, null, 0, detail);
        }

        boolean available() {
            return method != null && userSelectionField != null
                    && textMethod != null && boundsMethod != null
                    && confidence >= MIN_SELECTION_CONFIDENCE;
        }

        Method method() { return method; }
        Field userSelectionField() { return userSelectionField; }
        Method textMethod() { return textMethod; }
        Method boundsMethod() { return boundsMethod; }
        Method pointMethod() { return pointMethod; }
        int confidence() { return confidence; }
        String detail() { return detail; }

        DynamicSelectionSnapshot snapshot(Object metadata) {
            if (metadata == null || !available()) {
                return new DynamicSelectionSnapshot("", null, "", "metadata/binding unavailable");
            }
            try {
                Object selection = userSelectionField.get(metadata);
                if (selection == null) {
                    return new DynamicSelectionSnapshot("", null, "",
                            "userSelection=null metadata=" + metadata.getClass().getName());
                }
                Object rawText = textMethod.invoke(selection);
                Object rawBounds = boundsMethod.invoke(selection);
                String text = rawText instanceof String value ? value.trim() : "";
                RectF bounds = rawBounds instanceof RectF value ? new RectF(value) : null;
                StringBuilder detail = new StringBuilder();
                detail.append("dynamicSelectionClass=").append(selection.getClass().getName())
                        .append(" confidence=").append(confidence);
                if (pointMethod != null) {
                    try {
                        Object point = pointMethod.invoke(selection);
                        if (point instanceof PointF p) {
                            detail.append(" point=").append(p.x).append(",").append(p.y);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (bounds != null) detail.append(" rawBounds=").append(bounds);
                if (!text.isBlank()) detail.append(" textLen=").append(text.length());
                return new DynamicSelectionSnapshot(text, bounds,
                        selection.getClass().getName(), detail.toString());
            } catch (Throwable t) {
                return new DynamicSelectionSnapshot("", null, "",
                        "dynamic snapshot failed=" + t.getClass().getSimpleName());
            }
        }
    }

    static final class DynamicSelectionSnapshot {
        private final String text;
        private final RectF rawBounds;
        private final String selectionClass;
        private final String detail;

        DynamicSelectionSnapshot(String text, RectF rawBounds,
                                 String selectionClass, String detail) {
            this.text = text == null ? "" : text;
            this.rawBounds = rawBounds == null ? null : new RectF(rawBounds);
            this.selectionClass = selectionClass == null ? "" : selectionClass;
            this.detail = detail == null ? "" : detail;
        }

        String text() { return text; }
        RectF rawBounds() { return rawBounds == null ? null : new RectF(rawBounds); }
        String selectionClass() { return selectionClass; }
        String detail() { return detail; }

        boolean directRegionLike() {
            return text.isBlank() && rawBounds != null
                    && rawBounds.width() > 0f && rawBounds.height() > 0f;
        }
    }

    static final class Candidate {
        final Field userSelectionField;
        final Method textMethod;
        final Method boundsMethod;
        final Method pointMethod;
        final int score;

        Candidate(Field userSelectionField, Method textMethod, Method boundsMethod,
                  Method pointMethod, int score) {
            this.userSelectionField = userSelectionField;
            this.textMethod = textMethod;
            this.boundsMethod = boundsMethod;
            this.pointMethod = pointMethod;
            this.score = score;
        }
    }

    private GoogleLensDynamicResolver() {}
}
