package com.yagay.YFloat.hook;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.DisplayMetrics;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Google App 17.60.16.ve symbol map for the pre-17.60 YFloat CTS behavior.
 *
 * <p>This profile changes only obfuscated Google symbols. Selection semantics stay identical to
 * the stable 17.58 implementation: concrete WordSelection owns TextSelection/word boxes and
 * concrete RegionSearchSelection is the only direct region commit.</p>
 */
final class GoogleLens1760Profile {
    static final String NAME = "17.60.16.ve";

    static final String CONTROLLER = "dsxb";
    static final String SELECTION_METADATA = "dsws";
    static final String USER_SELECTION = "dufw";
    static final String REGION_SELECTION = "dufu";
    static final String WORD_SELECTION = "dufy";
    static final String TEXT_SELECTION = "duqg";
    static final String TEXT_SELECTION_RANGE = "dolv";
    static final String WORD_BOX = "dolw";
    static final String ROTATED_BOX = "dokt";
    static final String GEOMETRY_UTIL = "dokv";

    static final String PENDING_QUERY = "dukq";
    static final String QUERY_RESULT = "dukp";
    static final String LENS_IMAGE = "eszg";
    static final String LENS_RESULT = "eszz";
    static final String IMAGE_RESULT = "eszj";
    static final String INTERACTION_RESULT = "eszp";
    static final String INTERACTION_DATA = "eszn";
    static final String OPTIONAL = "fyqu";
    static final String ABSENT_OPTIONAL_HOLDER = "fyos";

    static final String TEXT_SELECTION_STATE = "doms";
    static final String WORD = "domd";
    static final String TEXT_SELECTION_CONTROLLER = "dupy";
    static final String TEXT_SELECTION_UPDATE_TASK = "dupt";

    static final String ACTION_MENU_CONTROLLER = "dpfz";

    static final String VIEWPORT_CONTROLLER = "duyj";
    static final String VIEWPORT_REQUEST = "duxw";
    static final String VIEWPORT_STATE = "dtsi";
    static final int VIEWPORT_SOURCE_TEXT = 1;

    static final String FROZEN_IMAGE_VIEW =
            "com.google.android.libraries.lens.view.frozenimage.FrozenImageView";
    static final String TEXT_SELECTION_VIEW =
            "com.google.android.libraries.lens.common.text.selection.ui.TextSelectionView";
    static final String IMMUTABLE_LIST = "com.google.common.collect.ImmutableList";

    static boolean available(ClassLoader loader) {
        return validationError(loader).isBlank();
    }

    static String validationError(ClassLoader loader) {
        if (loader == null) return "classLoader=null";
        try {
            Class<?> controller = Class.forName(CONTROLLER, false, loader);
            boolean selection = false;
            boolean pending = false;
            boolean result = false;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(controller)) {
                if (!(executable instanceof Method method)) continue;
                selection |= isSelectionMethod(method);
                pending |= isPendingQueryMethod(method);
                result |= isQueryResultMethod(method);
            }
            if (!selection || !pending || !result) {
                return "controller methods mismatch selection=" + selection
                        + " pending=" + pending + " result=" + result;
            }
            Class<?> metadata = Class.forName(SELECTION_METADATA, false, loader);
            if (!hasField(metadata, "a", USER_SELECTION)) {
                return "SelectionWithMetadata.a UserSelection missing";
            }
            Class<?> user = Class.forName(USER_SELECTION, false, loader);
            if (!hasNoArgMethod(user, "b", RectF.class.getName())
                    || !hasNoArgMethod(user, "k", String.class.getName())) {
                return "UserSelection semantic methods mismatch";
            }
            Class.forName(REGION_SELECTION, false, loader);
            Class.forName(WORD_SELECTION, false, loader);
            Class.forName(TEXT_SELECTION, false, loader);
            Class.forName(WORD_BOX, false, loader);
            Class.forName(ROTATED_BOX, false, loader);
            Class.forName(GEOMETRY_UTIL, false, loader);
            return "";
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
        }
    }

    static boolean isSelectionMethod(Method method) {
        if (method == null || !"y".equals(method.getName())
                || method.getReturnType() != void.class) return false;
        Class<?>[] p = method.getParameterTypes();
        return p.length == 2
                && SELECTION_METADATA.equals(p[0].getName())
                && p[1] == boolean.class;
    }

    static boolean isPendingQueryMethod(Method method) {
        if (method == null || !"p".equals(method.getName())
                || method.getReturnType() != void.class) return false;
        Class<?>[] p = method.getParameterTypes();
        return p.length == 1 && PENDING_QUERY.equals(p[0].getName());
    }

    static boolean isQueryResultMethod(Method method) {
        if (method == null || !"q".equals(method.getName())
                || method.getReturnType() != void.class) return false;
        Class<?>[] p = method.getParameterTypes();
        return p.length == 1 && QUERY_RESULT.equals(p[0].getName());
    }

    static boolean isDirectRegionSelectionClass(String className) {
        return REGION_SELECTION.equals(className);
    }

    static SelectionSnapshot selection(Object metadata, Context context) {
        if (metadata == null) {
            return new SelectionSnapshot("", null, "", "metadata=null");
        }
        Object user = GoogleReflection.readField(metadata, "a", USER_SELECTION);
        if (user == null) user = firstFieldByType(metadata, USER_SELECTION);
        if (user == null) {
            return new SelectionSnapshot("", null, "",
                    "metadataClass=" + metadata.getClass().getName()
                            + " userSelection=missing");
        }

        String userClass = user.getClass().getName();
        String text = asString(GoogleReflection.invokeNoArg(user, "k")).trim();

        // Stable 17.58 behavior: concrete WordSelection owns TextSelection in field a; generic
        // UserSelection.k() may deliberately be empty. 17.60 is structurally identical.
        if (text.isBlank() && WORD_SELECTION.equals(userClass)) {
            Object textSelection = GoogleReflection.readField(user, "a", TEXT_SELECTION);
            Object selectedText = GoogleReflection.readField(
                    textSelection, "a", String.class.getName());
            text = asString(selectedText).trim();
        }

        RectF raw = wordBoxUnionBounds(user);
        String boundsSource = raw == null ? "" : "wordBoxes";
        if (raw == null) {
            raw = asRectF(GoogleReflection.invokeNoArg(user, "b"));
            if (raw != null) boundsSource = "userSelection.b";
        }
        if (raw == null) {
            raw = firstRectF(user, metadata);
            if (raw != null) boundsSource = "reflectedRect";
        }

        Rect bounds = toScreenBounds(raw, context);
        String detail = "profile=" + NAME
                + " metadataClass=" + metadata.getClass().getName()
                + " userSelectionClass=" + userClass
                + " textLen=" + text.length()
                + (boundsSource.isBlank() ? "" : " boundsSource=" + boundsSource)
                + " rawBounds=" + String.valueOf(raw)
                + " bounds=" + String.valueOf(bounds);
        return new SelectionSnapshot(text, bounds, userClass, detail);
    }

    private static RectF wordBoxUnionBounds(Object user) {
        if (user == null || !WORD_SELECTION.equals(user.getClass().getName())) return null;
        try {
            Object textSelection = GoogleReflection.readField(user, "a", TEXT_SELECTION);
            Object rawBoxes = GoogleReflection.readField(
                    textSelection, "b", IMMUTABLE_LIST);
            if (!(rawBoxes instanceof Iterable<?> boxes)) return null;

            RectF union = null;
            for (Object wordBox : boxes) {
                if (wordBox == null || !WORD_BOX.equals(wordBox.getClass().getName())) continue;
                Object rotatedBox = GoogleReflection.readField(wordBox, "d", ROTATED_BOX);
                RectF rect = rectFromGoogleRotatedBox(rotatedBox);
                if (rect == null || rect.width() <= 0f || rect.height() <= 0f) continue;
                if (union == null) union = new RectF(rect);
                else union.union(rect);
            }
            return union;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 17.60 keeps the same geometry contract as 17.58 with renamed classes only. */
    private static RectF rectFromGoogleRotatedBox(Object rotatedBox) {
        if (rotatedBox == null || !ROTATED_BOX.equals(rotatedBox.getClass().getName())) {
            return null;
        }
        try {
            ClassLoader loader = rotatedBox.getClass().getClassLoader();
            Class<?> geometry = Class.forName(GEOMETRY_UTIL, false, loader);

            Object oriented = invokeStaticOneArg(geometry, "v", rotatedBox);
            Object rect = invokeStaticOneArg(geometry, "D", oriented);
            RectF out = asRectF(rect);
            if (out != null) return out;

            Object quad = invokeStaticOneArg(geometry, "w", rotatedBox);
            rect = invokeStaticOneArg(geometry, "E", quad);
            return asRectF(rect);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeStaticOneArg(Class<?> type, String name, Object arg) {
        if (type == null || name == null || arg == null) return null;
        for (Method method : GoogleReflection.declaredMethods(type)) {
            if (!name.equals(method.getName()) || !Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (!parameter.isAssignableFrom(arg.getClass())) continue;
            try {
                method.setAccessible(true);
                return method.invoke(null, arg);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static RectF firstRectF(Object... owners) {
        if (owners == null) return null;
        for (Object owner : owners) {
            if (owner == null) continue;
            for (Field field : GoogleReflection.instanceFields(owner.getClass())) {
                if (field.getType() != RectF.class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value instanceof RectF rect
                            && rect.width() > 0f && rect.height() > 0f) {
                        return new RectF(rect);
                    }
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    /**
     * Kept for exact query-result symbol compatibility. Core result/confirm behavior remains owned
     * by the pre-17.60 inspector and app-side bridge controller.
     */
    static ResultSnapshot result(Object queryResult) {
        if (queryResult == null) return new ResultSnapshot(false, false, "queryResult=null");
        Object lensResult = GoogleReflection.readField(queryResult, "d", LENS_RESULT);
        if (lensResult == null) lensResult = firstFieldByType(queryResult, LENS_RESULT);
        if (lensResult == null) {
            return new ResultSnapshot(false, false,
                    "queryResult=" + queryResult.getClass().getName() + " lensResult=missing");
        }

        boolean complete = Boolean.TRUE.equals(GoogleReflection.invokeNoArg(lensResult, "f"));
        Object interaction = findUnwrappedByType(lensResult, INTERACTION_RESULT);
        Object interactionData = interaction == null
                ? null : findUnwrappedByType(interaction, INTERACTION_DATA);
        boolean presentation = false;
        int presentationCount = 0;
        if (interactionData != null) {
            Object list = GoogleReflection.readField(interactionData, "f", IMMUTABLE_LIST);
            if (list instanceof Iterable<?> iterable) {
                for (Object ignored : iterable) presentationCount++;
                presentation = presentationCount > 0;
            }
        }
        return new ResultSnapshot(complete, presentation,
                "profile=" + NAME
                        + " result=" + queryResult.getClass().getName()
                        + " lensResult=" + lensResult.getClass().getName()
                        + " interaction=" + className(interaction)
                        + " interactionData=" + className(interactionData)
                        + " presentationCount=" + presentationCount);
    }

    static boolean shouldSuppressTextViewportFocus(String requestClass, int source,
                                                    boolean hasBounds) {
        return VIEWPORT_REQUEST.equals(requestClass)
                && source == VIEWPORT_SOURCE_TEXT && hasBounds;
    }

    private static Object findUnwrappedByType(Object owner, String targetType) {
        if (owner == null || targetType == null) return null;
        for (Field field : GoogleReflection.instanceFields(owner.getClass())) {
            try {
                field.setAccessible(true);
                Object raw = field.get(owner);
                if (raw == null) continue;
                if (targetType.equals(raw.getClass().getName())) return raw;
                if (!OPTIONAL.equals(raw.getClass().getName())) continue;
                Object value = unwrapOptional(raw);
                if (value != null && targetType.equals(value.getClass().getName())) return value;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static Object unwrapOptional(Object optional) {
        if (optional == null) return null;
        Object present = GoogleReflection.invokeNoArg(optional, "g");
        if (present instanceof Boolean b && !b) return null;
        Object value = GoogleReflection.invokeNoArg(optional, "c");
        if (value == null) value = GoogleReflection.invokeNoArg(optional, "f");
        return value;
    }

    private static Object firstFieldByType(Object owner, String typeName) {
        if (owner == null) return null;
        return GoogleReflection.readField(owner, null, typeName);
    }

    private static boolean hasField(Class<?> type, String name, String typeName) {
        for (Field field : GoogleReflection.instanceFields(type)) {
            if (name.equals(field.getName()) && typeName.equals(field.getType().getName())) return true;
        }
        return false;
    }

    private static boolean hasNoArgMethod(Class<?> type, String name, String returnType) {
        for (Method method : GoogleReflection.declaredMethods(type)) {
            if (name.equals(method.getName()) && method.getParameterCount() == 0
                    && returnType.equals(method.getReturnType().getName())) return true;
        }
        return false;
    }

    private static RectF asRectF(Object value) {
        return value instanceof RectF r ? new RectF(r) : null;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : "";
    }

    private static Rect toScreenBounds(RectF raw, Context context) {
        if (raw == null || raw.width() <= 0f || raw.height() <= 0f) return null;
        RectF value = new RectF(raw);
        boolean normalized = value.left >= -0.05f && value.top >= -0.05f
                && value.right <= 1.05f && value.bottom <= 1.05f;
        if (normalized) {
            if (context == null) return null;
            try {
                DisplayMetrics dm = context.getResources().getDisplayMetrics();
                value.set(value.left * dm.widthPixels, value.top * dm.heightPixels,
                        value.right * dm.widthPixels, value.bottom * dm.heightPixels);
            } catch (Throwable ignored) { return null; }
        }
        Rect out = new Rect();
        value.roundOut(out);
        return out.isEmpty() ? null : out;
    }

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    static final class SelectionSnapshot {
        private final String text;
        private final Rect bounds;
        private final String selectionClass;
        private final String detail;

        SelectionSnapshot(String text, Rect bounds, String selectionClass, String detail) {
            this.text = text == null ? "" : text;
            this.bounds = bounds == null ? null : new Rect(bounds);
            this.selectionClass = selectionClass == null ? "" : selectionClass;
            this.detail = detail == null ? "" : detail;
        }

        String text() { return text; }
        Rect bounds() { return bounds == null ? null : new Rect(bounds); }
        String selectionClass() { return selectionClass; }
        String detail() { return detail; }
    }

    static final class ResultSnapshot {
        private final boolean complete;
        private final boolean presentationPresent;
        private final String detail;

        ResultSnapshot(boolean complete, boolean presentationPresent, String detail) {
            this.complete = complete;
            this.presentationPresent = presentationPresent;
            this.detail = detail == null ? "" : detail;
        }

        boolean complete() { return complete; }
        boolean presentationPresent() { return presentationPresent; }
        String detail() { return detail; }
    }

    private GoogleLens1760Profile() {}
}
