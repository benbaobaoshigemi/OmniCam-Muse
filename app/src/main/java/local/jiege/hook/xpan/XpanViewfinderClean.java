package local.jiege.hook.xpan;

import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.widget.ImageView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModuleSettings;

/**
 * XPan 取景净化, each part on its own:
 *
 * - Small viewfinder (framed, Hasselblad body) and large viewfinder (full screen) masks: one GL
 *   mask (XpanMaskViewDrawer) serves both. It reads its inner padding (xpan_mask_view_*_inner_padding*)
 *   once, and on each switch lays out the XPAN frame for the form and its opening, the frame inset by
 *   that padding, with a feathered edge. After the layout, for a cleaned form (told apart by the
 *   frame's size) the opening is widened to the frame and the edge radius set to 0 (drawn as the
 *   camera's minimum 0.01); the other form keeps the camera's opening and radius.
 * - Developing animation: CoverDrawable draws the photo without the inverted paint and finishes at
 *   its first frame through its own completion branch (callback, state, bitmaps); the capture view
 *   then shrinks to the thumbnail, and the view showing a CoverDrawable is hidden with its
 *   animation at 0 ms, keeping the end action that resets it.
 */
public final class XpanViewfinderClean {
    private static final String TAG = "XpanClean";
    private static final String COVER = "com.oplus.camera.feature.xpan.view.widget.CoverDrawable";
    /**
     * The large (full screen) frame's short side is about 0.93 of the screen's, the small (framed)
     * one's about 0.67.
     */
    private static final float LARGE_FRAME = 0.8f;

    /** Padding (horizontal, vertical) being read on this thread, null outside. */
    private static final ThreadLocal<int[]> READING = new ThreadLocal<>();
    /** Each mask's padding and own edge radius. */
    private static final Map<Object, int[]> PADDING = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Float> RADIUS = Collections.synchronizedMap(new WeakHashMap<>());
    /** Animators of views showing a CoverDrawable, by the view that made them. */
    private static final Map<ViewPropertyAnimator, WeakReference<View>> ANIMATORS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private XpanViewfinderClean() {}

    public static void installCamera(ClassLoader loader) {
        boolean small = Settings.get(ModuleSettings.XPAN_CLEAN_SMALL_MASK);
        boolean large = Settings.get(ModuleSettings.XPAN_CLEAN_LARGE_MASK);
        if (small || large) Log.guard(TAG, "XPAN mask", () -> installMask(small, large));
        if (Settings.get(ModuleSettings.XPAN_CLEAN_ANIMATION)) Log.guard(TAG, "XPAN developing animation", () -> installCapture(loader));
    }

    private static void installMask(boolean small, boolean large) throws Exception {
        Class<?> drawer = Symbols.get().cls("XpanMaskDrawer");
        Field radius = only(drawer, float.class, true);
        Field frame = only(drawer, Rect.class, false);
        Method padding = null;
        for (Method method : drawer.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 2 || types[0] != Resources.class || types[1].isPrimitive() || method.getReturnType() != void.class) continue;
            if (padding != null) throw new IllegalStateException("mask has several padding readers");
            padding = method;
        }
        if (padding == null) throw new IllegalStateException("mask padding reader not found");
        Class<?> screen = padding.getParameterTypes()[1];
        Method layout = null;
        for (Method method : drawer.getDeclaredMethods()) {
            if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != screen || method.getReturnType() != void.class) continue;
            if (layout != null) throw new IllegalStateException("mask has several frame layouts");
            layout = method;
        }
        if (layout == null) throw new IllegalStateException("mask frame layout not found");
        Field[] ints = fields(drawer, int.class);
        // The other no-argument void method besides releaseTexture applies the opening and radius at once.
        Method release = Symbols.get().method("XpanMaskDrawer.releaseTexture");
        Method apply = null;
        for (Method method : drawer.getDeclaredMethods()) {
            if (method.getParameterCount() != 0 || method.getReturnType() != void.class || method.equals(release)
                || Modifier.isStatic(method.getModifiers())) continue;
            if (apply != null) throw new IllegalStateException("mask has several apply methods");
            apply = method;
        }
        if (apply == null) throw new IllegalStateException("mask apply method not found");
        apply.setAccessible(true);
        Method applyNow = apply;

        // The padding the mask read (horizontal, vertical), as layout insets the opening by it.
        XposedBridge.hookMethod(padding, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                READING.set(new int[]{-1, -1});
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                int[] read = READING.get();
                READING.remove();
                if (read != null && read[0] >= 0 && read[1] >= 0) PADDING.put(param.thisObject, read);
            }
        });
        XposedHelpers.findAndHookMethod(Resources.class, "getDimensionPixelSize", int.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                int[] read = READING.get();
                if (read == null) return;
                String name;
                try {
                    name = ((Resources) param.thisObject).getResourceEntryName((Integer) param.args[0]);
                } catch (Resources.NotFoundException unnamed) {
                    return;
                }
                if (name.startsWith("xpan_mask_view_horizontal_inner_padding")) read[0] = (Integer) param.getResult();
                else if (name.startsWith("xpan_mask_view_vertical_inner_padding")) read[1] = (Integer) param.getResult();
            }
        });
        XposedBridge.hookMethod(layout, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws IllegalAccessException {
                Object mask = param.thisObject;
                Rect rect = (Rect) frame.get(mask);
                int[] inset = PADDING.get(mask);
                if (rect == null || rect.isEmpty() || inset == null) return;
                Float own = RADIUS.get(mask);
                if (own == null) RADIUS.put(mask, own = radius.getFloat(mask));
                DisplayMetrics display = Resources.getSystem().getDisplayMetrics();
                boolean isLarge = Math.min(rect.width(), rect.height())
                    >= LARGE_FRAME * Math.min(display.widthPixels, display.heightPixels);
                float before = radius.getFloat(mask);
                if (!(isLarge ? large : small)) {
                    radius.setFloat(mask, own);
                    if (before != own) apply(applyNow, mask);
                    return;
                }
                // The opening layout set: the frame inset by the padding; widened to the frame.
                int[] padded = {rect.left + inset[0], rect.top + inset[1], rect.width() - 2 * inset[0], rect.height() - 2 * inset[1]};
                int[] whole = {rect.left, rect.top, rect.width(), rect.height()};
                Field[] opening = new Field[4];
                for (int i = 0; i < 4; i++) {
                    for (Field field : ints) {
                        if (field.getInt(mask) != padded[i]) continue;
                        if (opening[i] != null) return;  // ambiguous: leave the camera's mask
                        opening[i] = field;
                    }
                    if (opening[i] == null) return;
                }
                for (int i = 0; i < 4; i++) opening[i].setInt(mask, whole[i]);
                radius.setFloat(mask, 0f);
                if (before != 0f) apply(applyNow, mask);
            }
        });
        Log.i(TAG, "mask off for" + (small ? " small" : "") + (large ? " large" : "") + " viewfinder");
    }

    private static void installCapture(ClassLoader loader) throws Exception {
        Class<?> cover = XposedHelpers.findClass(COVER, loader);
        Field[] longs = fields(cover, long.class);
        if (longs.length != 2) throw new IllegalStateException("CoverDrawable has " + longs.length + " long fields");
        Method tick = null;
        for (Method method : cover.getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType() == void.class && !Modifier.isStatic(method.getModifiers())) {
                if (tick != null) throw new IllegalStateException("CoverDrawable has several frame methods");
                tick = method;
            }
        }
        if (tick == null) throw new IllegalStateException("CoverDrawable frame method not found");

        // The inverted photo is drawn with the paint holding a colour filter.
        XposedBridge.hookAllConstructors(cover, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws IllegalAccessException {
                for (Field field : fields(cover, Paint.class)) {
                    Paint paint = (Paint) field.get(param.thisObject);
                    if (paint != null && paint.getColorFilter() != null) paint.setColorFilter(null);
                }
            }
        });
        // A running animation holds its start (elapsedRealtime) and its duration; a start of 1 sends
        // the frame into the completed branch.
        XposedBridge.hookMethod(tick, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws IllegalAccessException {
                long a = longs[0].getLong(param.thisObject);
                long b = longs[1].getLong(param.thisObject);
                if (a <= 0 || b <= 0) return;
                Field start = a > b ? longs[0] : longs[1];
                if (SystemClock.elapsedRealtime() - 1 >= Math.min(a, b)) start.setLong(param.thisObject, 1);
            }
        });
        XposedHelpers.findAndHookMethod(View.class, "animate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof ImageView)) return;
                Drawable drawable = ((ImageView) param.thisObject).getDrawable();
                if (cover.isInstance(drawable)) {
                    ANIMATORS.put((ViewPropertyAnimator) param.getResult(), new WeakReference<>((View) param.thisObject));
                } else {
                    ANIMATORS.remove(param.getResult());
                }
            }
        });
        XposedHelpers.findAndHookMethod(ViewPropertyAnimator.class, "setDuration", long.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                WeakReference<View> owner = ANIMATORS.remove(param.thisObject);
                View view = owner != null ? owner.get() : null;
                if (view == null) return;
                view.setVisibility(View.INVISIBLE);
                param.args[0] = 0L;
            }
        });
        Log.i(TAG, "developing animation off");
    }

    /** Runs the mask's apply method; the radius only takes effect through it after a switch. */
    private static void apply(Method method, Object mask) {
        try {
            method.invoke(mask);
        } catch (ReflectiveOperationException error) {
            Log.e(TAG, "mask apply", error);
        }
    }

    /** The one non-static field of the type (final or not). */
    private static Field only(Class<?> owner, Class<?> type, boolean isFinal) {
        Field found = null;
        for (Field field : fields(owner, type)) {
            if (Modifier.isFinal(field.getModifiers()) != isFinal) continue;
            if (found != null) throw new IllegalStateException(owner.getName() + " has several " + type.getSimpleName() + " fields");
            found = field;
        }
        if (found == null) throw new IllegalStateException(owner.getName() + " has no " + type.getSimpleName() + " field");
        return found;
    }

    private static Field[] fields(Class<?> owner, Class<?> type) {
        return Arrays.stream(owner.getDeclaredFields())
            .filter(field -> field.getType() == type && !Modifier.isStatic(field.getModifiers()))
            .peek(field -> field.setAccessible(true))
            .toArray(Field[]::new);
    }
}
