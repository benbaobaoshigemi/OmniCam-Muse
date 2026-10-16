package local.jiege.hook.pop;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import local.jiege.hook.common.Log;

/**
 * POP port: rear POP captures crash the camera when a face is present. The face-base retouch node
 * (aps_algo_facebase_retouch -> libOPAlgoCamAiUnifySkin, calculateMeanSkinColor /
 * fp16Tofp32_neon_accelerate_convert) reads past the POP rear capture buffer (8-bit SDR). Stock
 * X9U has no POP mode, so this combination was never tuned; front POP is unaffected.
 * The node is removed from the capture algorithm list of rear POP requests.
 */
final class PopFaceRetouchGuard {
    private static final String TAG = "PopPort";
    private static final String SDK = "com.oplus.ocs.camera.";
    private static final String FACE_RETOUCH = "aps_algo_facebase_retouch";

    private PopFaceRetouchGuard() {}

    static void install(ClassLoader classLoader) {
        Class<?> processor = XposedHelpers.findClass(SDK + "consumer.ApsProcessor", classLoader);
        Class<?> tagClass = XposedHelpers.findClass(SDK + "common.util.CameraRequestTag", classLoader);
        final Object algorithmKey = XposedHelpers.getStaticObjectField(
            XposedHelpers.findClass(SDK + "common.util.ParameterKeys", classLoader), "KEY_CAPTURE_ALGO_LIST");
        XposedHelpers.findAndHookMethod(processor, "createMetaItemInfo", tagClass, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                Object tag = param.args[0];
                Object mode = XposedHelpers.getObjectField(tag, "mCaptureMode");
                if (!(mode instanceof String) || !((String) mode).toLowerCase().contains("retro")) return;
                if (XposedHelpers.getIntField(tag, "mRearFrontCameraId") != 0) return;
                Object meta = param.getResult();
                String[] algorithms = (String[]) XposedHelpers.callMethod(meta, "get", algorithmKey);
                Log.i(TAG, "rear POP algorithms " + Arrays.toString(algorithms));
                if (algorithms == null) return;
                List<String> kept = new ArrayList<>();
                for (String algorithm : algorithms) if (!FACE_RETOUCH.equals(algorithm)) kept.add(algorithm);
                if (kept.size() == algorithms.length) return;
                XposedHelpers.callMethod(meta, "setParameter", algorithmKey, (Object) kept.toArray(new String[0]));
                Log.i(TAG, "rear POP face retouch removed");
            }
        });
    }
}
