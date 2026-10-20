package local.jiege.hook.diag;

import android.view.MotionEvent;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.concurrent.atomic.AtomicInteger;
import local.jiege.hook.common.Log;

/**
 * Narrow, read-only probe for the right-hand enter button ({@code camera_menu_right_enter_button} /
 * {@code com.oplus.camera.ui.effectcontainer.MenuRightButton}): why does tapping it open the filter /
 * palette panel in photo mode but only focus the preview in the high-pixel mode?
 *
 * <p>Deliberately not on a hot path - the lesson from PaletteProbe, which hooked DataManager,
 * CameraConfig, the filter menu and the algo switch and made the camera window render nothing at
 * all. Here only four narrow things are watched, all logging only:
 *
 * <ul>
 *   <li>{@code MenuRightButton.onTouchEvent}: does the button receive the touch at all (and with
 *       which action)? A tap that only focuses the preview would leave these lines empty.</li>
 *   <li>{@code MenuRightButton.performClick}: was a click actually dispatched?</li>
 *   <li>{@code tp.k0.Ad/Bf/Q7/M6}: the four setters that install a click listener on the four
 *       button instances ({@code x/w/v/o}). If none is called for a mode, the button has no
 *       listener and the touch falls through to the preview - which is what it looks like.</li>
 *   <li>{@code tp.k0.wg}: the effect-panel toggle the mode switch feeds.</li>
 * </ul>
 *
 * <p>Enable with {@code setprop persist.omnicam.aura.entryprobe 1}, then force-stop the camera.
 */
public final class EntryButtonProbe {
    private static final String TAG = "EntryProbe";
    private static final String PROP = "persist.omnicam.aura.entryprobe";
    private static final String BUTTON = "com.oplus.camera.ui.effectcontainer.MenuRightButton";
    private static final AtomicInteger touches = new AtomicInteger();

    private EntryButtonProbe() {}

    public static void install(ClassLoader loader) {
        if (!"1".equals(readProp(PROP))) {
            Log.i(TAG, "off (" + PROP + ")");
            return;
        }
        Log.i(TAG, "on: watching the right-hand enter button");
        Log.guard(TAG, "touch", () -> hookTouch(loader));
        Log.guard(TAG, "click", () -> hookClick(loader));
        Log.guard(TAG, "clickable", () -> hookClickable(loader));
        Log.guard(TAG, "listener setters", () -> hookSetters(loader));
        Log.guard(TAG, "panel toggle", () -> hookPanelToggle(loader));
    }

    /**
     * The button reached {@code onTouchEvent} with {@code clickable=false} on device, so the touch
     * was not consumed and fell through to the preview as a focus tap. This catches the moment the
     * flag is cleared, with enough of the stack to name the caller.
     */
    private static void hookClickable(ClassLoader loader) throws Throwable {
        Class<?> button = XposedHelpers.findClass(BUTTON, loader);
        XposedBridge.hookAllMethods(button, "setClickable", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                boolean value = Boolean.TRUE.equals(param.args[0]);
                if (value) return;                     // only the "turn it off" direction matters
                Log.i(TAG, "setClickable(false) on " + describe(param.thisObject));
                StackTraceElement[] stack = new Throwable().getStackTrace();
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (StackTraceElement frame : stack) {
                    String cls = frame.getClassName();
                    if (cls.startsWith("de.robv") || cls.startsWith("java.lang.Thread")
                        || cls.endsWith("EntryButtonProbe")) continue;
                    sb.append("\n      at ").append(cls).append('.').append(frame.getMethodName())
                        .append(':').append(frame.getLineNumber());
                    if (++shown >= 7) break;
                }
                Log.i(TAG, "  via:" + sb);
            }
        });
        Log.i(TAG, "clickable installed");
    }

    private static void hookTouch(ClassLoader loader) throws Throwable {
        Class<?> button = XposedHelpers.findClass(BUTTON, loader);
        XposedBridge.hookAllMethods(button, "onTouchEvent", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.args[0] instanceof MotionEvent)) return;
                int action = ((MotionEvent) param.args[0]).getActionMasked();
                if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return;
                Log.i(TAG, "touch action=" + action + " on " + describe(param.thisObject)
                    + "  [#" + touches.incrementAndGet() + "]");
            }
        });
        Log.i(TAG, "touch installed on " + BUTTON);
    }

    private static void hookClick(ClassLoader loader) throws Throwable {
        Class<?> button = XposedHelpers.findClass(BUTTON, loader);
        XposedBridge.hookAllMethods(button, "performClick", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Log.i(TAG, "performClick on " + describe(param.thisObject));
            }
        });
        Log.i(TAG, "click installed on " + BUTTON);
    }

    /** The four setters are the only place a click listener reaches these buttons. */
    private static void hookSetters(ClassLoader loader) throws Throwable {
        Class<?> k0 = XposedHelpers.findClass("tp.k0", loader);
        for (String name : new String[] {"Ad", "Bf", "Q7", "M6"}) {
            XposedBridge.hookAllMethods(k0, name, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "click listener installed by tp.k0." + param.method.getName()
                        + " on " + describe(XposedHelpers.getObjectField(param.thisObject,
                            fieldFor(param.method.getName()))));
                }
            });
        }
        Log.i(TAG, "listener setters installed");
    }

    /** {@code Ad} drives {@code x}, {@code Bf} {@code w}, {@code Q7} {@code v}, {@code M6} {@code o}. */
    private static String fieldFor(String method) {
        switch (method) {
            case "Ad": return "x";
            case "Bf": return "w";
            case "Q7": return "v";
            default: return "o";
        }
    }

    private static void hookPanelToggle(ClassLoader loader) throws Throwable {
        Class<?> k0 = XposedHelpers.findClass("tp.k0", loader);
        XposedBridge.hookAllMethods(k0, "wg", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Log.i(TAG, "tp.k0.wg(" + (param.args.length > 0 ? param.args[0] : "-")
                    + ") - effect panel toggled");
            }
        });
        Log.i(TAG, "panel toggle installed");
    }

    private static String describe(Object view) {
        if (!(view instanceof View)) return String.valueOf(view);
        View v = (View) view;
        return v.getClass().getSimpleName() + " id=" + v.getId() + " vis=" + v.getVisibility()
            + " shown=" + v.isShown() + " clickable=" + v.isClickable()
            + " " + v.getWidth() + "x" + v.getHeight()
            + " y=" + (int) v.getY() + " ty=" + (int) v.getTranslationY();
    }

    private static String readProp(String name) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            return String.valueOf(type.getMethod("get", String.class, String.class)
                .invoke(null, name, ""));
        } catch (Throwable error) {
            return "";
        }
    }
}
