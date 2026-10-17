package local.jiege.hook.diag;

import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.concurrent.atomic.AtomicBoolean;
import local.jiege.hook.common.Log;

/**
 * Diagnostic probe for the high-pixel question on PKJ110: the "哈苏超清" entry X9U carries
 * (full-sensor capture, feature key {@code com.oplus.camera.hasselblad.super.definition.support})
 * is absent here, yet X8U's own {@code camera_unit_config} does list {@code high_pixel_mode} and an
 * {@code ai_high_pixel_case} built on {@code capture_raw10_fullsize} - i.e. the use case for a
 * full-size (50 MP) capture exists. Which of the two is true decides whether a 50 MP high-pixel
 * entry can be brought back:
 *
 * <ul>
 *   <li>the App side only (feature switches and picture-size tags missing) - the entry can be
 *       answered in Java, the way the palette switch already is; or</li>
 *   <li>the platform side as well (no picture size for the full-size capture anywhere) - the
 *       entry can be made to appear but has nothing to capture into.</li>
 * </ul>
 *
 * <p>Both halves are read from the running camera rather than from the filesystem: X8U's
 * {@code oplus_camera_config} is an opaque container decoded natively, and
 * {@code dumpsys media.camera} does not list this family of vendor tags at all (measured: the
 * palette tag the module already reads at runtime is absent from it while working on device).
 *
 * <p>Read-only, and off unless asked for: {@code setprop persist.omnicam.aura.hpdump 1}, then
 * force-stop the camera. It logs every high-pixel key the App asks about with the answer it got -
 * which is how the answer was found: only {@code hasselblad.super.definition.support} is false,
 * {@code com.oplus.high.picturesize} is a live 8192x6144, and the 200m family is absent. The
 * override this probe was built to test now lives in
 * {@code local.jiege.hook.highpixel.HighPixelSupport}.
 */
public final class HighPixelProbe {
    private static final String TAG = "HpProbe";
    private static final String PROP = "persist.omnicam.aura.hpdump";
    private static final String CAMERA_CONFIG = "com.oplus.camera.configure.CameraConfig";

    /** Every key the high-pixel path is known to consult, from the 7.006.123 dex alone. */
    private static final String[] KEYS = {
        "com.oplus.camera.hasselblad.super.definition.support",
        "com.oplus.camera.high_pixel.200m.support",
        "com.oplus.camera.high_pixel.200m.support.zoom.values",
        "com.oplus.rm.200mp.support",
        "com.oplus.feature.200m",
        "com.oplus.high.picturesize",
        "com.oplus.high.picturesize.name",
        "com.oplus.high.picturesize_front",
        "com.oplus.high.picturesize.name_front",
        "com.oplus.high.pixel.mode.raw1x.picturesize",
        "com.oplus.high.pixel.mode.raw6x.picturesize",
        "com.oplus.high.pixel.dark.env.picturesize.name",
        "com.oplus.high.livephoto.picturesize",
        "com.oplus.200m.supported.picturesize",
        "com.oplus.200m.tele.supported.picturesize",
        "com.oplus.super.resolution.picturesize",
        "com.oplus.ultra.wide.high.picturesize",
        "com.oplus.front.high.pixel.picturesize.name",
        "com.oplus.custom.jpeg.size",
        "com.oplus.custom.raw.size",
        "com.oplus.custom.support.raw.size",
        "com.oplus.camera.preview.8bit.hdr.support.mode.list",
        "com.oplus.available.wide.zoomvalues",
        "com.oplus.feature.color.palette.support",
    };

    private static final AtomicBoolean swept = new AtomicBoolean();
    private static final AtomicBoolean logging = new AtomicBoolean();

    private HighPixelProbe() {}

    public static void install(ClassLoader loader) {
        if (!"1".equals(readProp(PROP))) {
            if (!"0".equals(readProp(PROP))) Log.i(TAG, "off (" + PROP + "=" + readProp(PROP) + ")");
            return;
        }
        Log.i(TAG, "on: installing high-pixel probe (read-only)");
        Log.guard(TAG, "config reads", () -> hookReads(loader));
    }

    /** Logs every high-pixel key the App itself asks about, with the answer it got. */
    private static void hookReads(ClassLoader loader) {
        Class<?> config = XposedHelpers.findClass(CAMERA_CONFIG, loader);
        for (final String method : new String[] {
                "getConfigBooleanValue", "d", "x", "k", "s", "f", "n", "q", "u", "h",
                "getSizeConfigValue"}) {
            try {
                XposedBridge.hookAllMethods(config, method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0) return;
                        if (!(param.args[0] instanceof String)) return;
                        String key = (String) param.args[0];
                        if (!interesting(key)) return;
                        if (logging.compareAndSet(false, true)) {
                            Log.i(TAG, "<<< first high-pixel lookup of this process");
                        }
                        Log.i(TAG, "asked " + method + "(" + key + ") = " + describe(param.getResult()));
                        sweepOnce(loader);
                    }
                });
            } catch (Throwable error) {
                Log.e(TAG, "cannot hook " + method, error);
            }
        }
        Log.i(TAG, "config reads hooked");
    }

    /**
     * Asks each key once, a moment after the camera has finished bringing its config up. The
     * override itself is not made here: it is a feature, {@code local.jiege.hook.highpixel.HighPixelSupport},
     * so there is exactly one owner of the switch.
     */
    private static void sweepOnce(ClassLoader loader) {
        if (!swept.compareAndSet(false, true)) return;
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> Log.guard(TAG, "sweep", () -> sweep(loader)), 2500);
    }

    private static void sweep(ClassLoader loader) {
        Class<?> config = XposedHelpers.findClass(CAMERA_CONFIG, loader);
        Log.i(TAG, "<<< config sweep, " + KEYS.length + " keys");
        for (String key : KEYS) {
            StringBuilder line = new StringBuilder("  ").append(key);
            line.append(" bool=").append(ask(config, "getConfigBooleanValue", key));
            line.append(" d=").append(ask(config, "d", key, Boolean.FALSE, Boolean.FALSE));
            line.append(" size=").append(ask(config, "x", key));
            line.append(" jpeg=").append(ask(config, "getSizeConfigValue", key, Boolean.FALSE));
            line.append(" str=").append(ask(config, "k", key));
            line.append(" int=").append(ask(config, "n", key));
            line.append(" long=").append(ask(config, "q", key));
            line.append(" floats=").append(ask(config, "f", key));
            line.append(" list=").append(ask(config, "u", key));
            Log.i(TAG, line.toString());
        }
        dataKeys(loader);
        Log.i(TAG, "<<< sweep done");
    }

    /**
     * The two settings the high-pixel path stores: {@code n9.e.g1} (pref_camera_high_pixel_key,
     * one of high_pixel_off / high_pixel_50m / high_pixel_200m) and {@code n9.e.K}
     * (pref_camera_ai_high_pixel_key). Read with a marker default so the stored value can be told
     * apart from "not set".
     */
    private static void dataKeys(ClassLoader loader) {
        try {
            Class<?> keys = XposedHelpers.findClass("n9.e", loader);
            Class<?> dataManager = XposedHelpers.findClass("com.oplus.camera.data.DataManager", loader);
            Object instance = XposedHelpers.callStaticMethod(dataManager, "getInstance");
            for (String[] entry : new String[][] {
                    {"g1", "pref_camera_high_pixel_key"}, {"K", "pref_camera_ai_high_pixel_key"}}) {
                Object key = XposedHelpers.getStaticObjectField(keys, entry[0]);
                Object value = XposedHelpers.callMethod(instance, "b", key, "<unset>");
                Log.i(TAG, "  dataKey n9.e." + entry[0] + " (" + entry[1] + ") = " + value);
            }
        } catch (Throwable error) {
            Log.e(TAG, "dataKey read failed", error);
        }
    }

    private static String ask(Class<?> config, String method, Object... args) {
        try {
            Object value = XposedHelpers.callStaticMethod(config, method, args);
            return describe(value);
        } catch (Throwable error) {
            String message = error.getMessage();
            return "!" + (message == null ? error.getClass().getSimpleName() : message);
        }
    }

    private static String describe(Object value) {
        if (value == null) return "null";
        if (value instanceof Object[]) return java.util.Arrays.toString((Object[]) value);
        if (value instanceof float[]) return java.util.Arrays.toString((float[]) value);
        if (value instanceof int[]) return java.util.Arrays.toString((int[]) value);
        String text = String.valueOf(value);
        return text.length() > 160 ? text.substring(0, 160) + "..." : text;
    }

    private static boolean interesting(String key) {
        String low = key.toLowerCase();
        for (String needle : new String[] {
                "pixel", "picturesize", "200m", "50m", "hasselblad", "super.definition",
                "rm.200mp", "high.picture"}) {
            if (low.contains(needle)) return true;
        }
        return false;
    }

    private static String readProp(String name) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            return String.valueOf(type.getMethod("get", String.class, String.class)
                .invoke(null, name, ""));
        } catch (Throwable error) {
            return "";
        }
    }
}
