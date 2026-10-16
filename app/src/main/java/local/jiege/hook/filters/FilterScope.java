package local.jiege.hook.filters;

/**
 * The two independently managed filter scopes. Each has its own catalog and its own filter groups
 * (static fields of both FilterGroupManager classes, SDK and app).
 */
public enum FilterScope {
    /** Every photo mode except master: photo, portrait, sticker, night. */
    NORMAL("sFilterGroup", "sPortraitFilterGroup", "sStickerFilterGroup", "sNightFilterGroup"),
    /** Master mode (mode name "professional"), all gears; its only group is the Pro group. */
    MASTER("sProFilterGroup");

    /** Mode name of master mode (BaseMode.T0). */
    public static final String MASTER_MODE = "professional";

    final String[] groups;

    FilterScope(String... groups) {
        this.groups = groups;
    }

    /** The scope whose filters a mode shows. */
    public static FilterScope ofMode(Object modeName) {
        return MASTER_MODE.equals(modeName) ? MASTER : NORMAL;
    }
}
