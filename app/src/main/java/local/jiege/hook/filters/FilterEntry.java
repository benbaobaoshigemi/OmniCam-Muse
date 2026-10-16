package local.jiege.hook.filters;

/**
 * One filter this module adds to a filter scope (normal or master). Entries of different scopes
 * are independent: the same filter type may be listed in both, each with its own position,
 * on/off state and label.
 */
public final class FilterEntry {
    /** Where the LUT comes from. */
    public enum Source {
        /** Stock camera LUT that older lists showed (/odm/etc/camera/filters_lut). */
        STOCK_RESTORED,
        /** LUT shipped in the KSU module payload (meishe_lut). */
        MODULE_LUT,
        /** X10 filter (清透/琥珀): module LUT with its own X10 capture filter ID and palette. */
        X10_PORT,
        /** LUT the user imported in the settings app (MasterConfig), synced to the camera's data. */
        IMPORTED,
    }

    /**
     * Series shown with the filter ("类别-名称" in the settings app) and on its white label card.
     * Stock filters are PRESET and get no label card.
     */
    public enum Category {
        PRESET("预置滤镜", null),
        CLASSIC("旧版经典", "旧版经典"),
        FUJI("富士滤镜", "富士滤镜"),
        LEICA("徕卡风格", "徕卡风格"),
        RICOH("理光滤镜", "理光滤镜"),
        FILM("胶片模拟", "胶片模拟"),
        IMPORTED("导入滤镜", "导入滤镜");

        public final String displayName;
        /** White label card text, or null for the camera's own card. */
        public final String label;

        Category(String displayName, String label) {
            this.displayName = displayName;
            this.label = label;
        }
    }

    /** Where the entry goes in each filter group of its scope. */
    public enum Placement {
        /** Right after 标准, in catalog order. */
        HEAD,
        /** At the end of the group, in catalog order. */
        TAIL,
    }

    /** Filter type (the LUT file name the camera uses as the type); the stable ID. */
    public final String id;
    /** Camera string resource the filter group is given for the name. */
    public final String nameResource;
    /** Display name that replaces the resource text in lists and cards, or null to keep it. */
    public final String title;
    public final Category category;
    /** White label card text (the category's), or null for the camera's own card. */
    public final String label;
    public final Source source;
    public final Placement placement;
    /**
     * Filter type whose capture filter ID this entry uses, or null when the camera's capture ID
     * map already has one. The camera sends the filter type (LUT file) and this ID with every
     * capture; an entry without an ID borrows one of the same LUT kind. The ID map is global, so
     * every scope listing a type must agree on this.
     */
    public final String captureIdFrom;
    /** Shown by default; the user's layout may override it. */
    public final boolean enabled;

    public FilterEntry(String id, String nameResource, String title, Category category, Source source,
                       Placement placement, String captureIdFrom, boolean enabled) {
        this.id = id;
        this.nameResource = nameResource;
        this.title = title;
        this.category = category;
        this.label = category.label;
        this.source = source;
        this.placement = placement;
        this.captureIdFrom = captureIdFrom;
        this.enabled = enabled;
    }
}
