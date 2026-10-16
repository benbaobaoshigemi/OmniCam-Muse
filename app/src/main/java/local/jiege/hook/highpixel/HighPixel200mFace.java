package local.jiege.hook.highpixel;

import java.io.File;
import local.jiege.hook.common.MarkerSwitch;
import local.jiege.settings.ModuleSettings;

/**
 * 哈苏超清 200MP without the big-face gate. The native side (face200m.cpp) serves the decision copy
 * staged by service.sh while the marker is there; applies after the camera process restarts.
 */
public final class HighPixel200mFace {
    private HighPixel200mFace() {}

    public static void installCamera(ClassLoader classLoader) {
        MarkerSwitch.install("HighPixel200mFace", ModuleSettings.HIGH_PIXEL_200M_NO_FACE,
            new File("/data/user/0/com.oplus.camera/files/jiege/face200m"));
    }
}
