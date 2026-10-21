package local.jiege.hook.masterlut;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.filters.FilterScope;

/**
 * Master LUT slots: the module's own tables in the stock master BasicTone chain, Pre before the stock
 * adjustments (SC 影调 / 饱和度, CWCM 冷暖 / 青品) and Post after them. The tables and the capture and
 * preview hooks are native (master_lut_slots.cpp); this half hands it the parameters, marks which preview
 * tables are master ones, and recombines the preview table when a parameter changes.
 *
 * Preview: Lut3dDataManager initialises the combiner with the mode name (Lut3d.load) and recombines
 * through CombineLutHelper.process. A master recombination is bracketed by nativePreviewBegin / End; End
 * covers the stock path that copies the LMT without merging (every stock adjustment at zero).
 */
public final class MasterLutSlots {
    private static final String TAG = "MuseLutSlots";
    private static final String COMBINER = "com.oplus.ocs.camera.CombineLutHelper";

    /** Slot parameters; the order is the native Param enum. Each is -100..100, 0 = identity; FX_BLUE uses 0..100. */
    public enum Param { CONTRAST, FX_BLUE }

    private static final int[] values = new int[Param.values().length];
    private static volatile String previewMode;
    private static volatile Object lut3d;

    private MasterLutSlots() {}

    private static native void nativeSetParams(int[] values);
    private static native void nativePreviewBegin(boolean master);
    private static native void nativePreviewEnd(float[] table, int size);

    public static void installCamera(ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        Log.guard(TAG, "parameters", () -> nativeSetParams(snapshot()));
        Log.guard(TAG, "preview mode", () -> XposedBridge.hookMethod(symbols.method("Lut3d.load"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                lut3d = param.thisObject;
                previewMode = (String) param.args[2];
            }
        }));
        Log.guard(TAG, "preview table", () -> XposedBridge.hookAllMethods(XposedHelpers.findClass(COMBINER, classLoader), "process",
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    nativePreviewBegin(FilterScope.MASTER_MODE.equals(previewMode));
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    int[] size = null;
                    for (Object arg : param.args) if (arg instanceof int[]) size = (int[]) arg;
                    Object table = param.getResult();
                    nativePreviewEnd(table instanceof float[] ? (float[]) table : null, size != null && size.length > 0 ? size[0] : 0);
                }
            }));
    }

    /** Sets one parameter (-100..100) for capture and preview, and recombines the preview table. */
    public static void set(Param param, int value) {
        int clamped = Math.max(-100, Math.min(100, value));
        synchronized (values) {
            if (values[param.ordinal()] == clamped) return;
            values[param.ordinal()] = clamped;
        }
        nativeSetParams(snapshot());
        final Object target = lut3d;
        // As the stock adjustments: their value callbacks recombine on the calling thread, so a drag
        // updates the preview live.
        if (target == null || !FilterScope.MASTER_MODE.equals(previewMode)) return;
        try {
            Symbols.get().call("Lut3d.update", target);
        } catch (Throwable error) {
            Log.e(TAG, "preview recombination failed", error);
        }
    }

    public static int get(Param param) {
        synchronized (values) {
            return values[param.ordinal()];
        }
    }

    private static int[] snapshot() {
        synchronized (values) {
            return values.clone();
        }
    }
}
