package local.jiege.hook.natural;

import android.hardware.camera2.CaptureRequest;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.settings.ModuleSettings;

/**
 * "Natural photo mode": keeps two files under files/jiege/natural in step with the settings, and
 * nothing else. The native side (natural_mode.cpp) reads them when the camera algorithms start a
 * capture, so a change applies to the next photo.
 *
 *   conf_tuning_params.json  stock tuning json with the selected features zeroed; absent = stock
 *   soften                   guided-filter eps of the normal-photo tone transfer; absent = stock
 */
public final class NaturalMode {
    private static final String TAG = "NaturalMode";
    private static final String STOCK_JSON = "/odm/etc/camera/conf_tuning_params.json";
    private static final File ROOT = new File("/data/user/0/com.oplus.camera/files/jiege/natural");
    private static final long CHECK_INTERVAL_MS = 500;
    /** eps range of the soften slider: 0 % is the stock value, 100 % the softest setting. */
    private static final double EPS_STOCK = 1e-7;
    private static final double EPS_SOFTEST = 1e-3;

    private static long checkedAt;
    private static String appliedConfig = "";
    private static String appliedSoften = "";
    private static native void setSoftenMode(boolean enabled);

    private NaturalMode() {}

    public static void installCamera(ClassLoader classLoader) {
        ROOT.mkdirs();
        Log.guard(TAG, "soften mode isolation", () -> XposedBridge.hookMethod(
            local.jiege.hook.common.Symbols.get().method("ModeSwitch.switch"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    String to = (String) param.args[1];
                    if (to != null && !to.isEmpty())
                        setSoftenMode(!"professional".equals(to) && !"gr".equals(to));
                }
            }));
        Log.guard(TAG, "settings sync", () -> XposedBridge.hookAllMethods(CaptureRequest.Builder.class, "build",
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    sync();
                }
            }));
        Log.i(TAG, "installed");
    }

    /** Brings the two files up to date with the settings; cheap, at most twice a second. */
    private static synchronized void sync() {
        long now = System.currentTimeMillis();
        if (now - checkedAt < CHECK_INTERVAL_MS) return;
        checkedAt = now;
        boolean on = Settings.get(ModuleSettings.NATURAL_MODE);
        syncConfig(on && Settings.get(ModuleSettings.NATURAL_NO_AI_DETAIL), on && Settings.get(ModuleSettings.NATURAL_NO_LCE));
        syncSoften(on ? Settings.getInt(ModuleSettings.NATURAL_SOFTEN) : 0);
    }

    private static void syncConfig(boolean noAiDetail, boolean noLce) {
        String wanted = noAiDetail || noLce ? "ai=" + noAiDetail + ",lce=" + noLce : "";
        if (wanted.equals(appliedConfig)) return;
        File target = new File(ROOT, "conf_tuning_params.json");
        try {
            if (wanted.isEmpty()) {
                target.delete();
            } else {
                write(target, NaturalConfig.apply(read(new File(STOCK_JSON)), noAiDetail, noLce));
            }
            appliedConfig = wanted;
            Log.i(TAG, "tuning json: " + (wanted.isEmpty() ? "stock" : wanted));
        } catch (Exception error) {
            Log.e(TAG, "tuning json", error);
            target.delete();
            appliedConfig = "";
        }
    }

    private static void syncSoften(int percent) {
        String wanted = percent <= 0 ? "" : String.format(Locale.ROOT, "%.3e", epsFor(percent));
        if (wanted.equals(appliedSoften)) return;
        File target = new File(ROOT, "soften");
        try {
            if (wanted.isEmpty()) {
                target.delete();
            } else {
                write(target, wanted);
            }
            appliedSoften = wanted;
            Log.i(TAG, "soften " + percent + "%, eps " + (wanted.isEmpty() ? "stock" : wanted));
        } catch (IOException error) {
            Log.e(TAG, "soften", error);
            target.delete();
            appliedSoften = "";
        }
    }

    /** Log-interpolated eps for 1..100 %. */
    private static double epsFor(int percent) {
        double t = Math.min(100, percent) / 100.0;
        double logStock = Math.log10(EPS_STOCK);
        return Math.pow(10, logStock + t * (Math.log10(EPS_SOFTEST) - logStock));
    }

    private static String read(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Replaces the file atomically so the native reader never sees a partial write. */
    private static void write(File target, String content) throws IOException {
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(target)) throw new IOException("rename " + tmp);
    }
}
