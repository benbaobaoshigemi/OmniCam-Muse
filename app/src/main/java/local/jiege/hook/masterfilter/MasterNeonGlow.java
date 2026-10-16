package local.jiege.hook.masterfilter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Settings;
import local.jiege.settings.ModuleSettings;

/**
 * Master filter completion, 霓虹 (800T): the master 800T (800t_master.bin) gets the normal-mode
 * 800T rendering, i.e. the Meishe C800T node with its halation glow and its own grain.
 *
 * Two gates keep C800T off in master mode:
 *   - the native filter node enables C800T only when the HAL filter type is exactly "800t.bin";
 *   - master mode fills 800t_master's effect fields as a Portra-style film (setPortraFilterGrain,
 *     the last of ProfessionalMode's grain setters): Portra grain and no glow.
 * After that last setter, the request tag gets exactly what BaseMode.setFujiFilterGrain's
 * "800t.bin" branch gives normal mode (glow and grain), with the Portra-only fields back at their
 * defaults. C800T's grain renderer is tuned for these values; Portra grain values through C800T
 * produce oversized colour grain. The filter type is renamed only in the APS metadata sent to the
 * HAL, so the app keeps treating the filter as master 800T. All four 800t_*.bin LUT files are
 * byte-identical, so the colour does not change.
 */
final class MasterNeonGlow {
    private static final String SDK = "com.oplus.ocs.camera.";
    private static final String MASTER_800T = "800t_master.bin";
    private static final String NORMAL_800T = "800t.bin";

    private MasterNeonGlow() {}

    static void install(ClassLoader classLoader) {
        final Class<?> filterConstant = XposedHelpers.findClass(SDK + "ipusdk.processunit.filter.list.FilterConstant", classLoader);
        final Class<?> filterUnit = XposedHelpers.findClass(SDK + "ipusdk.processunit.filter.FilterProcessUnitSDK", classLoader);
        XposedHelpers.findAndHookMethod(SDK + "producer.mode.BaseMode", classLoader, "setPortraFilterGrain",
            SDK + "common.util.CameraRequestTag", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object tag = param.args[0];
                    if (tag == null || !MASTER_800T.equals(XposedHelpers.getObjectField(tag, "mFilterType"))) return;
                    if (!Settings.get(ModuleSettings.MASTER_EFFECT_NEON)) return;
                    applyNormal800t(tag, filterConstant, filterUnit);
                }
            });

        final Object filterTypeKey = XposedHelpers.getStaticObjectField(
            XposedHelpers.findClass(SDK + "common.util.ParameterKeys", classLoader), "KEY_FILTER_TYPE");
        XposedHelpers.findAndHookMethod(SDK + "consumer.apsAdapter.adapter.ApsParameters$ParameterModel", classLoader,
            "setParameter", SDK + "consumer.apsAdapter.adapter.ApsParameters$Key", Object.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[0] == filterTypeKey && MASTER_800T.equals(param.args[1])
                            && Settings.get(ModuleSettings.MASTER_EFFECT_NEON)) {
                        param.args[1] = NORMAL_800T;
                    }
                }
            });
    }

    /** BaseMode.setFujiFilterGrain's "800t.bin" branch, on top of CameraRequestTag defaults. */
    private static void applyNormal800t(Object tag, Class<?> filterConstant, Class<?> filterUnit) {
        float[] grain = (float[]) XposedHelpers.getStaticObjectField(filterUnit, "GRAIN_PARAM_800T");
        float[] glow = (float[]) XposedHelpers.getStaticObjectField(filterConstant, "FILTER_800T_GLOW_THRESH_ARRAY");
        // Grain
        XposedHelpers.setFloatField(tag, "mbFilterGainAmount", grain[0]);
        XposedHelpers.setFloatField(tag, "mbFilterGainHighlight", 1.0f);
        XposedHelpers.setFloatField(tag, "mbFilterGainSize", 0.42f);
        XposedHelpers.setFloatField(tag, "mbFilterGainRoughness", 0.29f);
        XposedHelpers.setIntField(tag, "mFilterColorGrain", 0);
        XposedHelpers.setFloatField(tag, "mbFilterGainColorRatio", 0.7f);
        XposedHelpers.setFloatField(tag, "mbFilterGainMidtones", grain[1]);
        XposedHelpers.setFloatField(tag, "mbFilterGainShadows", grain[2]);
        // Glow
        XposedHelpers.setFloatField(tag, "mbFilterGlowRadius", 20.0f);
        XposedHelpers.setFloatField(tag, "mbFilterGlowIntensity", 0.4f);
        XposedHelpers.setFloatField(tag, "mbFilterGlowThresh", glow[0]);
        XposedHelpers.setFloatField(tag, "mbFilterGlowQuickImgThresh", glow[1]);
        XposedHelpers.setFloatField(tag, "mbFilterGlowPhotoThresh", glow[2]);
        XposedHelpers.setFloatField(tag, "mbFilterGlowPreviewBgThresh", glow[3]);
        XposedHelpers.setFloatField(tag, "mbFilterGlowQuickImgBgThresh", glow[4]);
        XposedHelpers.setFloatField(tag, "mbFilterGlowPhotoBgThresh", glow[5]);
        XposedHelpers.setObjectField(tag, "mbFilterGlowColor", "1,0.15,0.01,1");
        // Portra-only fields back to their defaults (normal 800T never sets them).
        XposedHelpers.setIntField(tag, "mnFilterGrainType", 0);
        XposedHelpers.setBooleanField(tag, "mFilterGrainEnable", true);
        XposedHelpers.setFloatField(tag, "mfFilterBlurRadius", 0.0f);
        XposedHelpers.setFloatField(tag, "mfFilterHighlightOpacity", 0.0f);
        XposedHelpers.setFloatField(tag, "mfFilterrShadowOpacity", 0.0f);
        XposedHelpers.setFloatField(tag, "mfFilterHighlightThresh", 0.0f);
        XposedHelpers.setFloatField(tag, "mfFilterShadowThresh", 0.0f);
    }
}
