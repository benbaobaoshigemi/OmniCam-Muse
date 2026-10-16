package local.jiege.hook.filters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Order and visibility of the master filter list (stock and added filters alike). Plain Java so
 * it can be tested offline.
 *
 * Hidden filters are not removed: they are moved to the end of the Pro group and the filter strip
 * shows only the part before them. Every filter, hidden or not, stays in the camera's list, so
 * presets bound to a hidden filter keep working and positions of visible filters are those the
 * camera computes (preset list = custom presets + one built-in preset per filter, in list order).
 *
 * Resulting order: 标准 first; the user's order; filters the user's order does not list (new stock
 * or catalog ones, or stock ones the user never moved) go right after the filter that precedes
 * them in the camera's list, so they keep their natural place. Then visible filters in that order,
 * then the hidden ones.
 */
public final class MasterLayout {
    /** Type of 标准; always first and never hidden. */
    public static final String STANDARD = "default";

    /** User's saved layout: filter IDs in display order, and per-ID visibility overrides. */
    public static final class Saved {
        public final List<String> order;
        /** filter ID -> shown; IDs not in here use the catalog default (stock filters: shown). */
        public final Map<String, Boolean> visible;

        public Saved(List<String> order, Map<String, Boolean> visible) {
            this.order = Collections.unmodifiableList(new ArrayList<>(order));
            this.visible = visible;
        }
    }

    public static final class Result {
        /** Filter types in their new list order. */
        public final List<String> order;
        /** Number of hidden filters at the end of the order. */
        public final int hiddenCount;

        Result(List<String> order, int hiddenCount) {
            this.order = Collections.unmodifiableList(order);
            this.hiddenCount = hiddenCount;
        }
    }

    private MasterLayout() {}

    /**
     * @param current the Pro group's filter types as the camera and the injector built them
     * @param catalog the master catalog (default visibility of added filters)
     * @param saved   the user's layout, or null for defaults
     */
    public static Result compute(List<String> current, FilterCatalog catalog, Saved saved) {
        Set<String> present = new LinkedHashSet<>(current);
        List<String> visible = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        if (present.remove(STANDARD)) visible.add(STANDARD);

        List<String> sequence = new ArrayList<>();
        if (saved != null) for (String id : saved.order) if (present.contains(id) && !sequence.contains(id)) sequence.add(id);
        // Unlisted filters follow their predecessor in the camera's list (or lead, without one).
        String previous = null;
        for (String id : current) {
            if (STANDARD.equals(id)) continue;
            if (!sequence.contains(id)) sequence.add(previous == null ? 0 : sequence.indexOf(previous) + 1, id);
            previous = id;
        }

        for (String id : sequence) (isVisible(id, catalog, saved) ? visible : hidden).add(id);
        List<String> order = new ArrayList<>(visible);
        order.addAll(hidden);
        return new Result(order, hidden.size());
    }

    public static boolean isVisible(String id, FilterCatalog catalog, Saved saved) {
        return isVisible(id, catalog.find(id), saved);
    }

    /** entry: the catalog entry of the filter, or null for a stock one. */
    public static boolean isVisible(String id, FilterEntry entry, Saved saved) {
        if (STANDARD.equals(id)) return true;
        if (saved != null) {
            Boolean shown = saved.visible.get(id);
            if (shown != null) return shown;
        }
        return entry == null || entry.enabled;
    }

    /**
     * Reorders parallel lists (types and their names) to follow order. Types not in order keep
     * their relative position after the ordered ones (e.g. a front list without some filters).
     */
    public static <T> void reorder(List<String> types, List<T> names, List<String> order) {
        if (names != null && names.size() != types.size()) throw new IllegalStateException("Parallel lists differ in size");
        List<String> newTypes = new ArrayList<>(types.size());
        List<T> newNames = new ArrayList<>(types.size());
        boolean[] taken = new boolean[types.size()];
        for (String id : order) {
            int index = types.indexOf(id);
            if (index < 0 || taken[index]) continue;
            taken[index] = true;
            newTypes.add(types.get(index));
            if (names != null) newNames.add(names.get(index));
        }
        for (int i = 0; i < types.size(); i++) {
            if (taken[i]) continue;
            newTypes.add(types.get(i));
            if (names != null) newNames.add(names.get(i));
        }
        types.clear();
        types.addAll(newTypes);
        if (names != null) {
            names.clear();
            names.addAll(newNames);
        }
    }
}
