package local.jiege.hook.gr;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Master mode's effect entry button (bottom right, orange while a filter or effect is applied).
 *
 * The camera refreshes the button's state in CaptureParams.refreshEffectEntry(), which is called
 * after every filter, preset and effect change, and returns at once when GrSupport.supported()
 * says the device has the GR mode. With the GR mode enabled the button was therefore only set when
 * the mode is entered and went stale after that: orange with no filter, white with one.
 * Outside GR mode, supported() answers false for the duration of that call, so master mode
 * refreshes the button as on a device without GR.
 */
final class GrMasterEffectEntry {
    private static final String TAG = "RicohGrPort";

    private GrMasterEffectEntry() {}

    static void install(final Symbols symbols) throws Throwable {
        final ThreadLocal<Boolean> refreshing = new ThreadLocal<>();
        XposedBridge.hookMethod(symbols.method("GrSupport.supported"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(refreshing.get())) param.setResult(Boolean.FALSE);
            }
        });
        XposedBridge.hookMethod(symbols.method("CaptureParams.refreshEffectEntry"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!"gr".equals(symbols.get("FeaturePresenter.modeName", param.thisObject))) refreshing.set(Boolean.TRUE);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                refreshing.remove();
            }
        });
        Log.i(TAG, "master effect entry refresh installed");
    }
}
