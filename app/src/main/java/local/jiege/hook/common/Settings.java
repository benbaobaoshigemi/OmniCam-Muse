package local.jiege.hook.common;

import de.robv.android.xposed.XSharedPreferences;
import local.jiege.settings.ModuleSettings;

/** Reads the module settings inside hooked apps. Changes apply on the next read. */
public final class Settings {
    private static final String MODULE_PACKAGE = "local.jiege.camera";
    private static XSharedPreferences preferences;

    private Settings() {}

    public static synchronized boolean get(String key) {
        return preferences().getBoolean(key, ModuleSettings.defaultValue(key));
    }

    public static synchronized int getInt(String key) {
        return preferences().getInt(key, ModuleSettings.defaultInt(key));
    }

    public static synchronized String getString(String key) {
        return preferences().getString(key, ModuleSettings.defaultString(key));
    }

    private static XSharedPreferences preferences() {
        if (preferences == null) {
            preferences = new XSharedPreferences(MODULE_PACKAGE, ModuleSettings.FILE);
        } else if (preferences.hasFileChanged()) {
            preferences.reload();
        }
        return preferences;
    }
}
