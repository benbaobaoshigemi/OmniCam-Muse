package local.jiege.hook.masterfilter;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import local.jiege.hook.common.Log;
import local.jiege.hook.filters.ProParamsRecord;

/**
 * NOT INSTALLED (shelved): kept for a later attempt; MasterFilterCompletion does not install it
 * and MasterPresetListSync does not call onSelected. On the device the gear switching fired as
 * designed but the result was not right, and the cause was not pinned down.
 *
 * Master presets remember the Pro gear and its manual parameters.
 *
 *   Save:   the camera's "保存预设" (af.b.b(l9.a); result code 0 = added, the preset's key a is
 *           then "custom_N"). In the Pro gear the Pro parameters are recorded for that key; in the
 *           auto gear any old record of the key is cleared (the camera reuses free custom_N).
 *   Delete: af.b.e(key) clears the record.
 *   Select: (MasterPresetListSync reports every change of the current preset)
 *           - a preset with a record: its parameters are written, then the Pro gear is switched
 *             on (the camera loads the stored parameters when it enters the Pro gear,
 *             CaptureParamPresenter.C2(true)); already in the Pro gear, C2(true) reloads them;
 *           - any other preset or filter, right after a recorded preset had been applied: the
 *             parameters are cleared back to their defaults and the auto gear is switched on.
 *             A Pro gear the user turned on by hand is left alone.
 *
 * Gear switching goes through the camera's own gear button handler (the df.b click listener that
 * CaptureParamPresenter df.r installs; it carries the presenter and the gear key), so the camera
 * updates its UI and preview exactly as for a tap. Pro state: presenter.P (CaptureParamView).k.
 * Pro parameters: m9.f.w0 white balance, x0 colour tone, y0 ISO, z0 exposure compensation,
 * A0 exposure time, B0 focus mode (String preferences; master photo mode uses them unscoped).
 * Records: per preset key, String preference "pref_jiege_pro_<key>" (ProParamsRecord text).
 */
final class MasterProPresets {
    private static final String TAG = "MasterPro";
    private static final String[][] PARAMS = {
        {"w0", ProParamsRecord.WHITE_BALANCE},
        {"x0", ProParamsRecord.COLOR_TONE},
        {"y0", ProParamsRecord.ISO},
        {"z0", ProParamsRecord.EXPOSURE_COMPENSATION},
        {"A0", ProParamsRecord.EXPOSURE_TIME},
        {"B0", ProParamsRecord.FOCUS_MODE},
    };
    private static final String RECORD_PREFIX = "pref_jiege_pro_";

    private static ClassLoader loader;
    private static final Map<String, Object> recordKeys = new HashMap<>();
    /** The camera's gear button handler (df.b), captured when the presenter installs it. */
    private static volatile Object gearListener;
    /** True while the current Pro gear and parameters come from a recorded preset. */
    private static boolean appliedByPreset;

    private MasterProPresets() {}

    static void install(ClassLoader classLoader) {
        loader = classLoader;
        try {
            Class<?> presenter = XposedHelpers.findClass("df.r", classLoader);
            XposedBridge.hookAllConstructors(XposedHelpers.findClass("df.b", classLoader), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View.OnClickListener && presenterOf(param.thisObject, presenter) != null) {
                        if (gearListener == null) Log.i(TAG, "gear button handler captured");
                        gearListener = param.thisObject;
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "gear button hook failed; presets cannot switch the gear", error);
        }
        Class<?> preset = XposedHelpers.findClass("l9.a", classLoader);
        XposedHelpers.findAndHookMethod("af.b", classLoader, "b", preset, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object result = param.getResult();
                    if (result == null || XposedHelpers.getIntField(result, "a") != 0) return;
                    String key = (String) XposedHelpers.getObjectField(param.args[0], "a");
                    if (key != null && key.startsWith("custom_")) saveFor(key);
                } catch (Throwable error) {
                    Log.e(TAG, "saving Pro parameters failed", error);
                }
            }
        });
        XposedHelpers.findAndHookMethod("af.b", classLoader, "e", String.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    String key = (String) param.args[0];
                    if (key != null && key.startsWith("custom_")) put(record(key), "");
                } catch (Throwable error) {
                    Log.e(TAG, "clearing Pro parameters failed", error);
                }
            }
        });
    }

    /** Called when the current master preset changes (MasterPresetListSync). */
    static void onSelected(String key) {
        if (loader == null || key == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                select(key);
            } catch (Throwable error) {
                Log.e(TAG, "applying preset gear failed for " + key, error);
            }
        });
    }

    private static void select(String key) {
        Map<String, String> values = key.startsWith("custom_")
            ? ProParamsRecord.parse((String) get(record(key), "")) : new LinkedHashMap<String, String>();
        Object presenter = presenter();
        if (!values.isEmpty()) {
            writeParams(values);
            appliedByPreset = true;
            if (presenter == null) {
                Log.i(TAG, key + ": parameters written; gear button not captured yet");
            } else if (!inProGear(presenter)) {
                clickGear();
                Log.i(TAG, key + ": Pro gear on with " + values);
            } else {
                XposedHelpers.callMethod(presenter, "C2", true);
                Log.i(TAG, key + ": Pro parameters reloaded " + values);
            }
            return;
        }
        if (!appliedByPreset) return;
        appliedByPreset = false;
        clearParams();
        if (presenter != null && inProGear(presenter)) clickGear();
        Log.i(TAG, key + ": left a Pro preset, parameters cleared, auto gear on");
    }

    private static void saveFor(String key) {
        Object presenter = presenter();
        boolean pro = presenter != null ? inProGear(presenter) : Boolean.FALSE.equals(get(param("J0"), Boolean.TRUE));
        Map<String, String> values = new LinkedHashMap<>();
        if (pro) {
            for (String[] entry : PARAMS) {
                Object value = get(param(entry[0]), "");
                if (value != null && !"".equals(value)) values.put(entry[1], value.toString());
            }
        }
        put(record(key), ProParamsRecord.write(values));
        Log.i(TAG, "preset " + key + (pro ? " saved with Pro parameters " + values : " saved in the auto gear"));
    }

    private static void writeParams(Map<String, String> values) {
        for (String[] entry : PARAMS) {
            String value = values.get(entry[1]);
            if (value != null) put(param(entry[0]), value);
            else remove(param(entry[0]));
        }
    }

    private static void clearParams() {
        for (String[] entry : PARAMS) remove(param(entry[0]));
    }

    // ---- camera objects ---------------------------------------------------------------------------

    private static Object presenterOf(Object listener, Class<?> presenterClass) {
        for (java.lang.reflect.Field field : listener.getClass().getDeclaredFields()) {
            if (!presenterClass.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                return field.get(listener);
            } catch (IllegalAccessException error) {
                return null;
            }
        }
        return null;
    }

    private static Object presenter() {
        Object listener = gearListener;
        return listener == null ? null : presenterOf(listener, XposedHelpers.findClass("df.r", loader));
    }

    private static boolean inProGear(Object presenter) {
        return XposedHelpers.getBooleanField(XposedHelpers.getObjectField(presenter, "P"), "k");
    }

    private static void clickGear() {
        ((View.OnClickListener) gearListener).onClick(null);
    }

    private static Object param(String field) {
        return XposedHelpers.getStaticObjectField(XposedHelpers.findClass("m9.f", loader), field);
    }

    private static synchronized Object record(String presetKey) {
        Object key = recordKeys.get(presetKey);
        if (key == null) {
            key = XposedHelpers.callStaticMethod(XposedHelpers.findClass("androidx.preference.e", loader), "H0",
                String.class, RECORD_PREFIX + presetKey);
            recordKeys.put(presetKey, key);
        }
        return key;
    }

    private static Object data() {
        return XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.oplus.camera.data.DataManager", loader), "getInstance");
    }

    /** DataManager.b(key, default); the default must not be null. */
    private static Object get(Object key, Object fallback) {
        return XposedHelpers.callMethod(data(), "b", key, fallback);
    }

    private static void put(Object key, Object value) {
        XposedHelpers.callMethod(data(), "g", key, value);
    }

    /** DataManager.f(key): back to the default value. */
    private static void remove(Object key) {
        XposedHelpers.callMethod(data(), "f", key);
    }
}
