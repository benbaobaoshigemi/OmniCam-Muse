package local.jiege.hook.common;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Hooks a method that may be absent on some builds; absence is not an error. */
public final class OptionalHook {
    private OptionalHook() {}

    public static void method(Class<?> type, String name, XC_MethodHook hook, Class<?>... parameterTypes) {
        if (type == null) return;
        Object[] arguments = new Object[parameterTypes.length + 1];
        System.arraycopy(parameterTypes, 0, arguments, 0, parameterTypes.length);
        arguments[parameterTypes.length] = hook;
        try {
            XposedHelpers.findAndHookMethod(type, name, arguments);
        } catch (Throwable ignored) {
        }
    }
}
