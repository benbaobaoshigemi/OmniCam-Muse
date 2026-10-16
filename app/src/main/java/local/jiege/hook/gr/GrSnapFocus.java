package local.jiege.hook.gr;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CaptureRequest;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.List;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Symbols;

/**
 * GR port: snap focus. A top-menu switch (pref_snap_switch_key: off / 1m / 2.5m / 5m / ∞) fixes
 * the focus distance in GR mode, like the GR's snap focus. The menu icons come from this APK
 * (res/drawable/snap_switch_*).
 */
final class GrSnapFocus {
    private static final String TAG = "RicohGrPort";
    private static final String SNAP_KEY = "pref_snap_switch_key";
    private static final String MODULE_PACKAGE = "local.jiege.camera";
    private static final ThreadLocal<Boolean> loadingModuleDrawable = new ThreadLocal<>();
    private static volatile Context moduleContext;
    private static volatile boolean dataManagerHooked;

    private GrSnapFocus() {}

    static void install(final ClassLoader classLoader) {
        Symbols symbols = Symbols.get();
        Log.guard(TAG, "snap capture stage", () -> hookCaptureStage(symbols));
        Log.guard(TAG, "snap AF mode", () -> hookAutofocusMode(symbols));
        Log.guard(TAG, "snap menu click", () -> hookMenuClick(symbols, classLoader));
        hookStoredSetting(symbols);
        hookTopMenu(classLoader);
        hookMenuIcons(classLoader);
    }

    /** Focus distance in diopters, or -1 for autofocus. */
    private static float diopters(String snap) {
        if (snap == null || "off".equalsIgnoreCase(snap)) return -1.0f;
        if ("1m".equalsIgnoreCase(snap)) return 1.0f;
        if ("2.5m".equalsIgnoreCase(snap)) return 0.4f;
        if ("5m".equalsIgnoreCase(snap)) return 0.2f;
        if ("∞".equals(snap) || "inf".equalsIgnoreCase(snap) || "100".equals(snap)) return 0.0f;
        return -1.0f;
    }

    /** Applies the snap setting to the preview request of a mode (Mode.setPreviewParameter / setRequestKey). */
    private static void applyToMode(Object mode, String snap) {
        if (mode == null) return;
        try {
            Symbols symbols = Symbols.get();
            ClassLoader classLoader = mode.getClass().getClassLoader();
            Object focusModeKey = XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass("com.oplus.ocs.camera.CameraParameter", classLoader), "FOCUS_MODE");
            float diopters = diopters(snap);
            if (diopters < 0.0f) {
                symbols.call("Mode.setPreviewParameter", mode, focusModeKey, 4);
                symbols.call("Mode.setRequestKey", mode, CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                symbols.call("Mode.setRequestKey", mode, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            } else {
                symbols.call("Mode.setPreviewParameter", mode, focusModeKey, 0);
                symbols.call("Mode.setRequestKey", mode, CaptureRequest.LENS_FOCUS_DISTANCE, diopters);
            }
        } catch (Throwable error) {
            Log.e(TAG, "snap focus apply failed", error);
        }
    }

    /** A new GR mode instance starts with the stored snap setting applied. */
    static void onModeAttached(Object mode) {
        if (GrState.snapActive()) applyToMode(mode, GrState.snapSetting);
    }

    private static void onSnapChanged(String snap) {
        GrState.snapSetting = snap;
        applyToMode(GrState.currentMode, snap);
    }

    private static void hookCaptureStage(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("GrMode.captureStage"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (GrState.isGrMode() && GrState.snapActive()) applyToMode(param.thisObject, GrState.snapSetting);
            }
        });
    }

    /**
     * Mode.afMode() is the AF mode put into every repeating request (continuous picture, 4), picked
     * from CONTROL_AF_AVAILABLE_MODES. It is declared only in BaseMode; GrMode does not override
     * it. With snap focus, GR requests need AF off so the LENS_FOCUS_DISTANCE set by applyToMode
     * is kept.
     */
    private static void hookAutofocusMode(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("Mode.afMode"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (GrState.isGrMode() && GrState.snapActive()) param.setResult(CaptureRequest.CONTROL_AF_MODE_OFF);
            }
        });
    }

    /** CaptureParams.menuClick handles top-menu option clicks; persist and apply the snap choice. */
    private static void hookMenuClick(final Symbols symbols, final ClassLoader classLoader) {
        XposedBridge.hookMethod(symbols.method("CaptureParams.menuClick"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!SNAP_KEY.equals(param.args[0])) return;
                String value = (String) param.args[1];
                GrState.snapSetting = value;
                try {
                    DataStore.put(symbols, classLoader, DataStore.key(symbols, "Keys.snap", SNAP_KEY), value);
                } catch (Throwable error) {
                    Log.e(TAG, "snap setting store failed", error);
                }
                applyToMode(GrState.currentMode, value);
            }
        });
    }

    /** Tracks the stored snap value (DataManager writes), installed once the app is created. */
    private static void hookStoredSetting(final Symbols symbols) {
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (dataManagerHooked) return;
                dataManagerHooked = true;
                try {
                    XC_MethodHook trackSnap = new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam write) throws Throwable {
                            Object key = write.args[0];
                            if (key != null && Reflection.hasStringField(key, SNAP_KEY)) onSnapChanged(String.valueOf(write.args[1]));
                        }
                    };
                    XposedBridge.hookMethod(symbols.method("DataManager.putSync"), trackSnap);
                    XposedBridge.hookMethod(symbols.method("DataManager.put"), trackSnap);
                } catch (Throwable error) {
                    Log.e(TAG, "snap setting tracking skipped", error);
                }
            }
        });
    }

    /** Puts the snap switch into the top menu, just before HDR. */
    private static void hookTopMenu(ClassLoader classLoader) {
        try {
            Class<?> settingsConfig = XposedHelpers.findClass("com.oplus.camera.common.config.CameraSettingsConfig", classLoader);
            XposedHelpers.findAndHookMethod(settingsConfig, "parseMenuTopSetting", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    @SuppressWarnings("unchecked") List<String> menu =
                        (List<String>) XposedHelpers.getObjectField(param.thisObject, "mMenuTopList");
                    addSnapBeforeHdr(menu);
                }
            });
            XposedHelpers.findAndHookMethod(settingsConfig, "getMenuTopOptionList", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    @SuppressWarnings("unchecked") List<String> menu = (List<String>) param.getResult();
                    addSnapBeforeHdr(menu);
                }
            });
            // The config may already be parsed when the hooks go in.
            Object parsed = XposedHelpers.getStaticObjectField(settingsConfig, "sCameraSettingConfig");
            if (parsed != null) {
                @SuppressWarnings("unchecked") List<String> menu = (List<String>) XposedHelpers.getObjectField(parsed, "mMenuTopList");
                if (menu != null && !menu.contains(SNAP_KEY)) menu.add(SNAP_KEY);
            }
        } catch (Throwable error) {
            Log.e(TAG, "snap top menu hook failed", error);
        }
    }

    private static void addSnapBeforeHdr(List<String> menu) {
        if (menu == null || menu.contains(SNAP_KEY)) return;
        int hdr = menu.indexOf("pref_camera_hdr_mode_key");
        if (hdr >= 0) menu.add(hdr, SNAP_KEY);
        else menu.add(SNAP_KEY);
    }

    /** Serves res/drawable/snap_switch_* from this APK wherever the camera loads them. */
    private static void hookMenuIcons(ClassLoader classLoader) {
        XC_MethodHook replaceIcon = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Drawable icon = moduleIcon((Resources) param.thisObject, (Integer) param.args[0]);
                if (icon != null) param.setResult(icon);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Resources.class, "getDrawable", Integer.TYPE, replaceIcon);
            XposedHelpers.findAndHookMethod(Resources.class, "getDrawable", Integer.TYPE, Resources.Theme.class, replaceIcon);
            XposedHelpers.findAndHookMethod(Resources.class, "getDrawableForDensity", Integer.TYPE, Integer.TYPE, replaceIcon);
            XposedHelpers.findAndHookMethod(Resources.class, "getDrawableForDensity", Integer.TYPE, Integer.TYPE,
                Resources.Theme.class, replaceIcon);
            XposedBridge.hookAllMethods(XposedHelpers.findClass("android.content.res.ResourcesImpl", classLoader), "loadDrawable",
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        int id = 0;
                        Resources resources = null;
                        for (Object argument : param.args) {
                            if (argument instanceof Integer && (Integer) argument > 0x7f000000) id = (Integer) argument;
                            else if (argument instanceof Resources) resources = (Resources) argument;
                        }
                        Drawable icon = moduleIcon(resources, id);
                        if (icon != null) param.setResult(icon);
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "snap icon hooks failed", error);
        }
    }

    private static Drawable moduleIcon(Resources hostResources, int id) {
        if (hostResources == null || id == 0 || Boolean.TRUE.equals(loadingModuleDrawable.get())) return null;
        try {
            String name = hostResources.getResourceEntryName(id);
            if (!name.startsWith("snap_switch_")) return null;
            Context module = moduleContext();
            if (module == null || hostResources == module.getResources()) return null;
            int moduleId = module.getResources().getIdentifier(name, "drawable", MODULE_PACKAGE);
            if (moduleId == 0) return null;
            loadingModuleDrawable.set(Boolean.TRUE);
            try {
                Drawable icon = module.getDrawable(moduleId);
                return icon == null ? null : icon.mutate();
            } finally {
                loadingModuleDrawable.remove();
            }
        } catch (Throwable error) {
            return null;
        }
    }

    private static Context moduleContext() {
        if (moduleContext == null) {
            synchronized (GrSnapFocus.class) {
                if (moduleContext == null) {
                    try {
                        Application app = (Application) XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
                        if (app != null) moduleContext = app.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
                    } catch (Throwable error) {
                        Log.e(TAG, "module context unavailable", error);
                    }
                }
            }
        }
        return moduleContext;
    }
}
