package local.jiege.hook.pop;

import android.graphics.RectF;
import android.graphics.SweepGradient;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * POP port: keeps the shutter's drawing geometry centred when the shutter changes size.
 *
 * MainShutterButton.j5 (called from onDraw) creates its ring and disc rectangles once, centred on
 * the view size at that first draw, and onSizeChanged never updates them. Stock X9U modes all use
 * the same shutter size, so this never shows. The ported POP mode uses a larger shutter (288 px vs
 * 264 px); once the shutter has been drawn at one size, the other size draws the inner disc
 * 12 px off centre on both axes (measured +11.5, +11.5) until the camera restarts.
 *
 * On a real size change the centred rectangles are moved to the new centre, and the gradients
 * built from their coordinates are dropped or rebuilt, so the next draw matches the new size.
 */
final class PopShutterGeometry {
    /** Rectangles built in j5 around the view centre with size-independent radii. */
    private static final String[] CENTRED_RECTS = {"b3", "c3", "d3", "e3", "g3", "h3", "l3"};
    /** Gradients created lazily (null-checked) from those rectangles. */
    private static final String[] LAZY_GRADIENTS = {"t4", "H4", "I4"};

    private PopShutterGeometry() {}

    static void install(ClassLoader classLoader) {
        // MainShutterButton is not obfuscated and these fields kept their names across camera
        // builds; they are symbols (Shutter.*) so that a build without them leaves the hook out.
        local.jiege.hook.common.Symbols symbols = local.jiege.hook.common.Symbols.get();
        for (String name : CENTRED_RECTS) symbols.field("Shutter." + name);
        for (String name : LAZY_GRADIENTS) symbols.field("Shutter." + name);
        for (String name : new String[] {"s3", "j1", "k1"}) symbols.field("Shutter." + name);
        XposedHelpers.findAndHookMethod("com.oplus.camera.control.MainShutterButton", classLoader, "onSizeChanged",
            Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    int width = (Integer) param.args[0], height = (Integer) param.args[1];
                    int oldWidth = (Integer) param.args[2], oldHeight = (Integer) param.args[3];
                    if (oldWidth == 0 || oldHeight == 0 || (width == oldWidth && height == oldHeight)) return;
                    try {
                        recentre(param.thisObject, (width - oldWidth) / 2f, (height - oldHeight) / 2f);
                    } catch (Throwable error) {
                        local.jiege.hook.common.Log.e("PopPort", "shutter recentre failed", error);
                    }
                }
            });
    }

    private static void recentre(Object button, float dx, float dy) {
        for (String name : CENTRED_RECTS) {
            Object rect = XposedHelpers.getObjectField(button, name);
            if (rect instanceof RectF) ((RectF) rect).offset(dx, dy);
        }
        for (String name : LAZY_GRADIENTS) XposedHelpers.setObjectField(button, name, null);
        // c3's sweep gradient (paint s3) is only built together with c3; rebuild it at the new centre.
        Object ring = XposedHelpers.getObjectField(button, "c3");
        Object paint = XposedHelpers.getObjectField(button, "s3");
        if (ring instanceof RectF && paint != null) {
            XposedHelpers.callMethod(paint, "setShader", new SweepGradient(((RectF) ring).centerX(), ((RectF) ring).centerY(),
                XposedHelpers.getIntField(button, "j1"), XposedHelpers.getIntField(button, "k1")));
        }
        ((android.view.View) button).invalidate();
    }
}
