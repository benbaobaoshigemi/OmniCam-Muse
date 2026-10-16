package local.jiege.hook.filters;

import java.util.List;

/**
 * Keeps the master mode's current preset selection bound to a preset, not to a list position.
 * Plain Java (no Android, no Xposed) so it can be tested offline.
 *
 * The camera stores the current selection as a position in its preset list (m9.f.a1): custom
 * presets first, then one built-in preset per filter in filter list order. When filters are added,
 * reordered or switched off, a stored position points at a different filter. So the selection is
 * also recorded by stable key (the preset's key: "custom_N" for custom presets, the filter type
 * for built-in ones, "" for 标准) together with the filter list signature at that time:
 *   - same signature: the stored position was written for this list (by the user or the camera),
 *     so it is trusted and the record follows it;
 *   - other signature: the list changed since; the position is recomputed from the recorded key,
 *     or falls back to 标准 when that preset is gone.
 * Custom presets are added and deleted by the camera together with a new position, so the custom
 * count is not part of the signature.
 */
public final class PresetSelection {
    /** Key of the built-in 标准 preset. */
    public static final String STANDARD_KEY = "";

    private PresetSelection() {}

    public static final class Result {
        /** Position the camera should use. */
        public final int index;
        /** Selection record to store. */
        public final String key;
        public final String signature;
        /** True when index differs from the stored position. */
        public final boolean relocated;

        Result(int index, String key, String signature, boolean relocated) {
            this.index = index;
            this.key = key;
            this.signature = signature;
            this.relocated = relocated;
        }
    }

    /** Signature of a filter type list; changes when filters are added, removed or reordered. */
    public static String signature(List<String> filterTypes) {
        StringBuilder out = new StringBuilder();
        for (String type : filterTypes) out.append(type).append('\n');
        return Integer.toHexString(out.toString().hashCode()) + ":" + filterTypes.size();
    }

    /**
     * @param storedIndex the camera's stored position
     * @param presetKeys  keys of the camera's preset list, in list order
     * @param customCount number of custom presets at the head of the list
     * @param signature   signature of the current filter list
     * @param savedKey    recorded key, or null when nothing is recorded yet
     * @param savedSignature recorded signature, or null
     */
    public static Result resolve(int storedIndex, List<String> presetKeys, int customCount, String signature,
                                 String savedKey, String savedSignature) {
        if (presetKeys.isEmpty()) return new Result(storedIndex, savedKey, savedSignature, false);
        boolean listChanged = savedKey != null && savedSignature != null && !savedSignature.equals(signature);
        int index = storedIndex;
        if (listChanged) {
            index = presetKeys.indexOf(savedKey);
            if (index < 0) index = standardIndex(presetKeys, customCount);
        } else if (index < 0 || index >= presetKeys.size()) {
            index = standardIndex(presetKeys, customCount);
        }
        return new Result(index, presetKeys.get(index), signature, index != storedIndex);
    }

    private static int standardIndex(List<String> presetKeys, int customCount) {
        int index = presetKeys.indexOf(STANDARD_KEY);
        if (index >= 0) return index;
        return Math.max(0, Math.min(customCount, presetKeys.size() - 1));
    }
}
