package local.jiege.hook.palette;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Gallery process: which photos the photo editor runs in palette mode (the "色彩风格" entry).
 *
 * The gallery decides palette mode once for the whole app (isColorPaletteMode, a lazy value). The
 * value is replaced by one that also asks the photo being edited: any photo that carries a
 * filter.info (every camera capture with a filter applied) gets palette mode, everything else
 * (screenshots, downloads) keeps the stock filter editor. That makes the entry consistent across
 * camera photos; the older version-5-only split sent non-palette captures to the stock filter
 * editor, where the module's master styles never show.
 *
 * IMPORTANT (X8U): the palette pipeline cannot re-render from src.image on this device. X8U
 * captures store src.image as a plain JPEG (X10 uses a private format plus basictone.info /
 * master.mode.preset.info meta), and the engine fails the meta read ("resolvePaletteSrcOrientation
 * invalid metaIndex=0" in libAlgoInterface filterProcessMeisheV2), which garbles the BasicTone
 * stage output. GalleryColorPalette therefore bypasses that stage and lets the selected filter
 * apply straight onto the rendered image; capturedFilter keeps the editor's automatic
 * re-selection of the photo's own style from double-applying.
 */
final class GalleryPaletteScope {
    private static final String TAG = "GalleryPaletteScope";
    private static final String EDITOR = "com.oplus.gallery.pictureeditorpage.PhotoEditorActivity";
    private static final String CONTAINER = "com.oplus.gallery.foundation.fileaccess.extension.FileExtendedContainer";
    private static final String OPEN_MODE = "com.oplus.gallery.foundation.fileaccess.extension.OpenFileMode";
    private static final String FILTER_INFO = "filter.info";

    /**
     * The filter the photo was captured with (filter.info +28, e.g. "hu_po.bin"), or null. The
     * palette re-render must not apply it again: the capture already has it baked in, and this
     * device's pipeline cannot start from src.image (see the class comment), so the SDK hooks use
     * this to keep the editor's automatic re-selection of the captured style a no-op.
     */
    public static volatile String capturedFilter;

    private GalleryPaletteScope() {}

    static void install(ClassLoader classLoader) {
        try {
            Symbols symbols = Symbols.get();
            if (!symbols.has("ColorPaletteMode.lazy")) {
                Log.i(TAG, "palette mode symbol missing");
                return;
            }
            Field field = symbols.field("ColorPaletteMode.lazy");
            Object base = field.get(null);
            Class<?> lazy = field.getType();
            InvocationHandler handler = (proxy, method, args) -> {
                Object value = method.invoke(base, args);
                if ("getValue".equals(method.getName())) return Boolean.TRUE.equals(value);
                return value;
            };
            field.set(null, Proxy.newProxyInstance(lazy.getClassLoader(), new Class<?>[] {lazy}, handler));
            XposedHelpers.findAndHookMethod(EDITOR, classLoader, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    readCapturedFilter(activity, editedImage(activity.getIntent()), classLoader);
                }
            });
            Log.i(TAG, "installed");
        } catch (Throwable error) {
            Log.e(TAG, "palette scope hook failed", error);
        }
    }

    /** The image the editor opens (InputArgumentsVM): editor_image_uri when started from a photo, else the intent data. */
    private static Uri editedImage(Intent intent) {
        if (intent == null) return null;
        if (intent.getBooleanExtra("editor_from_photo", false)) {
            String uri = intent.getStringExtra("editor_image_uri");
            return uri == null ? null : Uri.parse(uri);
        }
        return intent.getData();
    }

    private static void readCapturedFilter(Activity activity, Uri uri, ClassLoader classLoader) {
        if (uri == null) { capturedFilter = null; return; }
        Object container = null;
        try {
            container = XposedHelpers.newInstance(XposedHelpers.findClass(CONTAINER, classLoader));
            Object read = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(OPEN_MODE, classLoader), "MODE_READ");
            if (!(Boolean) XposedHelpers.callMethod(container, "setDataSource", activity, uri, read)) {
                capturedFilter = null;
                return;
            }
            byte[] info = (byte[]) XposedHelpers.callMethod(container, "getExtensionData", FILTER_INFO);
            // Every photo opens in palette mode, so the 色彩风格 entry is consistent (the stock
            // filter editor only offered the two normal-scope module filters). The palette
            // pipeline itself cannot re-render from src.image on this device (the native meta
            // read fails, metaIndex=0), so GalleryColorPalette bypasses the BasicTone stage and
            // applies the selected filter straight onto the rendered image instead.
            capturedFilter = info != null && info.length >= 44 ? filterId(info) : null;
            Log.i(TAG, "editing " + uri + (capturedFilter == null ? "" : ", captured " + capturedFilter));
            dumpExtensions(container, uri, info);
        } catch (Throwable error) {
            capturedFilter = null;
            Log.e(TAG, "filter.info read failed for " + uri, error);
        } finally {
            if (container != null) {
                try {
                    XposedHelpers.callMethod(container, "close");
                } catch (Throwable ignored) {
                    // closing a container that failed to open
                }
            }
        }
    }

    /**
     * The filter LUT name stored at filter.info +28 (NUL-terminated ASCII, preceded by a flag
     * word at +24), or null. Longer than 36 bytes for generated names, so scan to +64.
     */
    private static String filterId(byte[] info) {
        int start = 28, end = Math.min(info.length, 64);
        if (start >= end || info[start] == 0) return null;
        for (int i = start; i < end; i++) {
            if (info[i] == 0) {
                try { return new String(info, start, i - start, "US-ASCII"); } catch (Exception ignored) { return null; }
            }
        }
        return null;
    }

    /**
     * One line per editor open: which extension blocks the photo carries and the decrypted
     * filter.info bytes. The container payloads are encrypted on disk, so this (through
     * FileExtendedContainer) is the only way to see the real values; it exists to diagnose the
     * palette metaIndex gap (X8U captures vs X10 reference photos).
     */
    private static void dumpExtensions(Object container, Uri uri, byte[] info) {
        try {
            StringBuilder sb = new StringBuilder("extdump ").append(uri.getLastPathSegment());
            for (String name : new String[] {"filter.info", "src.image", "basictone.info",
                    "master.mode.preset.info", "basictone.vig.table", "basictone.lmtlut.table", "fb.param"}) {
                try {
                    byte[] data = (byte[]) XposedHelpers.callMethod(container, "getExtensionData", name);
                    sb.append(' ').append(name).append('=').append(data == null ? "null" : data.length);
                    if ("src.image".equals(name) && data != null && data.length >= 16) {
                        StringBuilder head = new StringBuilder();
                        for (int i = 0; i < 16; i++) head.append(String.format("%02x", data[i]));
                        sb.append(" jpeg=").append(data[0] == (byte) 0xFF && data[1] == (byte) 0xD8)
                          .append(" head=").append(head);
                    }
                } catch (Throwable ignored) {
                    // a block this gallery build cannot read
                }
            }
            Log.i(TAG, sb.toString());
            if (info != null) {
                StringBuilder hex = new StringBuilder("filterinfo-hex len=").append(info.length).append(' ');
                for (int i = 0; i < Math.min(info.length, 216); i++) hex.append(String.format("%02x", info[i]));
                Log.i(TAG, hex.toString());
            }
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }
}
