package local.jiege.hook.masterfilter;

import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Arrays;
import java.util.List;
import local.jiege.hook.filters.CaptureIds;
import local.jiege.hook.filters.FilterCatalog;
import local.jiege.hook.filters.FilterEntry;
import local.jiege.hook.filters.FilterScope;
import local.jiege.hook.common.ModeName;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.x10.X10FilterPort;

/**
 * Names and capture IDs of the catalog filters (local.jiege.hook.filters). The filters themselves
 * are put into the groups by FilterGroupInjector; here:
 *   - list entries and cards show the entry titles instead of their registration resource;
 *   - the filter name the master watermark prints (filterName, from the current filter's name or
 *     from a preset's filter) is the entry title too, not the borrowed resource's (大师童话);
 *   - entries without a capture filter ID get the one they borrow (CaptureIds; applied again by
 *     FilterGroupInjector once the user's configuration with the imported filters is loaded).
 */
final class MasterFilmLuts {
    private static final String TAG = "MasterFilmLuts";

    private MasterFilmLuts() {}

    static void install(ClassLoader classLoader) {
        CaptureIds.apply(classLoader);
        hookTitles(classLoader);
        hookWatermarkNames(classLoader);
        XposedBridge.log(TAG + ": loaded");
    }

    /** Title for a filter type in a scope, or null to keep the camera's name. */
    private static String titleFor(FilterScope scope, String type) {
        FilterEntry entry = FilterCatalog.get(scope).find(type);
        return entry == null ? null : entry.title;
    }

    private static void hookTitles(ClassLoader loader) {
        try {
            // Filter list beans carry no mode; a type's title is the same in both scopes
            // (FilterCatalog checks this), so the master catalog, then the normal one, is asked.
            for (java.lang.reflect.Method method : Symbols.get().<java.lang.reflect.Method>all("FilterBeans.lists")) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam x) {
                        Object result = x.getResult();
                        if (!(result instanceof List)) return;
                        for (Object bean : (List<?>) result) {
                            try {
                                String type = (String) XposedHelpers.getObjectField(bean, "mType");
                                String title = titleFor(FilterScope.MASTER, type);
                                if (title == null) title = titleFor(FilterScope.NORMAL, type);
                                if (title != null) XposedHelpers.setObjectField(bean, "mName", title);
                            } catch (Throwable t) { XposedBridge.log(TAG + ": filter bean label failed " + t); }
                        }
                    }
                });
            }
        } catch (Throwable t) { XposedBridge.log(TAG + ": filter bean naming hook failed " + t); }
        try {
            final Symbols symbols = Symbols.get();
            XposedBridge.hookMethod(symbols.method("Card.layout"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam x) {
                    try {
                        Object model = symbols.get("Card.mode", x.thisObject);
                        if (model == null) return;
                        FilterScope scope = FilterScope.ofMode(ModeName.of(model));
                        String title = titleFor(scope, (String) symbols.call("ModeUi.filterType", model));
                        TextView view = X10FilterPort.cardTexts(x.thisObject)[0];
                        if (title != null && view != null) {
                            view.setText(title);
                            X10FilterPort.fitCardTextWidths(x.thisObject);
                        }
                    } catch (Throwable t) { XposedBridge.log(TAG + ": card label failed " + t); }
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": card hook failed " + t); }
    }

    private static String titleForType(String type) {
        String title = titleFor(FilterScope.MASTER, type);
        return title != null ? title : titleFor(FilterScope.NORMAL, type);
    }

    private static void hookWatermarkNames(ClassLoader loader) {
        try {
            final Symbols symbols = Symbols.get();
            XposedBridge.hookMethod(symbols.method("FilterNames.nameAt"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam x) {
                    try {
                        List<?> types = (List<?>) symbols.call("FilterNames.types", x.thisObject);
                        int index = (Integer) x.args[0];
                        if (types == null || index < 0 || index >= types.size()) return;
                        String title = titleForType((String) types.get(index));
                        if (title != null) x.setResult(title);
                    } catch (Throwable t) { XposedBridge.log(TAG + ": filter name failed " + t); }
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": filter name hook failed " + t); }
        try {
            // applyPresetFilter(preset, resources, types, nameIds, index) stores getString(nameId);
            // the preset's String fields holding that text get the title.
            Class<?> util = XposedHelpers.findClass("com.oplus.camera.common.utils.PresetDataUtil", loader);
            for (java.lang.reflect.Method method : util.getDeclaredMethods()) {
                if (!"applyPresetFilter".equals(method.getName()) || method.getParameterTypes().length != 5) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam x) {
                        try {
                            if (!Boolean.TRUE.equals(x.getResult())) return;
                            List<?> types = (List<?>) x.args[2];
                            List<?> nameIds = (List<?>) x.args[3];
                            int index = (Integer) x.args[4];
                            String title = titleForType((String) types.get(index));
                            if (title == null) return;
                            String stock = ((android.content.res.Resources) x.args[1]).getString((Integer) nameIds.get(index));
                            for (java.lang.reflect.Field field : x.args[0].getClass().getDeclaredFields()) {
                                if (field.getType() != String.class) continue;
                                field.setAccessible(true);
                                if (stock.equals(field.get(x.args[0]))) field.set(x.args[0], title);
                            }
                        } catch (Throwable t) { XposedBridge.log(TAG + ": preset filter name failed " + t); }
                    }
                });
            }
        } catch (Throwable t) { XposedBridge.log(TAG + ": preset filter name hook failed " + t); }
    }
}
