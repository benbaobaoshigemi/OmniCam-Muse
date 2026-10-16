package local.jiege.hook.common;

import de.robv.android.xposed.XposedHelpers;

/**
 * The camera's settings store (com.oplus.camera.data.DataManager). Its accessors are symbols
 * (DataManager.get / getOr / put / putSync); keys are found by the preference name they carry.
 */
public final class DataStore {
    private DataStore() {}

    public static Object manager(ClassLoader loader) {
        return XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.oplus.camera.data.DataManager", loader), "getInstance");
    }

    /** DataManager.getOr(key, fallback); the store dereferences the fallback, so it must not be null. */
    public static Object get(Symbols symbols, ClassLoader loader, Object key, Object fallback) throws Throwable {
        return symbols.call("DataManager.getOr", manager(loader), key, fallback);
    }

    public static void put(Symbols symbols, ClassLoader loader, Object key, Object value) throws Throwable {
        symbols.call("DataManager.put", manager(loader), key, value);
    }

    /**
     * The DataKey constant for a preference name, from the key holder classes of a "Keys.*"
     * symbol (every class that defines the preference name and holds DataKey constants).
     */
    public static Object key(Symbols symbols, String holders, String preferenceName) throws IllegalAccessException {
        for (Class<?> holder : symbols.<Class<?>>all(holders)) {
            try {
                return Reflection.findDataKey(holder, preferenceName);
            } catch (IllegalStateException notHere) {
                // next holder
            }
        }
        throw new IllegalStateException("DataKey not found: " + preferenceName);
    }
}
