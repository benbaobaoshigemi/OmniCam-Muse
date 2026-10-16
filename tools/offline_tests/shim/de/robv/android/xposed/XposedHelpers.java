package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Offline-test stand-in for the parts of XposedHelpers the tested code calls (the api-82.jar
 * classes are compile-only stubs). Plain reflection with Xposed's matching rules for these cases:
 * fields by name up the class chain, methods by name and arguments, boxed arguments matching
 * primitive parameters.
 */
public final class XposedHelpers {
    private XposedHelpers() {}

    public static Object getObjectField(Object obj, String name) {
        try {
            return field(obj.getClass(), name).get(obj);
        } catch (IllegalAccessException error) {
            throw new IllegalAccessError(error.getMessage());
        }
    }

    public static Object callMethod(Object obj, String name, Object... args) {
        for (Class<?> type = obj.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || !matches(method.getParameterTypes(), args)) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(obj, args);
                } catch (ReflectiveOperationException error) {
                    throw new RuntimeException(error);
                }
            }
        }
        throw new NoSuchMethodError(obj.getClass().getName() + "#" + name);
    }

    // Hook installation is not exercised offline.
    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("offline");
    }

    public static Object getStaticObjectField(Class<?> clazz, String name) {
        throw new UnsupportedOperationException("offline");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String name, Object... args) {
        throw new UnsupportedOperationException("offline");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String name, Object... args) {
        throw new UnsupportedOperationException("offline");
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // up the chain
            }
        }
        throw new NoSuchFieldError(type.getName() + "#" + name);
    }

    private static boolean matches(Class<?>[] parameters, Object[] args) {
        if (parameters.length != args.length) return false;
        for (int i = 0; i < parameters.length; i++) {
            Class<?> parameter = box(parameters[i]);
            if (args[i] != null && !parameter.isInstance(args[i])) return false;
            if (args[i] == null && parameters[i].isPrimitive()) return false;
        }
        return true;
    }

    private static Class<?> box(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        return type;
    }
}
