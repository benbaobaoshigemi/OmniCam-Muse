package local.jiege.hook.masterfilter;

import local.jiege.hook.common.Log;

/**
 * Master filter completion: extra master-mode LUTs, and the normal-mode effects that the master
 * versions of 霓虹 and 和光 lack.
 */
public final class MasterFilterCompletion {
    private static final String TAG = "MasterFilter";

    private MasterFilterCompletion() {}

    public static void installCamera(ClassLoader classLoader) {
        try {
            MasterFilmLuts.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "master LUT hooks failed", error);
        }
        try {
            MasterNeonGlow.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "neon glow hooks failed", error);
        }
        try {
            MasterPortraHalation.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "Portra halation hook failed", error);
        }
        try {
            MasterGrain.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "grain hooks failed", error);
        }
        try {
            MasterPresetListSync.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "preset list sync hook failed", error);
        }
        // MasterProPresets (Pro gear parameters kept with master presets) is not installed:
        // shelved until its behaviour is worked out on the device.
    }
}
