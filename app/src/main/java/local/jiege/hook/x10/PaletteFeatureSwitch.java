package local.jiege.hook.x10;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/**
 * Answer the palette feature switch for PKJ110, where the platform cannot answer it itself.
 *
 * <p>{@code Lut3dDataManager.paletteMode()} asks
 * {@code CameraConfig.d("com.oplus.feature.color.palette.support", false, false)}, which walks
 * {@code d -> w -> b -> y} and ends at
 * {@code CameraConfigure.getProjectSupportFeatureValue(String)}. Two of those hops are dead on
 * this platform and one is the only live one, all three measured on device:
 *
 * <ul>
 *   <li>{@code y} gates the {@code FeatureApi} call behind {@code oa.k.b}, which is
 *       {@code isFileNotEmpty(/odm/etc/camera/config/oplus_camera_feature_config)}. X8U ships no
 *       such file, so {@code FeatureApi} is never reached through this path.</li>
 *   <li>{@code CameraConfig.d} keeps its own {@code ConcurrentHashMap} of already answered
 *       booleans, so the vendor-tag fallback runs once per key per process.</li>
 *   <li>Therefore the only place a switch can take effect is {@code CameraConfig.d} itself.</li>
 * </ul>
 *
 * <p>The 1.3.1 attempt answered {@code FeatureApi} instead and was dead code. This version hooks
 * {@code CameraConfig.d} (all overloads, decided at call time by the first argument) and keeps
 * the {@code FeatureApi} hook as a harmless second net.
 *
 * <p>NOTE: 1.3.2 withdrew a hook of exactly this shape because the camera then died in a native
 * crash loop in {@code libCombineLutJni.processV3}. That withdrawal was made while the palette
 * colour-card settings were still incomplete: {@code SimTool_Preview_Palette_Normal.ini}, the
 * only file carrying the {@code [PaletteSCLut]} section, was never merged because the module
 * copied {@code SimTool_Palette_*.ini} and that name starts with {@code SimTool_Preview_}. The
 * result was that the palette branch asked the native combiner for colour cards it had no
 * description for. 1.3.4 merged that file and the crash went away; 1.3.5 ships this switch for
 * real (README 4.13).
 *
 * <p>Answering this switch was necessary but not sufficient for a coloured shot. A capture runs
 * through {@code libBasicTonePhoto.so}, and X8U's build of that file contains no palette code at
 * all, so the request went out carrying the palette algorithm while nothing applied the cards.
 * 1.3.5 binds the X9U backend over it in {@code post-mount.sh} (README 4.14). Preview never needed
 * that: the preview combiner is {@code libCombineLut.so}, which the camera APK ships itself.
 *
 * <p>The container probe stays: a device that ships the parse-version-2 container itself (X10 and
 * friends, whose {@code initialize()} ordering is correct) is left alone.
 *
 * <p>History: the first cut asked the probe as {@code isFeatureConfigureFileExist()}, so it never
 * installed on PKJ110 at all — on-device it logged "platform has its own config container, left
 * alone" while {@code /odm/etc/camera/config/oplus_camera_feature_config} did not exist. The probe
 * is {@code getConfigParseVersion()} now; see {@link #hasOwnContainer} for the decompiled reason.
 */
public final class PaletteFeatureSwitch {
    private static final String TAG = "X10Filter";
    private static final String PALETTE_SUPPORT = "com.oplus.feature.color.palette.support";
    private static final String CONFIGURE_HELPER =
            "com.oplus.ocs.camera.consumer.apsAdapter.adapter.ProtobufConfigureHelper";
    private static final String CAMERA_CONFIG = "com.oplus.camera.configure.CameraConfig";
    private static final String FEATURE_API = "com.oplus.ocs.camera.config.FeatureApi";

    private static volatile boolean answeredOnce;

    private PaletteFeatureSwitch() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "palette feature switch", () -> install(classLoader));
    }

    private static void install(ClassLoader classLoader) throws Throwable {
        if (hasOwnContainer(classLoader)) {
            Log.i(TAG, "palette switch: platform has its own config container, left alone");
            return;
        }
        // The gate a paletteMode() lookup actually passes through.
        Class<?> config = XposedHelpers.findClass(CAMERA_CONFIG, classLoader);
        XposedBridge.hookAllMethods(config, "d", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam x) {
                if (isPaletteKey(x)) x.setResult(Boolean.TRUE);
            }
        });
        // The OCS forwarder: dead on this platform, kept as a second net.
        Class<?> api = XposedHelpers.findClass(FEATURE_API, classLoader);
        XposedBridge.hookAllMethods(api, "getProjectSupportFeatureValue", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam x) {
                if (isPaletteKey(x)) x.setResult("true");
            }
        });
        Log.i(TAG, "palette switch installed on " + CAMERA_CONFIG + ".d (+ FeatureApi net)");
    }

    /** True when this call is a lookup of the palette key, whatever the overload. */
    private static boolean isPaletteKey(XC_MethodHook.MethodHookParam x) {
        if (x.args == null || x.args.length == 0) return false;
        if (!PALETTE_SUPPORT.equals(x.args[0])) return false;
        if (!answeredOnce) {
            answeredOnce = true;
            Log.i(TAG, "palette switch: answering " + PALETTE_SUPPORT + " = true");
        }
        return true;
    }

    /**
     * True when the device ships the parse-version-2 container itself. In that case the platform
     * already has the switch and a correct {@code CameraConfigure.initialize()} ordering, so the
     * stock path is left untouched.
     *
     * <p>This must be asked as {@code getConfigParseVersion()}, i.e. the
     * {@code oplus_camera_feature_config} half alone. It must NOT be asked as
     * {@code isFeatureConfigureFileExist()}: decompiled from
     * {@code com.oplus.camera.unit.sdk.adapter.jar} that one is
     * {@code isFileNotEmpty(camera_unit_feature_config.protobuf) || isFileNotEmpty(oplus_camera_feature_config)},
     * and its first half is an ordinary ODM file every X8U ships. Measured on PKJ110:
     * {@code /odm/etc/camera/config/camera_unit_feature_config.protobuf} is present (62,330 B, and
     * the module re-binds it besides) while {@code oplus_camera_feature_config} is absent — so the
     * old probe answered "true" on a device with no container at all and left the palette dark.
     * {@code getParseVersion()} keys on the V2 file only and gives 1 here, which is the real gate.
     *
     * <p>Public because it is a property of the platform rather than of the palette:
     * {@code local.jiege.hook.highpixel.HighPixelSupport} answers a second switch the same way and
     * has to make the same decision about whether the platform does it itself.
     */
    public static boolean hasOwnContainer(ClassLoader classLoader) {
        try {
            Class<?> helper = XposedHelpers.findClass(CONFIGURE_HELPER, classLoader);
            Object version = XposedHelpers.callStaticMethod(helper, "getConfigParseVersion");
            Log.i(TAG, "palette switch: config parse version = " + version);
            // 1 == no V2 container: the stock lookup answers null for every com.oplus.feature.* key,
            // so this process is exactly the one the switch has to be answered in.
            return !(version instanceof Integer) || ((Integer) version) != 1;
        } catch (Throwable t) {
            // Not being able to ask is not a reason to leave the palette dark: the container is
            // absent on every device that lacks the helper's class in the first place.
            Log.e(TAG, "palette switch: container probe failed, assuming none", t);
            return false;
        }
    }
}
