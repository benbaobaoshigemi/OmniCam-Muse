package local.jiege.hook.pop;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.Arrays;
import local.jiege.hook.common.Log;

/**
 * Read-only POP capture probe, off unless asked for: {@code setprop persist.omnicam.aura.popdiag 1},
 * then force-stop the camera. It logs one line per POP (retro) capture request with the exact
 * algorithm chain the app assembled plus the HDR / palette / 10-bit flags.
 *
 * <p>This is what settles the X8U "gate 3/4" question: on PMA110 the POP sliders only reach the
 * shot after {@code libAlgoInterface.so} is NOP-patched so {@code addCommonPipelines} adds the
 * BasicTone node even without {@code ultra_hdr_enable}. X8U ships a different 60 MB
 * {@code libAlgoInterface.so} and its decision is an encrypted container, so the same patch cannot
 * be copied. If the SDR capture line below still lists {@code aps_algo_basic_tone}, the gate is
 * already passed on X8U (the decision injection / the X10 libBasicTonePhoto backend add the node),
 * and the .so NOP is unnecessary.
 *
 * <p>Rides {@code ApsProcessor.createMetaItemInfo} (a narrow, read-only path), matches
 * {@code mCaptureMode} loosely (contains "retro"), and logs at most once per distinct mode+id.
 */
final class PopCaptureDiagnostics {
    private static final String TAG = "PopCapture";
    private static final String PROP = "persist.omnicam.aura.popdiag";
    private static final String SDK = "com.oplus.ocs.camera.";

    private PopCaptureDiagnostics() {}

    static void install(ClassLoader classLoader) throws Throwable {
        if (!"1".equals(readProp(PROP))) return;
        Class<?> processor = XposedHelpers.findClass(SDK + "consumer.ApsProcessor", classLoader);
        Class<?> tagClass = XposedHelpers.findClass(SDK + "common.util.CameraRequestTag", classLoader);
        Class<?> keys = XposedHelpers.findClass(SDK + "common.util.ParameterKeys", classLoader);
        final Object algorithmKey = XposedHelpers.getStaticObjectField(keys, "KEY_CAPTURE_ALGO_LIST");
        XposedHelpers.findAndHookMethod(processor, "createMetaItemInfo", tagClass, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object tag = param.args[0];
                    Object mode = XposedHelpers.getObjectField(tag, "mCaptureMode");
                    if (!(mode instanceof String) || !((String) mode).toLowerCase().contains("retro")) return;
                    Object aps = XposedHelpers.getObjectField(tag, "mApsRequestTag");
                    String algorithms = "?";
                    if (!param.hasThrowable() && param.getResult() != null) {
                        Object list = XposedHelpers.callMethod(param.getResult(), "get", algorithmKey);
                        algorithms = list instanceof String[] ? Arrays.toString((String[]) list) : String.valueOf(list);
                    }
                    Log.i(TAG, "mode=" + mode
                        + " camera=" + XposedHelpers.getIntField(tag, "mRearFrontCameraId")
                        + " tenBit=" + XposedHelpers.getBooleanField(tag, "mbPhoto10BitsEnable")
                        + " ultraHdr=" + XposedHelpers.getBooleanField(tag, "mbUltraHdrEnable")
                        + " palette=" + XposedHelpers.getBooleanField(tag, "mbPaletteEnable")
                        + " pictureSize=" + XposedHelpers.getObjectField(aps, "mPictureSize")
                        + " algorithms=" + algorithms);
                } catch (Throwable error) {
                    Log.e(TAG, "log", error);
                }
            }
        });
        Log.i(TAG, "POP capture probe installed (read-only)");
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
