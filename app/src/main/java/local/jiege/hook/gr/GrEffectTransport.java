package local.jiege.hook.gr;

import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Symbols;

/**
 * GR port: carries the GR image settings (saturation, tone, contrast, clarity, ...) to preview
 * and capture.
 *
 * Preview: the combined-LUT helper is pointed at the GR LMT tree staged by service.sh.
 * Capture: the GR style panel's encoded parameter struct is attached to the capture stage and
 * the SDK request, where the native half (gr_basictone.cpp) and APS read it.
 */
final class GrEffectTransport {
    private static final String TAG = "RicohGrPort";
    private static final String GR_LMT_DIR = "/data/user/0/com.oplus.camera/files/ricoh_gr/lmt";

    private GrEffectTransport() {}

    static void install(final ClassLoader classLoader) {
        Symbols symbols = Symbols.get();
        Log.guard(TAG, "GR preview LUT", () -> hookPreviewLut(symbols, classLoader));
        Log.guard(TAG, "GR capture stage", () -> hookCaptureStage(symbols, classLoader));
        Log.guard(TAG, "GR SDK request", () -> hookSdkRequest(symbols, classLoader));
    }

    private static void hookPreviewLut(final Symbols symbols, ClassLoader classLoader) {
        final ThreadLocal<Boolean> loadingGrLut = new ThreadLocal<>();
        XposedBridge.hookMethod(symbols.method("Lut3d.load"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                boolean gr = "gr".equals(param.args[2]);
                // Entering or leaving GR invalidates the cached LUT (index = -1).
                if (gr != "gr".equals(symbols.get("Lut3d.mode", param.thisObject))) {
                    symbols.set("Lut3d.index", param.thisObject, -1);
                }
                loadingGrLut.set(gr);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                loadingGrLut.remove();
            }
        });
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.oplus.ocs.camera.CombineLutHelper", classLoader), "init",
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(loadingGrLut.get())) param.args[0] = GR_LMT_DIR;
                }
            });
    }

    private static void hookCaptureStage(final Symbols symbols, final ClassLoader classLoader) {
        final Method putBundle = symbols.method("StageRequest.putBundle");
        XposedBridge.hookMethod(symbols.method("GrMode.captureStage"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                GrState.currentMode = param.thisObject;
                if (!GrState.isGrMode()) return;
                try {
                    byte[] effect = loadEffectBytes(symbols);
                    if (effect == null || effect.length == 0) {
                        Log.i(TAG, "GR effect encoder unavailable");
                        return;
                    }
                    Object key = XposedHelpers.getStaticObjectField(
                        XposedHelpers.findClass("com.oplus.ocs.camera.CameraParameter", classLoader), "GR_MODE_PARAM");
                    Object request = symbols.get("Stage.request", param.args[0]);
                    if (request == null) {
                        Log.i(TAG, "GR capture stage has no request builder");
                        return;
                    }
                    putBundle.invoke(request, key, effectBundle(effect));
                } catch (Throwable error) {
                    Log.e(TAG, "GR effect stage attach failed", error);
                }
            }
        });
    }

    private static void hookSdkRequest(final Symbols symbols, final ClassLoader classLoader) {
        Class<?> grCapMode = XposedHelpers.findClass("com.oplus.ocs.camera.producer.mode.GRCapMode", classLoader);
        XposedBridge.hookAllMethods(grCapMode, "updateCaptureRequestTag", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 3 || !GrState.isGrMode()) return;
                try {
                    Object builder = param.args[0];
                    Object key = XposedHelpers.getStaticObjectField(
                        XposedHelpers.findClass("com.oplus.ocs.camera.metadata.UPreviewKeys", classLoader), "KEY_GR_MODE_PARAM");
                    if (Boolean.TRUE.equals(XposedHelpers.callMethod(builder, "containCustomKey", key))) return;
                    byte[] effect = loadEffectBytes(symbols);
                    if (effect != null && effect.length != 0) XposedHelpers.callMethod(builder, "set", key, effectBundle(effect));
                } catch (Throwable error) {
                    Log.e(TAG, "GR effect request injection failed", error);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length != 3 || !GrState.isGrMode()) return;
                try {
                    Object tag = param.args[1];
                    Object current = XposedHelpers.getObjectField(tag, "mGrEffectParam");
                    if (current != null && XposedHelpers.callMethod(current, "getGrEffectParams") != null) return;
                    byte[] effect = loadEffectBytes(symbols);
                    if (effect == null || effect.length == 0) return;
                    Object holder = XposedHelpers.newInstance(XposedHelpers.findClass(
                        "com.oplus.ocs.camera.consumer.apsAdapter.adapter.GrEffectParam", classLoader));
                    XposedHelpers.callMethod(holder, "setGrEffectParams", new Class<?>[] {byte[].class}, effect);
                    XposedHelpers.setObjectField(tag, "mGrEffectParam", holder);
                } catch (Throwable error) {
                    Log.e(TAG, "GR effect request tag attach failed", error);
                }
            }
        });
    }

    private static Bundle effectBundle(byte[] effect) {
        Bundle bundle = new Bundle();
        bundle.putByteArray("gr.effect.params", effect);
        return bundle;
    }

    /** Encodes the GR style panel state or, if the panel is absent, the default effect table. */
    private static byte[] loadEffectBytes(Symbols symbols) throws Throwable {
        Object presenter = GrModeHooks.captureParams(symbols);
        if (presenter == null) return null;
        Object panel = Reflection.findFieldByType(presenter, symbols.cls("GrStylePanel").getName());
        if (panel != null) return structBytes(symbols, symbols.call("GrStylePanel.effect", panel));
        Class<?> tableType = symbols.cls("GrEffectTable");
        Object table = Reflection.findFieldByType(presenter, tableType.getName());
        if (table == null) table = XposedHelpers.newInstance(tableType);
        return structBytes(symbols, XposedHelpers.newInstance(symbols.cls("GrEffect"), table));
    }

    private static byte[] structBytes(Symbols symbols, Object effect) throws Throwable {
        Object table = symbols.call("GrEffect.table", effect);
        ByteBuffer buffer = ((ByteBuffer) symbols.call("GrEffectTable.buffer", table)).duplicate();
        buffer.clear();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
