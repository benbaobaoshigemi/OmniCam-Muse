package local.jiege.hook.guard;

import android.system.Os;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Diagnostics;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * The camera without the module's /odm files: KernelSU 卸载模块 for the camera, enforced for every
 * app when Zygisk Next's denylist is on (the 10007 ad-block module turns it on at each boot).
 * The camera then reads its stock feature config and never builds the GR / POP mode UI; entering
 * those modes crashes or hangs (RetroCameraUIContainer.showUIInternal). ModuleEntry installs no
 * features then, and this guard keeps the camera out of GR / POP.
 */
public final class UnmountedGuard {
    private static final String TAG = "UnmountedGuard";
    /** Bind-mounted from /data by the KSU module, so it sits on another device than its directory. */
    private static final String FEATURE_CONFIG = "/odm/etc/camera/config/camera_unit_feature_config.protobuf";

    private static volatile Object modeKey;
    private static volatile boolean logged;

    private UnmountedGuard() {}

    public static boolean filesMounted() {
        try {
            return Os.stat(FEATURE_CONFIG).st_dev != Os.stat("/odm/etc/camera/config").st_dev;
        } catch (Exception error) {
            return false;
        }
    }

    /**
     * The gallery's palette and filters read the module's basictone and LUT files; with the stock
     * ones the palette renders garbage, so the gallery features stay off.
     */
    public static void installGallery() {
        Diagnostics.setFilesMounted(false);
        Log.i(TAG, "module files not mounted in the gallery process, features off");
    }

    public static void installCamera(ClassLoader classLoader) {
        Diagnostics.setFilesMounted(false);
        Log.i(TAG, "module files not mounted in the camera process, features off");
        Log.guard(TAG, "photo mode in place of GR / POP", () -> install(classLoader));
    }

    /**
     * Every read of the camera mode (also the early ones before the activity, which reopen the
     * remembered mode) answers the photo mode in place of GR / POP.
     */
    private static void install(ClassLoader classLoader) {
        Symbols symbols = Symbols.get();
        XC_MethodHook photoMode = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object mode = param.getResult();
                if (!"gr".equals(mode) && !"retroCamera".equals(mode)) return;
                try {
                    if (modeKey == null) modeKey = DataStore.key(symbols, "Keys.cameraMode", "pref_camera_mode_key");
                } catch (Throwable error) {
                    Log.e(TAG, "camera mode key unavailable", error);
                    return;
                }
                if (param.args[0] != modeKey) return;
                param.setResult("common");
                if (!logged) Log.i(TAG, "camera mode " + mode + " -> common");
                logged = true;
            }
        };
        XposedBridge.hookMethod(symbols.method("DataManager.get"), photoMode);
        XposedBridge.hookMethod(symbols.method("DataManager.getOr"), photoMode);
    }
}
