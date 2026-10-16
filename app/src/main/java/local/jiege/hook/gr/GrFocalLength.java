package local.jiege.hook.gr;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.common.ZoomFocal;

/**
 * GR port: the zoom bar shows 35mm-equivalent focal lengths ("28", "40", ...) instead of zoom
 * ratios ("1x", "2x", ...), like a GR.
 *
 * ZoomSeekBar members are symbols (Zoom.*): the unit suffix (Zoom.unit, "mm" marks a millimetre
 * bar), the two cached label bitmaps, the label alphas and label-visible flag of its base class.
 * The writes are the ones the 1.2.4 port actually performed (its decompiler-alias field names
 * never existed and are left out).
 */
final class GrFocalLength {
    private static final String TAG = "RicohGrPort";
    private static final String ZOOM_BAR = "com.oplus.camera.feature.zoom.view.ZoomSeekBar";

    private GrFocalLength() {}

    static void install(final ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        final Class<?> bar = de.robv.android.xposed.XposedHelpers.findClass(ZOOM_BAR, classLoader);

        // GR offers the wide-camera zoom stops.
        Log.guard(TAG, "GR zoom stops", () -> XposedBridge.hookMethod(symbols.method("ZoomValues.forMode"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if ("gr".equals(param.args[0])) {
                    param.setResult(symbols.call("ZoomValues.parseWide", null,
                        symbols.call("CameraConfig.list", null, "com.oplus.available.wide.zoomvalues")));
                }
            }
        }));

        XposedBridge.hookAllConstructors(bar, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!GrState.isGrMode()) return;
                try {
                    labelInMillimetres(symbols, param.thisObject);
                    symbols.call("Zoom.relabel", param.thisObject);
                } catch (Throwable ignored) {
                }
            }
        });

        final XC_MethodHook relabel = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (isMillimetreBar(symbols, param.thisObject)) labelInMillimetres(symbols, param.thisObject);
            }
        };
        Log.guard(TAG, "zoom relabel", () -> {
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(bar, "setCurrentDisplayText", Float.TYPE, relabel);
            XposedBridge.hookMethod(symbols.method("Zoom.setZoom"), relabel);
            XposedBridge.hookMethod(symbols.method("Zoom.relabel"), relabel);
        });
        Log.guard(TAG, "zoom format", () -> XposedBridge.hookMethod(symbols.method("Zoom.formatZoom"), relabel));
        Log.guard(TAG, "zoom hide labels", () -> XposedBridge.hookMethod(symbols.method("Zoom.hideLabels"), relabel));

        // Chip index for a zoom value, computed against the millimetre stops.
        Log.guard(TAG, "zoom chip index", () -> XposedBridge.hookMethod(symbols.method("Zoom.chipIndex"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (isMillimetreBar(symbols, param.thisObject)) {
                    param.setResult(chipIndex(symbols, param.thisObject, (Float) param.args[0]));
                }
            }
        }));

        // Label text: keep the number, drop "x"/"×"/"mm" (the unit is drawn separately).
        Log.guard(TAG, "zoom label text", () -> {
            XposedBridge.hookMethod(symbols.method("Zoom.labelText"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (isMillimetreBar(symbols, param.thisObject)) param.setResult(param.args[0]);
                }
            });
            XposedBridge.hookMethod(symbols.method("Zoom.unitText"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    String text = (String) param.args[0];
                    if (GrState.isGrMode() && text != null && (Boolean) param.args[1]) param.setResult(bareNumber(text));
                }
            });
        });
        Log.guard(TAG, "zoom label drawing", () -> {
            final Method drawUnitText = symbols.method("Zoom.drawUnitText");
            XC_MethodHook drawBareNumber = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isMillimetreBar(symbols, param.thisObject)) return;
                    try {
                        String text = (String) param.args[4];
                        if (text != null) {
                            drawUnitText.invoke(param.thisObject, param.args[0], param.args[1], param.args[2], param.args[3], bareNumber(text));
                        }
                    } catch (Throwable ignored) {
                    }
                    param.setResult(null);
                }
            };
            XposedBridge.hookMethod(symbols.method("Zoom.drawLabel"), drawBareNumber);
            XposedBridge.hookMethod(symbols.method("Zoom.drawLabelChecked"), drawBareNumber);
        });

        // Cached label bitmaps are rebuilt, and labels stay fully opaque in GR.
        final XC_MethodHook dropLabelCache = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!isMillimetreBar(symbols, param.thisObject)) return;
                try {
                    symbols.set("Zoom.iconBitmap", param.thisObject, null);
                    symbols.set("Zoom.labelBitmap", param.thisObject, null);
                } catch (Throwable ignored) {
                }
            }
        };
        Log.guard(TAG, "zoom label cache", () -> {
            XposedBridge.hookMethod(symbols.method("Zoom.drawText"), dropLabelCache);
            XposedBridge.hookMethod(symbols.method("Zoom.drawIcon"), dropLabelCache);
        });
        // fadeLabels() fades the labels out; in GR the labels stay.
        // Zoom.fadeLabels is anchored by name; ZoomBase.fadeLabels confirms it overrides the base class.
        Log.guard(TAG, "zoom label fade", () -> XposedBridge.hookMethod(symbols.has("ZoomBase.fadeLabels") ? symbols.method("Zoom.fadeLabels") : null, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (isMillimetreBar(symbols, param.thisObject)) param.setResult(null);
            }
        }));
        Log.guard(TAG, "zoom label alpha", () -> XposedBridge.hookMethod(symbols.method("Zoom.drawChip"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!isMillimetreBar(symbols, param.thisObject)) return;
                try {
                    for (java.lang.reflect.Field alpha : symbols.<java.lang.reflect.Field>all("ZoomBase.labelAlpha")) {
                        alpha.setInt(param.thisObject, 255);
                    }
                    symbols.field("ZoomBase.labelVisible").setBoolean(param.thisObject, true);
                } catch (Throwable ignored) {
                }
            }
        }));

        // The zoom UI controller passes the unit in; force "mm" and plain (non-ratio) labels.
        Log.guard(TAG, "zoom unit", () -> XposedBridge.hookMethod(symbols.method("ZoomUi.setUnit"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (GrState.isGrMode() || "mm".equals(param.args[0])) {
                    param.args[0] = "mm";
                    param.args[1] = Boolean.FALSE;
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!GrState.isGrMode() && !"mm".equals(param.args[0])) return;
                try {
                    Object zoomBar = symbols.get("ZoomUi.bar", param.thisObject);
                    if (zoomBar != null && bar.isInstance(zoomBar)) labelInMillimetres(symbols, zoomBar);
                } catch (Throwable ignored) {
                }
            }
        }));

        Log.guard(TAG, "GR focal length", () -> hookZoomToMillimetres(symbols));
    }

    /**
     * FocalLength.millimetres(zoom) returns the focal length shown for a zoom ratio. In GR it is
     * interpolated from the camera's zoom stops (ZoomFocal).
     */
    private static void hookZoomToMillimetres(final Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("FocalLength.millimetres"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!GrState.isGrMode()) return;
                param.setResult((float) Math.round(ZoomFocal.millimetres(symbols, (Float) param.args[0])));
            }
        });
    }

    /** In GR every zoom bar is relabelled; outside GR a bar stays in mm once it was labelled so. */
    private static boolean isMillimetreBar(Symbols symbols, Object bar) {
        if (GrState.isGrMode()) {
            labelInMillimetres(symbols, bar);
            return true;
        }
        try {
            return "mm".equals(symbols.get("Zoom.unit", bar));
        } catch (Throwable error) {
            return false;
        }
    }

    private static void labelInMillimetres(Symbols symbols, Object bar) {
        try {
            symbols.set("Zoom.unit", bar, "mm");
        } catch (Throwable ignored) {
        }
    }

    private static String bareNumber(String label) {
        return label.replace("×", "").replace("x", "").replace("X", "").replace("mm", "").trim();
    }

    /** Index of the last chip (stop list, chip map) whose stop does not exceed the zoom. */
    private static int chipIndex(Symbols symbols, Object bar, float zoom) {
        List<?> stops = null;
        try {
            Object value = symbols.get("Zoom.stops", bar);
            if (value instanceof List) stops = (List<?>) value;
        } catch (Throwable ignored) {
        }
        int chipCount = 10;
        try {
            Object chips = symbols.get("Zoom.chips", bar);
            if (chips instanceof Map) chipCount = ((Map<?, ?>) chips).size();
            else if (chips instanceof List) chipCount = ((List<?>) chips).size();
        } catch (Throwable ignored) {
        }
        // The 1.2.4 port read this stop through a field name that never existed, so 0.6 always applied.
        float ultraWideStop = 0.6f;
        if (stops == null) return 0;
        int index = 0;
        for (int i = 0; i < stops.size() && i < chipCount; i++) {
            Object value = stops.get(i);
            if (!(value instanceof Float)) continue;
            float stop = (Float) value;
            // Condition kept verbatim from the original port.
            boolean passUltraWideStop = Float.compare(stop, ultraWideStop) == 0 && zoom >= 1.0f && zoom < stop;
            if (!passUltraWideStop && Float.compare(stop, zoom) > 0) break;
            index = i;
        }
        return index;
    }
}
