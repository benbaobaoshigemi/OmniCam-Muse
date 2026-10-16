package local.jiege.hook.masterfilter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Settings;
import local.jiege.settings.ModuleSettings;

/**
 * Master filter completion, 和光 (Portra 400): the normal-mode Portra runs the Meishe PT400
 * highlight halation (a blurred layer lightened into the highlights). BaseMode.setPortraFilterGrain
 * turns it off for master types by setting both thresholds to -1; the native node skips PT400
 * unless the shadow threshold is > 0. Master Portra gets the normal-mode thresholds instead
 * (highlight 1.0, shadow 0.2). The filter name and grain parameters stay as master mode sets them.
 * With the 和光物理胶片 lab switch the highlight opacity goes out as 0: the module's pt400.inc.shader
 * (libmsnativefilter.so) then renders Portra 400's emulsion scatter, halation and DIR edges instead of
 * the stock blend, whose opacity is always 0.2.
 */
final class MasterPortraHalation {
    private static final String SDK = "com.oplus.ocs.camera.";

    private MasterPortraHalation() {}

    static void install(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(SDK + "producer.mode.BaseMode", classLoader, "setPortraFilterGrain",
            SDK + "common.util.CameraRequestTag", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object tag = param.args[0];
                    Object type = tag == null ? null : XposedHelpers.getObjectField(tag, "mFilterType");
                    if (!(type instanceof String)) return;
                    String filter = (String) type;
                    if (!filter.contains("portra400") || !filter.contains("master")) return;
                    if (!Settings.get(ModuleSettings.MASTER_EFFECT_PORTRA)) return;
                    XposedHelpers.setFloatField(tag, "mfFilterHighlightThresh", 1.0f);
                    XposedHelpers.setFloatField(tag, "mfFilterShadowThresh", 0.2f);
                    if (Settings.get(ModuleSettings.MASTER_PORTRA_PHYSICAL))
                        XposedHelpers.setFloatField(tag, "mfFilterHighlightOpacity", 0.0f);
                }
            });
    }
}
