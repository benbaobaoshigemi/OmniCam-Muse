package local.jiege.hook.filters;

import android.content.Context;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.HashMap;
import java.util.List;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Puts each scope's enabled catalog entries into that scope's filter groups, in both filter group
 * managers: the SDK one (names as "R.string.<name>") and the app one (names as resource IDs).
 * HEAD entries go right after 标准, TAIL entries at the end, each in catalog order. Groups other
 * than a scope's own are never touched. The night group takes back-camera filters only. With the
 * normal scope following the master list (NormalMasterMirror) the photo and portrait groups are
 * ordered like the Pro group once that is laid out.
 *
 * Master scope: every catalog entry is added, switched-off ones included, and the Pro group is
 * then ordered by MasterLayout (hidden filters at the end). The filter strip leaves the hidden
 * tail out (hookStrip), so presets bound to a hidden filter keep working.
 */
public final class FilterGroupInjector {
    private static final String TAG = "FilterGroups";
    private static final String SDK = "com.oplus.ocs.camera.ipusdk.processunit.filter.list.FilterGroupManager";
    private static final String APP = "com.oplus.camera.filter.FilterGroupManager";
    private static final String CAMERA = "com.oplus.camera";
    private static final String NIGHT_GROUP = "sNightFilterGroup";
    private static final String CAMERA_ACTIVITY = "com.oplus.camera.Camera";

    /** Hidden filters at the end of the Pro group, as last laid out; read by the strip hook. */
    private static volatile List<String> masterHidden = java.util.Collections.emptyList();

    private FilterGroupInjector() {}

    /** Number of hidden filters at the end of the Pro group, as last laid out. */
    static int masterHiddenCount() {
        return masterHidden.size();
    }

    public static void installCamera(ClassLoader classLoader) {
        try {
            Class<?> sdk = XposedHelpers.findClass(SDK, classLoader);
            XposedBridge.hookAllMethods(sdk, "init", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { injectAll(sdk, null, classLoader); }
            });
        } catch (Throwable error) {
            Log.e(TAG, "SDK filter group hook failed", error);
        }
        try {
            Class<?> app = XposedHelpers.findClass(APP, classLoader);
            XposedHelpers.findAndHookMethod(app, "initFromIpu", Context.class, HashMap.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { injectAll(app, (Context) param.args[0], classLoader); }
            });
        } catch (Throwable error) {
            Log.e(TAG, "app filter group hook failed", error);
        }
        hookStrip();
        hookPresetMatch(classLoader);
        hookReload();
    }

    /**
     * When the camera's main screen comes back after the master filter configuration changed,
     * the camera restarts once to apply it (groups are built once per process). Only for the
     * camera opened as itself (launcher intent), never for another app's capture request.
     */
    private static void hookReload() {
        try {
            XposedHelpers.findAndHookMethod(android.app.Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    android.app.Activity activity = (android.app.Activity) param.thisObject;
                    if (!CAMERA_ACTIVITY.equals(activity.getClass().getName())) return;
                    android.content.Intent intent = activity.getIntent();
                    if (intent == null || !android.content.Intent.ACTION_MAIN.equals(intent.getAction())) return;
                    if (!MasterFilterSync.changedSinceLoad()) return;
                    android.content.Intent restart = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
                    if (restart == null) return;
                    Log.i(TAG, "master filter configuration changed, restarting the camera");
                    restart.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    activity.finishAffinity();
                    activity.startActivity(restart);
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "reload hook failed", error);
        }
    }

    /**
     * FilterPresenter.typesFor(boolean) is the filter strip's type list (FilterPresenter.types; its
     * size is the strip's switch count, FilterPresenter.styleCount). In master mode the hidden
     * tail of the Pro group is left out.
     */
    private static void hookStrip() {
        try {
            final Symbols symbols = Symbols.get();
            XposedBridge.hookMethod(symbols.method("FilterPresenter.typesFor"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    List<String> hidden = masterHidden;
                    if (hidden.isEmpty() || !(param.getResult() instanceof List)) return;
                    if (FilterScope.ofMode(symbols.get("FeaturePresenter.modeName", param.thisObject)) != FilterScope.MASTER) return;
                    @SuppressWarnings("unchecked") List<String> types = (List<String>) param.getResult();
                    int visible = types.size() - hidden.size();
                    if (visible < 1 || !types.subList(visible, types.size()).equals(hidden)) return;
                    param.setResult(new java.util.ArrayList<>(types.subList(0, visible)));
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "filter strip hook failed", error);
        }
    }

    /**
     * PresetDataUtil.matchPresetFilterOnDevice(preset, resources, types, nameIds) decides whether a
     * custom preset's filter exists; a refused preset is dropped with its preferences when the
     * camera loads or imports it.
     *
     * The master strip's type list leaves out the hidden tail (hookStrip) while the filter name-ID
     * list keeps it, and the match refuses every preset when the two sizes differ, so the name-ID
     * list it is given loses the same tail. A preset whose filter is hidden is accepted as it is:
     * it keeps its filter type in storage and shows as 标准 until the filter is shown again.
     */
    private static void hookPresetMatch(ClassLoader classLoader) {
        try {
            Class<?> util = XposedHelpers.findClass("com.oplus.camera.common.utils.PresetDataUtil", classLoader);
            XposedBridge.hookAllMethods(util, "matchPresetFilterOnDevice", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 4 || !(param.args[2] instanceof List) || !(param.args[3] instanceof List)) return;
                    List<String> hidden = masterHidden;
                    if (hidden.isEmpty()) return;
                    List<?> types = (List<?>) param.args[2];
                    List<?> nameIds = (List<?>) param.args[3];
                    if (nameIds.size() - types.size() != hidden.size()) return;
                    if (hidden.contains(presetFilterType(param.args[0]))) {
                        param.setResult(Boolean.TRUE);
                        return;
                    }
                    param.args[3] = new java.util.ArrayList<>(nameIds.subList(0, types.size()));
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "preset filter match hook failed", error);
        }
    }

    /** A preset's filter type, read from its toString() ("…', mFilterType=…, mFilterNameResEntry=…"). */
    private static String presetFilterType(Object preset) {
        String text = String.valueOf(preset);
        int start = text.indexOf("', mFilterType=");
        if (start < 0) return null;
        start += "', mFilterType=".length();
        int end = text.indexOf(", mFilterNameResEntry=", start);
        return end < 0 ? null : text.substring(start, end);
    }

    /** context == null: the SDK manager. cameraLoader: the camera's class loader (the SDK has its own). */
    private static void injectAll(Class<?> manager, Context context, ClassLoader cameraLoader) {
        MasterFilterSync.load(context);
        // The configuration may add imported filters: they need capture IDs before they can be picked.
        CaptureIds.apply(cameraLoader);
        for (FilterScope scope : FilterScope.values()) {
            FilterCatalog catalog = FilterCatalog.get(scope);
            List<FilterEntry> entries = scope == FilterScope.MASTER ? catalog.entries() : catalog.enabled();
            for (String groupName : scope.groups) {
                try {
                    Object group = XposedHelpers.getStaticObjectField(manager, groupName);
                    if (group == null) continue;
                    int added = inject(group, NIGHT_GROUP.equals(groupName), forGroup(scope, groupName, entries), context);
                    String layout = scope == FilterScope.MASTER ? layOut(group, catalog) : "";
                    if (scope == FilterScope.MASTER && context != null) reportStock(group, catalog, context);
                    Log.i(TAG, (context == null ? "SDK " : "app ") + scope + " " + groupName + " +" + added + layout);
                } catch (Throwable error) {
                    Log.e(TAG, scope + " " + groupName + " injection failed", error);
                }
            }
        }
        if (FilterCatalog.mirrorsMaster()) {
            try {
                Log.i(TAG, (context == null ? "SDK" : "app") + " normal groups" + NormalMasterMirror.apply(manager));
            } catch (Throwable error) {
                Log.e(TAG, "normal groups in master order failed", error);
            }
        }
    }

    /** The scope's entries that go into one of its groups (mirrored master filters only into some). */
    private static List<FilterEntry> forGroup(FilterScope scope, String groupName, List<FilterEntry> entries) {
        if (scope != FilterScope.NORMAL) return entries;
        List<FilterEntry> out = new java.util.ArrayList<>();
        for (FilterEntry entry : entries) if (NormalMasterMirror.takes(groupName, entry)) out.add(entry);
        return out;
    }

    /** Orders the Pro group by the master layout; returns a log note. */
    @SuppressWarnings("unchecked")
    static String layOut(Object group, FilterCatalog catalog) {
        List<String> back = (List<String>) XposedHelpers.getObjectField(group, "mBackTypeList");
        MasterLayout.Result result = MasterLayout.compute(back, catalog, MasterLayoutStore.load());
        MasterLayout.reorder(back, (List<Object>) XposedHelpers.getObjectField(group, "mBackNameIdList"), result.order);
        MasterLayout.reorder((List<String>) XposedHelpers.getObjectField(group, "mFrontTypeList"),
            (List<Object>) XposedHelpers.getObjectField(group, "mFrontNameIdList"), result.order);
        masterHidden = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(
            result.order.subList(result.order.size() - result.hiddenCount, result.order.size())));
        return ", " + result.hiddenCount + " hidden";
    }

    /**
     * The laid-out Pro group for the settings app: every filter type in list order, with the
     * camera's display name for stock filters (catalog filters are named by the catalog).
     */
    @SuppressWarnings("unchecked")
    private static void reportStock(Object group, FilterCatalog catalog, Context context) {
        List<String> types = (List<String>) XposedHelpers.getObjectField(group, "mBackTypeList");
        List<Object> names = (List<Object>) XposedHelpers.getObjectField(group, "mBackNameIdList");
        List<String> stockTypes = new java.util.ArrayList<>();
        List<String> stockNames = new java.util.ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            if (catalog.find(types.get(i)) != null) {
                stockTypes.add(types.get(i));
                stockNames.add(null);
                continue;
            }
            String name = null;
            try {
                if (names.get(i) instanceof Integer) name = context.getString((Integer) names.get(i));
            } catch (Throwable missing) {
                // keep the type
            }
            stockTypes.add(types.get(i));
            stockNames.add(name);
        }
        MasterFilterSync.reportStock(stockTypes, stockNames);
    }

    private static int inject(Object group, boolean backOnly, List<FilterEntry> entries, Context context) {
        String add = backOnly ? "addBack" : "addFrontAndBack";
        int head = 1;  // position 0 is 标准
        int added = 0;
        for (FilterEntry entry : entries) {
            @SuppressWarnings("unchecked") List<String> types = (List<String>) XposedHelpers.getObjectField(group, "mBackTypeList");
            if (types != null && types.contains(entry.id)) {
                if (entry.placement == FilterEntry.Placement.HEAD) head++;
                continue;
            }
            Object name;
            if (context == null) {
                name = "R.string." + entry.nameResource;
            } else {
                int id = context.getResources().getIdentifier(entry.nameResource, "string", CAMERA);
                if (id == 0) throw new IllegalStateException("Missing name resource " + entry.nameResource);
                name = Integer.valueOf(id);
            }
            if (entry.placement == FilterEntry.Placement.HEAD) {
                XposedHelpers.callMethod(group, add, Integer.valueOf(head++), entry.id, name);
            } else {
                XposedHelpers.callMethod(group, add, entry.id, name);
            }
            added++;
        }
        return added;
    }
}
