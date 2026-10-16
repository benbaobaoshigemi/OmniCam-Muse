package local.jiege.hook.filters;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import de.robv.android.xposed.XSharedPreferences;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import local.jiege.hook.common.Diagnostics;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.settings.MasterFilterContract;
import local.jiege.settings.MasterFilterStore;
import local.jiege.settings.ModuleSettings;

/**
 * Camera side of the master filter configuration (settings app: MasterFilterStore).
 *
 * The settings app keeps the configuration and the imported LUTs in the module's LSPosed
 * preferences directory (xposedsharedprefs), which hooked apps can read; the camera reads them
 * there directly, so the settings app does not have to run (ColorOS blocks starting another
 * app's process from the camera). Once per camera process, before the filter groups are built:
 * reads the configuration, hands it to FilterCatalog (imported filters) and MasterLayout (order,
 * visibility), and copies the imported LUTs into the camera's data (files/jiege/lut, where the
 * native path remap serves reads of /odm/etc/camera/meishe_lut/<id>). Imported filters the user
 * deleted since the last sync are queued for MasterPresetListSync, which deletes their presets.
 *
 * The camera's master list goes the other way through MasterFilterProvider; that only works
 * while the settings app runs, and only names stock filters in the settings app.
 *
 * Without the settings data (LSPosed preferences unavailable) the defaults apply.
 */
public final class MasterFilterSync {
    private static final String TAG = "MasterFilterSync";
    /** Camera data: imported LUTs, and the imported IDs seen at the last sync. */
    private static final String LUT_DIR = "jiege/lut";
    private static final String KNOWN_IMPORTS = "jiege/imported_ids.txt";

    private static boolean loaded;
    /** Modification stamp of the configuration this process loaded. */
    private static String loadedStamp;
    private static final Set<String> removedImports = new LinkedHashSet<>();

    private MasterFilterSync() {}

    /** Loads the configuration once; later calls return at once. */
    public static synchronized void load(Context hint) {
        if (loaded) return;
        Context context = context(hint);
        if (context == null) return;  // retried on the next call
        loaded = true;
        MasterConfig config = MasterConfig.empty();
        File settings = settingsDir();
        loadedStamp = stamp(settings);
        try {
            File file = settings == null ? null : new File(settings, MasterFilterStore.CONFIG);
            if (file != null && file.isFile()) {
                config = MasterConfig.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
        } catch (Throwable error) {
            Log.e(TAG, "configuration unreadable, using defaults", error);
        }
        FilterCatalog.configure(config, Settings.get(ModuleSettings.NORMAL_FOLLOWS_MASTER));
        MasterLayoutStore.set(config.layout());
        trackImports(context, config);
        final MasterConfig synced = config;
        new Thread(() -> syncLuts(context, settings, synced), "JiegeLutSync").start();
        Log.i(TAG, "configuration: " + config.order.size() + " ordered, " + config.visible.size()
            + " visibility, " + config.imported.size() + " imported");
    }

    /**
     * True when the settings app changed the configuration after this process loaded it. The
     * filter groups are built once per process, so the camera has to restart to apply it
     * (ColorOS often keeps the camera process when it is swiped away).
     */
    public static synchronized boolean changedSinceLoad() {
        return loaded && (!stamp(settingsDir()).equals(loadedStamp)
            || Settings.get(ModuleSettings.NORMAL_FOLLOWS_MASTER) != FilterCatalog.mirrorsMaster());
    }

    private static String stamp(File settings) {
        File file = settings == null ? null : new File(settings, MasterFilterStore.CONFIG);
        return file == null || !file.isFile() ? "none" : file.lastModified() + ":" + file.length();
    }

    /** Imported filter IDs deleted by the user since the last sync; cleared by the caller. */
    public static synchronized List<String> takeRemovedImports() {
        List<String> out = new ArrayList<>(removedImports);
        removedImports.clear();
        return out;
    }

    /** Keeps the camera's stock master filters (type, display name) for the settings app (Diagnostics). */
    public static void reportStock(List<String> types, List<String> names) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            text.append(types.get(i).replace('\t', ' ')).append('\t')
                .append(names.get(i) == null ? "" : names.get(i).replace('\t', ' ').replace('\n', ' ')).append('\n');
        }
        Diagnostics.setMasterStock(text.toString());
    }

    /** The module's LSPosed preferences directory, or null. */
    private static File settingsDir() {
        try {
            File file = new XSharedPreferences(MasterFilterContract.MODULE_PACKAGE, ModuleSettings.FILE).getFile();
            return file == null ? null : file.getParentFile();
        } catch (Throwable error) {
            Log.e(TAG, "settings directory unavailable", error);
            return null;
        }
    }

    private static Context context(Context hint) {
        if (hint != null) return hint;
        Application application = AndroidAppHelper.currentApplication();
        return application;
    }

    private static void trackImports(Context context, MasterConfig config) {
        File known = new File(context.getFilesDir(), KNOWN_IMPORTS);
        Set<String> current = new LinkedHashSet<>();
        for (MasterConfig.Imported item : config.imported) current.add(item.id);
        try {
            Set<String> previous = new HashSet<>();
            if (known.isFile()) {
                for (String line : new String(Files.readAllBytes(known.toPath()), StandardCharsets.UTF_8).split("\n")) {
                    if (!line.trim().isEmpty()) previous.add(line.trim());
                }
            }
            for (String id : previous) if (!current.contains(id)) removedImports.add(id);
            known.getParentFile().mkdirs();
            Files.write(known.toPath(), String.join("\n", current).getBytes(StandardCharsets.UTF_8));
            if (!removedImports.isEmpty()) Log.i(TAG, "imports removed: " + removedImports);
        } catch (Throwable error) {
            Log.e(TAG, "import tracking failed", error);
        }
    }

    private static void syncLuts(Context context, File settings, MasterConfig config) {
        File dir = new File(context.getFilesDir(), LUT_DIR);
        dir.mkdirs();
        Set<String> wanted = new HashSet<>();
        for (MasterConfig.Imported item : config.imported) {
            wanted.add(item.id);
            File target = new File(dir, item.id);
            if (target.isFile() && target.length() == item.size) continue;
            File temp = new File(dir, item.id + ".part");
            if (settings == null) continue;
            try (InputStream in = new FileInputStream(MasterFilterStore.lutFile(settings, item.id));
                 OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            } catch (Throwable error) {
                temp.delete();
                Log.e(TAG, "LUT copy failed " + item.id, error);
                continue;
            }
            if (!temp.renameTo(target)) Log.i(TAG, "LUT rename failed " + item.id);
        }
        File[] files = dir.listFiles();
        for (File file : files == null ? new File[0] : files) {
            if (!wanted.contains(file.getName())) file.delete();
        }
        Log.i(TAG, "LUTs synced: " + wanted.size());
    }
}
