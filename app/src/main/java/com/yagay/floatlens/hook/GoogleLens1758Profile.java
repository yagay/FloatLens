package com.yagay.floatlens.hook;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Versioned structural adapter for Google App 17.58.16.ve Lens internals.
 *
 * <p>Keep every obfuscated Google symbol in this one file. Runtime hooks should consume semantic
 * snapshots instead of reaching into obfuscated fields directly. The profile is intentionally
 * fail-soft: a missing field after a Google update produces diagnostics/fallback data instead of
 * crashing the Google process.</p>
 */
final class GoogleLens1758Profile {
    static final String NAME = "17.58.16.ve";
    static final String CONTROLLER = "dscu";          // LensUiController
    static final String SELECTION_METADATA = "dscl";  // SelectionWithMetadata
    static final String USER_SELECTION = "dtlp";      // UserSelection interface
    static final String PENDING_QUERY = "dtqj";       // PendingLensQuery
    static final String QUERY_RESULT = "dtqi";        // LensQueryResult
    static final String LENS_IMAGE = "esel";          // LensImage
    static final String LENS_RESULT = "esfe";         // LensResult
    static final String IMAGE_RESULT = "eseo";        // LensImageResult
    static final String INTERACTION_RESULT = "eseu";  // LensInteractionResult
    static final String INTERACTION_DATA = "eses";    // InteractionDataResult
    static final String OPTIONAL = "fxsy";            // Google/Guava optional wrapper

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
                return "LensUiController methods mismatch selection=" + selection
                        + " pending=" + pending + " result=" + result;
            }

            Class<?> selectionMetadata = Class.forName(SELECTION_METADATA, false, loader);
            if (!hasField(selectionMetadata, "a", USER_SELECTION)) {
                return "SelectionWithMetadata.a UserSelection missing";
            }

            Class<?> userSelection = Class.forName(USER_SELECTION, false, loader);
            if (!hasNoArgMethod(userSelection, "b", RectF.class.getName())
                    || !hasNoArgMethod(userSelection, "k", String.class.getName())) {
                return "UserSelection semantic methods mismatch";
            }

            Class<?> pendingQuery = Class.forName(PENDING_QUERY, false, loader);
            if (!hasField(pendingQuery, "d", LENS_IMAGE)) {
                return "PendingLensQuery.d LensImage missing";
            }

            Class<?> queryResult = Class.forName(QUERY_RESULT, false, loader);
            if (!hasField(queryResult, "d", LENS_RESULT)
                    || !hasField(queryResult, "f", LENS_IMAGE)) {
                return "LensQueryResult payload fields mismatch";
            }

            Class<?> lensImage = Class.forName(LENS_IMAGE, false, loader);
            if (!hasField(lensImage, "b", Bitmap.class.getName())) {
                return "LensImage.b Bitmap missing";
            }

            Class<?> lensResult = Class.forName(LENS_RESULT, false, loader);
            if (!hasField(lensResult, "b", IMAGE_RESULT)
                    || !hasField(lensResult, "c", OPTIONAL)
                    || !hasField(lensResult, "d", OPTIONAL)
                    || !hasNoArgMethod(lensResult, "f", boolean.class.getName())) {
                return "LensResult structure mismatch";
            }

            Class<?> imageResult = Class.forName(IMAGE_RESULT, false, loader);
            if (!hasField(imageResult, "c", boolean.class.getName())) {
                return "LensImageResult.c isComplete missing";
            }

            Class<?> interactionResult = Class.forName(INTERACTION_RESULT, false, loader);
            if (!hasField(interactionResult, "a", OPTIONAL)
                    || !hasField(interactionResult, "c", OPTIONAL)
                    || !hasField(interactionResult, "d", boolean.class.getName())) {
                return "LensInteractionResult structure mismatch";
            }

            Class<?> interactionData = Class.forName(INTERACTION_DATA, false, loader);
            if (!hasField(interactionData, "b", OPTIONAL)) {
                return "InteractionDataResult.b selectedText missing";
            }

            Class<?> optional = Class.forName(OPTIONAL, false, loader);
            if (!hasNoArgMethod(optional, "g", boolean.class.getName())
                    || !hasNoArgMethod(optional, "c", Object.class.getName())
                    || !hasNoArgMethod(optional, "f", Object.class.getName())) {
                return "Google Optional methods mismatch";
            }
            return "";
        } catch (Throwable t) {
            String message = t.getMessage();
            return t.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? "" : ":" + message);
        }
    }

    private static boolean hasField(Class<?> cls, String name, String typeName) {
        if (cls == null || name == null || typeName == null) return false;
        for (Field field : instanceFields(cls)) {
            if (name.equals(field.getName()) && typeName.equals(field.getType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNoArgMethod(Class<?> cls, String name, String returnTypeName) {
        if (cls == null || name == null || returnTypeName == null) return false;
        for (Method method : methodsOf(cls)) {
            if (name.equals(method.getName())
                    && method.getParameterCount() == 0
                    && returnTypeName.equals(method.getReturnType().getName())) {
                return true;
            }
        }
        return false;
    }

    static boolean isSelectionMethod(Method method) {
        if (method == null || !"y".equals(method.getName())) return false;
        Class<?>[] p = method.getParameterTypes();
        return p.length == 2 && SELECTION_METADATA.equals(p[0].getName())
                && p[1] == boolean.class;
    }

    static boolean isPendingQueryMethod(Method method) {
        if (method == null || !"p".equals(method.getName())) return false;
        Class<?>[] p = method.getParameterTypes();
        return p.length == 1 && PENDING_QUERY.equals(p[0].getName());
    }

    static boolean isQueryResultMethod(Method method) {
        if (method == null || !"q".equals(method.getName())) return false;
        Class<?>[] p = method.getParameterTypes();
        return p.length == 1 && QUERY_RESULT.equals(p[0].getName())
                && method.getReturnType() == void.class;
    }

    static SelectionSnapshot selection(Object metadata) {
        if (metadata == null) {
            return new SelectionSnapshot("", null, null, "metadata=null");
        }

        Object userSelection = readField(metadata, "a", USER_SELECTION);
        if (userSelection == null) userSelection = firstFieldByType(metadata, USER_SELECTION);
        if (userSelection == null) {
            String raw = compact(metadata, 4200);
            String text = selectedTextFromString(raw);
            RectF rawBounds = selectionBoundsFromString(raw);
            return new SelectionSnapshot(text, absoluteRect(rawBounds), rawBounds,
                    "metadataClass=" + metadata.getClass().getName()
                            + " userSelection=unavailable raw=" + raw);
        }

        String text = asString(invokeNoArg(userSelection, "k"));
        // 17.58 WordSelection(dtlr) keeps TextSelection(dtvz) in field a. Keep this fallback
        // because some UserSelection implementations return an empty generic k() string.
        if (text.isBlank() && "dtlr".equals(userSelection.getClass().getName())) {
            Object textSelection = readField(userSelection, "a", "dtvz");
            Object selectedText = readField(textSelection, "a", String.class.getName());
            text = asString(selectedText);
        }

        String rawSelection = compact(userSelection, 4200);
        if (text.isBlank()) text = selectedTextFromString(rawSelection);

        Object region = invokeNoArg(userSelection, "b"); // UserSelection.b() -> RectF
        RectF rawBounds = asRectF(region);
        if (rawBounds == null) rawBounds = firstRectF(userSelection, metadata);
        if (rawBounds == null) rawBounds = selectionBoundsFromString(rawSelection);
        Rect bounds = absoluteRect(rawBounds);

        Object point = invokeNoArg(userSelection, "a");  // UserSelection.a() -> PointF
        Object gesture = invokeNoArg(userSelection, "c");
        Object drawing = invokeNoArg(userSelection, "d");

        StringBuilder detail = new StringBuilder();
        detail.append("metadataClass=").append(metadata.getClass().getName())
                .append(" userSelectionClass=").append(userSelection.getClass().getName());
        if (!text.isBlank()) detail.append(" selectedText=").append(quote(text, 1800));
        if (bounds != null) detail.append(" bounds=").append(bounds.toShortString());
        else if (rawBounds != null) detail.append(" normalizedBounds=").append(rawBounds);
        if (point instanceof PointF p) detail.append(" point=").append(p.x).append(",").append(p.y);
        if (gesture != null) detail.append(" gesture=").append(compact(gesture, 500));
        if (drawing != null) detail.append(" drawing=").append(compact(drawing, 700));
        detail.append(" selection=").append(rawSelection);
        return new SelectionSnapshot(text, bounds, rawBounds, detail.toString());
    }

    static PendingSnapshot pending(Object pending) {
        if (pending == null) return new PendingSnapshot(null, "pending=null");
        Object lensImage = readField(pending, "d", LENS_IMAGE);
        if (lensImage == null) lensImage = firstFieldByType(pending, LENS_IMAGE);
        Bitmap frame = bitmapFromLensImage(lensImage);

        Object query = readField(pending, "c", "esew");
        String detail = "pendingClass=" + pending.getClass().getName()
                + " frame=" + bitmapSummary(frame)
                + " lensImage=" + compact(lensImage, 1800)
                + " query=" + compact(query, 1800)
                + " raw=" + compact(pending, 3000);
        return new PendingSnapshot(frame, detail);
    }

    static PresentationRequestSuppression suppressTextPresentationRequest(Object pending) {
        if (pending == null) {
            return new PresentationRequestSuppression(false, "pending=null");
        }

        Object query = readField(pending, "c", "esew");
        if (query == null) {
            return new PresentationRequestSuppression(false,
                    "LensQuery unavailable pendingClass=" + pending.getClass().getName());
        }

        Object interaction = findPresentationRequestObject(
                query, 0,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        if (interaction == null) {
            return new PresentationRequestSuppression(false,
                    "requestPresentationResult object not found query="
                            + compact(query, 2200));
        }

        String before = compact(interaction, 3200);
        for (Field field : instanceFields(interaction.getClass())) {
            if (field.getType() != boolean.class) continue;
            try {
                field.setAccessible(true);
                boolean original = field.getBoolean(interaction);
                if (!original) continue;

                field.setBoolean(interaction, false);
                String changed = compact(interaction, 3200);
                if (changed.contains("requestPresentationResult=false")) {
                    return new PresentationRequestSuppression(true,
                            "class=" + interaction.getClass().getName()
                                    + " field=" + field.getName()
                                    + " before=" + trimForDiagnostic(before, 1400)
                                    + " after=" + trimForDiagnostic(changed, 1400));
                }

                // This true boolean was unrelated to requestPresentationResult.
                field.setBoolean(interaction, true);
            } catch (Throwable ignored) {
            }
        }

        return new PresentationRequestSuppression(false,
                "candidate=" + interaction.getClass().getName()
                        + " but target boolean could not be changed"
                        + " raw=" + trimForDiagnostic(before, 1800));
    }

    private static Object findPresentationRequestObject(Object value, int depth,
                                                        Set<Object> seen) {
        if (value == null || depth > 6 || seen == null || !seen.add(value)) return null;

        Class<?> type = value.getClass();
        if (type.isPrimitive() || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Character
                || value instanceof Bitmap || value instanceof Rect || value instanceof RectF
                || value instanceof PointF) {
            return null;
        }

        String raw = compact(value, 3400);
        boolean looksLikeInteraction = raw.contains("requestPresentationResult=true")
                && (raw.contains("selectionType=WORD_BOXES")
                || raw.contains("textSelection=Optional.of"));
        if (looksLikeInteraction) {
            // LensQuery itself contains the nested interaction in its toString. Prefer the
            // deepest object that directly owns the boolean; verify by probing its own fields.
            for (Field field : instanceFields(type)) {
                if (field.getType() == boolean.class) {
                    try {
                        field.setAccessible(true);
                        if (field.getBoolean(value)) return value;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        for (Field field : instanceFields(type)) {
            Class<?> ft = field.getType();
            if (ft.isPrimitive() || ft == String.class) continue;
            try {
                field.setAccessible(true);
                Object child = field.get(value);
                if (child == null || child == value) continue;

                if (hasTypeInHierarchy(child.getClass(), OPTIONAL)) {
                    child = unwrapOptional(child);
                    if (child == null) continue;
                }

                String childName = child.getClass().getName();
                if (childName.startsWith("android.")
                        || childName.startsWith("java.")
                        || childName.startsWith("kotlin.")) {
                    continue;
                }

                Object found = findPresentationRequestObject(child, depth + 1, seen);
                if (found != null) return found;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String trimForDiagnostic(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    static ResultSnapshot result(Object queryResult) {
        if (queryResult == null) {
            return new ResultSnapshot(null, "", false, false, false, false,
                    "queryResult=null");
        }

        Object lensImage = readField(queryResult, "f", LENS_IMAGE);
        if (lensImage == null) lensImage = firstFieldByType(queryResult, LENS_IMAGE);
        Bitmap frame = bitmapFromLensImage(lensImage);

        Object lensResult = readField(queryResult, "d", LENS_RESULT);
        if (lensResult == null) lensResult = firstFieldByType(queryResult, LENS_RESULT);

        Object imageResult = readField(lensResult, "b", IMAGE_RESULT);
        if (imageResult == null) imageResult = firstFieldByType(lensResult, IMAGE_RESULT);
        boolean imageComplete = readBoolean(imageResult, "c", false);

        Object interactionOptional = readField(lensResult, "c", OPTIONAL);
        boolean interactionPresent = optionalPresent(interactionOptional);
        Object interaction = unwrapOptional(interactionOptional);
        boolean interactionComplete = interaction == null
                || readBoolean(interaction, "d", false);

        // Google 17.58 LensInteractionResult.c is presentationResult. In the observed CTS
        // flow it becomes present only when LensResultPanelResponse (the Google search panel)
        // is ready. This is the stable boundary FloatLens can intercept before UI presentation.
        Object presentationOptional = readField(interaction, "c", OPTIONAL);
        boolean presentationPresent = optionalPresent(presentationOptional);
        Object presentation = unwrapOptional(presentationOptional);

        Object contentOptional = readField(lensResult, "d", OPTIONAL);
        boolean contentPresent = optionalPresent(contentOptional);

        String selectedText = selectedText(interaction);
        Boolean googleComplete = invokeBooleanNoArg(lensResult, "f");
        boolean complete = googleComplete != null
                ? googleComplete
                : isCompleteState(imageComplete, interactionPresent, interactionComplete);

        String detail = "queryResultClass=" + queryResult.getClass().getName()
                + " frame=" + bitmapSummary(frame)
                + " imageComplete=" + imageComplete
                + " interactionPresent=" + interactionPresent
                + " interactionComplete=" + interactionComplete
                + " presentationPresent=" + presentationPresent
                + " contentPresent=" + contentPresent
                + " googleComplete=" + String.valueOf(googleComplete)
                + " selectedTextLen=" + selectedText.length()
                + " presentation=" + compact(presentation, 2200)
                + " lensResult=" + compact(lensResult, 3000)
                + " raw=" + compact(queryResult, 3000);
        return new ResultSnapshot(frame, selectedText, complete,
                imageComplete, interactionPresent, presentationPresent, detail);
    }

    /**
     * 17.58 publishes LensImageResult and LensInteractionResult independently. A result is stable
     * when image processing is complete and any present interaction result is also complete.
     */
    static boolean isCompleteState(boolean imageComplete,
                                   boolean interactionPresent,
                                   boolean interactionComplete) {
        return imageComplete && (!interactionPresent || interactionComplete);
    }

    static boolean isPresentationBoundary(boolean complete,
                                          boolean interactionPresent,
                                          boolean presentationPresent) {
        return complete && interactionPresent && presentationPresent;
    }

    private static String selectedText(Object interaction) {
        if (interaction == null || !INTERACTION_RESULT.equals(interaction.getClass().getName())) {
            return "";
        }
        Object dataOptional = readField(interaction, "a", OPTIONAL);
        Object data = unwrapOptional(dataOptional);
        if (data == null || !INTERACTION_DATA.equals(data.getClass().getName())) return "";

        Object selectedOptional = readField(data, "b", OPTIONAL);
        Object selected = unwrapOptional(selectedOptional);
        if (selected instanceof String s) return s.trim();
        String value = firstString(selected);
        return value == null ? "" : value.trim();
    }

    private static Bitmap bitmapFromLensImage(Object lensImage) {
        if (lensImage == null) return null;
        Object exact = readField(lensImage, "b", Bitmap.class.getName());
        if (exact instanceof Bitmap bitmap && !bitmap.isRecycled()) return bitmap;
        Object any = firstFieldByType(lensImage, Bitmap.class.getName());
        return any instanceof Bitmap bitmap && !bitmap.isRecycled() ? bitmap : null;
    }

    /**
     * Google Lens model classes are ordinary app classes. Standard reflection is both simpler and
     * more reliable here than using HiddenApiBypass for field values. Keep HiddenApiBypass only as
     * a fallback for unusual runtime implementations.
     */
    private static Object readField(Object target, String name, String typeName) {
        if (target == null) return null;
        for (Field field : instanceFields(target.getClass())) {
            if (name != null && !name.equals(field.getName())) continue;
            if (typeName != null && !typeName.equals(field.getType().getName())) continue;
            try {
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object firstFieldByType(Object target, String typeName) {
        return readField(target, null, typeName);
    }

    private static boolean readBoolean(Object target, String name, boolean fallback) {
        Object value = readField(target, name, boolean.class.getName());
        return value instanceof Boolean b ? b : fallback;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null) return null;
        for (Method method : methodsOf(target.getClass())) {
            if (!methodName.equals(method.getName()) || method.getParameterCount() != 0) continue;
            try {
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Throwable ignored) {
            }
        }
        try {
            return HiddenApiBypass.invoke(target.getClass(), target, methodName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<Field> instanceFields(Class<?> cls) {
        ArrayList<Field> out = new ArrayList<>();
        for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
            try {
                for (Field field : current.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) out.add(field);
                }
            } catch (Throwable ignored) {
            }
        }
        if (!out.isEmpty()) return out;
        try {
            for (Field field : HiddenApiBypass.getInstanceFields(cls)) out.add(field);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static List<Method> methodsOf(Class<?> cls) {
        ArrayList<Method> out = new ArrayList<>();
        for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
            try {
                for (Method method : current.getDeclaredMethods()) out.add(method);
            } catch (Throwable ignored) {
            }
        }
        if (!out.isEmpty()) return out;
        try {
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(cls)) {
                if (executable instanceof Method method) out.add(method);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static Boolean invokeBooleanNoArg(Object target, String methodName) {
        Object value = invokeNoArg(target, methodName);
        return value instanceof Boolean b ? b : null;
    }

    private static boolean optionalPresent(Object optional) {
        if (optional == null || !hasTypeInHierarchy(optional.getClass(), OPTIONAL)) return false;
        Object value = invokeNoArg(optional, "g");
        return value instanceof Boolean b && b;
    }

    private static Object unwrapOptional(Object optional) {
        if (!optionalPresent(optional)) return null;
        Object value = invokeNoArg(optional, "c");
        if (value != null) return value;
        return invokeNoArg(optional, "f");
    }

    /**
     * Google/Guava Optional is declared as fxsy but runtime instances are concrete subclasses
     * (for example fxtg). Never compare getClass().getName() directly with fxsy.
     */
    static boolean hasTypeInHierarchy(Class<?> type, String expectedName) {
        if (type == null || expectedName == null || expectedName.isBlank()) return false;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (expectedName.equals(current.getName())) return true;
            for (Class<?> iface : current.getInterfaces()) {
                if (hasTypeInHierarchy(iface, expectedName)) return true;
            }
        }
        return false;
    }

    private static String firstString(Object target) {
        if (target == null) return null;
        if (target instanceof String s) return s;
        Object value = firstFieldByType(target, String.class.getName());
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static RectF firstRectF(Object... targets) {
        if (targets == null) return null;
        for (Object target : targets) {
            if (target == null) continue;
            RectF direct = asRectF(target);
            if (direct != null) return direct;
            Object rect = firstFieldByType(target, Rect.class.getName());
            direct = asRectF(rect);
            if (direct != null) return direct;
            Object rectF = firstFieldByType(target, RectF.class.getName());
            direct = asRectF(rectF);
            if (direct != null) return direct;
        }
        return null;
    }

    private static RectF asRectF(Object value) {
        if (value instanceof Rect rect && !rect.isEmpty()) return new RectF(rect);
        if (value instanceof RectF rectF && rectF.width() > 0f && rectF.height() > 0f) {
            return new RectF(rectF);
        }
        return null;
    }

    private static Rect absoluteRect(RectF rectF) {
        if (rectF == null || looksNormalized(rectF)) return null;
        Rect out = new Rect();
        rectF.roundOut(out);
        return out.isEmpty() ? null : out;
    }

    private static boolean looksNormalized(RectF rect) {
        return rect != null
                && rect.left >= -0.05f && rect.top >= -0.05f
                && rect.right <= 1.05f && rect.bottom <= 1.05f;
    }

    static String selectedTextFromString(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String marker = "selectedText=";
        int start = raw.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = raw.indexOf(", wordBoxes=", start);
        if (end < 0) end = raw.indexOf(", selectionRange=", start);
        if (end < 0) end = raw.indexOf(')', start);
        if (end < 0 || end <= start) return "";
        return raw.substring(start, end).trim();
    }

    private static RectF selectionBoundsFromString(String raw) {
        if (raw == null || raw.isBlank()) return null;
        RectF rect = parseRectF(raw, "rect=RectF(");
        if (rect != null) return rect;

        Float minX = parseNamedFloat(raw, "minX=");
        Float minY = parseNamedFloat(raw, "minY=");
        Float maxX = parseNamedFloat(raw, "maxX=");
        Float maxY = parseNamedFloat(raw, "maxY=");
        if (minX == null || minY == null || maxX == null || maxY == null
                || maxX <= minX || maxY <= minY) return null;
        return new RectF(minX, minY, maxX, maxY);
    }

    private static RectF parseRectF(String raw, String marker) {
        int start = raw.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = raw.indexOf(')', start);
        if (end < 0) return null;
        String[] parts = raw.substring(start, end).split(",");
        if (parts.length != 4) return null;
        try {
            float left = Float.parseFloat(parts[0].trim());
            float top = Float.parseFloat(parts[1].trim());
            float right = Float.parseFloat(parts[2].trim());
            float bottom = Float.parseFloat(parts[3].trim());
            if (right <= left || bottom <= top) return null;
            return new RectF(left, top, right, bottom);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Float parseNamedFloat(String raw, String marker) {
        int start = raw.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = start;
        while (end < raw.length()) {
            char ch = raw.charAt(end);
            if ((ch >= '0' && ch <= '9') || ch == '-' || ch == '+' || ch == '.'
                    || ch == 'e' || ch == 'E') {
                end++;
            } else {
                break;
            }
        }
        if (end <= start) return null;
        try {
            return Float.parseFloat(raw.substring(start, end));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String asString(Object value) {
        return value instanceof String s ? s.trim() : "";
    }

    private static String compact(Object value, int max) {
        if (value == null) return "null";
        String out;
        try {
            out = value.getClass().getName() + "{" + String.valueOf(value) + "}";
        } catch (Throwable ignored) {
            out = value.getClass().getName();
        }
        out = out.replace("\n", " ").replace("\r", " ").replace("\u0000", "?");
        return out.length() <= max ? out : out.substring(0, max) + "…";
    }

    private static String quote(String value, int max) {
        String out = value == null ? "" : value.replace("\n", " ").replace("\r", " ");
        if (out.length() > max) out = out.substring(0, max) + "…";
        return "\"" + out + "\"";
    }

    private static String bitmapSummary(Bitmap bitmap) {
        return bitmap == null ? "null"
                : bitmap.getWidth() + "x" + bitmap.getHeight() + "/" + bitmap.getConfig();
    }

    static final class SelectionSnapshot {
        private final String text;
        private final Rect bounds;
        private final RectF rawBounds;
        private final String detail;

        SelectionSnapshot(String text, Rect bounds, RectF rawBounds, String detail) {
            this.text = text == null ? "" : text;
            this.bounds = bounds == null ? null : new Rect(bounds);
            this.rawBounds = rawBounds == null ? null : new RectF(rawBounds);
            this.detail = detail == null ? "" : detail;
        }

        String text() { return text; }

        Rect bounds() { return bounds == null ? null : new Rect(bounds); }

        Rect boundsForFrame(int width, int height) {
            if (bounds != null) return new Rect(bounds);
            if (rawBounds == null || !looksNormalized(rawBounds)
                    || width <= 0 || height <= 0) return null;
            RectF scaled = new RectF(
                    rawBounds.left * width,
                    rawBounds.top * height,
                    rawBounds.right * width,
                    rawBounds.bottom * height);
            Rect out = new Rect();
            scaled.roundOut(out);
            return out.isEmpty() ? null : out;
        }

        String detail() { return detail; }
    }

    static final class PresentationRequestSuppression {
        private final boolean suppressed;
        private final String detail;

        PresentationRequestSuppression(boolean suppressed, String detail) {
            this.suppressed = suppressed;
            this.detail = detail == null ? "" : detail;
        }

        boolean suppressed() { return suppressed; }
        String detail() { return detail; }
    }

    static final class PendingSnapshot {
        private final Bitmap frame;
        private final String detail;

        PendingSnapshot(Bitmap frame, String detail) {
            this.frame = frame;
            this.detail = detail == null ? "" : detail;
        }

        Bitmap frame() { return frame; }
        String detail() { return detail; }
    }

    static final class ResultSnapshot {
        private final Bitmap frame;
        private final String text;
        private final boolean complete;
        private final boolean imageComplete;
        private final boolean interactionPresent;
        private final boolean presentationPresent;
        private final String detail;

        ResultSnapshot(Bitmap frame, String text, boolean complete,
                       boolean imageComplete, boolean interactionPresent,
                       boolean presentationPresent, String detail) {
            this.frame = frame;
            this.text = text == null ? "" : text;
            this.complete = complete;
            this.imageComplete = imageComplete;
            this.interactionPresent = interactionPresent;
            this.presentationPresent = presentationPresent;
            this.detail = detail == null ? "" : detail;
        }

        Bitmap frame() { return frame; }
        String text() { return text; }
        boolean complete() { return complete; }
        boolean imageComplete() { return imageComplete; }
        boolean interactionPresent() { return interactionPresent; }
        boolean presentationPresent() { return presentationPresent; }
        String detail() { return detail; }
    }

    private GoogleLens1758Profile() {}
}
