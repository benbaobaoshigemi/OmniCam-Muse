package local.jiege.hook.gr;

import android.content.res.Resources;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import local.jiege.hook.common.Log;

/**
 * Drives the GR effect parameters (saturation, hue, tone, contrast, ...) through the camera's own
 * controls instead of writing the settings store.
 *
 * <p>How the GR panel is wired (established with the run-time probe and field cross-reference, see
 * port-x8u/README.md 4.23): the panel keeps one row per parameter - a small model carrying the row's
 * DataKey, its label resource and its value - a vertical list of rows on screen, and one scale bar
 * for the whole panel. Tapping a row makes it the selected one; dragging the bar then runs the
 * panel listener's single int callback, which writes the new value into the selected row's model,
 * repaints that row and its readout, and hands the pair (value, DataKey) to the panel's owner. The
 * effect that reaches preview and capture is therefore built from those row models and from what the
 * owner does with the pair - writing the settings store alone changes nothing.
 *
 * <p>So a parameter is moved the way a finger would: the matching row is selected through the row
 * list's own click listener, and then the bar listener is called with the new value. Both are found
 * by shape - the row list is the panel field that owns a list of row models, the bar listener is the
 * object captured from the bar, and its value method is the one int callback it exposes - so no
 * obfuscated name, resource id or DataKey id is hard-coded. Parameters are named from each row's own
 * label resource (camera_gr_saturation and so on).
 *
 * <p>If the row list cannot be used, the two functional steps are performed directly instead - write
 * the row model, then notify the owner - which is what a drag amounts to.
 */
public final class GrEffectValues {
    private static final String TAG = "GrEffects";
    /** The bar the rows use; SoftLightScaleBar is its sibling on other panels. */
    private static final String[] BARS = {
        "com.oplus.camera.coui.seekbar.HSScaleBar",
        "com.oplus.camera.coui.seekbar.SoftLightScaleBar",
    };
    private static final String LISTENER = "com.oplus.camera.coui.seekbar.HSScaleBar$e";
    private static final String DATA_KEY = "com.oplus.camera.data.DataKey";
    private static final String LABEL_PREFIX = "camera_gr_";
    /** The camera's style row inside the GR panel: a picker, never one of the dial's parameters. */
    private static final String STYLE_ROW = "camera_gr_fitler";
    /** The GR parameters are moved in steps; the bar's step 0..8 is the display value -4..+4. */
    private static final int STEP_ZERO = 4;
    private static final int STEP_SCALE = 10;
    private static final String TEST_PROP = "persist.omnicam.aura.grtest";
    private static final String PARAM_PROP = "persist.omnicam.aura.grparam";
    private static final String SET_PROP = "persist.omnicam.aura.grset";
    private static volatile boolean testApplied;
    private static int installs;
    private static int attempts;
    private static int refusals;
    private static volatile boolean watching;
    private static volatile String lastSet;

    /** parameter name (the row's label resource) -> the panel that carries it. */
    private static final Map<String, Panel> named = new ConcurrentHashMap<>();
    private static final Map<Object, Panel> panels = new ConcurrentHashMap<>();
    private static final Map<Object, Panel> listeners = new ConcurrentHashMap<>();
    private static int drags;

    private GrEffectValues() {}

    public static void install(ClassLoader classLoader) {
        Class<?> listenerInterface = XposedHelpers.findClassIfExists(LISTENER, classLoader);
        if (listenerInterface == null) {
            Log.i(TAG, "absent: listener interface " + LISTENER);
            return;
        }
        for (String name : BARS) {
            try {
                Class<?> bar = XposedHelpers.findClassIfExists(name, classLoader);
                if (bar == null) {
                    Log.i(TAG, "absent: " + name);
                    continue;
                }
                int attached = XposedBridge.hookAllMethods(bar, "setScaleBarValueChangeListener",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (++installs > 40) return;
                                if (param.args.length != 1 || param.args[0] == null) return;
                                bind(param.args[0]);
                            } catch (Throwable error) {
                                Log.e(TAG, "bind failed", error);
                            }
                        }
                    }).size();
                Log.i(TAG, "hooked " + attached + " setter(s) on " + name);
            } catch (Throwable error) {
                Log.e(TAG, "hooking " + name + " failed", error);
            }
        }
    }

    /** Works out one panel's rows, its value field and how to move them, from a captured listener. */
    private static void bind(Object listener) {
        // The listener keeps the panel, and the panel is not itself a View, so the panel is the
        // field that owns the row table rather than the field that happens to be a View.
        Object panel = null;
        List<?> rows = null;
        for (Field field : allFields(listener.getClass())) {
            Object value = read(field, listener);
            List<?> list = rowTableOf(value, null);
            if (list != null && !list.isEmpty()) {
                panel = value;
                rows = list;
                break;
            }
        }
        if (panel == null) {
            if (++refusals <= 12) Log.i(TAG, "no row table on " + listener.getClass().getName());
            return;
        }
        if (panels.containsKey(panel)) return;

        Class<?> rowType = rows.get(0).getClass();
        Field keyField = fieldOfType(rowType, DATA_KEY);
        Resources resources = resourcesOf(panel);
        Map<Integer, String> labels = new LinkedHashMap<>();
        Field labelField = null;
        for (Field field : intFields(rowType)) {
            Map<Integer, String> found = new LinkedHashMap<>();
            for (int i = 0; i < rows.size(); i++) {
                String name = resourceName(resources, intAt(field, rows.get(i)));
                if (name != null && name.startsWith(LABEL_PREFIX)) found.put(i, name);
            }
            if (!found.isEmpty()) {
                labelField = field;
                labels = found;
                break;
            }
        }
        if (labelField == null || keyField == null) {
            Log.i(TAG, "panel " + panel.getClass().getName() + " has no labelled rows");
            return;
        }

        // The owner callback and the row list are both installed after the panel is constructed, so
        // candidates are collected now and resolved when they are needed.
        List<Field> owners = ownerCandidates(panel, keyField);
        Object rowList = rowListOf(panel, rowType);
        List<?> holders = rowList == null ? null : rowTableOf(rowList, rowType);
        Field holderRow = holders == null || holders.isEmpty()
            ? null : fieldOfType(holders.get(0).getClass(), rowType.getName());
        Method onClick = methodOf(View.OnClickListener.class, "onClick", View.class);

        Field valueField = valueField(rows, labelField, resources);
        Method drag = intCallback(listener.getClass());
        Panel wiring = new Panel(panel, rows, intFields(rowType), keyField, valueField, owners,
            listener, drag, rowList, holders, holderRow, onClick, labelField, resources);
        panels.put(panel, wiring);
        listeners.put(listener, wiring);
        watchDrag(wiring);
        Log.i(TAG, "gr panel bound: " + rows.size() + " rows, value=" + name(valueField)
            + ", row list=" + (rowList == null ? "no" : rowList.getClass().getSimpleName()
                + " holders=" + (holders == null ? 0 : holders.size()))
            + ", drag=" + (drag == null ? "no" : drag.getName())
            + ", owners=" + owners.size());
        for (Map.Entry<Integer, String> entry : labels.entrySet()) {
            String parameter = entry.getValue();
            // "保存风格" is an action row, not a value the panel can drive.
            if (parameter.endsWith("_save_style")) continue;
            // The camera's own style row is a picker, not a parameter: its "value" is a style
            // index, and driving it switches styles and drags the style list up over the picture.
            // Styles are chosen on the camera's own strip, so this row stays out of the list.
            if (STYLE_ROW.equals(parameter)) continue;
            named.put(parameter, wiring);
            wiring.rows.put(parameter, entry.getKey());
            wiring.order.put(entry.getKey(), parameter);
            Log.i(TAG, "  " + entry.getKey() + " " + parameter + " = "
                + (valueField == null ? "?" : String.valueOf(read(valueField, rows.get(entry.getKey())))));
        }
        applyTest();
        applySet();
        watchSet();
    }

    /**
     * Watches persist.omnicam.aura.grset while it is in use, so a parameter can be moved from a
     * distance without rebuilding the panel. The loop costs nothing while the prop is unset.
     */
    private static void watchSet() {
        if (watching) return;
        watching = true;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            GrEffectValues::pollSet, 1000);
    }

    private static void pollSet() {
        try {
            String raw = readProp(SET_PROP);
            if (raw != null && !raw.isEmpty() && !raw.equals(lastSet) && !named.isEmpty()) {
                lastSet = raw;
                applySet();
            }
        } catch (Throwable error) {
            Log.e(TAG, "poll failed", error);
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            GrEffectValues::pollSet, 1000);
    }

    /**
     * Records what a real drag does - the value the bar passes and the row field it lands on - so
     * the scale comes from the camera rather than from a guess. Read-only: the gesture is untouched.
     */
    private static void watchDrag(final Panel wiring) {
        if (wiring.drag == null) return;
        try {
            XposedBridge.hookMethod(wiring.drag, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    wiring.before = snapshotTable(wiring);
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object[] before = wiring.before;
                        Object[] after = snapshotTable(wiring);
                        if (before == null || ++drags > 150) return;
                        int arg = param.args.length == 1 && param.args[0] instanceof Integer
                            ? (Integer) param.args[0] : Integer.MIN_VALUE;
                        Log.i(TAG, "drag arg=" + arg + " " + diff(wiring, before, after));
                    } catch (Throwable ignored) {
                        // never let a diagnostic break the gesture
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "drag hook skipped", error);
        }
    }

    private static Object[] snapshotTable(Panel wiring) {
        Object[] out = new Object[wiring.table.size() * wiring.ints.size()];
        for (int r = 0; r < wiring.table.size(); r++) {
            for (int f = 0; f < wiring.ints.size(); f++) {
                out[r * wiring.ints.size() + f] = read(wiring.ints.get(f), wiring.table.get(r));
            }
        }
        return out;
    }

    private static String diff(Panel wiring, Object[] before, Object[] after) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < before.length && i < after.length; i++) {
            if (before[i] == null || before[i].equals(after[i])) continue;
            int row = i / wiring.ints.size();
            Field field = wiring.ints.get(i % wiring.ints.size());
            out.append("row ").append(row).append(' ').append(field.getName()).append(' ')
                .append(before[i]).append(" -> ").append(after[i]).append(';');
            wiring.value = field;
        }
        return out.length() == 0 ? "no row change" : out.toString();
    }

    /**
     * Applies the "parameter=value" written to persist.omnicam.aura.grset, on every panel that is
     * built. Used to drive one parameter from a distance while the effect is being proven out.
     */
    private static void applySet() {
        String raw = readProp(SET_PROP);
        if (raw == null || raw.isEmpty()) return;
        int split = raw.indexOf('=');
        if (split <= 0) return;
        String parameter = raw.substring(0, split).trim();
        int value;
        try {
            value = Integer.parseInt(raw.substring(split + 1).trim());
        } catch (Throwable error) {
            return;
        }
        if (!named.containsKey(parameter)) {
            Log.i(TAG, "prop set waiting for " + parameter + "; known: " + named.keySet());
            return;
        }
        Log.i(TAG, "prop set: " + parameter + " = " + value);
        set(parameter, value);
    }

    /** The panel fields that could be the owner callback: by declared type first, then the rest. */
    private static List<Field> ownerCandidates(Object panel, Field keyField) {
        List<Field> early = new ArrayList<>();
        List<Field> late = new ArrayList<>();
        for (Field field : allFields(panel.getClass())) {
            if (field.getType().isPrimitive()) continue;
            if (callbackOn(field.getType(), keyField.getType()) != null) {
                early.add(field);
            } else {
                late.add(field);
            }
        }
        early.addAll(late);
        return early;
    }

    private static Object rowListOf(Object panel, Class<?> rowType) {
        for (Field field : allFields(panel.getClass())) {
            if (field.getType().isPrimitive()) continue;
            Object value = read(field, panel);
            if (!(value instanceof View)) continue;
            List<?> inner = rowTableOf(value, rowType);
            if (inner != null && !inner.isEmpty()) return value;
        }
        return null;
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

    /**
     * One-shot verification hook: with persist.omnicam.aura.grtest set to a value, the named
     * parameter (persist.omnicam.aura.grparam, saturation by default) is moved to it as soon as the
     * panel exists. Used to prove the drive path on the device before the panel UI is built.
     */
    private static void applyTest() {
        if (testApplied) return;
        String raw = readProp(TEST_PROP);
        if (raw == null || raw.isEmpty() || "0".equals(raw)) return;
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (Throwable error) {
            return;
        }
        String parameter = readProp(PARAM_PROP);
        if (parameter == null || parameter.isEmpty()) parameter = "camera_gr_saturation";
        Panel wiring = named.get(parameter);
        if (wiring == null) {
            Log.i(TAG, "test waiting for " + parameter + "; known: " + named.keySet());
            return;
        }
        if (set(parameter, value)) {
            testApplied = true;
            Log.i(TAG, "test drive done: " + parameter + " = " + value);
            return;
        }
        if (attempts++ > 40) {
            Log.i(TAG, "test drive gave up on " + parameter);
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            GrEffectValues::applyTest, 250);
    }

    /** True once the GR panel has been built and its parameters are known. */
    public static boolean available() {
        return !named.isEmpty();
    }

    /** One GR parameter, as the panel UI needs it. */
    public static final class Spec {
        public final String name;
        public final String label;
        public final int min;
        public final int max;

        Spec(String name, String label, int min, int max) {
            this.name = name;
            this.label = label;
            this.min = min;
            this.max = max;
        }

        public int value() {
            return GrEffectValues.step(name);
        }
    }

    /**
     * The parameters the panel should offer, in the order the camera lists them, with the labels
     * taken from the camera's own strings so the panel reads like the native one.
     */
    public static List<Spec> specs() {
        List<Spec> out = new ArrayList<>();
        Panel wiring = latest();
        if (wiring == null) return out;
        for (Map.Entry<Integer, String> entry : wiring.order.entrySet()) {
            String name = entry.getValue();
            String label = resourceText(wiring.resources, intAt(wiring.label, wiring.table.get(entry.getKey())));
            out.add(new Spec(name, label == null ? name : label, 0, STEP_ZERO * 2));
        }
        return out;
    }

    /** The camera's GR panel root, so the panel UI can sit exactly where the native one does. */
    public static View panelRoot() {
        Panel wiring = latest();
        if (wiring == null) return null;
        View best = null;
        for (Field field : allFields(wiring.panel.getClass())) {
            Object value = read(field, wiring.panel);
            if (!(value instanceof View)) continue;
            View view = (View) value;
            if (!view.isAttachedToWindow()) continue;
            if (best == null || area(view) > area(best)) best = view;
        }
        return best;
    }

    private static int area(View view) {
        return Math.max(0, view.getWidth()) * Math.max(0, view.getHeight());
    }

    private static Panel latest() {
        Panel best = null;
        for (Panel wiring : named.values()) {
            if (wiring == null || wiring.table.isEmpty()) continue;
            if (best == null || wiring.table.size() > best.table.size()) best = wiring;
        }
        return best;
    }

    private static String resourceText(Resources resources, int id) {
        if (resources == null || id <= 0) return null;
        try {
            return resources.getString(id);
        } catch (Throwable error) {
            return null;
        }
    }

    /** The parameter's current step, or Integer.MIN_VALUE while it is not known. */
    public static int step(String parameter) {
        int internal = get(parameter);
        return internal == Integer.MIN_VALUE ? Integer.MIN_VALUE
            : internal / STEP_SCALE + STEP_ZERO;
    }

    public static Map<String, Object> parameters() {
        return new LinkedHashMap<>(named);
    }

    /**
     * Moves one parameter the way a finger would: the row is selected, then the bar listener is
     * given the value. Falls back to writing the row model and notifying the owner directly when the
     * row list is not usable.
     */
    public static boolean set(String parameter, int value) {
        Panel wiring = named.get(parameter);
        Integer index = wiring == null ? null : wiring.rows.get(parameter);
        if (wiring == null || index == null) return false;
        if (wiring.drag != null && select(wiring, index)) {
            Object row = wiring.table.get(index);
            List<Field> ints = wiring.ints;
            Object[] before = snapshot(ints, row);
            try {
                wiring.drag.invoke(wiring.listener, value);
            } catch (Throwable error) {
                Log.e(TAG, "drag callback failed", error);
                return deliver(wiring, parameter, index, value);
            }
            Object[] after = snapshot(ints, row);
            learn(wiring, ints, before, after);
            Log.i(TAG, "set " + parameter + " = " + value + " (row " + index + " selected, then "
                + wiring.drag.getName() + "); row now " + describe(ints, after));
            return true;
        }
        return deliver(wiring, parameter, index, value);
    }

    /**
     * The field the camera moved on the row is the value; taken from a real drive rather than
     * guessed, so a panel whose rows all start at the same value cannot mislead it.
     */
    private static void learn(Panel wiring, List<Field> ints, Object[] before, Object[] after) {
        for (int i = 0; i < ints.size(); i++) {
            if (before[i] == null || after[i] == null) continue;
            if (before[i].equals(after[i])) continue;
            if (wiring.value != null && wiring.value.equals(ints.get(i))) return;
            wiring.value = ints.get(i);
            Log.i(TAG, "value field = " + ints.get(i).getName() + " (" + before[i] + " -> "
                + after[i] + ")");
            return;
        }
    }

    private static Object[] snapshot(List<Field> fields, Object owner) {
        if (fields == null || owner == null) return null;
        Object[] out = new Object[fields.size()];
        for (int i = 0; i < fields.size(); i++) out[i] = read(fields.get(i), owner);
        return out;
    }

    private static String describe(List<Field> fields, Object[] values) {
        if (fields == null || values == null) return "?";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            out.append(' ').append(fields.get(i).getName()).append('=').append(values[i]);
        }
        return out.toString();
    }

    /** Selects a row through the camera's own click path, which also updates the panel and the bar. */
    private static boolean select(Panel wiring, int index) {
        if (wiring.holders == null || wiring.holderRow == null) return false;
        Object holder = null;
        for (Object candidate : wiring.holders) {
            if (read(wiring.holderRow, candidate) == wiring.table.get(index)) {
                holder = candidate;
                break;
            }
        }
        if (holder == null && index < wiring.holders.size()) holder = wiring.holders.get(index);
        if (holder == null || wiring.onClick == null) return false;
        try {
            wiring.onClick.invoke(holder, (Object) null);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "selecting row " + index + " failed", error);
            return false;
        }
    }

    /** The direct equivalent of a drag: the row model takes the value, then the owner is told. */
    private static boolean deliver(Panel wiring, String parameter, int index, int value) {
        if (wiring.value == null) return false;
        try {
            Object row = wiring.table.get(index);
            if (row == null) return false;
            Object before = read(wiring.value, row);
            wiring.value.set(row, value);
            boolean told = notify(wiring, value, read(wiring.key, row));
            Log.i(TAG, "set " + parameter + " " + before + " -> " + value
                + (told ? " delivered" : " not delivered"));
            return told;
        } catch (Throwable error) {
            Log.e(TAG, "set " + parameter + " failed", error);
            return false;
        }
    }

    private static boolean notify(Panel wiring, int value, Object key) {
        for (Field field : wiring.owners) {
            Object callback = read(field, wiring.panel);
            if (callback == null) continue;
            Method call = callbackOn(callback.getClass(), wiring.key.getType());
            if (call == null) continue;
            try {
                call.invoke(callback, value, key);
                Log.i(TAG, "notified via " + field.getName() + " = "
                    + callback.getClass().getName() + "." + call.getName());
                return true;
            } catch (Throwable error) {
                Log.e(TAG, "callback " + call.getName() + " failed", error);
                return false;
            }
        }
        return false;
    }

    /** The parameter's current value, or Integer.MIN_VALUE while it is not known yet. */
    public static int get(String parameter) {
        Panel wiring = named.get(parameter);
        Integer index = wiring == null ? null : wiring.rows.get(parameter);
        if (wiring == null || wiring.value == null || index == null) return Integer.MIN_VALUE;
        Object row = wiring.table.get(index);
        Object value = row == null ? null : read(wiring.value, row);
        return value instanceof Integer ? (Integer) value : Integer.MIN_VALUE;
    }

    /**
     * The row's value field: the int that is neither the label nor the icon resource. Both of those
     * resolve to a resource name, the value does not, and among what is left the kind constant is
     * shared by every slider row while the value differs between them.
     */
    private static Field valueField(List<?> rows, Field labelField, Resources resources) {
        List<Field> candidates = new ArrayList<>();
        for (Field field : intFields(rows.get(0).getClass())) {
            if (field.equals(labelField)) continue;
            boolean resource = false;
            for (Object row : rows) {
                if (resourceName(resources, intAt(field, row)) != null) {
                    resource = true;
                    break;
                }
            }
            if (!resource) candidates.add(field);
        }
        Field best = null;
        int bestSpread = -1;
        for (Field field : candidates) {
            List<Integer> seen = new ArrayList<>();
            for (Object row : rows) {
                int value = intAt(field, row);
                if (!seen.contains(value)) seen.add(value);
            }
            Log.i(TAG, "value candidate " + field.getName() + ": distinct=" + seen.size());
            if (seen.size() > bestSpread) {
                bestSpread = seen.size();
                best = field;
            }
        }
        return best;
    }

    /** The list on the owner whose elements carry a field of the given row model type. */
    private static List<?> rowTableOf(Object owner, Class<?> rowType) {
        if (owner == null) return null;
        for (Field field : allFields(owner.getClass())) {
            Object value = read(field, owner);
            if (!(value instanceof List)) continue;
            List<?> list = (List<?>) value;
            if (list.isEmpty()) continue;
            if (rowType == null) {
                if (fieldOfType(list.get(0).getClass(), DATA_KEY) != null) return list;
            } else if (fieldOfType(list.get(0).getClass(), rowType.getName()) != null) {
                return list;
            }
        }
        return null;
    }

    private static Field fieldOfType(Class<?> type, String name) {
        for (Field field : allFields(type)) {
            if (name.equals(field.getType().getName())) return field;
        }
        return null;
    }

    /** The owner callback: any void method taking exactly the new value and the row's DataKey. */
    private static Method callbackOn(Class<?> type, Class<?> key) {
        for (Method method : type.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2 || params[0] != int.class || !params[1].equals(key)) continue;
            if (method.getReturnType() != void.class) continue;
            return method;
        }
        return null;
    }

    /** The bar listener's value callback: the single int method it exposes. */
    private static Method intCallback(Class<?> type) {
        for (Method method : type.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != int.class) continue;
            if (method.getReturnType() != void.class) continue;
            if (method.getDeclaringClass() == Object.class) continue;
            return method;
        }
        return null;
    }

    private static Method methodOf(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (Throwable error) {
            return null;
        }
    }

    private static Resources resourcesOf(Object owner) {
        if (owner instanceof View) return ((View) owner).getResources();
        for (Field field : allFields(owner.getClass())) {
            Object value = read(field, owner);
            if (value instanceof View) return ((View) value).getResources();
        }
        return null;
    }

    private static String resourceName(Resources resources, int id) {
        if (resources == null || id <= 0) return null;
        try {
            return resources.getResourceEntryName(id);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String name(Field field) {
        return field == null ? "none" : field.getName();
    }

    private static int intAt(Field field, Object owner) {
        Object value = read(field, owner);
        return value instanceof Integer ? (Integer) value : 0;
    }

    private static Object read(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (Throwable error) {
            return null;
        }
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Class<?> t = type; t != null; t = t.getSuperclass()) {
            for (Field field : t.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                out.add(field);
            }
        }
        return out;
    }

    private static List<Field> intFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field field : allFields(type)) {
            if (field.getType() == int.class) out.add(field);
        }
        return out;
    }

    /** One panel's wiring: its rows, how to move them, and where its owner callback lives. */
    private static final class Panel {
        final Object panel;
        final List<?> table;
        final List<Field> ints;
        final Field key;
        Field value;
        final List<Field> owners;
        final Object listener;
        final Method drag;
        final Object rowList;
        final List<?> holders;
        final Field holderRow;
        final Method onClick;
        final Field label;
        final Resources resources;
        volatile Object[] before;
        final Map<String, Integer> rows = new LinkedHashMap<>();
        /** row index -> parameter name, in the order the camera lists them. */
        final Map<Integer, String> order = new LinkedHashMap<>();

        Panel(Object panel, List<?> table, List<Field> ints, Field key, Field value,
              List<Field> owners, Object listener, Method drag, Object rowList, List<?> holders,
              Field holderRow, Method onClick, Field label, Resources resources) {
            this.panel = panel;
            this.table = table;
            this.ints = ints;
            this.key = key;
            this.value = value;
            this.owners = owners;
            this.listener = listener;
            this.drag = drag;
            this.rowList = rowList;
            this.holders = holders;
            this.holderRow = holderRow;
            this.onClick = onClick;
            this.label = label;
            this.resources = resources;
        }
    }
}
