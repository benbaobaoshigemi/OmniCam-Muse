package local.jiege.hook.palette;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Gallery process: the photo editor runs in palette mode only for photos the camera captured in palette mode.
 *
 * The gallery decides palette mode once for the whole app (isColorPaletteMode, a lazy value), and the
 * mode decides how a camera photo's filter is re-rendered from its src.image: palette mode adds the
 * BasicTone stage before the filter, the filter mode applies the filter alone. Which one is right is in
 * the photo's filter.info: a palette capture (version 5 or later, isPalette or paletteAlgoUsed set)
 * stores src.image before BasicTone; this camera's captures (version 4) store it after BasicTone, ready
 * for the filter. So the lazy value is replaced by one that also asks the photo being edited: palette
 * captures and images without filter.info get palette mode, the camera's filter photos get the stock
 * filter editor.
 */
final class GalleryPaletteScope {
    private static final String TAG = "GalleryPaletteScope";
    private static final String EDITOR = "com.oplus.gallery.pictureeditorpage.PhotoEditorActivity";
    private static final String CONTAINER = "com.oplus.gallery.foundation.fileaccess.extension.FileExtendedContainer";
    private static final String OPEN_MODE = "com.oplus.gallery.foundation.fileaccess.extension.OpenFileMode";
    private static final String FILTER_INFO = "filter.info";
    // filter.info layout (little endian): version float at 0; from version 5, isPalette at 140 and paletteAlgoUsed at 144.
    private static final int IS_PALETTE = 140;
    private static final int PALETTE_ALGO_USED = 144;

    private static volatile boolean photoPalette = true;

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
                if ("getValue".equals(method.getName())) return Boolean.TRUE.equals(value) && photoPalette;
                return value;
            };
            field.set(null, Proxy.newProxyInstance(lazy.getClassLoader(), new Class<?>[] {lazy}, handler));
            XposedHelpers.findAndHookMethod(EDITOR, classLoader, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    photoPalette = paletteCapture(activity, editedImage(activity.getIntent()), classLoader);
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

    private static boolean paletteCapture(Activity activity, Uri uri, ClassLoader classLoader) {
        if (uri == null) return true;
        Object container = null;
        try {
            container = XposedHelpers.newInstance(XposedHelpers.findClass(CONTAINER, classLoader));
            Object read = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(OPEN_MODE, classLoader), "MODE_READ");
            if (!(Boolean) XposedHelpers.callMethod(container, "setDataSource", activity, uri, read)) return true;
            byte[] info = (byte[]) XposedHelpers.callMethod(container, "getExtensionData", FILTER_INFO);
            boolean palette = info == null || paletteSemantics(info);
            Log.i(TAG, "editing " + uri + ": " + (palette ? "palette" : "filter") + " mode");
            return palette;
        } catch (Throwable error) {
            Log.e(TAG, "filter.info read failed for " + uri, error);
            return true;
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

    private static boolean paletteSemantics(byte[] info) {
        ByteBuffer buffer = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN);
        if (info.length < PALETTE_ALGO_USED + 4 || buffer.getFloat(0) < 5.0f) return false;
        return buffer.getInt(IS_PALETTE) == 1 || buffer.getInt(PALETTE_ALGO_USED) == 1;
    }
}
