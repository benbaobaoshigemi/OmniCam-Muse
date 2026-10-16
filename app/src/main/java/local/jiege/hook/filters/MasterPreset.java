package local.jiege.hook.filters;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A master-mode preset saved by the module ("save as new preset"). Data model only; nothing
 * creates, stores or applies presets yet.
 *
 * Binding: the preset refers to its filter by FilterEntry.id (the filter type), never by list
 * position, so reordering, switching filters off or importing new ones cannot rebind it. A preset
 * whose filter is missing or switched off is kept, and shows as unavailable.
 *
 * Storage: in the camera's data directory (/data/user/0/com.oplus.camera/files/jiege/), so
 * presets live exactly as long as the camera's data; clearing the camera's data removes them.
 *
 * Pro parameters: master mode has a Pro gear (sub mode "professionalFull"; the other gear is
 * "master"). A preset saved in the Pro gear also records the manual capture parameters; one saved
 * in the other gear has none, and applying it leaves the Pro parameters as they are.
 */
public final class MasterPreset {
    /** Stable preset ID (e.g. a UUID), assigned once on save. */
    public final String id;
    public final String name;
    /** FilterEntry.id of the bound filter, or a stock filter type. */
    public final String filterId;
    /**
     * The stock preset's effect values (filter strength, tone and the like), by the camera's
     * own preset field names.
     */
    public final Map<String, Object> effect;
    /** Manual parameters when saved in the Pro gear, otherwise null. */
    public final ProParams pro;

    public MasterPreset(String id, String name, String filterId, Map<String, Object> effect, ProParams pro) {
        this.id = id;
        this.name = name;
        this.filterId = filterId;
        this.effect = Collections.unmodifiableMap(new LinkedHashMap<>(effect));
        this.pro = pro;
    }

    /**
     * Pro-gear manual parameters. Each value is the camera's own setting value; null means the
     * parameter was on auto and stays auto when applied.
     */
    public static final class ProParams {
        public final String iso;
        public final String shutter;
        public final String whiteBalance;
        public final String exposureCompensation;
        public final String focus;
        /** Further Pro settings by the camera's own key, for parameters not modelled above. */
        public final Map<String, String> extra;

        public ProParams(String iso, String shutter, String whiteBalance, String exposureCompensation,
                         String focus, Map<String, String> extra) {
            this.iso = iso;
            this.shutter = shutter;
            this.whiteBalance = whiteBalance;
            this.exposureCompensation = exposureCompensation;
            this.focus = focus;
            this.extra = Collections.unmodifiableMap(new LinkedHashMap<>(extra));
        }
    }
}
