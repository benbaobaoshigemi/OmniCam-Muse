package local.jiege.settings;

/**
 * Interface between the settings app and the hooked camera (Diagnostics). The camera cannot see
 * the settings app (package visibility), so the app asks: call(CAMERA_AUTHORITY, METHOD_STATE)
 * on the camera's exported EntryProvider, whose call() the hook answers for the module's uid with
 * the keys below. The call starts the camera process when it is not running. An empty Bundle
 * means the camera is not hooked. The gallery answers the same call on its exported
 * GalleryOpenProvider (GALLERY_AUTHORITY), the one without a permission.
 */
public final class DiagnosticsContract {
    public static final String CAMERA_AUTHORITY = "com.oplus.camera.entry";
    public static final String CAMERA_PROVIDER_CLASS = "com.oplus.camera.entry.EntryProvider";
    public static final String GALLERY_PACKAGE = "com.coloros.gallery3d";
    public static final String GALLERY_AUTHORITY = "com.oppo.gallery3d.open.provider";
    public static final String GALLERY_PROVIDER_CLASS = "com.oplus.gallery.openabilitypage.GalleryOpenProvider";
    public static final String METHOD_STATE = "jiege.diagnostics";
    public static final String MODULE_PACKAGE = "local.jiege.camera";
    public static final String KEY_PROCESS = "process";
    public static final String KEY_PID = "pid";
    public static final String KEY_START = "start";
    /** The camera APK. */
    public static final String KEY_APK = "apk";
    /** The module APK the hooks were loaded from. */
    public static final String KEY_MODULE_APK = "moduleApk";
    public static final String KEY_SYMBOLS_RESOLVED = "symbolsResolved";
    public static final String KEY_SYMBOLS_MISSING = "symbolsMissing";
    /** Number of error lines (hooks that failed to install or failed at run time). */
    public static final String KEY_ERRORS = "errors";
    /** Whether the process sees the module's /odm files (false: unmounted for it, features off). */
    public static final String KEY_FILES_MOUNTED = "filesMounted";
    /** Recent module log lines of the camera process. */
    public static final String KEY_LOG = "log";
    /** The camera's master filter list (MasterFilterContract stock list text), null before the camera built it. */
    public static final String KEY_MASTER_STOCK = "masterStock";

    private DiagnosticsContract() {}
}
