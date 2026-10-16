package local.jiege.hook.watermark;

import android.text.TextUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModuleSettings;

/**
 * The XPAN watermark applies in XPAN mode only.
 *
 * The camera has one photo watermark slot for all modes: style id (and its backup), open state and
 * the capture switch. The XPAN style (hassel_style_xpan_*) is chosen in the gallery editor while in
 * XPAN mode and goes into that shared slot. The editor lists it only for XPAN, so in other modes
 * the style cannot be found or changed, yet the capture still reads the slot and burns it in; and a
 * style chosen in another mode overwrites it.
 *
 * The slot is swapped on a mode switch (ModeSwitch.switch, also called at launch with no previous
 * mode). Leaving XPAN with an XPAN style in the slot saves it as the "xpan" copy and puts the "other"
 * copy back (or clears the slot if there is none). Entering XPAN saves a non-XPAN style in the slot
 * as the "other" copy and puts the "xpan" copy back. A copy is only restored if its style files
 * exist: the camera deletes the resource folders of every style that is not the selected one, so the
 * folder of a saved style is copied to files/jiege/wm_backup when the slot is saved and copied back
 * when it is missing. Like ModeStateSplit, the keys are read at the first mode switch, not at install.
 */
public final class XpanWatermarkIsolation {
    private static final String TAG = "XpanWatermark";
    private static final String XPAN_MODE = "xpan";
    private static final String XPAN_STYLE_PREFIX = "hassel_style_xpan_";
    /** styleId of POP's own Polaroid watermark (files/pop_port/pld_watermark/pld); it is not a slot style. */
    private static final String POP_STYLE = "pld";
    private static final String PHOTO_DIR = "/data/user/0/com.oplus.camera/files/photo_watermark";
    private static final String BACKUP_DIR = "/data/user/0/com.oplus.camera/files/jiege/wm_backup";
    private static final String STYLE = "pref_ai_master_watermark_photo_style_id";
    private static final String BACKUP = "pref_ai_master_watermark_photo_style_id_backup";
    private static final String OPEN = "pref_ai_master_watermark_photo_open_state";
    private static final String SWITCH = "pref_watermark_capture_switch_key";
    private static final String SAVED_PREFIX = "pref_jiege_wm_";

    private XpanWatermarkIsolation() {}

    /** The four values of the photo watermark slot, as camera keys. */
    private static final class Keys {
        Object style;
        Object backup;
        Object open;
        Object capture;
    }

    private static final class State {
        Keys live;
        Keys xpan;
        Keys other;
    }

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "XPAN watermark isolation", () -> install(classLoader));
    }

    private static void install(final ClassLoader classLoader) throws Throwable {
        final Symbols symbols = Symbols.get();
        symbols.method("DataKeys.prefs");
        final State state = new State();
        XposedBridge.hookMethod(symbols.method("ModeSwitch.switch"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                String from = (String) param.args[0];
                String to = (String) param.args[1];
                if (TextUtils.isEmpty(to) || to.equals(from) || !Settings.get(ModuleSettings.XPAN_WATERMARK_ISOLATION)) return;
                try {
                    synchronized (state) {
                        if (state.live == null) init(symbols, state);
                    }
                    if (XPAN_MODE.equals(to)) enterXpan(symbols, classLoader, state);
                    else if (XPAN_MODE.equals(from) || TextUtils.isEmpty(from)) leaveXpan(symbols, classLoader, state);
                } catch (Throwable error) {
                    Log.e(TAG, "slot swap failed " + from + " -> " + to, error);
                }
            }
        });
        hookStyleJson(symbols, state, classLoader);
        Log.i(TAG, "installed");
    }

    /**
     * The photo watermark JSON is cached by the camera (WatermarkStyles.load) and is not reloaded when
     * the slot is swapped, so the capture would still burn in the previous mode's style. The JSON the
     * camera returns is checked against the style in the slot; if it is another style, the slot
     * style's own file is returned instead. POP's own style (pld) is left alone.
     */
    private static void hookStyleJson(final Symbols symbols, final State state, final ClassLoader classLoader) throws Throwable {
        XposedBridge.hookMethod(symbols.method("WatermarkStyles.load"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                // args: mode name, filter, capture identity, video. GR has its own style; video is not touched.
                if (Boolean.TRUE.equals(param.args[3]) || "gr".equals(param.args[0]) || !Settings.get(ModuleSettings.XPAN_WATERMARK_ISOLATION)) return;
                try {
                    synchronized (state) {
                        if (state.live == null) init(symbols, state);
                    }
                    String style = read(symbols, classLoader, state.live.style, "");
                    if (style.isEmpty()) return;
                    Object current = param.getResult();
                    String shown = current instanceof JSONObject ? ((JSONObject) current).optString("styleId") : "";
                    if (style.equals(shown) || POP_STYLE.equals(shown)) return;
                    File json = styleJson(PHOTO_DIR, style);
                    if (!json.isFile()) return;
                    param.setResult(new JSONObject(new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8)));
                    Log.i(TAG, "photo watermark JSON was " + (shown.isEmpty() ? "missing" : shown) + ", using " + style);
                } catch (Throwable error) {
                    Log.e(TAG, "watermark JSON check failed", error);
                }
            }
        });
    }

    private static void init(Symbols symbols, State state) throws Throwable {
        Keys live = new Keys();
        live.style = DataStore.key(symbols, "Keys.styleId", STYLE);
        live.backup = DataStore.key(symbols, "Keys.styleId", BACKUP);
        live.open = DataStore.key(symbols, "Keys.styleId", OPEN);
        live.capture = DataStore.key(symbols, "Keys.styleId", SWITCH);
        state.xpan = savedKeys(symbols, "xpan");
        state.other = savedKeys(symbols, "other");
        state.live = live;
    }

    private static Keys savedKeys(Symbols symbols, String name) throws Throwable {
        Keys keys = new Keys();
        keys.style = symbols.call("DataKeys.prefs", null, String.class, SAVED_PREFIX + name + "_style");
        keys.backup = symbols.call("DataKeys.prefs", null, String.class, SAVED_PREFIX + name + "_backup");
        keys.open = symbols.call("DataKeys.prefs", null, String.class, SAVED_PREFIX + name + "_open");
        keys.capture = symbols.call("DataKeys.prefs", null, String.class, SAVED_PREFIX + name + "_switch");
        return keys;
    }

    /** DataManager.getOr dereferences its fallback and casts it to the key's type, so pass one of that type. */
    private static String read(Symbols symbols, ClassLoader loader, Object key, Object fallback) throws Throwable {
        Object value = DataStore.get(symbols, loader, key, fallback);
        return String.valueOf(value == null ? fallback : value);
    }

    private static void setCapture(Symbols symbols, ClassLoader loader, Keys keys, String value) throws Throwable {
        symbols.call("DataManager.putSync", DataStore.manager(loader), keys.capture, value, Boolean.FALSE);
    }

    /** Copies the live slot into a saved copy (open state as text), and keeps the style's files. */
    private static void save(Symbols symbols, ClassLoader loader, Keys live, Keys copy) throws Throwable {
        String style = read(symbols, loader, live.style, "");
        DataStore.put(symbols, loader, copy.style, style);
        DataStore.put(symbols, loader, copy.backup, read(symbols, loader, live.backup, ""));
        DataStore.put(symbols, loader, copy.open, read(symbols, loader, live.open, Boolean.FALSE));
        DataStore.put(symbols, loader, copy.capture, read(symbols, loader, live.capture, "off"));
        backupFiles(style);
    }

    /** Puts a saved copy into the live slot if its style files can be made available; empties the copy. */
    private static boolean restore(Symbols symbols, ClassLoader loader, Keys copy, Keys live) throws Throwable {
        String style = read(symbols, loader, copy.style, "");
        if (style.isEmpty()) return false;
        if (!ensureFiles(style)) {
            Log.i(TAG, "style files are gone, not restored: " + style);
            return false;
        }
        DataStore.put(symbols, loader, live.style, style);
        DataStore.put(symbols, loader, live.backup, read(symbols, loader, copy.backup, style));
        DataStore.put(symbols, loader, live.open, Boolean.valueOf(read(symbols, loader, copy.open, "false")));
        setCapture(symbols, loader, live, read(symbols, loader, copy.capture, "off"));
        DataStore.put(symbols, loader, copy.style, "");
        return true;
    }

    /** No watermark in the live slot; the backup is what the switch restores, so it must not keep XPAN either. */
    private static void clear(Symbols symbols, ClassLoader loader, Keys live) throws Throwable {
        DataStore.put(symbols, loader, live.style, "");
        DataStore.put(symbols, loader, live.open, Boolean.FALSE);
        setCapture(symbols, loader, live, "off");
        if (read(symbols, loader, live.backup, "").startsWith(XPAN_STYLE_PREFIX)) DataStore.put(symbols, loader, live.backup, "");
    }

    /** Leaving XPAN (or launching outside it): the XPAN style goes aside and the other mode's watermark comes back. */
    private static void leaveXpan(Symbols symbols, ClassLoader loader, State state) throws Throwable {
        String style = read(symbols, loader, state.live.style, "");
        if (!style.startsWith(XPAN_STYLE_PREFIX)) return;
        save(symbols, loader, state.live, state.xpan);
        boolean restored = restore(symbols, loader, state.other, state.live);
        if (!restored) clear(symbols, loader, state.live);
        Log.i(TAG, "left XPAN, saved " + style + (restored ? ", other watermark restored" : ", slot cleared"));
    }

    /** Entering XPAN: another mode's watermark goes aside and the XPAN one comes back. */
    private static void enterXpan(Symbols symbols, ClassLoader loader, State state) throws Throwable {
        String style = read(symbols, loader, state.live.style, "");
        if (style.startsWith(XPAN_STYLE_PREFIX)) return;
        if (!style.isEmpty()) save(symbols, loader, state.live, state.other);
        boolean restored = restore(symbols, loader, state.xpan, state.live);
        Log.i(TAG, "entered XPAN, " + (restored ? "restored XPAN watermark" : "no saved XPAN watermark") + (style.isEmpty() ? "" : ", set aside " + style));
    }

    private static File styleJson(String dir, String style) {
        return new File(dir + "/" + style + "/" + style + ".json");
    }

    /** Keeps a copy of the style's resource folder where the camera's cleanup does not reach. */
    private static void backupFiles(String style) {
        if (style.isEmpty() || !styleJson(PHOTO_DIR, style).isFile()) return;
        try {
            copyTree(new File(PHOTO_DIR, style).toPath(), new File(BACKUP_DIR, style).toPath());
        } catch (IOException error) {
            Log.e(TAG, "style backup failed " + style, error);
        }
    }

    /** True if the style's files are in place; a missing folder is copied back from the backup. */
    private static boolean ensureFiles(String style) {
        if (styleJson(PHOTO_DIR, style).isFile()) return true;
        if (!styleJson(BACKUP_DIR, style).isFile()) return false;
        try {
            copyTree(new File(BACKUP_DIR, style).toPath(), new File(PHOTO_DIR, style).toPath());
        } catch (IOException error) {
            Log.e(TAG, "style restore failed " + style, error);
            return false;
        }
        return styleJson(PHOTO_DIR, style).isFile();
    }

    private static void copyTree(final Path from, final Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
