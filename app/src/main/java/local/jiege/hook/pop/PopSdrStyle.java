package local.jiege.hook.pop;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.util.ArrayList;
import java.util.List;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModuleSettings;

/**
 * POP style switch: Ultra HDR (default) or SDR. The camera sends ULTRA_HDR_ENABLE=on for the modes in
 * the capture HDR list com.oplus.camera.capture.hdr.cap.mode.value, which the module adds
 * retroCamera to. With the SDR style on, that list is read without retroCamera, so POP captures as
 * stock does: an 8-bit JPEG with the brighter SDR look.
 *
 * The list is read when a camera session is created, so a change applies after switching mode or lens.
 */
final class PopSdrStyle {
    private static final String TAG = "PopPort";
    private static final String CAPTURE_HDR_MODES = "com.oplus.camera.capture.hdr.cap.mode.value";
    private static final String POP_MODE = "retroCamera";

    private PopSdrStyle() {}

    static void install(ClassLoader classLoader) throws Throwable {
        XposedBridge.hookMethod(Symbols.get().method("CameraConfig.list"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!CAPTURE_HDR_MODES.equals(param.args[0]) || !(param.getResult() instanceof List)) return;
                if (!Settings.get(ModuleSettings.POP_SDR_STYLE)) return;
                List<?> modes = (List<?>) param.getResult();
                if (!modes.contains(POP_MODE)) return;
                List<Object> kept = new ArrayList<>(modes);
                kept.remove(POP_MODE);
                param.setResult(kept);
            }
        });
        Log.i(TAG, "POP SDR style hook installed");
    }
}
