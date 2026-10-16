package local.jiege.hook.highpixel;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.Arrays;
import local.jiege.hook.common.Log;

/**
 * One log line per 哈苏超清 capture request: what the app asked the algorithms for (live photo, picture
 * size, HDR / 10-bit, sensor sizes, palette / filter, algorithm list). Read with the LSPosed module log;
 * it is how live 50MP and live 25MP requests are compared.
 */
final class HighPixelDiagnostics {
    private static final String TAG = "HighPixelRequest";
    private static final String MODE = "high_pixel_mode";

    private HighPixelDiagnostics() {}

    static void install(ClassLoader classLoader) throws Throwable {
        Class<?> processor = XposedHelpers.findClass("com.oplus.ocs.camera.consumer.ApsProcessor", classLoader);
        Class<?> tagClass = XposedHelpers.findClass("com.oplus.ocs.camera.common.util.CameraRequestTag", classLoader);
        Class<?> keys = XposedHelpers.findClass("com.oplus.ocs.camera.common.util.ParameterKeys", classLoader);
        final Object algorithmKey = XposedHelpers.getStaticObjectField(keys, "KEY_CAPTURE_ALGO_LIST");
        XposedHelpers.findAndHookMethod(processor, "createMetaItemInfo", tagClass, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object tag = param.args[0];
                    if (!MODE.equals(XposedHelpers.getObjectField(tag, "mCaptureMode"))) return;
                    Object aps = XposedHelpers.getObjectField(tag, "mApsRequestTag");
                    String algorithms = "?";
                    if (!param.hasThrowable() && param.getResult() != null) {
                        Object list = XposedHelpers.callMethod(param.getResult(), "get", algorithmKey);
                        algorithms = list instanceof String[] ? Arrays.toString((String[]) list) : String.valueOf(list);
                    }
                    Log.i(TAG, "id=" + XposedHelpers.getIntField(tag, "mRequestId")
                        + " camera=" + XposedHelpers.getIntField(tag, "mRearFrontCameraId")
                        + " live=" + XposedHelpers.getBooleanField(tag, "mbIsLivePhotoOn")
                        + " pictureSize=" + XposedHelpers.getObjectField(aps, "mPictureSize")
                        + " highPictureSize=" + XposedHelpers.getBooleanField(aps, "mbHighPictureSizeEnable")
                        + " aiHighPixel=" + XposedHelpers.getBooleanField(aps, "mbAiHighPixelEnable")
                        + " tenBit=" + XposedHelpers.getBooleanField(tag, "mbPhoto10BitsEnable")
                        + " ultraHdr=" + XposedHelpers.getBooleanField(tag, "mbUltraHdrEnable")
                        + " rawSR=" + XposedHelpers.getBooleanField(tag, "mbRawSREnable")
                        + " sensorSizes=" + Arrays.toString((Object[]) XposedHelpers.getObjectField(tag, "mSensorSizes"))
                        + " evList=" + Arrays.toString((int[]) XposedHelpers.getObjectField(tag, "mCaptureEvList"))
                        + " palette=" + XposedHelpers.getBooleanField(tag, "mbPaletteEnable")
                        + " filterOpen=" + XposedHelpers.getBooleanField(tag, "mbFilterOpen")
                        + " filter=" + XposedHelpers.getObjectField(tag, "mFilterType")
                        + " softLight=" + XposedHelpers.getBooleanField(tag, "mbSoftLightOpen") + "/" + XposedHelpers.getObjectField(tag, "mSoftLightType")
                        + " algorithms=" + algorithms);
                } catch (Throwable error) {
                    Log.e(TAG, "log", error);
                }
            }
        });
        Log.i(TAG, "installed");
    }
}
