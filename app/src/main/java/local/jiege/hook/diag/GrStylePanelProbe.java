package local.jiege.hook.diag;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Diagnostic probe for the "drive the GR effect parameters from our own panel" work.
 *
 * Why it exists: the GR style panel keeps one row object per parameter, and the effect struct that
 * goes to preview and capture is built by reading each row's own integer value field
 * (GrStylePanel.effect() reads the row, converts it to a float and writes it into the GrEffect
 * builder). The DataKey values in the settings store are therefore not the source. To drive the
 * parameters from outside we have to know
 *   (a) which row belongs to which parameter, and
 *   (b) which of the row's integer fields is the value, and
 *   (c) which panel method the UI calls when a row changes.
 * All three are obfuscated and, as of this writing, cannot be expressed as symbols (see
 * port-x8u/README.md section 4.23), so they are discovered here at run time instead.
 *
 * What it logs, only while the property below is set:
 *   - every GrStylePanel constructor: the full row table (parameter name + every int field);
 *   - every GrStylePanel method taking one int: the row table before and after, so the method that
 *     moved a row is the one the seek bar calls, and the field that moved is the value;
 *   - GrStylePanel.effect(): the 63-byte effect struct it produced, as three float views.
 *
 * Enable with: setprop persist.omnicam.aura.grdump 1   (then force-stop the camera).
 */
public final class GrStylePanelProbe {
    private static final String TAG = "GrProbe";
    private static final String PROP = "persist.omnicam.aura.grdump";
    private static final String DATA_KEY = "com.oplus.camera.data.DataKey";
    private static final Set<Class<?>> hookedTypes = new HashSet<>();
    private static int dumps;
    private static int bars;
    private static int invocations;
    private static int valueCalls;

    private GrStylePanelProbe() {}

    public static void install(ClassLoader classLoader) {
        if (!"1".equals(readProp())) {
            Log.i(TAG, "off (" + PROP + " not 1)");
            return;
        }
        Log.i(TAG, "on: installing GR style panel probes");
        Log.guard(TAG, "panel", () -> hookPanel(classLoader));
        Log.guard(TAG, "effect", () -> hookEffect(classLoader));
        Log.guard(TAG, "seekbar", () -> hookSeekBar(classLoader));
        Log.guard(TAG, "scale bars", () -> hookScaleBars(classLoader));
    }

    /**
     * The GR parameter rows are not seek bars - dragging one never reaches ProgressBar.setProgress.
     * They are scale bars, and the panel gives the bar a listener whose value callback carries the
     * new value. Hooking the bar families (and the panel's own inner classes, which hold those
     * listeners) names both sides of the drag, which is what the panel methods alone did not show.
     */
    private static void hookScaleBars(ClassLoader loader) {
        String[] families = {
            "com.oplus.camera.coui.seekbar.HSScaleBar",
            "com.oplus.camera.coui.seekbar.SoftLightScaleBar",
            "com.oplus.camera.coui.seekbar.ScaleBar",
        };
        for (String name : families) {
            Class<?> type = XposedHelpers.findClassIfExists(name, loader);
            if (type == null) {
                Log.i(TAG, "absent: " + name);
                continue;
            }
            hookValueMethods(type);
            for (Class<?> inner : type.getDeclaredClasses()) hookValueMethods(inner);
        }
        Log.i(TAG, "scale bars hooked");
    }

    /** Hooks every one-int / one-float method of a type, logging each call with its caller. */
    private static void hookValueMethods(final Class<?> type) {
        synchronized (hookedTypes) {
            if (!hookedTypes.add(type)) return;
        }
        int count = 0;
        for (final Method method : type.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || (params[0] != int.class && params[0] != float.class)) continue;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (valueCalls++ > 400) return;
                        Log.i(TAG, type.getSimpleName() + "." + method.getName()
                            + "(" + param.args[0] + ") -> " + param.getResult());
                        if (valueCalls <= 60) Log.i(TAG, "   from " + caller(7));
                    }
                });
                count++;
            } catch (Throwable ignored) {
                // not hookable
            }
        }
        if (count > 0) Log.i(TAG, "value methods hooked on " + type.getName() + ": " + count);
    }

    /**
     * Every seek bar in this UI is a ProgressBar, and whatever the drag path is, moving the thumb
     * ends in setProgress on that bar. Logging the view (class, resource id) plus the caller stack
     * names the adapter class and the method the panel's value actually comes from - which is what
     * the panel methods alone did not reveal.
     */
    private static void hookSeekBar(ClassLoader loader) {
        Class<?> progress = XposedHelpers.findClassIfExists("android.widget.ProgressBar", loader);
        if (progress == null) {
            Log.i(TAG, "absent: ProgressBar");
            return;
        }
        for (String name : new String[] {"setProgress", "setProgressInternal"}) {
            try {
                XposedBridge.hookAllMethods(progress, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                        if (bars++ > 600) return;
                        Object view = param.thisObject;
                        Log.i(TAG, "setProgress " + describe(view) + " = " + param.args[0]);
                        Log.i(TAG, "   from " + caller(6));
                    }
                });
            } catch (Throwable ignored) {
                // that overload set is not on this build
            }
        }
        Log.i(TAG, "seek bar hooked");
    }

    private static String describe(Object view) {
        if (!(view instanceof android.view.View)) return String.valueOf(view);
        android.view.View bar = (android.view.View) view;
        String id = "no-id";
        if (bar.getId() != android.view.View.NO_ID && bar.getResources() != null) {
            try {
                id = bar.getResources().getResourceEntryName(bar.getId());
            } catch (Throwable ignored) {
                id = "0x" + Integer.toHexString(bar.getId());
            }
        }
        return bar.getClass().getName() + "#" + id;
    }

    private static void hookPanel(ClassLoader loader) throws Throwable {
        final Symbols symbols = Symbols.get();
        Class<?> panel = symbols.cls("GrStylePanel");
        Log.i(TAG, "GrStylePanel=" + panel.getName()
            + " rows=" + symbols.name("GrStylePanel.rows")
            + " effect=" + symbols.name("GrStylePanel.effect"));
        hookIntMethods(symbols, panel);
        for (Constructor<?> ctor : panel.getDeclaredConstructors()) {
            try {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object self = param.thisObject;
                        if (self == null) return;
                        try {
                            hookIntMethods(symbols, self.getClass());
                            for (Class<?> inner : self.getClass().getDeclaredClasses()) hookValueMethods(inner);
                            Log.i(TAG, "panel ready (" + self.getClass().getName() + ") rows:");
                            for (String line : rows(symbols, self)) Log.i(TAG, "   " + line);
                        } catch (Throwable error) {
                            Log.e(TAG, "panel probe failed", error);
                        }
                    }
                });
            } catch (Throwable ignored) {
                // that constructor is not hookable on this build
            }
        }
        Log.i(TAG, "panel hooked");
    }

    /** Hooks every one-int method of the type once, reporting only the calls that moved a row. */
    private static void hookIntMethods(final Symbols symbols, Class<?> type) {
        synchronized (hookedTypes) {
            if (!hookedTypes.add(type)) return;
        }
        int count = 0;
        for (final Method method : type.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != int.class) continue;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        param.setObjectExtra("before", snapshot(symbols, param.thisObject));
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object before = param.getObjectExtra("before");
                            List<String> after = rows(symbols, param.thisObject);
                            String flat = join(after);
                            boolean changed = before == null || !before.equals(flat);
                            if (invocations++ < 300) {
                                Log.i(TAG, "call " + method.getName() + "(" + param.args[0] + ") -> "
                                    + param.getResult() + (changed ? "   [rows changed]" : ""));
                                if (invocations <= 40) Log.i(TAG, "   from " + caller(6));
                            }
                            if (!changed) return;
                            if (++dumps > 400) return;
                            Log.i(TAG, "rows after " + method.getName() + "(" + param.args[0] + "):");
                            for (String line : after) Log.i(TAG, "   " + line);
                        } catch (Throwable error) {
                            Log.e(TAG, "row diff failed", error);
                        }
                    }
                });
                count++;
            } catch (Throwable ignored) {
                // not hookable
            }
        }
        Log.i(TAG, "int methods hooked on " + type.getName() + ": " + count);
    }

    /** The struct that preview and capture receive, so its field order can be read off. */
    private static void hookEffect(ClassLoader loader) throws Throwable {
        final Symbols symbols = Symbols.get();
        XposedBridge.hookMethod(symbols.method("GrStylePanel.effect"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object effect = param.getResult();
                    if (effect == null) return;
                    Object table = symbols.call("GrEffect.table", effect);
                    Object buffer = symbols.call("GrEffectTable.buffer", table);
                    ByteBuffer raw = ((ByteBuffer) buffer).duplicate();
                    raw.clear();
                    byte[] bytes = new byte[Math.min(raw.remaining(), 256)];
                    raw.get(bytes);
                    Log.i(TAG, "effect struct " + bytes.length + " bytes");
                    ByteBuffer floats = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    StringBuilder line = new StringBuilder();
                    for (int i = 0; i + 4 <= bytes.length; i += 4) {
                        line.append(String.format("%.4f ", floats.getFloat(i)));
                        if ((i / 4 + 1) % 8 == 0) {
                            Log.i(TAG, "   f[" + (i / 4 - 7) + ".." + (i / 4) + "] " + line);
                            line.setLength(0);
                        }
                    }
                    if (line.length() > 0) Log.i(TAG, "   tail " + line);
                } catch (Throwable error) {
                    Log.e(TAG, "effect probe failed", error);
                }
            }
        });
        Log.i(TAG, "effect hooked");
    }

    private static String snapshot(Symbols symbols, Object panel) {
        try {
            return join(rows(symbols, panel));
        } catch (Throwable error) {
            return "?";
        }
    }

    private static String join(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) out.append(line).append('|');
        return out.toString();
    }

    /** One line per row: the parameter's preference name and every instance int field it carries. */
    private static List<String> rows(Symbols symbols, Object panel) throws Throwable {
        if (panel == null) return Collections.emptyList();
        Object list = symbols.get("GrStylePanel.rows", panel);
        if (!(list instanceof List)) return Collections.singletonList("rows=" + list);
        List<String> out = new ArrayList<>();
        int index = 0;
        for (Object row : (List<?>) list) {
            if (row == null) {
                out.add(index++ + " null");
                continue;
            }
            StringBuilder line = new StringBuilder();
            line.append(index++).append(' ').append(preferenceName(row));
            for (Field field : row.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) continue;
                field.setAccessible(true);
                line.append(' ').append(field.getName()).append('=').append(field.get(row));
            }
            out.add(line.toString());
        }
        return out;
    }

    /** The preference name a row's DataKey carries; that is what names the parameter. */
    private static String preferenceName(Object row) {
        try {
            for (Class<?> type = row.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                        || !DATA_KEY.equals(field.getType().getName())) continue;
                    field.setAccessible(true);
                    Object key = field.get(row);
                    if (key == null) continue;
                    StringBuilder texts = new StringBuilder();
                    for (Class<?> keyType = key.getClass(); keyType != null; keyType = keyType.getSuperclass()) {
                        for (Field name : keyType.getDeclaredFields()) {
                            if (name.getType() != String.class || Modifier.isStatic(name.getModifiers())) continue;
                            name.setAccessible(true);
                            Object value = name.get(key);
                            if (value instanceof String) texts.append((String) value).append('+');
                        }
                    }
                    if (texts.length() > 0) return texts.toString();
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return "<no-key>";
    }

    private static String caller(int keep) {
        StringBuilder out = new StringBuilder();
        int kept = 0;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String name = frame.getClassName();
            if (name.startsWith("de.robv") || name.startsWith("local.jiege") || name.startsWith("java.lang")
                || name.contains("XC_MethodHook") || name.contains("XposedBridge")
                || name.startsWith("android.os") || name.startsWith("com.android.internal")) continue;
            out.append(name).append('.').append(frame.getMethodName()).append(':')
                .append(frame.getLineNumber()).append(" <- ");
            if (++kept == keep) break;
        }
        return out.toString();
    }

    private static String readProp() {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            return String.valueOf(type.getMethod("get", String.class, String.class)
                .invoke(null, PROP, "0"));
        } catch (Throwable error) {
            return "0";
        }
    }
}
