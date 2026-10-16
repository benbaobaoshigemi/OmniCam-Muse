package local.jiege.hook.pop;

import local.jiege.hook.common.Log;

/**
 * POP port: the retro camera (POP) mode. Its settings, LUTs and Polaroid assets are staged by the
 * KSU module; the native half (pop_path_remap.cpp) redirects ODM reads to them.
 */
public final class PopPort {
    private static final String TAG = "PopPort";

    private PopPort() {}

    /** @param mainProcess resources are only needed in the camera's main process */
    public static void installCamera(ClassLoader classLoader, boolean mainProcess) {
        if (mainProcess) {
            try {
                PopResources.install(classLoader);
            } catch (Throwable error) {
                Log.e(TAG, "POP resource hooks unavailable", error);
            }
        }
        try {
            PopShutterGeometry.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "POP shutter geometry fix unavailable", error);
        }
        try {
            PopFaceRetouchGuard.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "POP face retouch guard unavailable", error);
        }
        try {
            PopHighPixel.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "POP high pixel hook unavailable", error);
        }
        try {
            PopSdrStyle.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "POP SDR style hook unavailable", error);
        }
    }
}
