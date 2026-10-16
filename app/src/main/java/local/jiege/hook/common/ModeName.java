package local.jiege.hook.common;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The mode name of a camera mode instance ("common", "professional", "gr", ...). BaseMode takes
 * it as the first constructor argument; it is recorded there instead of read from an obfuscated
 * field.
 */
public final class ModeName {
    private static final Map<Object, String> names = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean installed;

    private ModeName() {}

    public static synchronized void install(ClassLoader classLoader) {
        if (installed) return;
        installed = true;
        XposedBridge.hookAllConstructors(XposedHelpers.findClass("com.oplus.camera.module.BaseMode", classLoader), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] instanceof String) names.put(param.thisObject, (String) param.args[0]);
            }
        });
    }

    /** The mode's name, or null for an object that is not a camera mode. */
    public static String of(Object mode) {
        return mode == null ? null : names.get(mode);
    }
}
