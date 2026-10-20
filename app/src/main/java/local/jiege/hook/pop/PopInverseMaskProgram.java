package local.jiege.hook.pop;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Constructor;
import local.jiege.hook.common.Log;

/**
 * POP port: make GLES20Canvas build its draw-inverse-mask program even though the vendor tag is
 * absent. This replaces the earlier "widen the config value" approach, which turned out to break
 * the whole camera (see below).
 *
 * GLES20Canvas (com.oplus.camera.common.gl.s) creates its draw_inverse_mask program in its own
 * constructor, but only when ia.n0.i0() is true -- and i0() is bit 2 of the vendor tag
 * "com.oplus.feature.colorful.screen.torch.config" (a BINARY bit string: X9U ships "111" == 7).
 * X8U ships no such tag, so the program is never built; the retro (POP) UI then draws with it
 * anyway and dereferences null:
 *
 *   NPE: void com.oplus.camera.common.gl.y0.s() on a null object reference
 *     at com.oplus.camera.common.gl.s.p0(GLES20Canvas.java:120)   // this.j0.s()
 *     at eq.d1.Ug(CameraPreviewUI.java:638)                       // drawGLInverseMask
 *
 * Why not just force the config bit on: that tag is NOT a GL-only switch. The same bit is read by
 * com.oplus.camera.CameraManager and by the whole feature.basic.flash.torch* module
 * (FlashConstants$TorchState, flash.x1, ... -- 8 call sites). Forcing it on made the flash/torch
 * subsystem believe X8U supports the colorful screen torch; the POP preview then deadlocked --
 * PreviewGLThread parked on GLThread.i0.C.wait() (guardedRun, GLThread.java:868) and every APS
 * preview routine (APSPreviewManager::previewManagerRoutine, APSDeferJobGoverner::deferJobRoutine,
 * APSThreadPool::doWork) sat in condition_variable::wait. No frames were ever produced
 * ("StreamOff ... last request id 24", HAL "offline configure streams failed"), and once it
 * happened the shared GL thread stayed parked, so switching back to stock modes also froze.
 *
 * So here we leave the config value, CameraManager and the flash module completely untouched and
 * mirror exactly the two instructions the GL path relies on. From the dexdump of gl.s.<init>()V:
 *
 *   025e: if-eqz v0, 026b   // i0()==false -> skip this block
 *   0260: new-instance y0 (R$raw.texture_vertex_shader_inverse_mask, R$raw.draw_inverse_mask_shader)
 *   0269: iput-object v0, v9, s.j0
 *
 * Only j0 is affected; every other program field (N/O/R/U/V/W/a0/b0/c0) is built unconditionally
 * by the constructor and is left alone.
 */
final class PopInverseMaskProgram {
    private static final String TAG = "PopPort";
    private static final String CANVAS = "com.oplus.camera.common.gl.s";
    private static final String PROGRAM = "com.oplus.camera.common.gl.y0";
    private static final String RAW = "com.oplus.camera.common.R$raw";
    private static final String VERTEX = "texture_vertex_shader_inverse_mask";
    private static final String FRAGMENT = "draw_inverse_mask_shader";

    private static volatile boolean reported;

    private PopInverseMaskProgram() {}

    static void install(ClassLoader classLoader) {
        final Class<?> canvas = XposedHelpers.findClass(CANVAS, classLoader);
        final Class<?> program = XposedHelpers.findClass(PROGRAM, classLoader);
        final Class<?> raw = XposedHelpers.findClass(RAW, classLoader);
        final Constructor<?> ctor = XposedHelpers.findConstructorExact(program, int.class, int.class);
        final int vertexShader = XposedHelpers.getStaticIntField(raw, VERTEX);
        final int fragmentShader = XposedHelpers.getStaticIntField(raw, FRAGMENT);

        XposedBridge.hookAllConstructors(canvas, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object target = param.thisObject;
                if (target == null) return;
                if (XposedHelpers.getObjectField(target, "j0") != null) return;
                try {
                    Object built = ctor.newInstance(vertexShader, fragmentShader);
                    XposedHelpers.setObjectField(target, "j0", built);
                    if (!reported) {
                        reported = true;
                        Log.i(TAG, "draw_inverse_mask program built for the retro UI");
                    }
                } catch (Throwable error) {
                    // Leave the canvas exactly as the device built it. A missing program still
                    // throws only on the retro draw path and never affects the other modes.
                    Log.e(TAG, "could not build the draw_inverse_mask program", error);
                }
            }
        });
    }
}
