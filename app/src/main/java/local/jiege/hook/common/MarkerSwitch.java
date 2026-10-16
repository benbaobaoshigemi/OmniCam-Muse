package local.jiege.hook.common;

import android.hardware.camera2.CaptureRequest;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.io.IOException;

/**
 * A boolean setting mirrored as a marker file "enabled" in a directory the native path hooks check, for
 * switches that serve staged config copies in place of ODM files. The configs are read when the camera
 * algorithms initialise, so a change applies after the camera process restarts.
 */
public final class MarkerSwitch {
    private static final long CHECK_INTERVAL_MS = 500;

    private final String tag;
    private final String setting;
    private final File root;
    private final File marker;
    private long checkedAt;
    private Boolean applied;

    private MarkerSwitch(String tag, String setting, File root) {
        this.tag = tag;
        this.setting = setting;
        this.root = root;
        this.marker = new File(root, "enabled");
    }

    public static void install(String tag, String setting, File root) {
        MarkerSwitch sw = new MarkerSwitch(tag, setting, root);
        // Before the algorithms initialise, so a fresh camera process starts with the right state.
        sw.sync();
        Log.guard(tag, "settings sync", () -> XposedBridge.hookAllMethods(CaptureRequest.Builder.class, "build",
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    sw.sync();
                }
            }));
        Log.i(tag, "installed");
    }

    /** Brings the marker up to date with the setting; cheap, at most twice a second. */
    private synchronized void sync() {
        long now = System.currentTimeMillis();
        if (applied != null && now - checkedAt < CHECK_INTERVAL_MS) return;
        checkedAt = now;
        boolean wanted = Settings.get(setting);
        if (applied != null && applied == wanted) return;
        try {
            if (wanted) {
                root.mkdirs();
                if (!marker.exists() && !marker.createNewFile()) throw new IOException("create " + marker);
            } else if (marker.exists() && !marker.delete()) {
                throw new IOException("delete " + marker);
            }
            applied = wanted;
            Log.i(tag, wanted ? "on" : "off (stock configs)");
        } catch (IOException error) {
            Log.e(tag, "marker", error);
        }
    }
}
