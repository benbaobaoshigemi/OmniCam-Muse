package local.jiege.hook.filters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The filters this module adds, one independent catalog per FilterScope. Every filter hook reads
 * the catalogs (group injection, names, label cards, capture IDs); nothing else lists filters.
 *
 * Identity: an entry is identified by its filter type (FilterEntry.id), which never changes,
 * whatever its position or state. Anything that must stay bound to a filter (a saved master
 * preset, see MasterPreset) stores this ID, never a list position: the camera's own presets store
 * the filter index, which moves whenever filters are added, removed, reordered or switched off.
 *
 * Source of the lists: the built-in defaults (NormalFilterDefaults, MasterFilterDefaults), in
 * display order. Order, on/off state and imported filters are meant to come from a persisted
 * layout per scope that overrides the defaults; the hooks only use get(scope). With the mirror
 * option (NormalMasterMirror) the normal catalog also holds the master entries shown in the
 * master list, as the same objects.
 */
public final class FilterCatalog {
    private static final Map<FilterScope, FilterCatalog> catalogs = new EnumMap<>(FilterScope.class);
    private static MasterConfig config = MasterConfig.empty();
    private static boolean mirrorMaster;

    public final FilterScope scope;
    private final List<FilterEntry> entries;
    private final Map<String, FilterEntry> byId = new LinkedHashMap<>();

    private FilterCatalog(FilterScope scope, List<FilterEntry> list) {
        this.scope = scope;
        for (FilterEntry entry : list) {
            if (byId.put(entry.id, entry) != null) throw new IllegalStateException("Duplicate " + scope + " filter " + entry.id);
        }
        this.entries = Collections.unmodifiableList(new ArrayList<>(list));
    }

    public static synchronized FilterCatalog get(FilterScope scope) {
        if (catalogs.isEmpty()) {
            List<FilterEntry> master = MasterFilterDefaults.entries(config);
            List<FilterEntry> normal = NormalFilterDefaults.entries();
            if (mirrorMaster) normal.addAll(NormalMasterMirror.mirrored(master, normal, config.layout()));
            catalogs.put(FilterScope.NORMAL, new FilterCatalog(FilterScope.NORMAL, normal));
            catalogs.put(FilterScope.MASTER, new FilterCatalog(FilterScope.MASTER, master));
            checkShared();
        }
        return catalogs.get(scope);
    }

    /** Sets the user's configuration (imported filters) and rebuilds the catalogs on next use. */
    public static synchronized void configure(MasterConfig userConfig) {
        configure(userConfig, false);
    }

    /**
     * As configure(userConfig); with mirrorMaster the normal catalog also lists the filters the
     * master list shows (NormalMasterMirror).
     */
    public static synchronized void configure(MasterConfig userConfig, boolean mirrorMaster) {
        config = userConfig == null ? MasterConfig.empty() : userConfig;
        FilterCatalog.mirrorMaster = mirrorMaster;
        catalogs.clear();
    }

    /** Whether the normal catalog follows the master list. */
    public static synchronized boolean mirrorsMaster() {
        return mirrorMaster;
    }

    public static synchronized MasterConfig config() {
        return config;
    }

    /** All entries in display order, disabled ones included. */
    public List<FilterEntry> entries() {
        return entries;
    }

    /** Entries to show, in display order. */
    public List<FilterEntry> enabled() {
        List<FilterEntry> out = new ArrayList<>();
        for (FilterEntry entry : entries) if (entry.enabled) out.add(entry);
        return out;
    }

    /** The entry for a filter type, or null for filters this scope does not add. */
    public FilterEntry find(String id) {
        return id == null ? null : byId.get(id);
    }

    /**
     * Some camera state is keyed by filter type alone, not by scope: the capture ID map, and the
     * filter list names (the list beans carry no mode). A type in both scopes must agree there.
     */
    private static void checkShared() {
        FilterCatalog master = catalogs.get(FilterScope.MASTER);
        for (FilterEntry normal : catalogs.get(FilterScope.NORMAL).entries) {
            FilterEntry other = master.find(normal.id);
            if (other == null) continue;
            if (!equal(normal.captureIdFrom, other.captureIdFrom) || !equal(normal.title, other.title)
                    || !equal(normal.nameResource, other.nameResource)) {
                throw new IllegalStateException(normal.id + ": normal and master entries disagree on shared state");
            }
        }
    }

    private static boolean equal(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
