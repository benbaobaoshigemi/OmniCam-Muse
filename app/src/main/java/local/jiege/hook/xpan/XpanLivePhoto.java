package local.jiege.hook.xpan;

import android.app.Application;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModuleSettings;
import org.json.JSONObject;

/**
 * XPAN photos become live photos: the camera's XPAN capture animation (the negative fading into the
 * photo) on the clean photo, inside the saved photo's frame, at full resolution, with the XPAN
 * shutter sound. Like the camera's live clips, the clip keeps a frame watermark (still) and has no
 * mark burned into the photo.
 *
 * The clean photo is the origin the camera hands to its watermark composite
 * (MasterWatermarkAssembler.assemble); without a watermark there is no composite and the saved photo
 * is clean. It is kept on disk, because the final photo is written later by deferred processing,
 * possibly in a new camera process. Whenever the camera writes an XPAN JPEG (the quick photo, then
 * the final one over it) the clip is attached with the camera's OLiveCreator, and a clean photo of a
 * watermarked photo is stored as src.image, as the camera does: landscape pixels with EXIF
 * orientation 6, like the camera's live clips. The gallery editor shows src.image when it takes the
 * watermark off and places the clip on it.
 */
public final class XpanLivePhoto {
    private static final String TAG = "XpanLive";
    private static final String MODE = "xpan";
    private static final String ASSEMBLER = "com.oplus.ocs.ipu.watermark.masterwatermark.MasterWatermarkAssembler";
    private static final String WATERMARK_PARAMS = "watermark.master.params";
    private static final String EXTENDER = "com.oplus.ocs.camera.OplusFileExtenderHelper";
    /** Composites of an XPAN capture (photo, thumbnail, gain map) come within this time after its request. */
    private static final long REQUEST_MS = 15_000;
    /** A capture without composite by then was saved without watermark. */
    private static final long PLAIN_AFTER_MS = 5_000;
    /** A capture belongs to a photo taken within this time of it. */
    private static final long MATCH_TIME_MS = 10_000;
    /** Deferred photos can be finished long after capture; captures are kept this long. */
    private static final long KEEP_MS = 24 * 3600_000L;
    /** Mean error (0-255) of the clean photo against the saved photo's photo area for a match. */
    private static final double MATCH_ERROR = 8.0;
    private static final int MATCH_EDGE = 512;
    private static final long WRITE_WAIT_MS = 20_000;
    private static final String PLAIN = ".plain";
    private static final Pattern DATE = Pattern.compile("mDate: (\\d+)");

    /** The XPAN capture in progress: when it was requested (elapsed and wall clock), its largest composite. */
    private static volatile long requestedAt;
    private static volatile long requestedWall;
    private static volatile long composedFor;
    private static long largestFor;
    private static long largestArea;
    /** The camera's composite running on this thread for an XPAN capture: what it reports. */
    private static final ThreadLocal<Probe> PROBE = new ThreadLocal<>();
    private static final Set<String> IN_PROGRESS = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "XpanLive");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private static final class Probe {
        Bitmap clean;
        int outputWidth;
        int outputHeight;
        int photoX = -1;
        int photoY = -1;
    }

    /**
     * The clean photo of a saved photo, JPEG, where it sits in the saved photo (its pixels) and its
     * src.image (null when the saved photo has no watermark and is the clean photo).
     */
    private static final class Clean {
        final byte[] jpeg;
        final Rect area;
        final byte[] source;

        Clean(byte[] jpeg, Rect area, byte[] source) {
            this.jpeg = jpeg;
            this.area = area;
            this.source = source;
        }
    }

    private XpanLivePhoto() {}

    public static void installCamera(ClassLoader loader) {
        Log.guard(TAG, "XPAN live photo", () -> install(loader));
    }

    private static void install(ClassLoader loader) throws Throwable {
        if (!Settings.get(ModuleSettings.XPAN_DEVELOPING_LIVE)) return;
        Symbols symbols = Symbols.get();
        XposedBridge.hookMethod(symbols.method("XpanPresenter.requestCaptureOrigin"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                long request = SystemClock.elapsedRealtime();
                long wall = System.currentTimeMillis();
                requestedAt = request;
                requestedWall = wall;
                WORKER.schedule(() -> markPlain(loader, request, wall), PLAIN_AFTER_MS, TimeUnit.MILLISECONDS);
            }
        });

        final Class<?> assembler = XposedHelpers.findClass(ASSEMBLER, loader);
        Method assemble = null;
        for (Method m : assembler.getDeclaredMethods()) {
            if ("assemble".equals(m.getName()) && m.getParameterTypes().length == 5) assemble = m;
        }
        if (assemble == null) throw new NoSuchMethodException(ASSEMBLER + ".assemble");
        XposedBridge.hookMethod(assemble, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                long request = requestedAt;
                if (request == 0 || SystemClock.elapsedRealtime() - request > REQUEST_MS) return;
                composedFor = request;
                try {
                    Bitmap clean = origin(assembler, param.args[2]);
                    long area = (long) clean.getWidth() * clean.getHeight();
                    // The camera composites the photo and smaller companions (thumbnail, gain map); keep the largest.
                    synchronized (XpanLivePhoto.class) {
                        if (largestFor == request && largestArea >= area) return;
                        largestFor = request;
                        largestArea = area;
                    }
                    Probe probe = new Probe();
                    probe.clean = clean;
                    PROBE.set(probe);
                } catch (Throwable error) {
                    Log.e(TAG, "clean photo unreadable", error);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Probe probe = PROBE.get();
                if (probe == null) return;
                PROBE.remove();
                if (param.hasThrowable() || probe.outputWidth <= 0 || probe.photoX < 0) return;
                save(loader, probe, requestedWall);
            }
        });
        XposedBridge.hookMethod(XposedHelpers.findMethodExact(assembler, "hardwareBufferAllocate", int.class, int.class, int.class),
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Probe probe = PROBE.get();
                    if (probe == null || probe.outputWidth > 0) return;
                    probe.outputWidth = (Integer) param.args[0];
                    probe.outputHeight = (Integer) param.args[1];
                }
            });
        XposedBridge.hookMethod(XposedHelpers.findMethodExact(assembler, "copyBufferToCoord", long.class, long.class, int.class, int.class),
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Probe probe = PROBE.get();
                    if (probe == null) return;
                    probe.photoX = (Integer) param.args[2];
                    probe.photoY = (Integer) param.args[3];
                }
            });

        // An XPAN JPEG is complete when the camera hands the quick photo to the gallery (updateQuick)
        // and when deferred processing writes the final photo over it (updateImage).
        XC_MethodHook written = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                try {
                    schedule(loader, param.args[0]);
                } catch (Throwable error) {
                    Log.e(TAG, "save callback failed", error);
                }
            }
        };
        XposedBridge.hookMethod(symbols.method("Storage.updateQuick"), written);
        XposedBridge.hookMethod(symbols.method("Storage.updateImage"), written);
        Log.i(TAG, "installed");
    }

    private static Bitmap origin(Class<?> assembler, Object origin) {
        Bitmap clean;
        if (origin instanceof Bitmap) {
            clean = ((Bitmap) origin).copy(Bitmap.Config.ARGB_8888, false);
        } else {
            long buffer = origin instanceof Long ? (Long) origin : Long.parseLong((String) origin);
            Bitmap view = (Bitmap) XposedHelpers.callStaticMethod(assembler, "hardwareBufferToBitmap8Bit", buffer);
            clean = view == null ? null : view.copy(Bitmap.Config.ARGB_8888, false);
        }
        if (clean == null) throw new IllegalStateException("origin unreadable: " + origin);
        return clean;
    }

    private static File store(Application app) {
        return new File(app.getFilesDir(), "jiege/xpan_live");
    }

    private static Application app(ClassLoader loader) {
        return (Application) XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", loader), "currentApplication");
    }

    /** Records that the capture requested at request had no composite: its photo has no watermark. */
    private static void markPlain(ClassLoader loader, long request, long wall) {
        if (composedFor == request) return;
        try {
            File dir = store(app(loader));
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
            Files.write(new File(dir, wall + PLAIN).toPath(), new byte[0]);
            Log.i(TAG, "capture " + wall + " without watermark");
        } catch (Throwable error) {
            Log.e(TAG, "plain capture mark failed", error);
        }
    }

    /**
     * Keeps the clean photo of a capture: clean.jpg (upright), src.jpg (its src.image: landscape,
     * EXIF orientation 6) and where it sits in the composite (meta.json).
     */
    private static void save(ClassLoader loader, Probe probe, long wall) {
        Log.i(TAG, "capture " + wall + ": clean " + probe.clean.getWidth() + "x" + probe.clean.getHeight() + " at "
            + probe.photoX + "," + probe.photoY + " of " + probe.outputWidth + "x" + probe.outputHeight);
        Application app = app(loader);
        WORKER.execute(() -> {
            File dir = new File(store(app), Long.toString(wall));
            File tmp = new File(store(app), wall + ".tmp");
            try {
                deleteTree(tmp);
                if (!tmp.mkdirs()) throw new IllegalStateException("cannot create " + tmp);
                try (FileOutputStream out = new FileOutputStream(new File(tmp, "clean.jpg"))) {
                    if (!probe.clean.compress(Bitmap.CompressFormat.JPEG, 97, out)) throw new IllegalStateException("JPEG encode failed");
                }
                Matrix landscape = new Matrix();
                landscape.postRotate(270);
                Bitmap source = Bitmap.createBitmap(probe.clean, 0, 0, probe.clean.getWidth(), probe.clean.getHeight(), landscape, false);
                File sourceFile = new File(tmp, "src.jpg");
                try (FileOutputStream out = new FileOutputStream(sourceFile)) {
                    if (!source.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw new IllegalStateException("JPEG encode failed");
                } finally {
                    source.recycle();
                }
                ExifInterface exif = new ExifInterface(sourceFile.getAbsolutePath());
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));
                exif.saveAttributes();
                JSONObject meta = new JSONObject().put("x", probe.photoX).put("y", probe.photoY)
                    .put("outputWidth", probe.outputWidth).put("outputHeight", probe.outputHeight);
                Files.write(new File(tmp, "meta.json").toPath(), meta.toString().getBytes(StandardCharsets.UTF_8));
                deleteTree(dir);
                if (!tmp.renameTo(dir)) throw new IllegalStateException("cannot publish " + dir);
            } catch (Throwable error) {
                Log.e(TAG, "saving capture " + wall + " failed", error);
            } finally {
                probe.clean.recycle();
            }
        });
    }

    private static void schedule(ClassLoader loader, Object picture) throws Exception {
        if (picture == null || !isXpanJpeg(picture)) return;
        Matcher date = DATE.matcher(picture.toString());
        if (!date.find()) throw new IllegalStateException("no capture time in " + picture);
        long takenAt = Long.parseLong(date.group(1));
        Application app = app(loader);
        String path = path(app, picture);
        if (path == null) throw new IllegalStateException("no file for " + picture);
        if (!IN_PROGRESS.add(path)) return;
        WORKER.execute(() -> {
            try {
                attach(app, loader, new File(path), takenAt);
            } catch (Throwable error) {
                Log.e(TAG, "XPAN live photo failed: " + path, error);
            } finally {
                IN_PROGRESS.remove(path);
            }
        });
    }

    /** The JPEG file: its path field, or the _data of its MediaStore uri (deferred photos have no path). */
    private static String path(Application app, Object picture) throws IllegalAccessException {
        String path = (String) XposedHelpers.getObjectField(picture, "mPath");
        if (path != null) return path;
        for (Field field : picture.getClass().getDeclaredFields()) {
            if (field.getType() != Uri.class) continue;
            field.setAccessible(true);
            Uri uri = (Uri) field.get(picture);
            if (uri == null) continue;
            try (Cursor cursor = app.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
            }
        }
        return null;
    }

    private static boolean isXpanJpeg(Object picture) throws IllegalAccessException {
        boolean xpan = false;
        boolean jpeg = false;
        for (Field field : picture.getClass().getDeclaredFields()) {
            if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(picture);
            if (MODE.equals(value)) xpan = true;
            if ("jpeg".equals(value)) jpeg = true;
        }
        return xpan && jpeg;
    }

    /** Renders the clip from the photo's clean photo and attaches it. */
    private static void attach(Application app, ClassLoader loader, File jpeg, long takenAt) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        for (long until = SystemClock.elapsedRealtime() + WRITE_WAIT_MS; ; Thread.sleep(250)) {
            BitmapFactory.decodeFile(jpeg.getAbsolutePath(), bounds);
            if (bounds.outWidth > 0 || SystemClock.elapsedRealtime() > until) break;
        }
        if (bounds.outWidth <= 0) throw new IllegalStateException("unreadable " + jpeg);
        Clean clean = cleanPhoto(app, jpeg, bounds.outWidth, bounds.outHeight, takenAt);
        if (clean == null) return;

        File cache = new File(app.getCacheDir(), "xpan_live");
        if (!cache.isDirectory() && !cache.mkdirs()) throw new IllegalStateException("no cache dir " + cache);
        File silent = File.createTempFile("silent_", ".mp4", cache);
        File video = File.createTempFile("live_", ".mp4", cache);
        File staged = new File(jpeg.getParentFile(), ".live_" + jpeg.getName());
        try {
            int orientation = XpanDevelopingVideo.orientation(jpeg);
            XpanDevelopingVideo.Clip clip = XpanDevelopingVideo.encode(jpeg, bounds.outWidth, bounds.outHeight,
                clean.jpeg, clean.area, orientation, silent);
            XpanShutterAudio.mux(app, silent, video, clip.rotation);
            XpanLiveExtension.write(video, clip);
            long modified = jpeg.lastModified();
            Files.copy(jpeg.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (clean.source != null) writeSourceImage(app, loader, staged, clean.source);
            writeVideoDisplayRect(app, loader, staged, clip.display);
            Class<?> creatorType = XposedHelpers.findClass("com.oplus.gallery.olive_editor.OLiveCreator", loader);
            Object creator = XposedHelpers.callStaticMethod(creatorType, "create", app, staged.getAbsolutePath());
            if (creator == null) throw new IllegalStateException("OLiveCreator rejected " + staged);
            int result;
            try (FileInputStream input = new FileInputStream(video)) {
                result = (Integer) XposedHelpers.callMethod(creator, "setVideoData", input, clip.coverUs, "video/mp4", "oplus");
            }
            if (result == 6) {
                Log.i(TAG, "already live: " + jpeg);
                return;
            }
            if (result != 0) throw new IllegalStateException("OLiveCreator.setVideoData returned " + result);
            Files.move(staged.toPath(), jpeg.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            jpeg.setLastModified(modified);
            MediaScannerConnection.scanFile(app, new String[]{jpeg.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            Log.i(TAG, "live photo saved: " + jpeg + " (" + bounds.outWidth + "x" + bounds.outHeight + "), clip "
                + clip.width + "x" + clip.height + " " + clip.mime + ", " + video.length() + " bytes");
        } finally {
            Files.deleteIfExists(staged.toPath());
            Files.deleteIfExists(silent.toPath());
            Files.deleteIfExists(video.toPath());
        }
    }

    /** Stores the clean photo as the src.image extension, as the camera does for watermarked photos. */
    private static void writeSourceImage(Application app, ClassLoader loader, File file, byte[] clean) {
        Class<?> extender = XposedHelpers.findClass(EXTENDER, loader);
        Object helper = XposedHelpers.callStaticMethod(extender, "creator", app.getContentResolver(), Uri.fromFile(file));
        XposedHelpers.callMethod(helper, "setExtensionData", "src.image", clean);
        XposedHelpers.callMethod(helper, "close");
    }

    /**
     * Points the frame watermark's videoDisplayRect (watermark.master.params) at the photo area in the
     * upright clip, where the gallery editor places the clip inside the frame. The camera leaves it
     * empty for watermarks drawn over the photo, which play the whole clip; those stay as they are.
     */
    private static void writeVideoDisplayRect(Application app, ClassLoader loader, File file, Rect display) {
        Class<?> extender = XposedHelpers.findClass(EXTENDER, loader);
        Object helper = XposedHelpers.callStaticMethod(extender, "creator", app.getContentResolver(), Uri.fromFile(file));
        try {
            byte[] params = (byte[]) XposedHelpers.callMethod(helper, "getExtensionDataByteArray", WATERMARK_PARAMS);
            int rect = videoDisplayRectOffset(params);
            if (rect < 0) return;
            ByteBuffer buffer = ByteBuffer.wrap(params).order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.getFloat(rect + 8) <= buffer.getFloat(rect) || buffer.getFloat(rect + 12) <= buffer.getFloat(rect + 4)) return;
            buffer.putFloat(rect, display.left).putFloat(rect + 4, display.top)
                .putFloat(rect + 8, display.right).putFloat(rect + 12, display.bottom);
            XposedHelpers.callMethod(helper, "setExtensionData", WATERMARK_PARAMS, params);
        } finally {
            XposedHelpers.callMethod(helper, "close");
        }
    }

    /**
     * Offset of videoDisplayRect in watermark.master.params, laid out as the gallery reads it
     * (AiWatermarkFileExtendInfo): version, style id, background, bitmaps, imageDisplayRect,
     * videoDisplayRect. -1 if absent.
     */
    private static int videoDisplayRectOffset(byte[] params) {
        if (params == null) return -1;
        ByteBuffer buffer = ByteBuffer.wrap(params).order(ByteOrder.LITTLE_ENDIAN);
        try {
            int pos = 4 + 64 + 2 + 16 + 128;
            int bitmaps = buffer.getShort(pos);
            pos += 2;
            for (int i = 0; i < bitmaps; i++) {
                int texts = buffer.getInt(pos);
                pos += 4 + Math.max(texts, 0) * (4 + 2 + 512 + 512 + 256 + 2 + 256 + 1) + 16;
                pos += 4 + Math.max(buffer.getInt(pos), 0);
                pos += 4 + Math.max(buffer.getInt(pos), 0);
                pos += 4;
            }
            pos += 16;
            return pos + 16 <= params.length ? pos : -1;
        } catch (IndexOutOfBoundsException malformed) {
            return -1;
        }
    }

    /**
     * The clean photo of the capture that took this photo: the stored capture closest in time whose
     * clean photo matches the photo's photo area, or the photo itself when the capture had no
     * watermark. Null if neither.
     */
    private static Clean cleanPhoto(Application app, File jpeg, int width, int height, long takenAt) throws Exception {
        File[] entries = store(app).listFiles();
        if (entries == null) entries = new File[0];
        long now = System.currentTimeMillis();
        File best = null;
        File plain = null;
        for (File entry : entries) {
            String name = entry.getName();
            boolean isPlain = name.endsWith(PLAIN);
            long at;
            try {
                at = Long.parseLong(isPlain ? name.substring(0, name.length() - PLAIN.length()) : name);
            } catch (NumberFormatException notCapture) {
                if (now - entry.lastModified() > KEEP_MS) deleteTree(entry);
                continue;
            }
            if (now - at > KEEP_MS) {
                deleteTree(entry);
                continue;
            }
            long distance = Math.abs(at - takenAt);
            if (distance > MATCH_TIME_MS) continue;
            if (isPlain) {
                if (plain == null || distance < Math.abs(stamp(plain) - takenAt)) plain = entry;
            } else if (best == null || distance < Math.abs(stamp(best) - takenAt)) {
                best = entry;
            }
        }
        if (best != null) {
            JSONObject meta = new JSONObject(new String(Files.readAllBytes(new File(best, "meta.json").toPath()), StandardCharsets.UTF_8));
            int outputWidth = meta.getInt("outputWidth");
            int outputHeight = meta.getInt("outputHeight");
            float aspect = width / (float) height;
            float outputAspect = outputWidth / (float) outputHeight;
            if (Math.abs(aspect - outputAspect) > aspect * 0.01f) {
                Log.i(TAG, "capture " + best.getName() + " is " + outputWidth + "x" + outputHeight + ", not " + width + "x" + height);
                return null;
            }
            File cleanFile = new File(best, "clean.jpg");
            BitmapFactory.Options cleanBounds = new BitmapFactory.Options();
            cleanBounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(cleanFile.getAbsolutePath(), cleanBounds);
            float scale = width / (float) outputWidth;
            int x = meta.getInt("x");
            int y = meta.getInt("y");
            Rect area = new Rect(Math.round(x * scale), Math.round(y * scale),
                Math.round((x + cleanBounds.outWidth) * scale), Math.round((y + cleanBounds.outHeight) * scale));
            double error = matchError(cleanFile, jpeg, area);
            Log.i(TAG, String.format(Locale.ROOT, "%s from capture %s, clean %dx%d, error %.2f", jpeg.getName(), best.getName(),
                cleanBounds.outWidth, cleanBounds.outHeight, error));
            return error <= MATCH_ERROR ? new Clean(Files.readAllBytes(cleanFile.toPath()), area,
                Files.readAllBytes(new File(best, "src.jpg").toPath())) : null;
        }
        if (plain != null) {
            Log.i(TAG, jpeg.getName() + " without watermark, capture " + plain.getName());
            return new Clean(Files.readAllBytes(jpeg.toPath()), new Rect(0, 0, width, height), null);
        }
        Log.i(TAG, "no capture for " + jpeg.getName() + " taken at " + takenAt);
        return null;
    }

    private static long stamp(File entry) {
        return Long.parseLong(entry.getName().replace(PLAIN, ""));
    }

    /** Mean per-channel error (0-255) of the clean photo against the saved photo's area, both small. */
    private static double matchError(File clean, File jpeg, Rect area) {
        Bitmap a = small(clean, null);
        Bitmap b = Bitmap.createScaledBitmap(small(jpeg, area), a.getWidth(), a.getHeight(), true);
        int n = a.getWidth() * a.getHeight();
        int[] pa = new int[n];
        int[] pb = new int[n];
        a.getPixels(pa, 0, a.getWidth(), 0, 0, a.getWidth(), a.getHeight());
        b.getPixels(pb, 0, a.getWidth(), 0, 0, a.getWidth(), a.getHeight());
        long sum = 0;
        for (int i = 0; i < n; i++) {
            for (int shift = 0; shift <= 16; shift += 8) sum += Math.abs(((pa[i] >> shift) & 0xff) - ((pb[i] >> shift) & 0xff));
        }
        return sum / (3.0 * n);
    }

    /** A JPEG (or one area of it) decoded with its long side near MATCH_EDGE. */
    private static Bitmap small(File file, Rect area) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MATCH_EDGE) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new IllegalStateException("cannot decode " + file);
        if (area == null) return bitmap;
        Rect r = new Rect(area.left / sample, area.top / sample, area.right / sample, area.bottom / sample);
        r.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        return Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height());
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
