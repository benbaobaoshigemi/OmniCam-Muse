package local.jiege.hook.unlock25mp;

import java.io.File;
import local.jiege.hook.common.MarkerSwitch;
import local.jiege.settings.ModuleSettings;

/**
 * Normal-mode 25MP unlock switch. The native side (unlock25mp.cpp) serves the relaxed ODM config copies
 * staged by service.sh while the marker is there; applies after the camera process restarts.
 */
public final class Unlock25mp {
    private Unlock25mp() {}

    public static void installCamera(ClassLoader classLoader) {
        MarkerSwitch.install("Unlock25mp", ModuleSettings.UNLOCK_25MP,
            new File("/data/user/0/com.oplus.camera/files/jiege/unlock25mp"));
    }
}
