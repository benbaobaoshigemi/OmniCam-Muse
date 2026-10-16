package local.jiege.hook.common;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;

/**
 * The camera's own log never reaches logcat on this system (its lines are dropped below
 * android.util.Log, or go to the private channel CameraUnitUtils.printPrivateLog), so the exported
 * log has none of it. Lines of the camera classes the module works with are copied into the
 * module log, which the export carries.
 */
public final class CameraLogMirror {
    private static final String TAG = "CameraLog";
    private static final String[] TAGS = {
        "OCAM_PresetDataRepository", "OCAM_EffectDataManager", "OCAM_EffectPreset", "OCAM_PresetDataUtil",
    };

    private CameraLogMirror() {}

    public static void install(ClassLoader classLoader) {
        // Log.println_native(bufID, priority, tag, msg): every android.util.Log call ends here.
        XposedBridge.hookMethod(XposedHelpers.findMethodExact(android.util.Log.class, "println_native",
            int.class, int.class, String.class, String.class), mirror(2, 3));
        Class<?> utils = XposedHelpers.findClassIfExists("com.oplus.ocs.camera.appinterface.adapter.CameraUnitUtils", classLoader);
        if (utils == null) return;
        for (Method method : utils.getDeclaredMethods()) {
            if ("printPrivateLog".equals(method.getName())) XposedBridge.hookMethod(method, mirror(0, 1));
        }
    }

    private static XC_MethodHook mirror(final int tagArg, final int messageArg) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object tag = param.args[tagArg];
                if (!(tag instanceof String) || !mirrored((String) tag)) return;
                Log.i(TAG, tag + " " + param.args[messageArg]);
            }
        };
    }

    private static boolean mirrored(String tag) {
        for (String prefix : TAGS) if (tag.startsWith(prefix)) return true;
        return false;
    }
}
