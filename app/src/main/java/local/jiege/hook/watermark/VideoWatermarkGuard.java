package local.jiege.hook.watermark;

import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Video watermark burnt in by the HAL: when recording starts, the camera takes the watermark of
 * the device orientation (WatermarkYuv.bundle) and gzips its "buffer" without a null check. A
 * style without a bitmap for that orientation (the GR styles only lay out the photo directions)
 * leaves the buffer null, and the NullPointerException aborts the recording. Such a bundle is
 * dropped, which the camera already handles as "no HAL watermark".
 */
public final class VideoWatermarkGuard {
    private static final String TAG = "VideoWatermarkGuard";

    private VideoWatermarkGuard() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "video watermark without a bitmap", () -> XposedBridge.hookMethod(
            Symbols.get().method("WatermarkYuv.bundle"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result instanceof Bundle && ((Bundle) result).getByteArray("buffer") == null) {
                        Log.i(TAG, "orientation " + param.args[0] + " has no watermark bitmap, recording without it");
                        param.setResult(null);
                    }
                }
            }));
    }
}
