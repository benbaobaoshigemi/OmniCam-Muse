package local.jiege.hook.filters;

import de.robv.android.xposed.XposedHelpers;
import java.util.Map;
import local.jiege.hook.common.Log;

/**
 * Capture filter IDs for the catalog filters (FilterEntry.captureIdFrom).
 *
 * With every capture the camera sends the filter type and its ID from
 * FilterHelper.sFilterPathConvertMap; a filter missing there makes takePicture fail with a null ID
 * (BaseMode.updateStageParameterBuilder), and the aborted request leaves every later capture
 * failing until one without a filter goes through. So every catalog filter must be in the map
 * before it can be picked: apply() runs at install and again once the user's configuration
 * (imported filters) is loaded (MasterFilterSync), and may run any number of times.
 */
public final class CaptureIds {
    private static final String TAG = "CaptureIds";

    private CaptureIds() {}

    public static synchronized void apply(ClassLoader loader) {
        try {
            Class<?> helper = XposedHelpers.findClass("com.oplus.camera.filter.FilterHelper", loader);
            @SuppressWarnings("unchecked") Map<String, Integer> ids =
                (Map<String, Integer>) XposedHelpers.getStaticObjectField(helper, "sFilterPathConvertMap");
            int mapped = 0;
            for (FilterScope scope : FilterScope.values()) {
                for (FilterEntry entry : FilterCatalog.get(scope).entries()) {
                    if (entry.captureIdFrom == null) {
                        // Own ID (X10 ones are added by X10FilterPort).
                        if (entry.source != FilterEntry.Source.X10_PORT && ids.get(entry.id) == null) {
                            Log.i(TAG, entry.id + " has no capture ID");
                        }
                        continue;
                    }
                    Integer borrowed = ids.get(entry.captureIdFrom);
                    if (borrowed == null) {
                        Log.i(TAG, entry.captureIdFrom + " capture ID is absent, " + entry.id + " not mapped");
                        continue;
                    }
                    Integer previous = ids.get(entry.id);
                    if (previous != null && !previous.equals(borrowed)) {
                        Log.i(TAG, entry.id + " already maps to capture ID " + previous + ", kept");
                        continue;
                    }
                    ids.put(entry.id, borrowed);
                    mapped++;
                }
            }
            Log.i(TAG, "capture IDs mapped: " + mapped);
        } catch (Throwable error) {
            Log.e(TAG, "capture ID map failed", error);
        }
    }
}
