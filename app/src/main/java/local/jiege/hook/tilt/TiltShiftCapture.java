package local.jiege.hook.tilt;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import local.jiege.hook.common.Log;

/**
 * Tilt-shift mode: the saved photo never got the tilt-shift algorithm. The engine takes the capture
 * algorithm list from the preview decision (ApsProcessor.mApsAlgoFlags), which has no tilt-shift node;
 * the list the app builds itself (getAlgoFromApp, which does contain aps_algo_tilt_shift) is only used
 * when there is no decision result. When the request carries a tilt-shift blur, the algorithm is added to
 * the list createMetaItemInfo produced, before the rotate/mirror node.
 */
public final class TiltShiftCapture {
    private static final String TAG = "TiltShift";
    private static final String SDK = "com.oplus.ocs.camera.";
    private static final String TILT_SHIFT = "aps_algo_tilt_shift";
    private static final String ROTATE_MIRROR = "aps_algo_rotate_mirror";

    private TiltShiftCapture() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "capture algorithm", () -> hookCaptureList(classLoader));
    }

    private static void hookCaptureList(ClassLoader classLoader) {
        Class<?> processor = XposedHelpers.findClass(SDK + "consumer.ApsProcessor", classLoader);
        Class<?> tagClass = XposedHelpers.findClass(SDK + "common.util.CameraRequestTag", classLoader);
        final Object algorithmKey = XposedHelpers.getStaticObjectField(
            XposedHelpers.findClass(SDK + "common.util.ParameterKeys", classLoader), "KEY_CAPTURE_ALGO_LIST");
        XposedHelpers.findAndHookMethod(processor, "createMetaItemInfo", tagClass, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                try {
                    Object tag = param.args[0];
                    if (!XposedHelpers.getBooleanField(tag, "mbTiltShiftOpen")
                            || XposedHelpers.getIntField(tag, "mTiltShiftBlurValue") <= 0) return;
                    Object meta = param.getResult();
                    String[] algorithms = (String[]) XposedHelpers.callMethod(meta, "get", algorithmKey);
                    if (algorithms == null) return;
                    List<String> updated = new ArrayList<>(Arrays.asList(algorithms));
                    if (updated.contains(TILT_SHIFT)) return;
                    int insertion = updated.indexOf(ROTATE_MIRROR);
                    updated.add(insertion < 0 ? updated.size() : insertion, TILT_SHIFT);
                    XposedHelpers.callMethod(meta, "setParameter", algorithmKey, (Object) updated.toArray(new String[0]));
                    Log.i(TAG, "tilt-shift capture algorithm added " + updated);
                } catch (Throwable t) { Log.e(TAG, "capture algorithm update failed", t); }
            }
        });
    }
}
