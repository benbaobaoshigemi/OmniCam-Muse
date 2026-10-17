package local.jiege.hook.highpixel;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.x10.PaletteFeatureSwitch;

/**
 * Brings the Hasselblad HI-RES entry ("哈苏超清", the {@code high_pixel_mode}) back on PKJ110.
 *
 * <p>X8U ships the whole high-pixel path in its own config and only hides the entry:
 *
 * <ul>
 *   <li>{@code camera_unit_config} lists {@code high_pixel_mode} for rear_main/rear_wide/rear_tele/
 *       rear_ultra_tele, keeps its {@code mode_operation_mode} and 3dol entries, and carries
 *       {@code usecase_info.ai_high_pixel_case} = preview + {@code capture_raw10_fullsize} +
 *       {@code raw_output};</li>
 *   <li>the platform answers {@code com.oplus.high.picturesize} = <b>8192x6144</b> (50 MP, exactly
 *       the sensor's full size in {@code CameraHWConfiguration.config}) and
 *       {@code com.oplus.feature.high.pixel.support} / {@code ...highpixel.merge.support} true;</li>
 *   <li>the only gate that answers false is
 *       {@code com.oplus.camera.hasselblad.super.definition.support} - the key
 *       {@code CameraEntry.b("highPixel")} maps the mode to, and what {@code CameraInfoCursor}
 *       filters its tile by. Measured on device with the diagnostic probe: answering just this one
 *       key puts 哈苏超清 back in the mode grid and a shutter press writes a 6144x8192 JPEG.</li>
 * </ul>
 *
 * <p>Only the 50 MP half comes back. 200 MP stays off on purpose: X9U's
 * {@code com.oplus.200m.supported.picturesize} (16320x12256) and
 * {@code com.oplus.camera.high_pixel.200m.support} are both absent here, and the sensor tops out at
 * 50 MP, so the mode's own size list offers 5000 万像素 and nothing larger. Answering the 200m keys
 * true as well was measured to be pointless - the size to capture into does not exist.
 *
 * <p>Answering this key is the same shape as {@link PaletteFeatureSwitch}: {@code CameraConfig}
 * caches every answered boolean in its own map and its vendor-tag fallback is dead on this
 * platform, so the switch has to be answered at {@code CameraConfig} itself. Devices that ship
 * their own feature container (X10 and friends) already answer it and are left alone.
 *
 * <p>Disable with {@code setprop persist.omnicam.aura.hires 0} (then force-stop the camera).
 */
public final class HighPixelSupport {
    private static final String TAG = "HighPixel";
    private static final String PROP = "persist.omnicam.aura.hires";
    private static final String CAMERA_CONFIG = "com.oplus.camera.configure.CameraConfig";
    private static final String HASSELBLAD_HI_RES =
            "com.oplus.camera.hasselblad.super.definition.support";

    /**
     * Keys X9U's {@code oplus_camera_config} defines and X8U's does not (the two files were compared key
     * by key). They are what the palette side of the effect panel is built on: the palette feature
     * itself, its IPU texture, the preview list in the high-pixel page and the global EV bar. X8U leaves
     * them out, so the panel for 哈苏超清 is never assembled even though the mode answers the filter
     * keys true.
     */
    private static final String[] X9U_ONLY = {
        "com.oplus.feature.color.palette.support",
        "com.oplus.ipu.color.palette.texture.support",
        "com.oplus.feature.high.pixel.preevlist.support",
        "com.oplus.high_pixel.global.ev.support",
    };

    private static final java.util.Set<String> answeredOnce =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private HighPixelSupport() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "hi-res entry", () -> install(classLoader));
    }

    private static void install(ClassLoader classLoader) throws Throwable {
        if ("0".equals(readProp())) {
            Log.i(TAG, "off (" + PROP + "=0)");
            return;
        }
        if (PaletteFeatureSwitch.hasOwnContainer(classLoader)) {
            Log.i(TAG, "hi-res entry: platform has its own config container, left alone");
            return;
        }
        Class<?> config = XposedHelpers.findClass(CAMERA_CONFIG, classLoader);
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!isHiResKey(param)) return;
                param.setResult(Boolean.TRUE);
            }
        };
        // CameraConfig caches its answers, so both entry points have to be covered: the photo path
        // asks getConfigBooleanValue, the entry list and the capture path ask d(key, false, false).
        XposedBridge.hookAllMethods(config, "getConfigBooleanValue", hook);
        XposedBridge.hookAllMethods(config, "d", hook);
        Log.i(TAG, "hi-res entry installed: answering " + HASSELBLAD_HI_RES + " = true");
    }

    /** True when this call is the Hasselblad HI-RES switch, whatever the overload. */
    private static boolean isHiResKey(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length == 0) return false;
        Object key = param.args[0];
        if (!(key instanceof String)) return false;
        if (!HASSELBLAD_HI_RES.equals(key) && java.util.Arrays.asList(X9U_ONLY).indexOf(key) < 0) return false;
        if (answeredOnce.add(String.valueOf(key))) {
            Log.i(TAG, "answering " + key + " = true");
        }
        return true;
    }

    private static String readProp() {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            return String.valueOf(type.getMethod("get", String.class, String.class)
                .invoke(null, PROP, "1"));
        } catch (Throwable error) {
            return "1";
        }
    }
}
