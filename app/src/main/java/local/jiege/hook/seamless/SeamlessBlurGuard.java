package local.jiege.hook.seamless;

import android.graphics.Bitmap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * POP is an Ultra HDR mode, so after a front POP shot the seamless UI path runs. It blurs the preview
 * screenshot (PreviewRenderer.setSeamlessGaussianBlurBitmap) after BitmapUtil.cropCenterBitmapByScale, which
 * returns null when the preview scale is below 1 (front wide framing: the crop box is larger than the
 * bitmap). The caller wraps the null into a texture without checking and the next frame throws
 * NullPointerException on the GL thread, closing the camera when the thumbnail is tapped.
 * When the crop fails the uncropped bitmap is returned, which is what the other three callers end up with.
 * The method is found by Symbols (BitmapUtil.cropCenterByScale, fingerprinted by its error log string).
 */
public final class SeamlessBlurGuard {
    private static final String TAG = "SeamlessBlur";

    private SeamlessBlurGuard() {}

    public static void installCamera(ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        Log.guard(TAG, "center crop fallback", () -> hookCrop(symbols));
    }

    private static void hookCrop(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("BitmapUtil.cropCenterByScale"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || param.getResult() != null) return;
                if (param.args[0] instanceof Bitmap) {
                    param.setResult(param.args[0]);
                    Log.i(TAG, "crop failed, keeping the uncropped bitmap");
                }
            }
        });
    }
}
