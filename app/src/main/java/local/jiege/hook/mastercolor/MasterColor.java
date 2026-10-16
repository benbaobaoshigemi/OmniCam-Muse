package local.jiege.hook.mastercolor;

import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.content.Context;
import android.app.AndroidAppHelper;
import java.util.HashMap;
import java.util.Map;
import android.hardware.camera2.params.ColorSpaceTransform;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.MasterAlign;
import local.jiege.settings.MasterLooks;
import local.jiege.settings.ModuleSettings;

/**
 * Master colour (大师色彩), Java half: live look tables (HNCS, Lumix, film, ...) in place of the master LMT.
 * The capture half and the baker are native (master_color.cpp, master_core.cpp, gr_basictone.cpp).
 *
 * Selection: the module setting (MasterLooks, 大师色彩 page) is mirrored to files/jiege/mastercolor/look,
 * which the native capture hooks read per frame; "oppo" restores the stock master LMT. In blend mode
 * the look file also carries the tone and colour weights (percent); blend mode has no "oppo" family,
 * and 0 % / 0 % keeps nothing of the look, so it is written as "oppo".
 *
 * Preview: master preview runs the stock LMTLut (the same table as capture) through CombineLut, so
 * its codes carry the capture encoding; the physical sensor calibration, CCT and CCM define each table. The latest
 * master preview TotalCaptureResult gives both; they are
 * written to files/jiege/mastercolor/live/p.../key.bin and CombineLutHelper.init is pointed there. The
 * native path hooks bake the table on the first LMTLut read.
 */
public final class MasterColor {
    private static final String TAG = "MasterColor";
    private static final String MASTER_MODE = "professional";
    private static final String ROOT = "/data/user/0/com.oplus.camera/files/jiege/mastercolor";
    private static final String LIVE = ROOT + "/live/";
    private static final int PREVIEW_INTERVAL_MS = 200;
    // Some streams (dual-camera portrait) alternate two physical cameras frame by frame; the preview follows a
    // new camera only after it has been the only one reporting for this long.
    private static final int CAMERA_SWITCH_MS = 300;
    private static final int CCT_STEP = 100;
    private static final int CCT_HYSTERESIS = 40;
    private static final float PREVIEW_MATRIX_CHANGE = 0.02f;
    private static final long SETTING_INTERVAL_MS = 1000;

    private static final CaptureResult.Key<Integer> AWB_CCT = key("org.quic.camera2.statsconfigs.AWBFrameControlCCT", Integer.class);

    private static volatile boolean enabled = true;
    private static volatile String look;
    private static volatile float exposureEv;
    private static volatile int tonePercent = 100;
    private static volatile int colourPercent = 100;
    private static volatile int[] densityPercent = {100, 100, 100};
    private static volatile int[] paramValues = new int[0];   // setting units of MasterLooks.paramsFor(look)
    private static volatile long settingCheckedAt;
    private static volatile boolean inMaster;
    private static volatile Object manager;
    private static volatile Object[] lastLoadArgs;
    private static long previewResultAt;
    private static String candidateCameraId;
    private static long candidateSince;

    // Current source metadata and its preview directory.
    private static String keyIdentity;
    private static int previewCct;
    private static float[] previewCcm;
    private static final Map<String, String> lenses = new HashMap<>();
    private static volatile String previewDir;
    private static volatile String loadedDir;
    private static volatile int generation;   // bumped by every look change; a request of an older one is not published

    // Preview table worker: one thread. Requests queued while a table is baking are reduced to the latest; a table
    // that is baking always finishes and is published (the preview then follows the newest within one bake), and it is
    // baked before it is published, so the camera never waits for a bake.
    private static final class Request {
        final int generation, cct;
        final float[] ccm;
        final String look, lens;
        final float ev;
        final int tone, colour;
        final int[] density, values;
        Request(int generation, int cct, float[] ccm, String look, float ev, int tone, int colour, int[] density, String lens, int[] values) {
            this.generation = generation; this.cct = cct; this.ccm = ccm; this.look = look; this.ev = ev;
            this.tone = tone; this.colour = colour; this.density = density; this.lens = lens; this.values = values;
        }
    }
    private static final Request CLEAR = new Request(0, 0, null, null, 0, 0, 0, null, null, null);
    private static final Object QUEUE = new Object();
    private static Request pending;
    private static Thread worker;
    private static final long LENS_RETRY_MS = 2000;
    private static final Map<String, Long> lensFailedAt = new HashMap<>();

    private MasterColor() {}

    @SuppressWarnings("unchecked")
    private static <T> CaptureResult.Key<T> key(String name, Class<T> type) {
        return (CaptureResult.Key<T>) XposedHelpers.newInstance(CaptureResult.Key.class,
            new Class<?>[] {String.class, Class.class}, name, type);
    }

    public static void installCamera(ClassLoader classLoader) {
        new File(LIVE).mkdirs();
        Log.guard(TAG, "master colour preview LUT", () -> hookPreviewLut(classLoader));
        Log.guard(TAG, "master colour preview results", MasterColor::hookResults);
        Log.guard(TAG, "master colour mode", () -> hookModeSwitch());
        Log.i(TAG, "installed");
    }

    // ---------------------------------------------------------------- switch
    private static void syncSetting() {
        long now = System.currentTimeMillis();
        if (now - settingCheckedAt < SETTING_INTERVAL_MS) return;
        settingCheckedAt = now;
        boolean blend = Settings.getInt(ModuleSettings.MASTER_LOOK_MODE) == MasterLooks.MODE_BLEND;
        int family = Settings.getInt(ModuleSettings.MASTER_LOOK);
        if (blend && family == MasterLooks.FAMILY_OPPO) family = MasterLooks.FAMILY_HNCS;
        String id = MasterLooks.lookId(family, Settings.getInt(ModuleSettings.MASTER_LOOK_OPPO),
            Settings.getInt(ModuleSettings.MASTER_LOOK_LUMIX), Settings.getInt(ModuleSettings.MASTER_LOOK_FILM),
            Settings.getInt(ModuleSettings.MASTER_LOOK_CANON),
            Settings.getInt(ModuleSettings.MASTER_LOOK_DARKTABLE), Settings.getInt(ModuleSettings.MASTER_LOOK_BASIC),
            Settings.getInt(ModuleSettings.MASTER_LOOK_PRINT),
            Settings.getInt(ModuleSettings.MASTER_HNCS_INTENT), Settings.getInt(ModuleSettings.MASTER_HNCS_CURVE));
        int tone = blend ? clampPercent(Settings.getInt(ModuleSettings.MASTER_BLEND_TONE)) : 100;
        int colour = blend ? clampPercent(Settings.getInt(ModuleSettings.MASTER_BLEND_COLOUR)) : 100;
        if (tone == 0 && colour == 0) id = "oppo";
        float ev = Math.max(-400, Math.min(400, Settings.getInt(ModuleSettings.MASTER_LOOK_EXPOSURE))) / 100f
            + MasterAlign.offset(id, Settings.getInt(ModuleSettings.MASTER_ALIGN));
        ev = Math.max(-4f, Math.min(4f, Math.round(ev * 100f) / 100f));
        int[] density = new int[] {
            Math.max(50, Math.min(150, Settings.getInt(ModuleSettings.MASTER_FILM_DENSITY_C))),
            Math.max(50, Math.min(150, Settings.getInt(ModuleSettings.MASTER_FILM_DENSITY_M))),
            Math.max(50, Math.min(150, Settings.getInt(ModuleSettings.MASTER_FILM_DENSITY_Y))),
        };
        if (family != MasterLooks.FAMILY_FILM || MasterLooks.isPositiveFilm(Settings.getInt(ModuleSettings.MASTER_LOOK_FILM)))
            density = new int[] {100, 100, 100};
        MasterLooks.Param[] parameters = MasterLooks.paramsFor(id);
        int[] values = new int[parameters.length];
        for (int i = 0; i < values.length; ++i)
            values[i] = Math.max(parameters[i].min, Math.min(parameters[i].max, Settings.getInt(parameters[i].key)));
        if (id.equals(look) && ev == exposureEv && tone == tonePercent && colour == colourPercent
            && Arrays.equals(density, densityPercent) && Arrays.equals(values, paramValues)) return;
        try {
            File tmp = new File(ROOT, "look.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                // Line 1 look, line 2 exposure compensation in EV, lines 3 and 4 the blend weights, line 5 CMY density, all in percent,
                // line 6 the look parameters, four numbers in display units, "nan" = the look's default (master_color.cpp masterColorLook).
                out.write((id + "\n" + String.format(Locale.ROOT, "%.2f", ev) + "\n" + tone + "\n" + colour + "\n" + density[0] + " " + density[1] + " " + density[2]
                    + "\n" + parameterText(parameters, values))
                    .getBytes(StandardCharsets.US_ASCII));
            }
            if (!tmp.renameTo(new File(ROOT, "look"))) Log.i(TAG, "look file rename failed");
        } catch (Throwable error) {
            Log.e(TAG, "look file", error);
        }
        synchronized (MasterColor.class) {
            look = id;
            exposureEv = ev;
            tonePercent = tone;
            colourPercent = colour;
            densityPercent = density;
            paramValues = values;
            enabled = !"oppo".equals(id);
            keyIdentity = null; // the next result writes a key for the new look
            previewResultAt = 0;
            previewDir = null;
            ++generation;
        }
        Log.i(TAG, "master colour " + id + (blend ? " blend tone " + tone + "% colour " + colour + "%" : ""));
        submit(CLEAR);
    }

    // ---------------------------------------------------------------- preview LUT
    private static void hookPreviewLut(ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        final ThreadLocal<String> liveDir = new ThreadLocal<>();
        XposedBridge.hookMethod(symbols.method("Lut3d.load"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                syncSetting();
                manager = param.thisObject;
                boolean master = MASTER_MODE.equals(param.args[2]);
                boolean wasMaster = MASTER_MODE.equals(symbols.get("Lut3d.mode", param.thisObject));
                inMaster = master;
                if (master) lastLoadArgs = param.args.clone();
                String dir = master && enabled ? previewDir : null;
                // Entering / leaving master or a new look table invalidates the cached LUT (index -1).
                if (master != wasMaster || (master && !String.valueOf(dir).equals(String.valueOf(loadedDir)))) {
                    symbols.set("Lut3d.index", param.thisObject, -1);
                }
                if (master) loadedDir = dir;
                liveDir.set(dir);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                liveDir.remove();
            }
        });
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.oplus.ocs.camera.CombineLutHelper", classLoader), "init",
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    String dir = liveDir.get();
                    if (dir != null) {
                        param.args[0] = dir;
                        Log.i(TAG, "preview LMT " + dir);
                    }
                }
            });
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private static void submit(Request request) {
        synchronized (QUEUE) {
            pending = request;
            if (worker == null) {
                worker = new Thread(MasterColor::workerLoop, "MasterColorWorker");
                worker.setDaemon(true);
                worker.start();
            }
            QUEUE.notifyAll();
        }
    }

    private static boolean superseded() {
        synchronized (QUEUE) { return pending != null; }
    }

    private static void workerLoop() {
        for (;;) {
            Request request;
            synchronized (QUEUE) {
                while (pending == null) {
                    try { QUEUE.wait(); } catch (InterruptedException ignored) { return; }
                }
                request = pending;
                pending = null;
            }
            try {
                process(request);
            } catch (Throwable error) {
                Log.e(TAG, "preview table", error);
            }
        }
    }

    private static void process(Request request) throws Throwable {
        String dir = null;
        if (request != CLEAR) {
            dir = writeKey(request.cct, request.ccm, request.look, request.ev, request.tone, request.colour, request.density, request.lens, request.values);
            if (dir == null) { clearPreview("preview: key write failed"); return; }
            // The native path hooks bake a table on the first read of its LMTLut0, here and not on a camera thread.
            if (superseded()) return;   // a newer table is already asked for: bake that one
            new File(dir, "LMTLut0").exists();
            synchronized (MasterColor.class) {
                if (request.generation != generation) return;
                previewDir = dir;
            }
            Log.i(TAG, "preview table " + dir);
        }
        reload(dir);
    }

    /** Re-runs the manager's load for the master mode so a new table reaches the preview. */
    private static void reload(String dir) throws Throwable {
        final Object lut = manager;
        final Object[] args = lastLoadArgs;
        if (lut == null || args == null || !inMaster) return;
        String wanted = enabled ? dir : null;
        if (String.valueOf(wanted).equals(String.valueOf(loadedDir))) return;
        Symbols symbols = Symbols.get();
        symbols.set("Lut3d.index", lut, -1);
        symbols.call("Lut3d.load", lut, args);
    }

    // ---------------------------------------------------------------- mode
    /** Leaving master stops the preview work at once; Lut3d.load alone is not called by every mode. */
    private static void hookModeSwitch() {
        XposedBridge.hookMethod(Symbols.get().method("ModeSwitch.switch"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                String to = (String) param.args[1];
                if (to == null || to.isEmpty()) return;
                boolean master = MASTER_MODE.equals(to);
                if (master == inMaster) return;
                inMaster = master;
                if (!master) leaveMaster();
            }
        });
    }

    private static void leaveMaster() {
        synchronized (QUEUE) { pending = null; }
        synchronized (MasterColor.class) {
            keyIdentity = null;
            candidateCameraId = null;
            previewResultAt = 0;
        }
    }

    // ---------------------------------------------------------------- preview results
    private static void hookResults() {
        // Android 17 hands CameraDeviceCallbacks.onResultReceived a CameraMetadataInfo (no get(Key));
        // every result still becomes a public TotalCaptureResult, read after construction.
        XposedBridge.hookAllConstructors(android.hardware.camera2.TotalCaptureResult.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!inMaster || param.hasThrowable()) return;
                Object result = param.thisObject;
                try {
                    syncSetting();
                    onPreviewResult(result);
                } catch (Throwable error) {
                    clearPreview("preview: source metadata read failed");
                    Log.e(TAG, "preview result", error);
                }
            }
        });
    }

    private static <T> T read(Object metadata, CaptureResult.Key<T> key) {
        return ((CaptureResult) metadata).get(key);
    }

    // Scene front stage lenses (scene.bin, tools/mastercolor/export_scene.py): the 35 mm equivalent focal length
    // of a physical rear camera selects its colour calibration; within 25 % of a listed focal length.
    private static final String[] LENS_NAMES = {"uw14", "main23", "tele70", "tele230"};
    private static final double[] LENS_FOCALS = {14, 23, 70, 230};

    private static String lens(String id) {
        String cached = lenses.get(id);
        if (cached != null) return cached;
        Long failed = lensFailedAt.get(id);
        if (failed != null && System.currentTimeMillis() - failed < LENS_RETRY_MS) return null;
        String found = lookupLens(id);
        if (found == null) lensFailedAt.put(id, System.currentTimeMillis());
        return found;
    }

    private static String lookupLens(String id) {
        Context app = AndroidAppHelper.currentApplication();
        if (app == null) return null;
        try {
            CameraManager cameras = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics c = cameras.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            android.util.SizeF size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (!c.getPhysicalCameraIds().isEmpty() || facing == null || facing != CameraCharacteristics.LENS_FACING_BACK
                || focals == null || focals.length == 0 || size == null || focals[0] <= 0) return null;
            double equivalent = focals[0] * 43.2666 / Math.hypot(size.getWidth(), size.getHeight());
            int best = -1;
            double bestError = Math.log(1.25);
            for (int i = 0; i < LENS_FOCALS.length; ++i) {
                double error = Math.abs(Math.log(equivalent / LENS_FOCALS[i]));
                if (error <= bestError) { bestError = error; best = i; }
            }
            if (best < 0) return null;
            lenses.put(id, LENS_NAMES[best]);
            return LENS_NAMES[best];
        } catch (Throwable error) {
            Log.e(TAG, "lens " + id, error);
            return null;
        }
    }

    private static float[] matrix(ColorSpaceTransform transform) {
        if (transform == null) return null;
        float[] values = new float[9];
        for (int i = 0; i < 9; ++i) values[i] = transform.getElement(i % 3, i / 3).floatValue();
        return values;
    }

    private static synchronized void clearPreview(String reason) {
        if (keyIdentity == null && previewDir == null) return;
        Log.i(TAG, reason);
        keyIdentity = null;
        if (previewDir != null) {
            previewDir = null;
            submit(CLEAR);
        }
    }

    private static synchronized void onPreviewResult(Object metadata) throws Exception {
        if (!enabled || look == null) return;
        String id = (String) read(metadata, CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
        if (id == null) id = (String) XposedHelpers.callMethod(metadata, "getCameraId");
        if (id == null) { clearPreview("preview: physical camera identity unavailable"); return; }
        long now = System.currentTimeMillis();
        if (keyIdentity != null && !id.equals(keyIdentity)) {
            if (!id.equals(candidateCameraId)) { candidateCameraId = id; candidateSince = now; return; }
            if (now - candidateSince < CAMERA_SWITCH_MS) return;
        } else {
            candidateCameraId = null;
            if (now - previewResultAt < PREVIEW_INTERVAL_MS) return;
        }
        previewResultAt = now;
        String lens = lens(id);
        Integer cct = (Integer) read(metadata, AWB_CCT);
        float[] ccm = matrix((ColorSpaceTransform) read(metadata, CaptureResult.COLOR_CORRECTION_TRANSFORM));
        if (lens == null || cct == null || cct < 1500 || cct > 20000 || ccm == null) {
            clearPreview("preview: incomplete colour metadata for camera " + id); return;
        }
        for (int i = 0; i < ccm.length; ++i) ccm[i] = Math.round(ccm[i] * 256f) / 256f;
        int bucket = previewCct;
        if (!id.equals(keyIdentity) || Math.abs(cct - previewCct) > CCT_STEP / 2 + CCT_HYSTERESIS)
            bucket = Math.max(1700, Math.min(15000, (cct + CCT_STEP / 2) / CCT_STEP * CCT_STEP));
        if (id.equals(keyIdentity)
            && bucket == previewCct
            && maxDifference(ccm, previewCcm) < PREVIEW_MATRIX_CHANGE) return;
        keyIdentity = id;
        previewCct = bucket;
        previewCcm = ccm;
        Log.i(TAG, "preview key camera=" + id + " look=" + look + " cct=" + bucket);
        submit(new Request(generation, bucket, ccm, look, exposureEv, tonePercent, colourPercent, densityPercent, lens, paramValues));
    }

    private static float maxDifference(float[] a, float[] b) {
        float difference = 0;
        for (int i = 0; i < a.length; ++i) difference = Math.max(difference, Math.abs(a[i] - b[i]));
        return difference;
    }

    /** The four look parameters in display units; entries beyond the look's own are NaN (the look file's default). */
    private static float[] parameterFloats(MasterLooks.Param[] parameters, int[] values) {
        float[] out = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
        for (int i = 0; i < Math.min(4, Math.min(parameters.length, values.length)); ++i) out[i] = values[i] / (float) parameters[i].scale;
        return out;
    }

    private static String parameterText(MasterLooks.Param[] parameters, int[] values) {
        StringBuilder text = new StringBuilder();
        for (float v : parameterFloats(parameters, values)) {
            if (text.length() > 0) text.append(' ');
            text.append(Float.isNaN(v) ? "nan" : String.format(Locale.ROOT, "%.2f", v));
        }
        return text.toString();
    }

    /** MCOLKEY8 carries the scene front stage lens and the four look parameters. */
    private static String writeKey(int cct, float[] ccm, String look, float ev, int tonePercent, int colourPercent, int[] density, String lens, int[] values) {
        float[] parameters = parameterFloats(MasterLooks.paramsFor(look), values);
        boolean blended = tonePercent < 100 || colourPercent < 100;
        float tone = tonePercent / 100f, colour = colourPercent / 100f;
        byte[] name = look.getBytes(StandardCharsets.US_ASCII);
        long hash = 1469598103934665603L;
        for (float v : ccm) hash = (hash ^ Float.floatToIntBits(v)) * 1099511628211L;
        for (byte b : name) hash = (hash ^ (b & 0xff)) * 1099511628211L;
        if (ev != 0) hash = (hash ^ Float.floatToIntBits(ev)) * 1099511628211L;
        if (blended) hash = (((hash ^ Float.floatToIntBits(tone)) * 1099511628211L) ^ Float.floatToIntBits(colour)) * 1099511628211L;
        if (!Arrays.equals(density, new int[] {100, 100, 100}))
            for (int value : density) hash = (hash ^ Float.floatToIntBits(value / 100f)) * 1099511628211L;
        byte[] camera = lens.getBytes(StandardCharsets.US_ASCII);
        for (byte b : camera) hash = (hash ^ (b & 0xff)) * 1099511628211L;
        for (float v : parameters) if (!Float.isNaN(v)) hash = (hash ^ Float.floatToIntBits(v)) * 1099511628211L;
        String dir = LIVE + String.format(Locale.ROOT, "p%05d_%016x", cct, hash);
        File folder = new File(dir);
        File key = new File(folder, "key.bin");
        if (key.exists()) {
            key.setLastModified(System.currentTimeMillis()); // recently used: native pruning keeps it
            return dir;
        }
        ByteBuffer buffer = ByteBuffer.allocate(8 + 4 * 4 + 9 * 4 + 4 + name.length + 4 + 8 + 12 + 4 + camera.length + 16).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("MCOLKEY8".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(2).putInt(cct);
        for (float v : ccm) buffer.putFloat(v);
        buffer.putInt(0).putInt(0);
        buffer.putInt(name.length).put(name);
        buffer.putFloat(ev);
        buffer.putFloat(tone).putFloat(colour);
        for (int value : density) buffer.putFloat(value / 100f);
        buffer.putInt(camera.length).put(camera);
        for (float v : parameters) buffer.putFloat(v);
        try {
            if (!folder.isDirectory() && !folder.mkdirs()) return null;
            File tmp = new File(folder, "key.bin.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(buffer.array());
            }
            if (!tmp.renameTo(key)) return null;
            return dir;
        } catch (Throwable error) {
            Log.e(TAG, "preview key write", error);
            return null;
        }
    }
}
