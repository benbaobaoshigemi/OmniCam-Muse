package local.jiege.hook.masterfilter;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import local.jiege.hook.common.Symbols;

/**
 * The master mode's effect preset list (EffectData.presets): the user's custom presets (keys
 * custom_<n>) first, then one built-in preset per filter in filter list order. A preset's key,
 * name and filter type are read from its toString() ("mPresetKey=…, mPresetName='…',
 * mFilterType=…, mFilterNameResEntry=…"), not from obfuscated fields.
 */
public final class MasterPresets {
    private static volatile WeakReference<Object> manager = new WeakReference<>(null);

    private MasterPresets() {}

    /** Records the EffectData manager the camera reads its presets from (MasterPresetListSync). */
    static void track(Object effectData) {
        if (effectData != null && manager.get() != effectData) manager = new WeakReference<>(effectData);
    }

    /** A copy of the current preset list, or an empty list before the camera built it. */
    @SuppressWarnings("unchecked")
    public static List<Object> list() {
        Object effectData = manager.get();
        if (effectData == null) return new ArrayList<>();
        try {
            List<Object> presets = (List<Object>) Symbols.get().get("EffectData.presets", effectData);
            return presets == null ? new ArrayList<>() : new ArrayList<>(presets);
        } catch (Throwable error) {
            return new ArrayList<>();
        }
    }

    /**
     * Number of custom presets at the head of the list. Counted from the list itself: the
     * repository's custom list is filled only when the camera loads storage and misses presets
     * imported or saved since.
     */
    public static int customCount(List<Object> presets) {
        int count = 0;
        for (Object preset : presets) {
            String key = key(preset);
            if (key == null || !key.startsWith("custom_")) break;
            count++;
        }
        return count;
    }

    public static String key(Object preset) {
        return value(text(preset, "{mPresetKey=", ", mPresetName='"));
    }

    public static String name(Object preset) {
        return value(text(preset, ", mPresetName='", "', mFilterType="));
    }

    public static String filterType(Object preset) {
        return value(text(preset, "', mFilterType=", ", mFilterNameResEntry="));
    }

    private static String value(String text) {
        return text == null || "null".equals(text) ? null : text;
    }

    /** Text between two labels of a preset's toString(), or null. */
    private static String text(Object preset, String label, String next) {
        String text = String.valueOf(preset);
        int start = text.indexOf(label);
        if (start < 0) return null;
        start += label.length();
        int end = text.indexOf(next, start);
        return end < 0 ? null : text.substring(start, end);
    }
}
