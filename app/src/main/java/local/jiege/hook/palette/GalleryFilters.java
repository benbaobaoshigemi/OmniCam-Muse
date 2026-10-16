package local.jiege.hook.palette;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.util.List;
import local.jiege.hook.common.Log;
import local.jiege.hook.filters.FilterCatalog;
import local.jiege.hook.filters.FilterEntry;
import local.jiege.hook.filters.FilterScope;

/**
 * Gallery process: the module's filters in the photo editor's filter / colour style lists.
 *
 * The gallery lists the camera unit SDK's filter groups (FilterGroupManager, from the jar
 * GalleryColorPalette loads). After the SDK fills them, the default catalog entries are appended:
 * normal entries to the common group, master entries to the master group. The name is the entry
 * title (the gallery shows a plain name as it is; "R.string.x" names are its own resources).
 * Imported LUTs live in the camera's data and are left out, as is any entry whose LUT is not in
 * the shared LUT folders. Appending keeps the stock indices, which watermarks refer to.
 */
final class GalleryFilters {
    private static final String TAG = "GalleryFilters";
    private static final String MANAGER = "com.oplus.ocs.camera.ipusdk.processunit.filter.list.FilterGroupManager";
    private static final String[] LUT_DIRS = {"/odm/etc/camera/meishe_lut/", "/odm/etc/camera/filters_lut/"};

    private GalleryFilters() {}

    static void install(ClassLoader sdkLoader) {
        try {
            final Class<?> manager = XposedHelpers.findClass(MANAGER, sdkLoader);
            XposedHelpers.findAndHookMethod(manager, "init", android.content.Context.class, boolean.class, boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    int normal = append(XposedHelpers.getStaticObjectField(manager, "sFilterGroup"), FilterScope.NORMAL);
                    int master = append(XposedHelpers.getStaticObjectField(manager, "sProFilterGroup"), FilterScope.MASTER);
                    Log.i(TAG, "gallery filters added: normal " + normal + ", master " + master);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "gallery filter hook failed", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static int append(Object group, FilterScope scope) {
        int added = 0;
        try {
            List<String> types = (List<String>) XposedHelpers.getObjectField(group, "mBackTypeList");
            for (FilterEntry entry : FilterCatalog.get(scope).entries()) {
                if (!entry.enabled || entry.source == FilterEntry.Source.IMPORTED) continue;
                if (types.contains(entry.id) || !lutExists(entry.id)) continue;
                String name = entry.title != null ? entry.title : "R.string." + entry.nameResource;
                XposedHelpers.callMethod(group, "addBack", entry.id, name);
                added++;
            }
        } catch (Throwable error) {
            Log.e(TAG, "gallery " + scope + " filters failed", error);
        }
        return added;
    }

    private static boolean lutExists(String type) {
        for (String dir : LUT_DIRS) {
            if (new File(dir + type).isFile()) return true;
        }
        return false;
    }
}
