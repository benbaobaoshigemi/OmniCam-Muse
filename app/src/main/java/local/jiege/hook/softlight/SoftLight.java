package local.jiege.hook.softlight;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.common.ZoomFocal;
import local.jiege.settings.ModuleSettings;

/**
 * Soft light replacement: weak (柔美) renders as a Ricoh GR HDF, medium (朦胧) as a 1/4 black
 * mist, strong (梦幻) as a white mist, all scattered with physically simulated point spreads in the native half
 * (soft_light.cpp). The halo is an angle in front of the lens, so its size in the image follows
 * the focal length; this half reports the 35mm-equivalent focal length of the current zoom, and
 * which levels are replaced (experimental settings, read when the camera opens).
 */
public final class SoftLight {
    private static final String TAG = "MuseSoftLight";
    private static final String ZOOM_BAR = "com.oplus.camera.feature.zoom.view.ZoomSeekBar";
    /**
     * Bit mask of the replaced levels, bit = ProMist type. The settings file lives in the module's
     * own data directory, which the camera process runs outside of (different uid, different
     * SELinux categories), so XSharedPreferences reads nothing there and every switch looks off.
     * The settings screen mirrors the mask here, which both processes can read.
     */
    private static final String LEVELS_PROP = "persist.omnicam.aura.softlight";

    private SoftLight() {}

    private static native void nativeSetFocal(float millimetres);
    private static native void nativeSetLevels(boolean weak, boolean medium, boolean strong);

    /** The replaced levels; the property wins, the settings file is the fallback. */
    private static int levels() {
        String value = property(LEVELS_PROP);
        if (value != null && !value.isEmpty()) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                // fall through to the settings file
            }
        }
        return (Settings.get(ModuleSettings.SOFT_LIGHT_WEAK_HDF) ? 1 << 1 : 0)
            | (Settings.get(ModuleSettings.SOFT_LIGHT_MEDIUM_BLACK_MIST) ? 1 << 0 : 0)
            | (Settings.get(ModuleSettings.SOFT_LIGHT_STRONG_WHITE_MIST) ? 1 << 2 : 0);
    }

    private static String property(String key) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            return (String) type.getMethod("get", String.class).invoke(null, key);
        } catch (Throwable error) {
            return null;
        }
    }

    public static void installCamera(ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        // A new zoom bar starts at 1x; later zoom changes report through setZoom.
        Log.guard(TAG, "soft light focal length at 1x", () -> XposedBridge.hookAllConstructors(
            XposedHelpers.findClass(ZOOM_BAR, classLoader), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int levels = levels();
                    nativeSetLevels((levels & (1 << 1)) != 0, (levels & (1 << 0)) != 0, (levels & (1 << 2)) != 0);
                    nativeSetFocal(ZoomFocal.millimetres(symbols, 1.0f));
                }
            }));
        Log.guard(TAG, "soft light focal length", () -> XposedBridge.hookMethod(symbols.method("Zoom.setZoom"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                nativeSetFocal(ZoomFocal.millimetres(symbols, (Float) param.args[0]));
            }
        }));
    }
}
