package local.jiege.hook.common;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;

/**
 * Non-virtual ("super") calls into another class's implementation.
 *
 * XposedBridge.invokeOriginalMethod on a hooked method calls that method's backup directly, so it
 * does not dispatch virtually. Hooking the parent implementation with an empty hook therefore
 * lets a child override delegate to exactly that implementation. {@link #verify()} checks this
 * behaviour once before any camera hook relies on it.
 */
public final class SuperCall {
    private SuperCall() {}

    public static class ProbeParent {
        public String value() {
            return "parent";
        }
    }

    public static final class ProbeChild extends ProbeParent {
        @Override public String value() {
            return "child";
        }
    }

    public static void verify() throws Throwable {
        Method parent = ProbeParent.class.getDeclaredMethod("value");
        XC_MethodHook.Unhook unhook = XposedBridge.hookMethod(parent, new XC_MethodHook() {});
        Object result = XposedBridge.invokeOriginalMethod(parent, new ProbeChild(), new Object[0]);
        unhook.unhook();
        if (!"parent".equals(result)) throw new IllegalStateException("Non-virtual superclass invocation unavailable");
    }

    /** Returns a method whose invokeOriginalMethod calls run exactly this implementation. */
    public static Method pin(Class<?> owner, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        XposedBridge.hookMethod(method, new XC_MethodHook() {});
        return method;
    }

    /** Returns a method whose invokeOriginalMethod calls run exactly this implementation. */
    public static Method pin(Method method) {
        method.setAccessible(true);
        XposedBridge.hookMethod(method, new XC_MethodHook() {});
        return method;
    }

    /** Makes {@code child} behave as {@code donor} (same signature, a superclass) on the same instance. */
    public static void inherit(Method child, Method donor) {
        final Method donorMethod = pin(donor);
        XposedBridge.hookMethod(child, new XC_MethodReplacement() {
            @Override protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                return XposedBridge.invokeOriginalMethod(donorMethod, param.thisObject, param.args);
            }
        });
    }

    /** Makes {@code child.name(...)} behave as {@code donor.name(...)} on the same instance. */
    public static void inherit(Class<?> child, Class<?> donor, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        final Method donorMethod = pin(donor, name, parameterTypes);
        XposedBridge.hookMethod(child.getDeclaredMethod(name, parameterTypes), new XC_MethodReplacement() {
            @Override protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                return XposedBridge.invokeOriginalMethod(donorMethod, param.thisObject, param.args);
            }
        });
    }
}
