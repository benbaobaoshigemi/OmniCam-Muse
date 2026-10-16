package local.jiege.hook.video;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModuleSettings;

/**
 * Normal video: keep normal (.bin) filters at 4K and at 60fps. Stock blocks this in three places,
 * all three must be lifted (see _debug/video-filter-runtime-20260927.md):
 *   1. feature graph (video_mode/rear_sat): feature_filter_index <-> pref_video_size_key /
 *      pref_video_fps_key conflicts force the spec back to 1080p30;
 *   2. FilterPresenter.rebuildVideo(), on preview rebuild, resets the filter index at 4K / 8K / 60 / 120fps;
 *   3. FilterPresenter.select(int), on filter selection, skips apply() at 4K / rear 60fps, so the
 *      preview request keeps "default".
 * The feature keys (video size, fps, AI enhancement) are found by their key names.
 * Only commonVideo, only up to 4K60, never with AI video enhancement; retention and neon keep the
 * stock path.
 */
public final class VideoFilterHighSpec {
    private static final String TAG = "VideoFilterHighSpec";
    private static final String MODE = "commonVideo";
    private static final List<String> SPEC_KEYS = Arrays.asList("pref_video_fps_key", "pref_video_size_key");
    private static final String FILTER_KEY = "feature_filter_index";
    private static final String RETENTION = "oplus_video_filter_portrait_retention";
    private static final String NEON = "oplus_video_filter_neon";

    /** Last mode seen by the filter presenter; the conflict lookup has no mode of its own. */
    private static volatile String currentMode;

    private VideoFilterHighSpec() {}

    public static void installCamera(ClassLoader classLoader) {
        try {
            install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "video filter high spec unavailable", error);
        }
    }

    private static boolean enabled() {
        return Settings.get(ModuleSettings.VIDEO_FILTER_HIGH_SPEC);
    }

    private static Symbols symbols;
    private static Object sizeKey, fpsKey, aiKey;

    private static void install(ClassLoader classLoader) throws Throwable {
        symbols = Symbols.get();
        final Class<?> keys = symbols.cls("FeatureKeys");
        final Class<?> keyType = symbols.cls("FeatureKey");
        sizeKey = featureKey(keys, keyType, "getVideoSizeFromSp");
        fpsKey = featureKey(keys, keyType, "getVideoFpsFromSp");
        aiKey = featureKey(keys, keyType, "isAiEnhancementVideoOpen");
        final int noneIndex = XposedHelpers.getStaticIntField(
            XposedHelpers.findClass("com.oplus.camera.filter.FilterUtil", classLoader), "sNoneIndex");

        Class<?> featureInfo = XposedHelpers.findClass("com.oplus.ocs.camera.configure.ProtobufFeatureInfoImpl", classLoader);
        XposedBridge.hookAllMethods(featureInfo, "getConflictFeatureList", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.getResult() instanceof List) || !MODE.equals(currentMode) || !enabled()) return;
                String from = String.valueOf(XposedHelpers.callMethod(param.thisObject, "getFeatureKeyName"));
                boolean fromFilter = FILTER_KEY.equals(from);
                if (!fromFilter && !SPEC_KEYS.contains(from)) return;
                List<?> result = (List<?>) param.getResult();
                List<Object> kept = new ArrayList<>(result.size());
                for (Object item : result) {
                    Object key = XposedHelpers.callMethod(item, "getFeatureKey");
                    String to = String.valueOf(XposedHelpers.callMethod(key, "getName"));
                    boolean drop = fromFilter ? SPEC_KEYS.contains(to) : FILTER_KEY.equals(to);
                    if (!drop) kept.add(item);
                }
                if (kept.size() != result.size()) param.setResult(kept);
            }
        });

        XposedBridge.hookMethod(symbols.method("FilterPresenter.rebuildVideo"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object w = param.thisObject;
                currentMode = mode(w);
                if (!MODE.equals(currentMode) || !enabled()) return;
                Spec spec = Spec.read(w);
                if (spec == null || !spec.allowed()) return;
                int index = (Integer) symbols.call("FilterPresenter.currentIndex", w);
                String type = type(w, index);
                if (type == null || RETENTION.equals(type) || NEON.equals(type)) return;
                Object request = symbols.call("FilterPresenter.previewRequest", w);
                XposedHelpers.callMethod(request, "setVideoRetentionState", false);
                XposedHelpers.callMethod(request, "setVideoNeonState", false);
                symbols.field("FilterPresenter.videoIndex").setInt(w, index);
                param.setResult(null);
            }
        });

        // Stock gates this on a "filter on" flag (set when the index is above FilterUtil.sNoneIndex);
        // the same condition is checked on the index here.
        XposedBridge.hookMethod(symbols.method("FilterPresenter.select"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object w = param.thisObject;
                currentMode = mode(w);
                if (!MODE.equals(currentMode) || !enabled()) return;
                int index = (Integer) param.args[0];
                if (index <= 0 || index <= noneIndex || (Integer) symbols.call("FilterPresenter.currentIndex", w) != index) return;
                String type = type(w, index);
                if (type == null || !type.endsWith(".bin")) return;
                Spec spec = Spec.read(w);
                // Below 4K and 60fps stock select() already called apply().
                if (spec == null || !spec.allowed() || (!spec.is4k() && spec.fps != 60)) return;
                symbols.call("FilterPresenter.apply", w, index, symbols.call("FilterPresenter.softLightIndex", w),
                        symbols.call("FilterPresenter.styleIndex", w), symbols.call("FilterPresenter.groupIndex", w));
                symbols.call("FeaturePresenter.resetState", w);
            }
        });
    }

    /** The FeatureKey constant (a static field of FeatureKeys) whose name is {@code name}. */
    private static Object featureKey(Class<?> keys, Class<?> keyType, String name) throws IllegalAccessException {
        for (java.lang.reflect.Field field : keys.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType() != keyType) continue;
            field.setAccessible(true);
            Object key = field.get(null);
            if (key != null && Reflection.hasStringField(key, name)) return key;
        }
        throw new IllegalStateException("feature key not found: " + name);
    }

    private static String mode(Object presenter) throws IllegalAccessException {
        return String.valueOf(symbols.get("FeaturePresenter.modeName", presenter));
    }

    private static String type(Object presenter, int index) throws Throwable {
        Object types = symbols.call("FilterPresenter.types", presenter);
        if (!(types instanceof List) || index < 0 || index >= ((List<?>) types).size()) return null;
        return String.valueOf(((List<?>) types).get(index));
    }

    private static final class Spec {
        String size;
        int fps;
        boolean ai;

        static Spec read(Object presenter) throws Throwable {
            Object size = symbols.call("FeaturePresenter.feature", presenter, "com.oplus.camera.feature.video_size", sizeKey, "");
            Object fps = symbols.call("FeaturePresenter.feature", presenter, "com.oplus.configure.video.fps", fpsKey, 30);
            Object ai = symbols.call("FeaturePresenter.feature", presenter, "com.oplus.camera.feature.ai_enhancement_video", aiKey, Boolean.FALSE);
            if (!(fps instanceof Integer) || !(ai instanceof Boolean)) return null;
            Spec spec = new Spec();
            spec.size = String.valueOf(size);
            spec.fps = (Integer) fps;
            spec.ai = (Boolean) ai;
            return spec;
        }

        boolean is4k() {
            return "video_size_4kuhd".equals(size);
        }

        boolean allowed() {
            return !ai && fps <= 60 && (is4k() || "video_size_1080p".equals(size));
        }
    }
}
