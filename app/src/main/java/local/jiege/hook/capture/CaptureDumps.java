package local.jiege.hook.capture;

import local.jiege.hook.common.Settings;
import local.jiege.settings.ModuleSettings;

/** Capture switches are read when the camera process starts. */
public final class CaptureDumps {
    private CaptureDumps() {}

    private static native void nativeConfigure(boolean raw, boolean ccm, boolean hdrt);

    public static void installCamera() {
        nativeConfigure(Settings.get(ModuleSettings.DUMP_RAW), Settings.get(ModuleSettings.DUMP_CCM),
            Settings.get(ModuleSettings.DUMP_HDRT));
    }
}
