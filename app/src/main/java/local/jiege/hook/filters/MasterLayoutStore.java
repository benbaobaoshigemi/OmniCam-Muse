package local.jiege.hook.filters;

/**
 * The user's master layout for this camera process, set by MasterFilterSync from the settings
 * app's configuration; null means the defaults (catalog visibility, camera order).
 */
final class MasterLayoutStore {
    private static volatile MasterLayout.Saved saved;

    private MasterLayoutStore() {}

    static MasterLayout.Saved load() {
        return saved;
    }

    static void set(MasterLayout.Saved layout) {
        saved = layout;
    }
}
