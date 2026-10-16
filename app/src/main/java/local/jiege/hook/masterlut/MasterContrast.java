package local.jiege.hook.masterlut;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.filters.FilterScope;

/**
 * 对比度 in the master effect bar, after 影调: the Ricoh GR contrast, applied through the Pre slot
 * (MasterLutSlots). It takes the stock 影调 icon, and 影调 takes the GR mode's 影调 icon.
 *
 * The item is a stock effect item with a key of its own on the -100..100 scale (EffectMenu.bipolar).
 * Storage: not part of presets; one global value (the stock store with an empty preset), applied on
 * entering master mode, on every change and by the bar's reset (which dispatches 0 to every item).
 * Switching filters runs the stock restore, which dispatches every item's per-preset value; that
 * dispatch is not a change and leaves the global value alone.
 */
public final class MasterContrast {
    private static final String TAG = "MuseContrast";
    private static final String KEY_NAME = "key_master_mode_effect_muse_contrast";
    private static final String PREF_GROUP = "com.oplus.camera_preferences_";
    private static final String PACKAGE = "com.oplus.camera";
    private static final String GLOBAL = "";

    private static final ThreadLocal<Object> building = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> restoring = new ThreadLocal<>();
    private static boolean resetting;
    private static volatile Object itemKey;

    private MasterContrast() {}

    public static void installCamera(ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        Log.guard(TAG, "value on entering master", () -> XposedBridge.hookMethod(symbols.method("Lut3d.load"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (FilterScope.MASTER_MODE.equals(param.args[2])) MasterLutSlots.set(MasterLutSlots.Param.CONTRAST, stored(symbols));
            }
        }));
        Log.guard(TAG, "menu construction", () -> XposedBridge.hookAllConstructors(symbols.cls("EffectMenu"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object mode = param.args[param.args.length - 1];
                if (!"masterVideo".equals(mode)) building.set(param.thisObject);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                building.remove();
            }
        }));
        // Effect items become views right after the container is built, so the list is arranged here.
        Log.guard(TAG, "item", () -> XposedBridge.hookAllConstructors(symbols.cls("EffectContainer"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object owner = building.get();
                if (owner == null) return;
                Context context = (Context) param.thisObject.getClass().getMethod("getContext").invoke(param.thisObject);
                arrange(symbols, owner, context);
            }
        }));
        Log.guard(TAG, "scale", () -> XposedBridge.hookMethod(symbols.method("EffectMenu.bipolar"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                List<?> items = (List<?>) symbols.get("EffectMenuBase.items", param.thisObject);
                int index = (Integer) param.args[0];
                if (index >= 0 && index < items.size() && isContrast(symbols, items.get(index))) param.setResult(true);
            }
        }));
        // The stock callback would store the value per preset; it is stored globally and applied instead.
        Log.guard(TAG, "value", () -> XposedBridge.hookMethod(symbols.method("EffectMenu.dispatch"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (itemKey == null || !itemKey.equals(param.args[1])) return;
                param.setResult(null);
                if (Boolean.TRUE.equals(restoring.get()) && !resetting) {
                    MasterLutSlots.set(MasterLutSlots.Param.CONTRAST, stored(symbols));
                    return;
                }
                int value = (Integer) param.args[0];
                symbols.call("Prefs.put", null, key(symbols), GLOBAL, Integer.class, value);
                MasterLutSlots.set(MasterLutSlots.Param.CONTRAST, value);
            }
        }));
        // The stock restore reads the item per preset; it shows the global value.
        Log.guard(TAG, "reset", () -> XposedBridge.hookMethod(symbols.method("EffectItemView.onClick"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object item = symbols.get("EffectItemView.item", param.thisObject);
                resetting = item != null && symbols.get("EffectItem.key", item) == null;
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                resetting = false;
            }
        }));
        Log.guard(TAG, "restore", () -> XposedBridge.hookMethod(symbols.method("EffectMenuBase.restore"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                restoring.set(Boolean.TRUE);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                restoring.remove();
                List<?> items = (List<?>) symbols.get("EffectMenuBase.items", param.thisObject);
                for (int i = 0; i < items.size(); i++) {
                    if (!isContrast(symbols, items.get(i))) continue;
                    symbols.set("EffectItem.value", items.get(i), stored(symbols));
                    symbols.call("EffectMenuBase.refreshItem", param.thisObject, i);
                }
            }
        }));
    }

    @SuppressWarnings("unchecked")
    private static void arrange(Symbols symbols, Object owner, Context context) throws Throwable {
        List<Object> items = (List<Object>) symbols.get("EffectMenuBase.items", owner);
        int toneIcon = id(context, "drawable", "master_contrast_icon");
        int grToneIcon = id(context, "drawable", "gr_mode_effect_tone_icon");
        int label = id(context, "string", "camera_master_contrast");
        if (toneIcon == 0 || grToneIcon == 0 || label == 0) throw new IllegalStateException("contrast resources missing");
        Constructor<?> make = symbols.cls("EffectItem").getDeclaredConstructors()[0];
        for (int i = 0; i < items.size(); i++) {
            Object tone = items.get(i);
            if ((Integer) symbols.get("EffectItem.icon", tone) != toneIcon) continue;
            items.set(i, make.newInstance(symbols.get("EffectItem.label", tone), grToneIcon, 0, symbols.get("EffectItem.key", tone)));
            items.add(i + 1, make.newInstance(label, toneIcon, 0, key(symbols)));
            return;
        }
        Log.i(TAG, "影调 item not found");
    }

    private static boolean isContrast(Symbols symbols, Object item) throws Throwable {
        return item != null && itemKey != null && itemKey.equals(symbols.get("EffectItem.key", item));
    }

    private static int stored(Symbols symbols) throws Throwable {
        Object stored = symbols.call("Prefs.get", null, key(symbols), GLOBAL, Integer.class, 0);
        return stored instanceof Integer ? (Integer) stored : 0;
    }

    /** Same factory as the stock effect keys: the store type as group, the per-camera preference file. */
    private static Object key(Symbols symbols) throws Throwable {
        Object key = itemKey;
        if (key == null) {
            synchronized (MasterContrast.class) {
                if (itemKey == null) {
                    Class<?> dataKey = symbols.method("Prefs.get").getParameterTypes()[0];
                    Object group = symbols.call("Prefs.storage", null);
                    itemKey = dataKey.getConstructor(String.class, String.class, String.class, Class.class)
                        .newInstance(group, PREF_GROUP, KEY_NAME, Integer.class);
                }
                key = itemKey;
            }
        }
        return key;
    }

    private static int id(Context context, String type, String name) {
        return context.getResources().getIdentifier(name, type, PACKAGE);
    }
}
