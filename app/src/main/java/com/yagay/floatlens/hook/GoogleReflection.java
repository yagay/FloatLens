package com.yagay.floatlens.hook;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** One tolerant reflection layer for Google App's obfuscated runtime models. */
final class GoogleReflection {
    static Method[] declaredMethods(Class<?> type) {
        if (type == null) return new Method[0];
        try {
            return type.getDeclaredMethods();
        } catch (Throwable ignored) {
            ArrayList<Method> out = new ArrayList<>();
            try {
                for (Executable executable : HiddenApiBypass.getDeclaredMethods(type)) {
                    if (executable instanceof Method method) out.add(method);
                }
            } catch (Throwable ignoredAgain) { }
            return out.toArray(new Method[0]);
        }
    }

    static List<Method> methodsInHierarchy(Class<?> type) {
        ArrayList<Method> out = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : declaredMethods(current)) out.add(method);
        }
        return out;
    }

    static Field[] instanceFields(Class<?> type) {
        return instanceFieldsInHierarchy(type).toArray(new Field[0]);
    }

    static List<Field> instanceFieldsInHierarchy(Class<?> type) {
        ArrayList<Field> out = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                for (Field field : current.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) out.add(field);
                }
            } catch (Throwable ignored) { }
        }
        if (!out.isEmpty()) return out;
        if (type != null) {
            try {
                for (Field field : HiddenApiBypass.getInstanceFields(type)) out.add(field);
            } catch (Throwable ignored) { }
        }
        return out;
    }

    static Object readField(Object target, String name, String typeName) {
        if (target == null) return null;
        for (Field field : instanceFieldsInHierarchy(target.getClass())) {
            if (name != null && !name.equals(field.getName())) continue;
            if (typeName != null && !typeName.equals(field.getType().getName())) continue;
            try {
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    static Object readNamedField(Object target, String name) {
        return readField(target, name, null);
    }

    static Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null) return null;
        for (Method method : methodsInHierarchy(target.getClass())) {
            if (!methodName.equals(method.getName()) || method.getParameterCount() != 0) continue;
            try {
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Throwable ignored) { }
        }
        try {
            return HiddenApiBypass.invoke(target.getClass(), target, methodName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private GoogleReflection() {}
}
