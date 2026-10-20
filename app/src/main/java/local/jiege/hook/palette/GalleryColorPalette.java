package local.jiege.hook.palette;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import dalvik.system.BaseDexClassLoader;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Gallery process (com.coloros.gallery3d): palette editing in the photo editor ("色彩风格": style
 * list, palette panel, re-edit of the camera's filter + palette from the photo's filter.info).
 *
 * Two gaps on this device:
 *   - feature_is_support_color_palette requires ro.product.first_api_level >= 37 (this device
 *     shipped with 36); every other term holds. The hook answers true for that one switch.
 *   - palette mode renders through CombineLutProcessUnitSDK and PaletteColorTable, which the
 *     system com.oplus.camera.unit.sdk.jar lacks. service.sh stages the X10 build of that jar in
 *     files/jiege_ipu; the gallery's shared-library loader for it is replaced by one over the
 *     staged jar (the JNI libraries it loads, libCombineLutJni etc., are identical on both),
 *     kept off the APS texture entry this device lacks (GL pipeline with P010 input instead).
 * Which photos the editor treats as palette photos: GalleryPaletteScope.
 */
public final class GalleryColorPalette {
    private static final String TAG = "GalleryColorPalette";
    private static final String FEATURE = "feature_is_support_color_palette";
    private static final String SDK_JAR = "com.oplus.camera.unit.sdk.jar";
    private static final String STAGED_SDK = "/data/user/0/com.coloros.gallery3d/files/jiege_ipu/" + SDK_JAR;
    private static final String LIBRARY_PATH = "/product/lib64:/system/lib64";
    /** Last tone/sat fallback diagnostic, so a drag does not spam one log line per frame. */
    private static String lastRestyleDiag;

    private GalleryColorPalette() {}

    /** Before anything in the gallery touches the camera unit SDK. */
    public static void installGallery(ClassLoader classLoader) {
        if (!replaceSdkLoader(classLoader)) return;
        try {
            XposedBridge.hookMethod(Symbols.get().method("Config.getBoolean"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (FEATURE.equals(param.args[0])) param.setResult(Boolean.TRUE);
                }
            });
            GalleryPaletteScope.install(classLoader);
            Log.i(TAG, "gallery palette editor enabled");
        } catch (Throwable error) {
            Log.e(TAG, "gallery palette switch hook failed", error);
        }
    }

    /**
     * The loader the framework builds for a system shared library (ApplicationLoaders): a shared
     * native namespace, which the SDK's JNI libraries in /product need for their system deps.
     */
    private static ClassLoader sharedLibraryLoader(ClassLoader parent) {
        int targetSdk = android.os.Build.VERSION.SDK_INT;
        return (ClassLoader) XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("com.android.internal.os.ClassLoaderFactory", null), "createClassLoader",
            new Class<?>[] {String.class, String.class, String.class, ClassLoader.class, int.class, boolean.class, String.class},
            STAGED_SDK, LIBRARY_PATH, LIBRARY_PATH, parent, targetSdk, true, null);
    }

    /**
     * The X10 SDK sends a gallery request with GL input/output textures straight to the APS
     * texture entry, which this device's APS lacks ("no origin image"). Without that branch the
     * SDK takes its GL pipeline: the texture is converted to P010 on the GPU and handed to
     * ipuColorPaletteProcess, whose gallery input on this device is P010. That pipeline shares
     * the caller's GL context, which this gallery does not pass for texture requests; the
     * context current on the calling thread is given to the SDK.
     */
    private static void p010PathOnly(ClassLoader sdkLoader) {
        String sdk = "com.oplus.ocs.camera.ipusdk.processunit.palette.PaletteProcessUnitSDK";
        XposedHelpers.findAndHookMethod(sdk, sdkLoader, "isTextureRequest", XC_MethodReplacement.returnConstant(Boolean.FALSE));
        XposedHelpers.findAndHookMethod(sdk, sdkLoader, "processImage", new XC_MethodHook() {
            private android.opengl.EGLDisplay display;
            private android.opengl.EGLSurface draw, read;
            private android.opengl.EGLContext context;

            @Override protected void beforeHookedMethod(MethodHookParam param) {
                display = EGL14.eglGetCurrentDisplay();
                context = EGL14.eglGetCurrentContext();
                draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
                read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
                if (context == null || context == EGL14.EGL_NO_CONTEXT) return;
                if (XposedHelpers.getObjectField(param.thisObject, "mShareGlContext") != null) return;
                XposedHelpers.callMethod(param.thisObject, "setShareGlContext", display, context);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                // The SDK's GL bridge makes its own context current and does not always restore
                // the caller's; the editor then renders into the wrong context (glUseProgram
                // GL_INVALID_VALUE) and the preview goes black. Put back what was current.
                if (context != null && context != EGL14.EGL_NO_CONTEXT) {
                    if (EGL14.eglGetCurrentContext() == context) return;
                    EGL14.eglMakeCurrent(display, draw, read, context);
                } else if (EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(EGL14.EGL_NO_DISPLAY, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                }
            }
        });
    }

    /** Palette mode without the newer SDK fails every filter render, so the switch follows this. */
    private static boolean replaceSdkLoader(ClassLoader classLoader) {
        if (!new File(STAGED_SDK).canRead()) {
            Log.i(TAG, "staged camera unit SDK missing: " + STAGED_SDK);
            return false;
        }
        try {
            ClassLoader[] loaders = (ClassLoader[]) XposedHelpers.getObjectField(classLoader, "sharedLibraryLoaders");
            if (loaders == null) return false;
            for (int i = 0; i < loaders.length; i++) {
                ClassLoader loader = loaders[i];
                if (loader instanceof BaseDexClassLoader && loader.toString().contains(SDK_JAR)) {
                    loaders[i] = sharedLibraryLoader(loader.getParent());
                    p010PathOnly(loaders[i]);
                    restyleDirectly(loaders[i]);
                    GalleryFilters.install(loaders[i]);
                    return true;
                }
            }
            Log.i(TAG, "camera unit SDK loader not found");
        } catch (Throwable error) {
            Log.e(TAG, "camera unit SDK loader replacement failed", error);
        }
        return false;
    }

    /**
     * The palette pipeline cannot re-render from src.image on this device: the native meta read
     * fails ("resolvePaletteSrcOrientation invalid metaIndex=0" — X8U captures store src.image as
     * a plain JPEG where X10 uses a private format plus basictone meta) and the BasicTone stage
     * outputs garbage, while the gallery hardcodes runFilterStage=false so the selected style is
     * never applied at all. Both are replaced: BasicTone passes its input through (every photo is
     * already toned at capture), and the filter stage runs for any style except the one the photo
     * was captured with (already baked in, see GalleryPaletteScope.capturedFilter). The result is
     * the stock filter editor's rendering path, which does work here.
     *
     * The panel's tone/saturation sliders live in the bypassed native stage, so they would do
     * nothing. The gallery sends them as process values (palette_tone / palette_saturation,
     * both -100..100, saturation 100 meaning a 2.0 multiplier — ColorPaletteParameter reads them
     * from the "tone"/"filter_saturation" editor keys and x8v.k forwards them per request); the
     * replacement reads them off the request entity and applies a Java equivalent onto the copy:
     * tone as a gamma curve (positive brightens the midtones), saturation as a luminance mix.
     */
    private static void restyleDirectly(ClassLoader sdkLoader) {
        String unit = "com.oplus.ocs.camera.ipusdk.processunit.palette.PaletteProcessUnitSDK";
        String stage = "com.oplus.ocs.camera.ipusdk.processunit.palette.PaletteFilterPreviewStage";
        try {
            XposedHelpers.findAndHookMethod(unit, sdkLoader, "runBasicToneStage",
                String.class, android.graphics.Bitmap.class, android.graphics.Bitmap.class,
                String.class, boolean.class, new XC_MethodReplacement() {
                    @Override protected Object replaceHookedMethod(MethodHookParam param) {
                        android.graphics.Bitmap input = (android.graphics.Bitmap) param.args[1];
                        if (input == null) return null;  // the caller turns this into failProcess
                        int[] panel = panelValues(sdkLoader, param.thisObject);
                        android.graphics.Bitmap restyled = panel == null ? null : toneSaturation(input, panel[0], panel[1]);
                        if (restyled == null && panel != null && (panel[0] != 0 || panel[1] != 0)) {
                            String diag = "tone/sat not applied: tone=" + panel[0] + " sat=" + panel[1]
                                + " config=" + input.getConfig() + " colorspace=" + input.getColorSpace();
                            if (!diag.equals(lastRestyleDiag)) {
                                lastRestyleDiag = diag;  // once per distinct situation, not per drag frame
                                Log.i(TAG, diag);
                            }
                        }
                        if (restyled != null) return restyled;
                        try {
                            return input.copy(input.getConfig(), false);
                        } catch (Throwable error) {
                            return input;  // e.g. a HARDWARE bitmap: better aliasing than failing
                        }
                    }
                });
            final Class<?> entity = XposedHelpers.findClass(
                "com.oplus.ocs.camera.ipusdk.processunit.BaseProcessUnitSDK$ParameterEntity", sdkLoader);
            XposedHelpers.findAndHookMethod(stage, sdkLoader, "shouldRunFilterStage", entity,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object value = needsFilterStage(sdkLoader, stage, param.args[0]);
                        if (value == null) return;  // hook setup incomplete: keep the stock answer
                        param.setResult(value);
                    }
                });
            Log.i(TAG, "gallery palette restyle enabled");
        } catch (Throwable error) {
            Log.e(TAG, "gallery palette restyle hook failed", error);
        }
    }

    /** needsFilterStage(entity), plus a runFilterStage override: false for the captured style. */
    private static Boolean needsFilterStage(ClassLoader sdkLoader, String stage, Object entity) {
        if (entity == null) return Boolean.FALSE;
        try {
            boolean needs = (Boolean) XposedHelpers.callStaticMethod(XposedHelpers.findClass(stage, sdkLoader),
                "needsFilterStage", entity);
            if (!needs) return Boolean.FALSE;
            String captured = GalleryPaletteScope.capturedFilter;
            if (captured != null) {
                Object key = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(
                    "com.oplus.ocs.camera.ipusdk.processunit.palette.key.PaletteProcessKeySDK", sdkLoader),
                    "PALETTE_FILTER_ID");
                if (captured.equals(XposedHelpers.callMethod(entity, "getProcessValue", key))) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        } catch (Throwable error) {
            Log.e(TAG, "needsFilterStage reflection failed", error);
            return Boolean.FALSE;
        }
    }

    /** The panel sliders of the request being processed: {tone, saturation}, both -100..100. */
    private static int[] panelValues(ClassLoader sdkLoader, Object unit) {
        try {
            Object entity = XposedHelpers.getObjectField(unit, "mRequestEntities");
            Class<?> keys = XposedHelpers.findClass(
                "com.oplus.ocs.camera.ipusdk.processunit.palette.key.PaletteProcessKeySDK", sdkLoader);
            return new int[] {
                intValue(XposedHelpers.callMethod(entity, "getProcessValue",
                    XposedHelpers.getStaticObjectField(keys, "PALETTE_TONE"))),
                intValue(XposedHelpers.callMethod(entity, "getProcessValue",
                    XposedHelpers.getStaticObjectField(keys, "PALETTE_SATURATION"))),
            };
        } catch (Throwable error) {
            return null;  // keys or entity unavailable: keep the identity bypass
        }
    }

    private static int intValue(Object value) {
        return value instanceof Integer ? (Integer) value : 0;
    }

    /**
     * The Java stand-in for the bypassed BasicTone: tone as a gamma curve over 0..255 (positive
     * brightens the midtones, 2^(-tone/100)), saturation as a mix towards/away from luminance
     * (100 = 2.0x, -100 = grayscale). The editor's input bitmap is typically RGBA_F16 in an
     * extended scRGB color space, so the pixel pass runs on an sRGB/8888 flatten and the result
     * is drawn back into the input's config and color space (values beyond 0..1 clamp — the
     * panel is SDR-only here). Returns null when the values are neutral or the bitmap cannot be
     * processed this way; the caller then falls back to the plain identity copy.
     */
    private static android.graphics.Bitmap toneSaturation(android.graphics.Bitmap input, int tone, int saturation) {
        if (tone == 0 && saturation == 0) return null;
        android.graphics.Bitmap.Config config = input.getConfig();
        if (config != android.graphics.Bitmap.Config.ARGB_8888
                && config != android.graphics.Bitmap.Config.RGBA_F16
                && config != android.graphics.Bitmap.Config.RGBA_1010102) return null;
        int w = input.getWidth(), h = input.getHeight();
        if (w <= 0 || h <= 0) return null;
        android.graphics.Bitmap flat;
        try {
            if (config == android.graphics.Bitmap.Config.ARGB_8888) {
                flat = input.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            } else {
                flat = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                new android.graphics.Canvas(flat).drawBitmap(input, 0f, 0f,
                    new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            }
        } catch (Throwable error) {
            Log.i(TAG, "tone/sat flatten failed: " + error);
            return null;
        }
        try {
            int[] toneLut = new int[256];
            double gamma = Math.pow(2.0, -tone / 100.0);
            for (int i = 0; i < 256; i++) {
                toneLut[i] = (int) Math.round(255.0 * Math.pow(i / 255.0, gamma));
            }
            float satMul = Math.max(0f, 1f + saturation / 100f);
            int rowsPerStrip = Math.max(1, 4 * 1024 * 1024 / w);  // ~16MB pixel strips, at least one row
            int[] pixels = new int[w * rowsPerStrip];
            for (int top = 0; top < h; ) {
                int rows = Math.min(h - top, rowsPerStrip);
                flat.getPixels(pixels, 0, w, 0, top, w, rows);
                for (int i = 0, count = w * rows; i < count; i++) {
                    int p = pixels[i];
                    int r = toneLut[(p >> 16) & 0xFF];
                    int g = toneLut[(p >> 8) & 0xFF];
                    int b = toneLut[p & 0xFF];
                    float luma = 0.299f * r + 0.587f * g + 0.114f * b;
                    pixels[i] = (p & 0xFF000000)
                        | (channel(luma, r, satMul) << 16)
                        | (channel(luma, g, satMul) << 8)
                        | channel(luma, b, satMul);
                }
                flat.setPixels(pixels, 0, w, 0, top, w, rows);
                top += rows;
            }
        } catch (Throwable error) {
            flat.recycle();
            Log.i(TAG, "tone/sat pixel pass failed: " + error);
            return null;
        }
        if (config == android.graphics.Bitmap.Config.ARGB_8888) {
            if (android.os.Build.VERSION.SDK_INT >= 34) {  // keep an Ultra HDR gainmap if any
                try {
                    android.graphics.Gainmap gainmap = input.getGainmap();
                    if (gainmap != null && flat.getGainmap() == null) flat.setGainmap(gainmap);
                } catch (Throwable ignored) {
                    // gainmaps are optional extras
                }
            }
            return flat;
        }
        try {  // back into the pipeline's own config + color space
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h, config, true, input.getColorSpace());
            new android.graphics.Canvas(out).drawBitmap(flat, 0f, 0f,
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            flat.recycle();
            return out;
        } catch (Throwable error) {
            flat.recycle();
            Log.i(TAG, "tone/sat re-encode failed: " + error);
            return null;
        }
    }

    private static int channel(float luma, int value, float satMul) {
        float c = luma + (value - luma) * satMul;
        return c <= 0f ? 0 : c >= 255f ? 255 : (int) c;
    }
}
