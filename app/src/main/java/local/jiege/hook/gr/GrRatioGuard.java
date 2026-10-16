package local.jiege.hook.gr;

import android.app.Application;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Symbols;

/**
 * GR port: keeps the 3:2 photo ratio out. The realme GR mode offers 3:2, which PMA110 cannot
 * capture; the ratio is removed from menus and rewritten to "standard" (4:3) wherever the
 * camera reads or stores it.
 */
final class GrRatioGuard {
    private static final String TAG = "RicohGrPort";
    private static final String RATIO_KEY = "pref_camera_photo_ratio_key";
    private static final String RATIO_3_2 = "3_2";
    private static volatile boolean dataManagerHooked;

    private GrRatioGuard() {}

    static void install(final ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        Log.guard(TAG, "GR photo ratio", () -> XposedBridge.hookMethod(symbols.method("GrMode.photoRatio"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (RATIO_3_2.equals(param.getResult())) param.setResult("standard");
            }
        }));
        // The settings UI takes each menu's options through SettingsUi.setOptions (the call GR's
        // ratio menu makes); every implementation drops 3:2 from the ratio menu.
        Log.guard(TAG, "ratio menu", () -> {
            XC_MethodHook dropFromMenu = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!RATIO_KEY.equals(param.args[0]) || !(param.args[1] instanceof String[])) return;
                    List<String> kept = new ArrayList<>();
                    for (String value : (String[]) param.args[1]) if (!RATIO_3_2.equals(value)) kept.add(value);
                    String[] values = (String[]) param.args[1];
                    if (kept.size() == values.length) return;
                    if (kept.isEmpty()) param.setResult(null);
                    else param.args[1] = kept.toArray(new String[0]);
                }
            };
            for (Method method : symbols.<Method>all("SettingsUi.setOptionsImpl")) XposedBridge.hookMethod(method, dropFromMenu);
        });
        Log.guard(TAG, "stored ratio", () -> hookStoredRatio(symbols));
    }

    private static void hookStoredRatio(final Symbols symbols) {
        XC_MethodHook read = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object key = param.args[0];
                if (key != null && Reflection.hasStringField(key, RATIO_KEY) && RATIO_3_2.equals(param.getResult())) {
                    param.setResult("standard");
                }
            }
        };
        XposedBridge.hookMethod(symbols.method("DataManager.get"), read);
        XposedBridge.hookMethod(symbols.method("DataManager.getOr"), read);
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (dataManagerHooked) return;
                dataManagerHooked = true;
                XC_MethodHook write = new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam write) throws Throwable {
                        Object key = write.args[0];
                        if (key != null && Reflection.hasStringField(key, RATIO_KEY) && RATIO_3_2.equals(write.args[1])) {
                            write.args[1] = "standard";
                        }
                    }
                };
                try {
                    XposedBridge.hookMethod(symbols.method("DataManager.putSync"), write);
                    XposedBridge.hookMethod(symbols.method("DataManager.put"), write);
                } catch (Throwable error) {
                    Log.e(TAG, "stored ratio write hook failed", error);
                }
            }
        });
    }
}
