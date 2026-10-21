package local.jiege.hook.common;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Lookups into the obfuscated camera and gallery code that do not rely on member names. */
public final class Reflection {
    private static final String DATA_KEY = "com.oplus.camera.data.DataKey";

    // Instance String fields per class; the DataManager read hooks match every key read against names.
    private static final Map<Class<?>, Field[]> stringFields = Collections.synchronizedMap(new IdentityHashMap<>());

    private Reflection() {}

    private static Field[] stringFields(Class<?> cls) {
        Field[] cached = stringFields.get(cls);
        if (cached != null) return cached;
        List<Field> found = new ArrayList<>();
        for (Class<?> type = cls; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() == String.class && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    found.add(field);
                }
            }
        }
        cached = found.toArray(new Field[0]);
        stringFields.put(cls, cached);
        return cached;
    }

    /** True if any instance String field of {@code object} (or a superclass) equals {@code value}. */
    public static boolean hasStringField(Object object, String value) throws IllegalAccessException {
        for (Field field : stringFields(object.getClass())) {
            if (value.equals(field.get(object))) return true;
        }
        return false;
    }

    /**
     * Finds the camera's DataKey constant for a preference name. The key holders (m9.d, m9.e, ...)
     * are obfuscated, but each DataKey keeps its preference name in a String field.
     */
    public static Object findDataKey(Class<?> holder, String preferenceName) throws IllegalAccessException {
        for (Field field : holder.getDeclaredFields()) {
            // Holders also keep feature keys (com.oplus.ocs...FeatureKey) under the same names.
            if (!Modifier.isStatic(field.getModifiers()) || !DATA_KEY.equals(field.getType().getName())) continue;
            field.setAccessible(true);
            Object key = field.get(null);
            if (key != null && hasStringField(key, preferenceName)) return key;
        }
        throw new IllegalStateException("DataKey not found: " + preferenceName);
    }

    /** The feature key constant (a static field of {@code keyType} in {@code holder}) whose name is {@code name}. */
    public static Object findFeatureKey(Class<?> holder, Class<?> keyType, String name) throws IllegalAccessException {
        for (Field field : holder.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != keyType) continue;
            field.setAccessible(true);
            Object key = field.get(null);
            if (key != null && hasStringField(key, name)) return key;
        }
        throw new IllegalStateException("feature key not found: " + name);
    }

    /** First non-null instance field whose declared type has the given class name. */
    public static Object findFieldByType(Object object, String typeName) throws IllegalAccessException {
        if (object == null) return null;
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !typeName.equals(field.getType().getName())) continue;
                field.setAccessible(true);
                Object value = field.get(object);
                if (value != null) return value;
            }
        }
        return null;
    }
}
