package local.jiege.hook.masterfilter;

import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.filters.MasterFilterSync;
import local.jiege.hook.filters.PresetSelection;

/**
 * Master filter completion: keeps the master "effect preset" list in step with the filter list,
 * and the current selection bound to a preset rather than a list position.
 *
 * The master mode's EffectData manager holds, in its preset list, the user's custom presets
 * (keys custom_<n>; each stores its filter by type and is re-indexed by type on restore)
 * followed by one built-in preset per filter (built by EffectData.rebuild: preset key = filter
 * type, "" for 标准). The current preset is presets[key_master_mode_current_preset_index]
 * (EffectData.current()).
 *
 * List: built from the filter feature's type array (FilterSource.types) at the moment of
 * building. When that happens while the filter list is still incomplete (seen after a camera
 * crash-restart: only 标准, 清透, 琥珀, 和光, 浓郁), every later filter index is out of range,
 * current() falls back to a fresh preset with filter 0, and all filters after 浓郁 turn into
 * "标准" with no effect until the camera restarts. Before current() reads, the filter part of the
 * list is rebuilt if it is shorter than the current list.
 *
 * Selection: the stored index is a list position, so a changed filter list (added, reordered or
 * switched-off filters) moves it to another filter. Before current() reads, PresetSelection
 * checks it against the recorded preset key and filter list signature, and relocates it when the
 * list changed.
 *
 * Deleted imports: custom presets bound to an imported filter the user deleted in the settings
 * app (MasterFilterSync.takeRemovedImports) are deleted where they are, through the camera's own
 * delete (EffectData.delete(key): list, storage and UI), posted to the main thread.
 *
 * Early check: when the filter list changed, the camera's own UI selects the first filter about a
 * second after it starts and writes that position (and key) itself. The selection is checked right
 * after the preset list is built, before that, and the stored position is moved to the recorded
 * preset only there. Once the camera has made its selection the stored position is what its pager
 * shows, so current() takes it over as it is (and records it) instead of moving it again: moving
 * the stored position under a pager that already sits elsewhere leaves the pager on one preset and
 * the content of another until the user swipes. The early pass leaves the record to current().
 *
 * Trace: every write of the position and preset-key entries is logged with its writer (module or
 * camera, with the camera's call site), and each change of the selection inputs is logged once.
 *
 * Preset keys and filter types: MasterPresets.
 */
final class MasterPresetListSync {
    private static final String TAG = "MasterFilter";
    private static final String SELECTED_KEY = "pref_jiege_master_selected_preset";
    private static final String SELECTED_SIGNATURE = "pref_jiege_master_selected_signature";
    private static final String POSITION_KEY = "key_master_mode_current_preset_index";
    private static final String PRESET_KEY = "key_master_mode_current_preset_key";
    /** Set while this module writes the camera's store, so the write trace can tell writers apart. */
    private static final ThreadLocal<Boolean> ownWrite = new ThreadLocal<>();
    private static String lastCheck;

    private MasterPresetListSync() {}

    static void install(final ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        final Class<?> filterSource = symbols.cls("FilterSource");
        traceStorageWrites(symbols);
        XposedBridge.hookMethod(symbols.method("EffectData.rebuild"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    settleEarly(symbols, classLoader, filterSource, param.thisObject);
                } catch (Throwable error) {
                    Log.e(TAG, "early master preset selection check failed", error);
                }
            }
        });
        XposedBridge.hookMethod(symbols.method("EffectData.current"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object manager = param.thisObject;
                MasterPresets.track(manager);
                @SuppressWarnings("unchecked") LinkedList<Object> presets = (LinkedList<Object>) symbols.get("EffectData.presets", manager);
                @SuppressWarnings("unchecked") List<String> types = (List<String>) filterTypes(symbols, manager, filterSource);
                if (presets == null || types == null || types.isEmpty()) return;
                int customCount = MasterPresets.customCount(presets);
                int expected = customCount + types.size();
                if (presets.size() < expected) {
                    while (presets.size() > customCount) presets.removeLast();
                    symbols.call("EffectData.rebuild", manager, types, presets);
                    Log.i(TAG, "master preset list rebuilt: " + presets.size() + " (was short of " + expected + ")");
                }
                try {
                    deletePresetsOfRemovedImports(symbols, manager, presets);
                } catch (Throwable error) {
                    Log.e(TAG, "preset cleanup failed", error);
                }
                try {
                    keepSelection(symbols, classLoader, presets, customCount, types, false);
                } catch (Throwable error) {
                    Log.e(TAG, "master preset selection check failed", error);
                }
            }
        });
    }

    /** Selection check right after a (re)build of the preset list, once the list is complete. */
    @SuppressWarnings("unchecked")
    private static void settleEarly(Symbols symbols, ClassLoader classLoader, Class<?> filterSource, Object manager) throws Throwable {
        MasterPresets.track(manager);
        LinkedList<Object> presets = (LinkedList<Object>) symbols.get("EffectData.presets", manager);
        List<String> types = (List<String>) filterTypes(symbols, manager, filterSource);
        if (presets == null || types == null || types.isEmpty()) return;
        int customCount = MasterPresets.customCount(presets);
        if (presets.size() < customCount + types.size()) return;
        keepSelection(symbols, classLoader, presets, customCount, types, true);
    }

    /** Logs writes of the position and preset-key entries: who wrote what, from where. */
    private static void traceStorageWrites(final Symbols symbols) {
        try {
            // Installed before the camera's application exists: the entries are told apart by the
            // name each key carries when it is written, never by loading the key holder classes.
            XposedBridge.hookMethod(symbols.method("DataManager.put"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    String entry = entryOf(param.args[0]);
                    if (entry == null) return;
                    boolean own = ownWrite.get() != null;
                    Log.i(TAG, (own ? "module" : "camera") + " writes " + entry
                        + "=" + param.args[1] + (own ? "" : " from " + Thread.currentThread().getName() + " " + caller()));
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "storage write trace unavailable", error);
        }
    }

    private static final java.util.Map<Object, String> entryCache = new java.util.IdentityHashMap<>();

    /** "position", "preset key", or null for any other entry. */
    private static String entryOf(Object key) {
        if (key == null) return null;
        synchronized (entryCache) {
            String known = entryCache.get(key);
            if (known != null) return known.isEmpty() ? null : known;
        }
        String entry = "";
        try {
            if (Reflection.hasStringField(key, POSITION_KEY)) entry = "position";
            else if (Reflection.hasStringField(key, PRESET_KEY)) entry = "preset key";
        } catch (Throwable ignored) {
            // not a key object we can read; treated as another entry
        }
        synchronized (entryCache) {
            entryCache.put(key, entry);
        }
        return entry.isEmpty() ? null : entry;
    }

    /** The first frames outside the hook framework and this module. */
    private static String caller() {
        StringBuilder out = new StringBuilder();
        int kept = 0;
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String name = frame.getClassName();
            if (name.startsWith("de.robv.android.xposed") || name.startsWith("org.lsposed") || name.startsWith("LSPHooker_")
                || name.startsWith("local.jiege") || name.startsWith("java.lang.reflect") || name.startsWith("jdk.internal")) continue;
            out.append(name).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber()).append(' ');
            if (++kept == 6) break;
        }
        return out.toString().trim();
    }

    private static void write(Symbols symbols, ClassLoader classLoader, Object key, Object value) throws Throwable {
        ownWrite.set(Boolean.TRUE);
        try {
            DataStore.put(symbols, classLoader, key, value);
        } finally {
            ownWrite.remove();
        }
    }

    /** FilterSource.types() of the manager's filter source (the field holding a FilterSource). */
    private static Object filterTypes(Symbols symbols, Object manager, Class<?> filterSource) throws Throwable {
        for (Class<?> type = manager.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(manager);
                if (filterSource.isInstance(value)) return symbols.call("FilterSource.types", value);
            }
        }
        return null;
    }

    private static void deletePresetsOfRemovedImports(final Symbols symbols, final Object manager, List<Object> presets) {
        List<String> removed = MasterFilterSync.takeRemovedImports();
        if (removed.isEmpty()) return;
        final List<String> keys = new ArrayList<>();
        for (Object preset : presets) {
            String key = MasterPresets.key(preset);
            if (key != null && key.startsWith("custom_") && removed.contains(MasterPresets.filterType(preset))) keys.add(key);
        }
        if (keys.isEmpty()) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            for (String key : keys) {
                try {
                    symbols.call("EffectData.delete", manager, key);
                    Log.i(TAG, "deleted preset " + key + " of a deleted imported filter");
                } catch (Throwable error) {
                    Log.e(TAG, "preset delete failed " + key, error);
                }
            }
        });
    }

    private static Object selectedKey, selectedSignature;

    /** early: relocate the stored position only; the selection record stays with the current() pass. */
    private static void keepSelection(Symbols symbols, ClassLoader classLoader, List<Object> presets, int customCount, List<String> types,
                                      boolean early) throws Throwable {
        Object position = DataStore.key(symbols, "Keys.presetIndex", POSITION_KEY);
        if (selectedKey == null) {
            selectedKey = symbols.call("DataKeys.prefs", null, String.class, SELECTED_KEY);
            selectedSignature = symbols.call("DataKeys.prefs", null, String.class, SELECTED_SIGNATURE);
        }
        List<String> keys = new ArrayList<>(presets.size());
        for (Object preset : presets) {
            String key = MasterPresets.key(preset);
            keys.add(key == null ? "" : key);
        }
        int stored = (Integer) DataStore.get(symbols, classLoader, position, Integer.valueOf(0));
        // DataManager.getOr(key, default) dereferences the default; "\u0000" stands for "not recorded".
        String savedKey = (String) DataStore.get(symbols, classLoader, selectedKey, "\u0000");
        String savedSignature = (String) DataStore.get(symbols, classLoader, selectedSignature, "\u0000");
        if ("\u0000".equals(savedKey)) savedKey = null;
        if ("\u0000".equals(savedSignature)) savedSignature = null;

        if (early && (savedKey == null || savedSignature == null)) return;
        String signature = PresetSelection.signature(types);
        PresetSelection.Result result = early
            ? PresetSelection.resolve(stored, keys, customCount, signature, savedKey, savedSignature)
            : PresetSelection.resolve(stored, keys, customCount, signature, null, null);
        String check = stored + "|" + savedKey + "|" + savedSignature + "|" + signature + "|" + keys.size() + "|" + customCount;
        if (!check.equals(lastCheck)) {
            lastCheck = check;
            Log.i(TAG, "selection check" + (early ? " (early)" : "") + ": stored=" + stored + " record=" + savedKey + "@" + savedSignature
                + " list=" + signature + " presets=" + keys.size() + " custom=" + customCount
                + " keys=" + keys.subList(0, Math.min(5, keys.size())) + " -> " + result.index);
        }
        if (result.relocated) {
            write(symbols, classLoader, position, Integer.valueOf(result.index));
            Log.i(TAG, "master preset selection " + stored + " -> " + result.index + " (" + savedKey + ")" + (early ? " early" : ""));
        }
        if (early) return;
        if (!result.key.equals(savedKey)) write(symbols, classLoader, selectedKey, result.key);
        if (!result.signature.equals(savedSignature)) write(symbols, classLoader, selectedSignature, result.signature);
    }
}
