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

    private SoftLight() {}

    private static native void nativeSetFocal(float millimetres);
    private static native void nativeSetLevels(boolean weak, boolean medium, boolean strong);

    public static void installCamera(ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        // A new zoom bar starts at 1x; later zoom changes report through setZoom.
        Log.guard(TAG, "soft light focal length at 1x", () -> XposedBridge.hookAllConstructors(
            XposedHelpers.findClass(ZOOM_BAR, classLoader), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    nativeSetLevels(Settings.get(ModuleSettings.SOFT_LIGHT_WEAK_HDF),
                        Settings.get(ModuleSettings.SOFT_LIGHT_MEDIUM_BLACK_MIST),
                        Settings.get(ModuleSettings.SOFT_LIGHT_STRONG_WHITE_MIST));
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
