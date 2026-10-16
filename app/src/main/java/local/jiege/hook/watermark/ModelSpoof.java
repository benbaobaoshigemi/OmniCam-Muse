package local.jiege.hook.watermark;

import android.content.Context;
import android.hardware.HardwareBuffer;
import android.system.Os;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModelPresets;
import local.jiege.settings.ModuleSettings;

/**
 * Model disguise in the camera: the model text of the master watermark, and the EXIF Model.
 *
 * Watermark: WatermarkProcessParameter (its members are not obfuscated) keeps the market name the
 * master watermark renders (mMarketName, and mEnMarketName for styles that ask for the English
 * name); it is replaced just before the parameter is turned into the render info. The renderer
 * also asks MarketNameInfo (symbols MarketName.*) directly, so that answers the disguise too.
 *
 * EXIF: every Java path that writes the Model tag (still photo, sticker, pano, HEIC) builds the
 * tag with OplusExifInterface.buildTag(TAG_MODEL, name), so the value is replaced there. The EXIF
 * disguise only applies while the watermark disguise is on. LensModel and the Make tag are left
 * as they are. Nothing here touches system properties, so other apps still see the real device.
 *
 * Settings are read at every use, so changes apply to the next photo.
 */
public final class ModelSpoof {
    private static final String TAG = "ModelSpoof";
    private static final String PARAMETER = "com.oplus.ocs.camera.ipuapi.process.watermark.entity.WatermarkProcessParameter";
    private static final String EXIF = "com.oplus.exif.OplusExifInterface";
    private static final String ICC = "com.oplus.camera.jni.IccProfile";
    private static final String EXIF_PARAMS = "com.oplus.camera.jni.ExifParams";

    private ModelSpoof() {}

    public static void installCamera(final ClassLoader classLoader) {
        Log.guard(TAG, "watermark model", () -> hookWatermark(classLoader));
        Log.guard(TAG, "EXIF model", () -> hookExif(classLoader));
        Log.guard(TAG, "native EXIF model", () -> hookNativeExif(classLoader));
        Log.guard(TAG, "JPEG bytes", () -> hookJpegWrite(classLoader));
    }

    /** The model name of the selected preset. */
    private static String model() {
        return ModelPresets.nameFor(Settings.getString(ModuleSettings.MODEL_PRESET_ID), Settings.getString(ModuleSettings.MODEL_CUSTOM_PRESETS));
    }

    private static void hookWatermark(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(PARAMETER, classLoader, "covertToWatermarkYuvInfo", Context.class, boolean.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!Settings.get(ModuleSettings.MODEL_SPOOF_WATERMARK)) return;
                String name = model();
                XposedHelpers.setObjectField(param.thisObject, "mMarketName", name);
                XposedHelpers.setObjectField(param.thisObject, "mEnMarketName", name);
                Log.i(TAG, "watermark market name " + name);
            }
        });
        // The renderer also reads the market name straight from MarketNameInfo (GR styles do), which
        // answers with the system market name.
        XC_MethodHook marketName = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (Settings.get(ModuleSettings.MODEL_SPOOF_WATERMARK)) param.setResult(model());
            }
        };
        XposedBridge.hookMethod(Symbols.get().method("MarketName.name"), marketName);
        XposedBridge.hookMethod(Symbols.get().method("MarketName.enName"), marketName);
        Log.i(TAG, "watermark hook installed");
    }

    private static void hookExif(ClassLoader classLoader) {
        final Class<?> exif = XposedHelpers.findClass(EXIF, classLoader);
        final int tagModel = XposedHelpers.getStaticIntField(exif, "TAG_MODEL");
        XposedHelpers.findAndHookMethod(exif, "buildTag", int.class, Object.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if ((Integer) param.args[0] != tagModel) return;
                if (!Settings.get(ModuleSettings.MODEL_SPOOF_WATERMARK) || !Settings.get(ModuleSettings.MODEL_SPOOF_EXIF)) return;
                param.args[1] = model();
                Log.i(TAG, "EXIF Model tag " + param.args[1]);
            }
        });
        // buildTag is tiny and compiled callers may inline it past the hook above, so the Model tag is also
        // forced right before any write of the EXIF block (writeExif / rewriteExif / forceRewriteExif).
        XC_MethodHook beforeWrite = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!Settings.get(ModuleSettings.MODEL_SPOOF_WATERMARK) || !Settings.get(ModuleSettings.MODEL_SPOOF_EXIF)) return;
                try {
                    Object tag = XposedHelpers.callMethod(param.thisObject, "buildTag", tagModel, (Object) model());
                    if (tag != null) XposedHelpers.callMethod(param.thisObject, "setTag", tag);
                    Log.i(TAG, "EXIF write " + param.method.getName() + ", Model " + model() + (tag == null ? " (tag refused)" : ""));
                } catch (Throwable error) {
                    Log.e(TAG, "EXIF Model before write failed", error);
                }
            }
        };
        XposedBridge.hookAllMethods(exif, "writeExif", beforeWrite);
        XposedBridge.hookAllMethods(exif, "rewriteExif", beforeWrite);
        XposedBridge.hookAllMethods(exif, "forceRewriteExif", beforeWrite);
        Log.i(TAG, "EXIF hook installed");
    }

    /** Streams that started a JPEG (their later chunks may hold the camera's appended data). */
    private static final Map<Object, Boolean> jpegStreams = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> patching = new ThreadLocal<>();
    private static volatile String deviceModel;

    /**
     * The camera's JPEG files carry the model name written by native code (the HAL/APS result, not the
     * Java EXIF writer), so the bytes are patched on their way to the file: every FileOutputStream write
     * that starts a JPEG, and later writes to the same stream. The size never changes (JpegModelPatcher).
     * The hardware-buffer writer hands the bytes to native code directly; it is only logged for now.
     */
    private static void hookJpegWrite(ClassLoader classLoader) {
        XC_MethodHook write = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (patching.get() != null || !(param.args[0] instanceof byte[])) return;
                byte[] data = (byte[]) param.args[0];
                int off = param.args.length == 1 ? 0 : (Integer) param.args[1];
                int len = param.args.length == 1 ? data.length : (Integer) param.args[2];
                if (len < 64 || off < 0 || off + len > data.length) return;
                boolean start = data[off] == (byte) 0xFF && data[off + 1] == (byte) 0xD8 && data[off + 2] == (byte) 0xFF;
                if (!start && !jpegStreams.containsKey(param.thisObject)) return;
                if (!Settings.get(ModuleSettings.MODEL_SPOOF_WATERMARK) || !Settings.get(ModuleSettings.MODEL_SPOOF_EXIF)) return;
                patching.set(Boolean.TRUE);
                try {
                    if (start) jpegStreams.put(param.thisObject, Boolean.TRUE);
                    JpegModelPatcher.Result result = JpegModelPatcher.patch(data, off, len, deviceModel(), model());
                    if (start || result.changed()) {
                        Log.i(TAG, "JPEG write " + len + " bytes" + (start ? " (start)" : "") + ": Model " + (result.exif ? "replaced" : result.tooLong ? "NOT replaced, name too long" : "unchanged")
                            + ", appended copies " + result.appended + ", was " + result.original);
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "JPEG patch failed", error);
                } finally {
                    patching.remove();
                }
            }
        };
        XposedHelpers.findAndHookMethod(FileOutputStream.class, "write", byte[].class, write);
        XposedHelpers.findAndHookMethod(FileOutputStream.class, "write", byte[].class, int.class, int.class, write);
        XposedHelpers.findAndHookMethod("com.oplus.camera.jni.FormatConverter", classLoader, "writeHardwareBufferToFile", HardwareBuffer.class, int.class, long.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!Settings.get(ModuleSettings.MODEL_SPOOF_WATERMARK) || !Settings.get(ModuleSettings.MODEL_SPOOF_EXIF)) return;
                // The writer closes the descriptor, so a duplicate is kept for after the write.
                try {
                    param.setObjectExtra("file", android.os.ParcelFileDescriptor.fromFd((Integer) param.args[1]));
                } catch (Throwable error) {
                    Log.e(TAG, "hardware buffer file unavailable", error);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                android.os.ParcelFileDescriptor file = (android.os.ParcelFileDescriptor) param.getObjectExtra("file");
                if (file == null) return;
                try (file) {
                    if (Boolean.TRUE.equals(param.getResult())) Log.i(TAG, "hardware buffer wrote " + param.args[2] + " bytes: " + patchFile(file.getFileDescriptor()));
                } catch (Throwable error) {
                    Log.e(TAG, "hardware buffer file patch failed", error);
                }
            }
        });
        Log.i(TAG, "JPEG write hooks installed");
    }

    /**
     * Patches a JPEG the camera's native code has just written (the full-size result, which replaces the
     * quick snapshot) through the camera's own descriptor: the file's path is the media provider's lower
     * path, which the camera may not open again. The file is read, patched in place by JpegModelPatcher
     * and only the changed byte ranges are written back, so the file size and everything else stay as
     * they are.
     */
    private static String patchFile(java.io.FileDescriptor file) throws Exception {
        long length = Os.fstat(file).st_size;
        if (length > 256L * 1024 * 1024) return "too large";
        byte[] data = new byte[(int) length];
        for (int read = 0; read < data.length; ) {
            int n = Os.pread(file, data, read, data.length - read, read);
            if (n <= 0) throw new java.io.EOFException("read " + read + " of " + data.length);
            read += n;
        }
        byte[] before = data.clone();
        JpegModelPatcher.Result result = JpegModelPatcher.patch(data, 0, data.length, deviceModel(), model());
        if (!result.changed()) return result.tooLong ? "name too long, not replaced" : "nothing to replace, was " + result.original;
        int at = 0;
        while (at < data.length) {
            if (data[at] == before[at]) {
                at++;
                continue;
            }
            int end = at;
            while (end < data.length && data[end] != before[end]) end++;
            for (int written = at; written < end; ) written += Os.pwrite(file, data, written, end - written, written);
            at = end;
        }
        return "Model " + (result.exif ? "replaced" : "unchanged") + ", appended copies " + result.appended + ", was " + result.original;
    }

    /** The device's own market name, the way the camera takes it for EXIF (for chunks without a Model tag). */
    private static String deviceModel() {
        String cached = deviceModel;
        if (cached != null) return cached;
        String value = "";
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            for (String key : new String[] {"ro.vendor.oplus.market.enname", "ro.vendor.oplus.market.name"}) {
                value = (String) properties.getMethod("get", String.class, String.class).invoke(null, key, "");
                if (!value.isEmpty()) break;
            }
        } catch (Throwable ignored) {
        }
        deviceModel = value;
        return value;
    }

    /**
     * The native JPEG encoder (pano / YUV paths) writes Model from ExifParams.exifModel, which the
     * camera fills with the market name, so it is replaced before the call.
     */
    private static void hookNativeExif(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(ICC, classLoader, "convertYuv2JpegWithExifParams",
            byte[].class, int.class, int.class, int.class, XposedHelpers.findClass(EXIF_PARAMS, classLoader), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[4] == null || !Settings.get(ModuleSettings.MODEL_SPOOF_WATERMARK) || !Settings.get(ModuleSettings.MODEL_SPOOF_EXIF)) return;
                    XposedHelpers.setObjectField(param.args[4], "exifModel", model());
                    Log.i(TAG, "native JPEG exifModel " + model());
                }
            });
        Log.i(TAG, "native EXIF hook installed");
    }
}
