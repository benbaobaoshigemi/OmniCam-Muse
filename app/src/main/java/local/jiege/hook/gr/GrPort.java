package local.jiege.hook.gr;

import local.jiege.hook.common.Log;

/**
 * Ricoh GR port: the realme GR mode with GR image settings, focal-length zoom bar, snap focus and
 * the GR watermark (camera and gallery), GR shutter sounds. The native half is gr_basictone.cpp.
 */
public final class GrPort {
    private static final String TAG = "RicohGrPort";

    private GrPort() {}

    public static void installCamera(ClassLoader classLoader) {
        try {
            // The other GR hooks assume the GR mode runs; without it they stay out.
            GrModeHooks.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "GR mode unavailable, GR port disabled", error);
            return;
        }
        install("effect transport", () -> GrEffectTransport.install(classLoader));
        install("focal length", () -> GrFocalLength.install(classLoader));
        install("snap focus", () -> GrSnapFocus.install(classLoader));
        install("ratio guard", () -> GrRatioGuard.install(classLoader));
        install("camera watermark", () -> GrCameraWatermark.install(classLoader));
        install("shutter sounds", () -> GrShutterSound.install(classLoader));
    }

    public static void installGallery(ClassLoader classLoader) {
        GrGalleryWatermark.install(classLoader);
    }

    private static void install(String part, Runnable installer) {
        try {
            installer.run();
        } catch (Throwable error) {
            Log.e(TAG, "GR " + part + " hooks failed", error);
        }
    }
}
