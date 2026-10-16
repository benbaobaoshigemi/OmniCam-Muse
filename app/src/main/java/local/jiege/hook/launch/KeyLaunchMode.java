package local.jiege.hook.launch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModuleSettings;

/**
 * Quick launch key mode: the key opens the camera in the chosen mode instead of the remembered one.
 *
 * The key starts the camera with android_camera_launch_type spruce_double_tap_launch_from_framework
 * (and android_spruce_key_launch_type), through CameraImageActivity on to Camera. CameraEntry opens
 * the mode an int "mode" extra names, mapped by CameraInfoCursor, whose PMA110 table lacks the
 * master and GR modes. The key's intent gets a "mode" no table uses, which the mapping turns into
 * the chosen mode name.
 *
 * The camera remembers the mode it shows (pref_camera_mode_key) for its next start. A key launch
 * keeps the remembered mode: when that camera session stops still in the key's mode, the mode
 * remembered before the key launch is written back. A mode the user picked during the session stays.
 */
public final class KeyLaunchMode {
    private static final String TAG = "KeyLaunchMode";
    private static final int CHOSEN = 9000;
    private static final String CAMERA = "com.oplus.camera.Camera";

    /** The key's mode and the mode remembered before the key launch, while a key session runs. */
    private static String sessionMode;
    private static Object remembered;
    /** pref_camera_mode_key; resolved at first use (resolving DataKeys while hooks install deadlocks the camera). */
    private static Object modeKey;

    private KeyLaunchMode() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "quick launch key mode", () -> install(classLoader));
    }

    private static void install(ClassLoader classLoader) throws Throwable {
        Symbols symbols = Symbols.get();

        XC_MethodHook mark = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                String activity = param.thisObject.getClass().getName();
                if (!CAMERA.equals(activity) && !"com.oplus.camera.component.CameraImageActivity".equals(activity)) return;
                Intent intent = param.args[0] instanceof Intent ? (Intent) param.args[0] : ((Activity) param.thisObject).getIntent();
                if (intent == null || !fromKey(intent) || !Settings.get(ModuleSettings.KEY_LAUNCH_MODE_ON)) return;
                intent.putExtra("mode", CHOSEN);
                if (sessionMode != null) return;
                try {
                    remembered = DataStore.get(symbols, classLoader, modeKey(symbols), "common");
                    sessionMode = Settings.getString(ModuleSettings.KEY_LAUNCH_MODE);
                } catch (Throwable error) {
                    Log.e(TAG, "remembered mode unreadable", error);
                }
            }
        };
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, mark);
        XposedHelpers.findAndHookMethod(Activity.class, "onNewIntent", Intent.class, mark);
        XposedHelpers.findAndHookMethod(Activity.class, "onStop", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (sessionMode == null || !CAMERA.equals(param.thisObject.getClass().getName())) return;
                try {
                    if (sessionMode.equals(DataStore.get(symbols, classLoader, modeKey(symbols), ""))) DataStore.put(symbols, classLoader, modeKey(symbols), remembered);
                } catch (Throwable error) {
                    Log.e(TAG, "remembered mode not restored", error);
                }
                sessionMode = null;
                remembered = null;
            }
        });

        XposedBridge.hookMethod(symbols.method("CameraInfoCursor.modeName"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if ((Integer) param.args[0] != CHOSEN) return;
                String mode = Settings.getString(ModuleSettings.KEY_LAUNCH_MODE);
                Log.i(TAG, "quick launch key opens " + mode);
                param.setResult(mode);
            }
        });
        Log.i(TAG, "installed");
    }

    private static Object modeKey(Symbols symbols) throws IllegalAccessException {
        if (modeKey == null) modeKey = DataStore.key(symbols, "Keys.cameraMode", "pref_camera_mode_key");
        return modeKey;
    }

    private static boolean fromKey(Intent intent) {
        return intent.getBooleanExtra("android_spruce_key_launch_type", false)
            || "spruce_double_tap_launch_from_framework".equals(intent.getStringExtra("android_camera_launch_type"));
    }
}
