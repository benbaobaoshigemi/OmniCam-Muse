package local.jiege.hook.common;

import de.robv.android.xposed.XposedBridge;

/** LSPosed log lines, prefixed with the feature tag; also kept for the exported log (Diagnostics). */
public final class Log {
    private Log() {}

    public static void i(String tag, String message) {
        String line = tag + ": " + message;
        XposedBridge.log(line);
        Diagnostics.record("I", line);
    }

    public static void e(String tag, String message, Throwable error) {
        String line = tag + ": " + message + " " + error;
        XposedBridge.log(line);
        Diagnostics.record("E", line + "\n" + android.util.Log.getStackTraceString(error));
    }

    /**
     * Runs one hook installer. A failure, typically a symbol that did not resolve on this app
     * build, turns off only that part; it is logged once with the reason.
     */
    public static void guard(String tag, String part, Installer installer) {
        try {
            installer.install();
        } catch (Throwable error) {
            e(tag, part + " unavailable", error);
        }
    }

    public interface Installer {
        void install() throws Throwable;
    }
}
