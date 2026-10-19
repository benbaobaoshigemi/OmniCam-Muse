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
}
