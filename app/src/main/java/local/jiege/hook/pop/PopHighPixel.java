package local.jiege.hook.pop;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.settings.ModuleSettings;

/**
 * POP port: in good light, photo mode outputs 25M (6120x4596) instead of 12.5M. POP (retro camera
 * mode) already captures with the same full-pixel sensor mode and bracket decision, but
 * ApsProcessor.updateUpscaleSize applies the 25M output size only when the request's capture
 * mode is "photo_mode" or "super_text_mode".
 *
 * While that method runs for a POP request, the capture mode is presented as "photo_mode"; it is
 * restored right after, so nothing else in the POP pipeline changes. Captures where the preview
 * detected a face are left at POP's stock size (see the face-retouch note below).
 */
final class PopHighPixel {
    private static final String TAG = "PopPort";
    private static final String SDK = "com.oplus.ocs.camera.";
    private static final String PHOTO_MODE = "photo_mode";
    private static final String SAVED_MODE = "pop.captureMode";

    private PopHighPixel() {}

    static void install(ClassLoader classLoader) {
        Class<?> processor = XposedHelpers.findClass(SDK + "consumer.ApsProcessor", classLoader);
        Class<?> tagClass = XposedHelpers.findClass(SDK + "common.util.CameraRequestTag", classLoader);
        int hooked = 0;
        for (Method method : processor.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!"updateUpscaleSize".equals(method.getName()) || parameters.length != 3 || parameters[2] != tagClass) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object tag = param.args[2];
                    if (tag == null) return;
                    Object mode = XposedHelpers.getObjectField(tag, "mCaptureMode");
                    if (!(mode instanceof String) || !((String) mode).toLowerCase().contains("retro")) return;
                    if (!Settings.get(ModuleSettings.POP_HIGH_PIXEL)) return;
                    // POP's face retouch (libOPAlgoCamAiUnifySkin via faceBaseRetouchProcess) is set up
                    // for POP's stock 12.5M output and reads past its buffers on a 25M image (SIGSEGV
                    // in fp16Tofp32_neon_accelerate_convert). Captures with a face stay at 12.5M.
                    boolean face = XposedHelpers.getBooleanField(tag, "mbIsDetectFace");
                    Log.i(TAG, "POP capture face=" + face + " feature=" + XposedHelpers.getObjectField(tag, "mApsDecisionFeatureType")
                        + (face ? " -> stock size" : " -> photo-mode output size"));
                    if (face) return;
                    param.setObjectExtra(SAVED_MODE, mode);
                    XposedHelpers.setObjectField(tag, "mCaptureMode", PHOTO_MODE);
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object mode = param.getObjectExtra(SAVED_MODE);
                    if (mode != null) XposedHelpers.setObjectField(param.args[2], "mCaptureMode", mode);
                }
            });
            hooked++;
        }
        if (hooked == 0) throw new IllegalStateException("ApsProcessor.updateUpscaleSize(..., CameraRequestTag) not found");
    }
}
